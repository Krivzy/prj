package dev.mcc.game;

import dev.mcc.core.ServerPlatform;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.BiConsumer;

import static dev.mcc.game.GameService.*;

/** Small in-process event presets. Never generates or force-loads a chunk. */
public final class GameEventEngine implements AutoCloseable, Listener {
    private final JavaPlugin plugin;
    private final BiConsumer<String, Object> events;
    private final Map<String, RunningEvent> running = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();
    private final BukkitTask task;
    private final NamespacedKey eventKey = new NamespacedKey("mcc", "game_event");
    private long tick;
    private int lastPreset;

    public GameEventEngine(JavaPlugin plugin, BiConsumer<String, Object> events) {
        requireMainThread(); this.plugin = plugin; this.events = events;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public Object execute(String action, Map<String, Object> params) {
        requireMainThread();
        if (action.equals("event.start")) return start(params);
        String id = params.containsKey("eventId") ? string(params, "eventId", 36) : string(params, "id", 36);
        RunningEvent event = running.get(id);
        if (event == null) throw new IllegalArgumentException("Событие не найдено");
        if (action.equals("event.stop")) { finish(event, "stopped"); return event.dto(); }
        if (action.equals("event.pause")) {
            boolean paused = bool(params, "paused", !event.paused);
            long now = System.currentTimeMillis();
            if (paused && !event.paused) event.pauseStarted = now;
            if (!paused && event.paused) event.until += now - event.pauseStarted;
            event.paused = paused;
            event.status = paused ? "paused" : "running";
            event.nextTick = tick + event.interval;
            events.accept("event", event.dto()); return event.dto();
        }
        throw new IllegalArgumentException("Неизвестное действие события");
    }

    private Object start(Map<String, Object> p) {
        if (running.size() >= Math.min(3, Math.max(1, plugin.getConfig().getInt("game.max-active-events", 3)))) throw new IllegalStateException("Достигнут лимит активных событий");
        if (ServerPlatform.lowTps()) throw new IllegalStateException("Performance Guard: новые события запрещены при TPS < 18");
        p = new LinkedHashMap<>(GameEventSpec.normalize(p));
        String preset = string(p, "preset", 30);
        Location location;
        if (p.containsKey("uuid")) location = online(p).getLocation();
        else if (p.containsKey("x")) location = location(p);
        else location = world(p).getSpawnLocation();
        requireLoaded(location);
        int count = integer(p, "count", 1, 128, 12);
        int interval = integer(p, "intervalTicks", 20, 1200, preset.equals("waves") ? 100 : 40);
        int duration = integer(p, "durationSeconds", 10, 3600, 120);
        double radius = decimal(p, "radius", 0, 32, 5);
        // Resolve registries and all rewards before starting any effects.
        for(String field:List.of("loot","reward"))if(p.get(field) instanceof List<?> rows)for(Object row:rows){
            @SuppressWarnings("unchecked") Map<String,Object> loot=(Map<String,Object>)row;ItemStack item=createItem(loot);
            if(item.getAmount()>item.getMaxStackSize())throw new IllegalArgumentException("Количество превышает размер стопки "+item.getType());
            if(preset.equals("food-rain")&&!item.getType().isEdible())throw new IllegalArgumentException("Для пикника выберите съедобную еду");
        }
        if(p.containsKey("mob")){EntityType type=enumValue(EntityType.class,string(p,"mob",100));if(!Set.of("ZOMBIE","HUSK","DROWNED","SKELETON","STRAY","BOGGED","SPIDER","CAVE_SPIDER","ZOMBIFIED_PIGLIN").contains(type.name()))throw new IllegalArgumentException("Выберите разрешённого противника: без взрывов и изменения блоков");}
        if(p.containsKey("sound")&&Registry.SOUNDS.get(key(string(p,"sound",150)))==null)throw new IllegalArgumentException("Неизвестный звук");
        RunningEvent event = new RunningEvent(UUID.randomUUID().toString(), preset, location.clone(), count, interval, duration, radius, p);
        if(preset.equals("checkpoint-race"))for(Location point:checkpoints(event)){requireLoaded(point);if(!point.getWorld().getWorldBorder().isInside(point))throw new IllegalArgumentException("Маршрут вне границы мира");}
        running.put(event.id, event);
        events.accept("event", event.dto());
        return event.dto();
    }

    public List<Map<String, Object>> activeEvents() {
        requireMainThread(); return running.values().stream().map(RunningEvent::dto).toList();
    }

    public List<Map<String, Object>> history() { requireMainThread(); return List.copyOf(history); }
    public static Map<String,Object> catalog(){return map("presets",GameEventSpec.DEFINITIONS.stream().map(GameEventSpec.Definition::dto).toList(),"commonFields",GameEventSpec.COMMON.stream().map(GameEventSpec.Field::dto).toList(),"limits",map("active",3,"entities",200,"participants",32,"operationsPerTick",3,"tickTargetMicros",750,"forceChunkLoads",false));}
    public boolean isRunning(String id) { requireMainThread(); return running.containsKey(id); }

    private void tick() {
        tick++;
        if (running.isEmpty()) return;
        boolean guard = ServerPlatform.lowTps();
        long now = System.currentTimeMillis();
        long deadline = System.nanoTime() + 750000;
        int operations = 0;
        List<RunningEvent> current = List.copyOf(running.values());
        // Round-robin order ensures one preset cannot starve another.
        int start = Math.floorMod(lastPreset++, current.size());
        for (int i = 0; i < current.size(); i++) {
            RunningEvent event = current.get((start + i) % current.size());
            if (!running.containsKey(event.id)) continue;
            if (tick % 40 == 0) {
                event.entities.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e != null && !e.isValid(); });
                if (event.bar != null) updateBossBar(event);
            }
            if (event.paused) continue;
            if (now >= event.until) { finish(event, "completed"); continue; }
            if (event.performancePaused != guard) { event.performancePaused = guard; events.accept("event", event.dto()); }
            if (guard || event.remaining == 0 || tick < event.nextTick || operations >= 3 || System.nanoTime() > deadline) continue;
            event.nextTick = tick + event.interval;
            int batch = event.preset.equals("waves") ? Math.min(integer(event.params,"batch",1,8,3), event.remaining) : 1;
            for (int n = 0; n < batch && operations < 3 && System.nanoTime() <= deadline; n++) {
                if (trackedCount() >= 200) { event.lastError = "Достигнут общий лимит 200 сущностей событий"; break; }
                Location point = event.location.clone().add((Math.random() - 0.5) * 2 * event.radius, 0, (Math.random() - 0.5) * 2 * event.radius);
                try {
                    requireLoaded(point);
                    if (!point.getWorld().getWorldBorder().isInside(point)) throw new IllegalArgumentException("Точка вне world border");
                    Entity spawned = spawnPreset(event, point);
                    if (spawned != null) {
                        if (!spawned.isValid()) throw new IllegalStateException("Спавн отменён сервером или другим плагином");
                        if (spawned instanceof LivingEntity living) {
                            living.setCanPickupItems(false);
                            var reinforcements = living.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
                            if (reinforcements != null) reinforcements.setBaseValue(0);
                        }
                        spawned.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, event.id);
                        event.entities.add(spawned.getUniqueId());
                    }
                    event.spawned++;
                } catch (RuntimeException ex) { event.failed++; event.lastError = ex.getMessage(); }
                event.remaining--; operations++;
            }
            events.accept("event", event.dto());
        }
    }

    private Entity spawnPreset(RunningEvent event, Location point) {
        return switch (event.preset) {
            case "fireworks" -> {Firework rocket=firework(point);FireworkMeta meta=rocket.getFireworkMeta();meta.clearEffects();meta.setPower(integer(event.params,"power",0,2,0));meta.addEffect(FireworkEffect.builder().withColor(color(event)).with(FireworkEffect.Type.valueOf(string(event.params,"shape",20))).trail(true).build());rocket.setFireworkMeta(meta);yield rocket;}
            case "lightning" -> point.getWorld().strikeLightningEffect(point);
            case "treasure","supply-drop","food-rain" -> point.getWorld().dropItem(point, loot(event,"loot"));
            case "sanctuary" -> {Entity animal=point.getWorld().spawnEntity(point,EntityType.valueOf(string(event.params,"animal",30)),false);if(animal instanceof Ageable age&&event.params.get("age").equals("baby"))age.setBaby();yield animal;}
            case "waves","glowing-hunt","elite-patrol" -> {
                LivingEntity mob = spawnMob(event,point);mob.setCustomName("MCC · "+GameEventSpec.definition(event.preset).ru());mob.setCustomNameVisible(true);
                if(event.preset.equals("glowing-hunt"))mob.setGlowing(true);
                if(event.preset.equals("elite-patrol")&&mob.getEquipment()!=null){String equipment=string(event.params,"equipment",20).toUpperCase(Locale.ROOT);var gear=mob.getEquipment();gear.setItemInMainHand(new ItemStack(Material.valueOf(equipment.equals("LEATHER")?"WOODEN_SWORD":equipment+"_SWORD")));gear.setHelmet(new ItemStack(Material.valueOf(equipment+"_HELMET")));gear.setChestplate(new ItemStack(Material.valueOf(equipment+"_CHESTPLATE")));gear.setHelmetDropChance(0);gear.setChestplateDropChance(0);gear.setItemInMainHandDropChance(0);}
                yield mob;
            }
            case "champion" -> {
                LivingEntity boss = spawnMob(event,point);
                var health = boss.getAttribute(Attribute.MAX_HEALTH);
                if (health != null) { double hp=decimal(event.params,"health",1,200,40);health.setBaseValue(hp);boss.setHealth(hp); }
                var damage = boss.getAttribute(Attribute.ATTACK_DAMAGE); if (damage != null) damage.setBaseValue(decimal(event.params,"damage",0,20,6));
                boss.setCustomName("MCC · Изумрудный страж"); boss.setCustomNameVisible(true); boss.setRemoveWhenFarAway(false); boss.setPersistent(true);
                event.boss = boss.getUniqueId();
                event.bar = Bukkit.createBossBar("Изумрудный страж", BarColor.GREEN, BarStyle.SEGMENTED_10);
                point.getWorld().getPlayers().forEach(event.bar::addPlayer);
                yield boss;
            }
            case "xp-rain" -> {ExperienceOrb orb=point.getWorld().spawn(point,ExperienceOrb.class);orb.setExperience(integer(event.params,"xp",1,100,5));yield orb;}
            case "slime-swarm" -> {Slime slime=point.getWorld().spawn(point,Slime.class);slime.setSize(integer(event.params,"size",1,4,2));yield slime;}
            case "bee-garden" -> {Bee bee=point.getWorld().spawn(point,Bee.class);bee.setAnger(0);yield bee;}
            case "snowball-arena" -> {Snowball ball=event.location.getWorld().spawn(event.location.clone().add(0,1,0),Snowball.class);double angle=event.spawned*2.399963;double speed=decimal(event.params,"speed",.1,1.5,.7);ball.setVelocity(new Vector(Math.cos(angle)*speed,.2,Math.sin(angle)*speed));yield ball;}
            case "egg-hunt" -> point.getWorld().dropItem(point,new ItemStack(Material.EGG));
            case "meteor-show" -> {int density=integer(event.params,"particles",1,80,24);int height=integer(event.params,"height",2,24,12);for(int i=0;i<4;i++)point.getWorld().spawnParticle(Particle.FLAME,point.clone().add(0,height-i*height/4.0,0),Math.max(1,density/4),.15,.5,.15,0);point.getWorld().spawnParticle(Particle.EXPLOSION,point,1);yield null;}
            case "aurora" -> {point.getWorld().spawnParticle(Particle.DUST,event.location.clone().add(Math.sin(event.spawned*.6)*event.radius,integer(event.params,"height",1,24,8),0),integer(event.params,"particles",1,80,30),3,.15,1,0,new Particle.DustOptions(color(event),1.3f));yield null;}
            case "music" -> {Sound sound=Registry.SOUNDS.get(key(string(event.params,"sound",150)));point.getWorld().playSound(event.location,sound,(float)decimal(event.params,"volume",0,2,1),(float)Math.min(2,decimal(event.params,"pitch",.5,1.5,1)*new double[]{1,1.122,1.26,1.498}[event.spawned%4]));yield null;}
            case "healing-zone","feast-zone","speed-trial","moonwalk" -> {support(event);yield null;}
            case "checkpoint-race" -> {race(event);yield null;}
            case "fishing-festival" -> {point.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,event.location,8,1,.5,1,0);yield null;}
            default -> throw new IllegalArgumentException("Неизвестный preset");
        };
    }

    private LivingEntity spawnMob(RunningEvent event,Location point){LivingEntity mob=(LivingEntity)point.getWorld().spawnEntity(point,EntityType.valueOf(string(event.params,"mob",100)),false);var max=mob.getAttribute(Attribute.MAX_HEALTH);if(max!=null){double hp=decimal(event.params,"health",1,200,40);max.setBaseValue(hp);mob.setHealth(hp);}return mob;}
    private Color color(RunningEvent event){return Color.fromRGB(Integer.parseInt(string(event.params,"color",6),16));}
    @SuppressWarnings("unchecked") private ItemStack loot(RunningEvent event,String key){List<Map<String,Object>> rows=(List<Map<String,Object>>)event.params.get(key);return createItem(rows.get(GameEventSpec.weightedIndex(rows,java.util.concurrent.ThreadLocalRandom.current().nextInt())));}
    private List<Player> participants(RunningEvent event){List<Player> result=new ArrayList<>(),all=event.location.getWorld().getPlayers();int start=Math.floorMod(event.spawned*128,Math.max(1,all.size()));for(int n=0;n<Math.min(128,all.size())&&result.size()<32;n++){Player player=all.get((start+n)%all.size());if(player.getGameMode()!=GameMode.SPECTATOR&&player.getLocation().distanceSquared(event.location)<=event.radius*event.radius)result.add(player);}return result;}
    private void support(RunningEvent event){List<Player> players=participants(event);if(players.isEmpty())return;Player p=players.get(Math.floorMod(event.spawned,players.size()));switch(event.preset){
        case "healing-zone"->{var max=p.getAttribute(Attribute.MAX_HEALTH);p.setHealth(Math.min(max==null?20:max.getValue(),p.getHealth()+decimal(event.params,"hearts",.5,5,1)*2));}
        case "feast-zone"->p.setFoodLevel(Math.min(20,p.getFoodLevel()+integer(event.params,"food",1,10,2)));
        case "speed-trial","moonwalk"->{int duration=integer(event.params,"effectSeconds",1,30,5)*20;int strength=integer(event.params,"strength",0,3,0);PotionEffectType effect=Registry.EFFECT.get(NamespacedKey.minecraft(event.preset.equals("speed-trial")?"speed":"jump_boost"));p.addPotionEffect(new PotionEffect(effect,duration,strength));if(event.preset.equals("moonwalk"))p.addPotionEffect(new PotionEffect(Registry.EFFECT.get(NamespacedKey.minecraft("slow_falling")),duration,0));}
    }}
    @SuppressWarnings("unchecked") private List<Location> checkpoints(RunningEvent event){return ((List<Map<String,Object>>)event.params.get("checkpoints")).stream().map(p->event.location.clone().add(((Number)p.get("x")).doubleValue(),((Number)p.get("y")).doubleValue(),((Number)p.get("z")).doubleValue())).toList();}
    private void race(RunningEvent event){List<Location> points=checkpoints(event);List<Player> players=participants(event);if(players.isEmpty())return;Player p=players.get(Math.floorMod(event.spawned,players.size()));int index=event.scores.getOrDefault(p.getUniqueId(),0);if(index>=points.size())return;Location target=points.get(index);requireLoaded(target);target.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,target,8,.3,.5,.3,0);if(p.getLocation().distanceSquared(target)<=Math.pow(decimal(event.params,"checkpointRadius",1,5,2),2))score(event,p,1,points.size());}
    private void score(RunningEvent event,Player p,int amount,int target){if(event.paused||event.performancePaused||!running.containsKey(event.id)||p.getWorld()!=event.location.getWorld()||event.winners.contains(p.getUniqueId()))return;if(!event.scores.containsKey(p.getUniqueId())&&event.scores.size()>=32)return;int next=Math.min(target,event.scores.getOrDefault(p.getUniqueId(),0)+amount);event.scores.put(p.getUniqueId(),next);if(next>=target){event.winners.add(p.getUniqueId());ItemStack reward=loot(event,"reward");var left=p.getInventory().addItem(reward);p.sendMessage("MCC · Победа: "+GameEventSpec.definition(event.preset).ru());if(!left.isEmpty())event.lastError="Не вся награда поместилась в инвентарь; предметы не выбрасываются за пределами события";}events.accept("event",event.dto());}

    private int trackedCount() { return running.values().stream().mapToInt(e -> e.entities.size()).sum(); }

    private void updateBossBar(RunningEvent event) {
        Entity e = Bukkit.getEntity(event.boss);
        if (!(e instanceof LivingEntity boss) || boss.isDead() || !boss.isValid()) { finish(event, "completed"); return; }
        var attribute = boss.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? 100 : attribute.getValue();
        event.bar.setProgress(Math.max(0, Math.min(1, boss.getHealth() / max)));
        for (Player player : List.copyOf(event.bar.getPlayers())) if (player.getWorld() != event.location.getWorld()) event.bar.removePlayer(player);
        for (Player player : event.location.getWorld().getPlayers()) event.bar.addPlayer(player);
    }

    private void finish(RunningEvent event, String status) {
        if (running.remove(event.id) == null) return;
        event.status = status;
        event.paused = false;
        for (UUID uuid : event.entities) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && !(entity instanceof Player) && event.id.equals(entity.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING))) entity.remove();
        }
        event.entities.clear();
        if (event.bar != null) event.bar.removeAll();
        Map<String, Object> dto = event.dto();
        history.addFirst(dto); while (history.size() > 20) history.removeLast();
        events.accept("event", dto);
        if(status.equals("completed"))events.accept("trigger",map("trigger","eventComplete","world",event.location.getWorld().getName(),"eventId",event.id,"preset",event.preset));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        // Unloaded entities still exist on disk and reserve their place in the cap until loaded or cleaned up.
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) return;
        String owner = event.getEntity().getPersistentDataContainer().get(eventKey, PersistentDataType.STRING);
        RunningEvent runningEvent = owner == null ? null : running.get(owner);
        if (runningEvent != null) runningEvent.entities.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTransform(EntityTransformEvent event) {
        String owner = event.getEntity().getPersistentDataContainer().get(eventKey, PersistentDataType.STRING);
        // Vanilla conversion replaces UUID and drops PDC ownership. Keep disposable preset mobs owned for cleanup.
        if (owner != null && running.containsKey(owner)) event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onSplit(SlimeSplitEvent e){if(e.getEntity().getPersistentDataContainer().has(eventKey,PersistentDataType.STRING))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onBreed(org.bukkit.event.entity.EntityBreedEvent e){if(e.getMother().getPersistentDataContainer().has(eventKey,PersistentDataType.STRING)||e.getFather().getPersistentDataContainer().has(eventKey,PersistentDataType.STRING))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void onDeath(EntityDeathEvent e){String owner=e.getEntity().getPersistentDataContainer().get(eventKey,PersistentDataType.STRING);RunningEvent event=running.get(owner);Player p=e.getEntity().getKiller();if(event!=null&&p!=null&&event.preset.equals("glowing-hunt"))score(event,p,1,integer(event.params,"targetScore",1,64,3));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPickup(EntityPickupItemEvent e){if(!(e.getEntity() instanceof Player p))return;String owner=e.getItem().getPersistentDataContainer().get(eventKey,PersistentDataType.STRING);RunningEvent event=running.get(owner);if(event==null||!event.preset.equals("egg-hunt")||e.getRemaining()>0)return;Bukkit.getScheduler().runTask(plugin,()->{if(!e.isCancelled())score(event,p,1,integer(event.params,"targetScore",1,64,5));});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onFish(PlayerFishEvent e){if(e.getState()!=PlayerFishEvent.State.CAUGHT_FISH||running.values().stream().noneMatch(event->event.preset.equals("fishing-festival")))return;Bukkit.getScheduler().runTask(plugin,()->{if(e.isCancelled())return;for(RunningEvent event:List.copyOf(running.values()))if(event.preset.equals("fishing-festival")&&e.getPlayer().getWorld()==event.location.getWorld()&&e.getPlayer().getLocation().distanceSquared(event.location)<=event.radius*event.radius)score(event,e.getPlayer(),1,integer(event.params,"targetScore",1,64,3));});}

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            String owner = entity.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING);
            if (owner != null && !running.containsKey(owner) && !(entity instanceof Player)) entity.remove();
        }
    }

    @Override public void close() { requireMainThread(); task.cancel(); for (RunningEvent event : List.copyOf(running.values())) finish(event, "stopped"); HandlerList.unregisterAll(this); }

    private final class RunningEvent {
        final String id, preset;
        final Location location;
        final int count, interval;
        final double radius;
        final Map<String,Object> params;
        final Map<UUID,Integer> scores=new LinkedHashMap<>();
        final Set<UUID> winners=new HashSet<>();
        final long startedAt = System.currentTimeMillis();
        final Set<UUID> entities = new HashSet<>();
        long until, nextTick, pauseStarted;
        int remaining, spawned, failed;
        boolean paused, performancePaused;
        String status = "running", lastError;
        UUID boss;
        BossBar bar;
        RunningEvent(String id, String preset, Location location, int count, int interval, int duration, double radius, Map<String,Object> params) {
            this.id = id; this.preset = preset; this.location = location; this.count = this.remaining = count; this.interval = interval; this.radius = radius; this.params=Map.copyOf(params); this.until = startedAt + duration * 1000L; this.nextTick = tick + 1;
        }
        Map<String, Object> dto() { return map("id", id, "preset", preset, "status", status, "world", location.getWorld().getName(), "location", locationDto(location), "requested", count, "spawned", spawned, "failed", failed, "remaining", remaining, "entities", entities.size(), "startedAt", startedAt, "until", until, "performancePaused", performancePaused, "error", lastError,"participants",scores.size(),"winners",winners.size(),"scores",scores.entrySet().stream().map(e->map("uuid",e.getKey().toString(),"score",e.getValue())).toList(),"config",params); }
    }
}
