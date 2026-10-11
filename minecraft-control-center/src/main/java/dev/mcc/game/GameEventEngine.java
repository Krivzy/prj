package dev.mcc.game;

import dev.mcc.core.ServerPlatform;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.BiConsumer;

import static dev.mcc.game.GameService.*;

/** Small in-process event presets. Never generates or force-loads a chunk. */
public final class GameEventEngine implements AutoCloseable, Listener {
    private static final Set<String> PRESETS = Set.of("waves", "fireworks", "lightning", "treasure", "animals", "boss");
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
        String preset = string(p, "preset", 30).toLowerCase(Locale.ROOT);
        if (!PRESETS.contains(preset)) throw new IllegalArgumentException("Неизвестный preset события");
        Location location;
        if (p.containsKey("uuid")) location = online(p).getLocation();
        else if (p.containsKey("x")) location = location(p);
        else location = world(p).getSpawnLocation();
        requireLoaded(location);
        int count = integer(p, "count", 1, 128, preset.equals("boss") ? 1 : 20);
        if (preset.equals("boss") && count != 1) throw new IllegalArgumentException("Boss preset создаёт одного boss");
        int interval = integer(p, "intervalTicks", 20, 1200, preset.equals("waves") ? 100 : 40);
        int duration = integer(p, "durationSeconds", 10, 3600, 120);
        double radius = decimal(p, "radius", 0, 32, 5);
        ItemStack treasure = preset.equals("treasure") ? createItem(p.containsKey("item") ? object(p, "item") : map("material", "DIAMOND", "amount", 1)) : null;
        RunningEvent event = new RunningEvent(UUID.randomUUID().toString(), preset, location.clone(), count, interval, duration, radius, treasure);
        running.put(event.id, event);
        events.accept("event", event.dto());
        return event.dto();
    }

    public List<Map<String, Object>> activeEvents() {
        requireMainThread(); return running.values().stream().map(RunningEvent::dto).toList();
    }

    public List<Map<String, Object>> history() { requireMainThread(); return List.copyOf(history); }
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
            int batch = event.preset.equals("waves") || event.preset.equals("animals") ? Math.min(3, event.remaining) : 1;
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
            case "fireworks" -> firework(point);
            case "lightning" -> point.getWorld().strikeLightningEffect(point);
            case "treasure" -> point.getWorld().dropItem(point, event.treasure.clone());
            case "animals" -> point.getWorld().spawnEntity(point, List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN).get(event.spawned % 4), false);
            case "waves" -> {
                LivingEntity mob = (LivingEntity) point.getWorld().spawnEntity(point, event.spawned % 3 == 2 ? EntityType.SKELETON : EntityType.ZOMBIE, false);
                mob.setCustomName("MCC · Волна"); mob.setCustomNameVisible(true); yield mob;
            }
            case "boss" -> {
                Zombie boss = point.getWorld().spawn(point, Zombie.class, false, null);
                var health = boss.getAttribute(Attribute.MAX_HEALTH);
                if (health != null) { health.setBaseValue(100); boss.setHealth(100); }
                var damage = boss.getAttribute(Attribute.ATTACK_DAMAGE); if (damage != null) damage.setBaseValue(6);
                boss.setCustomName("MCC · Изумрудный страж"); boss.setCustomNameVisible(true); boss.setRemoveWhenFarAway(false); boss.setPersistent(true);
                event.boss = boss.getUniqueId();
                event.bar = Bukkit.createBossBar("Изумрудный страж", BarColor.GREEN, BarStyle.SEGMENTED_10);
                point.getWorld().getPlayers().forEach(event.bar::addPlayer);
                yield boss;
            }
            default -> throw new IllegalArgumentException("Неизвестный preset");
        };
    }

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
        final ItemStack treasure;
        final long startedAt = System.currentTimeMillis();
        final Set<UUID> entities = new HashSet<>();
        long until, nextTick, pauseStarted;
        int remaining, spawned, failed;
        boolean paused, performancePaused;
        String status = "running", lastError;
        UUID boss;
        BossBar bar;
        RunningEvent(String id, String preset, Location location, int count, int interval, int duration, double radius, ItemStack treasure) {
            this.id = id; this.preset = preset; this.location = location; this.count = this.remaining = count; this.interval = interval; this.radius = radius; this.treasure = treasure; this.until = startedAt + duration * 1000L; this.nextTick = tick + 1;
        }
        Map<String, Object> dto() { return map("id", id, "preset", preset, "status", status, "world", location.getWorld().getName(), "location", locationDto(location), "requested", count, "spawned", spawned, "failed", failed, "remaining", remaining, "entities", entities.size(), "startedAt", startedAt, "until", until, "performancePaused", performancePaused, "error", lastError); }
    }
}
