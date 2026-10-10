package dev.mcc.game;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** All entry points except the async chat listener require the Paper main thread. */
public final class GameService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final BiConsumer<String, Object> events;
    private final Set<UUID> frozen = new HashSet<>();
    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, String> knownNames = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final Set<UUID> inventoryDirty = new HashSet<>();
    private final Deque<SpawnJob> spawnJobs = new ArrayDeque<>();
    private GameEventEngine eventEngine;
    private final MapEngine mapEngine;
    private boolean eventsEnabled = true;
    private final BukkitTask tickTask;
    private Map<String, Object> catalogCache;
    private BiConsumer<UUID, Map<String, Object>> moderationPersistence = (uuid, state) -> {};
    private volatile java.util.function.Predicate<UUID> muteLookup = uuid -> false;
    private Set<String> consoleAllowed = Set.of("list", "say", "time", "weather", "difficulty", "seed", "help", "version", "plugins");
    private boolean closed;
    private int ticks;

    public GameService(JavaPlugin plugin, BiConsumer<String, Object> events) {
        requireMainThread();
        this.plugin = Objects.requireNonNull(plugin);
        this.events = Objects.requireNonNull(events);
        Bukkit.getOnlinePlayers().forEach(p -> { knownNames.put(p.getUniqueId(), p.getName()); joinedAt.put(p.getUniqueId(), System.currentTimeMillis()); });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        eventEngine = new GameEventEngine(plugin, events);
        mapEngine = new MapEngine(plugin);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void setModerationPersistence(BiConsumer<UUID, Map<String, Object>> callback) {
        requireMainThread(); moderationPersistence = Objects.requireNonNull(callback);
    }
    public void setMuteLookup(java.util.function.Predicate<UUID> lookup) { requireMainThread(); muteLookup=Objects.requireNonNull(lookup); }

    public void restoreMutes(Map<UUID, Long> restored) {
        requireMainThread();
        restored.forEach((uuid, expiry) -> { if (expiry == 0 || expiry > System.currentTimeMillis()) mutes.put(uuid, expiry); });
    }

    public void restoreKnownPlayers(Map<UUID, String> restored) {
        requireMainThread();
        restored.forEach((uuid, name) -> { if (name != null && name.length() <= 100) knownNames.putIfAbsent(uuid, name); });
    }

    public void setEventsEnabled(boolean enabled) {
        requireMainThread();
        if (enabled == eventsEnabled) return;
        if (enabled) eventEngine = new GameEventEngine(plugin, events); else eventEngine.close();
        eventsEnabled = enabled;
    }

    public void setMapEnabled(boolean enabled) { requireMainThread(); mapEngine.enabled(enabled); }
    public java.util.concurrent.CompletableFuture<Map<String, Object>> mapTile(String world, int x, int z, int size) { requireMainThread(); return mapEngine.request(world, x, z, size); }
    public Map<String,Object> mapTerrain(String world,int cx,int cz,int radius,int step){requireMainThread();return mapEngine.terrain(world,cx,cz,radius,step);}
    public java.util.concurrent.CompletableFuture<Map<String,Object>> mapIndex(String world){requireMainThread();return mapEngine.worldIndex(world);}
    public void invalidateMapTerrain(String world,int x,int z){requireMainThread();mapEngine.invalidateTerrain(world,x,z);}
    public void invalidateMapWorld(String world){requireMainThread();mapEngine.invalidateWorld(world);}

    public void setConsoleAllowedCommands(Set<String> commands) {
        requireMainThread();
        consoleAllowed = commands.stream().map(x -> x.toLowerCase(Locale.ROOT)).filter(x -> x.matches("[a-z0-9_-]{1,40}")).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Map<String, Object> snapshot() {
        requireMainThread();
        Runtime runtime = Runtime.getRuntime();
        List<Map<String, Object>> worlds = Bukkit.getWorlds().stream().map(w -> worldDto(w, false)).toList();
        int entities = worlds.stream().mapToInt(w -> ((Number) w.get("entityCount")).intValue()).sum();
        int chunks = worlds.stream().mapToInt(w -> ((Number) w.get("loadedChunks")).intValue()).sum();
        double[] tps = Bukkit.getTPS();
        return map("timestamp", System.currentTimeMillis(), "serverName", Bukkit.getName(), "version", Bukkit.getVersion(),
                "minecraftVersion", Bukkit.getMinecraftVersion(), "online", Bukkit.getOnlinePlayers().size(), "maxPlayers", Bukkit.getMaxPlayers(),
                "tps", Arrays.stream(tps).map(x -> Math.min(20, x)).boxed().toList(), "mspt", Bukkit.getAverageTickTime(),
                "uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000, "heapUsed", runtime.totalMemory() - runtime.freeMemory(),
                "heapMax", runtime.maxMemory(), "cpu", null, "loadedChunks", chunks, "entityCount", entities,
                "worldCount", worlds.size(), "worlds", worlds, "activeEvents", activeEvents(), "pendingSpawnBatches", spawnJobs.size(), "performanceGuard", tps[0] < 18,
                "motd", PlainTextComponentSerializer.plainText().serialize(Bukkit.motd()), "whitelist", Bukkit.hasWhitelist(), "viewDistance", Bukkit.getViewDistance(), "simulationDistance", Bukkit.getSimulationDistance());
    }

    public List<Map<String, Object>> players() {
        requireMainThread();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) out.add(playerDto(player, false));
        for (var e : knownNames.entrySet()) if (Bukkit.getPlayer(e.getKey()) == null) out.add(map("uuid", e.getKey().toString(), "name", e.getValue(), "online", false, "muted", muted(e.getKey())));
        out.sort(Comparator.comparing(p -> String.valueOf(p.get("name")), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public Map<String, Object> player(String uuid) {
        requireMainThread();
        UUID id = parseUuid(uuid);
        Player p = Bukkit.getPlayer(id);
        if (p == null) return map("uuid", id.toString(), "name", knownNames.getOrDefault(id, id.toString()), "online", false, "muted", muted(id));
        return playerDto(p, true);
    }

    private Map<String, Object> playerDto(Player p, boolean details) {
        Location l = p.getLocation();
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        Map<String, Object> dto = map("uuid", p.getUniqueId().toString(), "name", p.getName(), "online", true, "world", p.getWorld().getName(),
                "x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", l.getYaw(), "pitch", l.getPitch(), "health", p.getHealth(),
                "maxHealth", max == null ? 20 : max.getValue(), "food", p.getFoodLevel(), "level", p.getLevel(), "gamemode", p.getGameMode().name(),
                "ping", p.getPing(), "op", p.isOp(), "connectionTime", joinedAt.get(p.getUniqueId()));
        if (details) {
            dto.putAll(map("xp", p.calculateTotalExperiencePoints(), "exp", p.getExp(), "saturation", p.getSaturation(), "air", p.getRemainingAir(),
                    "fireTicks", p.getFireTicks(), "allowFlight", p.getAllowFlight(), "flying", p.isFlying(), "walkSpeed", p.getWalkSpeed(),
                    "flySpeed", p.getFlySpeed(), "glowing", p.isGlowing(), "invisible", p.isInvisible(), "frozen", frozen.contains(p.getUniqueId()), "muted", muted(p.getUniqueId()),
                    "muteExpiresAt", mutes.get(p.getUniqueId()), "effects", p.getActivePotionEffects().stream().map(e -> map("type", e.getType().getKey().toString(), "duration", e.getDuration(), "amplifier", e.getAmplifier(), "ambient", e.isAmbient(), "particles", e.hasParticles())).toList()));
            Map<String, Object> stats = new LinkedHashMap<>();
            for (Statistic s : List.of(Statistic.PLAY_ONE_MINUTE, Statistic.DEATHS, Statistic.PLAYER_KILLS, Statistic.MOB_KILLS, Statistic.JUMP, Statistic.WALK_ONE_CM)) stats.put(s.name(), p.getStatistic(s));
            dto.put("statistics", stats);
            List<Map<String, Object>> advancements = new ArrayList<>();
            Bukkit.advancementIterator().forEachRemaining(a -> { var progress = p.getAdvancementProgress(a); if (progress.isDone()) advancements.add(map("key", a.getKey().toString(), "done", true)); });
            dto.put("advancements", advancements);
        }
        return dto;
    }

    public Map<String, Object> inventory(String uuid, String kind) {
        requireMainThread();
        Player p = online(map("uuid", uuid));
        Inventory inv = inventoryOf(p, kind);
        List<Map<String, Object>> slots = new ArrayList<>(inv.getSize());
        for (int i = 0; i < inv.getSize(); i++) slots.add(map("slot", i, "item", itemDto(inv.getItem(i))));
        return map("uuid", uuid, "kind", kind == null ? "main" : kind, "size", inv.getSize(), "revision", revision(inv), "slots", slots);
    }

    public List<Map<String, Object>> worlds() {
        requireMainThread(); return Bukkit.getWorlds().stream().map(w -> worldDto(w, true)).toList();
    }

    private Map<String, Object> worldDto(World w, boolean detailed) {
        Location center = w.getWorldBorder().getCenter();
        Map<String, Object> out = map("name", w.getName(), "uuid", w.getUID().toString(), "environment", w.getEnvironment().name(),
                "time", w.getTime(), "fullTime", w.getFullTime(), "weather", w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear", "difficulty", w.getDifficulty().name(),
                "pvp", w.getGameRuleValue(GameRules.PVP), "loadedChunks", w.getChunkCount(), "entityCount", w.getEntityCount(), "players", w.getPlayerCount(),
                "border", map("size", w.getWorldBorder().getSize(), "x", center.getX(), "z", center.getZ()), "spawn", locationDto(w.getSpawnLocation()));
        out.put("viewDistance", w.getViewDistance()); out.put("simulationDistance", w.getSimulationDistance());
        if (detailed) {
            Map<String, Object> rules = new TreeMap<>();
            for (GameRule<?> rule : Registry.GAME_RULE) {
                // The registry includes experimental rules absent from worlds without their feature flag.
                try { rules.put(rule.getKey().toString(), w.getGameRuleValue(rule)); }
                catch (IllegalArgumentException unavailableRule) { /* Not available in this world's feature set. */ }
            }
            out.put("gamerules", rules);
            out.put("seed", w.getSeed()); out.put("minHeight", w.getMinHeight()); out.put("maxHeight", w.getMaxHeight());
        }
        return out;
    }

    public Map<String, Object> catalogs() {
        requireMainThread();
        if (catalogCache == null) {
            List<Map<String, Object>> materials = Arrays.stream(Material.values()).filter(m -> m.isItem() || m.isBlock()).map(m -> map("key", m.name(), "name", m.name(), "maxStackSize", m.isItem()?m.getMaxStackSize():0, "item", m.isItem(), "block", m.isBlock())).toList();
            List<String> mobs = Arrays.stream(EntityType.values()).filter(GameService::allowedMob).map(Enum::name).sorted().toList();
            List<Map<String, Object>> ench = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).stream().map(e -> map("key", e.getKey().toString(), "maxLevel", e.getMaxLevel())).toList();
            catalogCache = map("materials", materials, "mobs", mobs, "effects", Registry.EFFECT.stream().map(e -> e.getKey().toString()).toList(),
                    "enchantments", ench, "sounds", Registry.SOUND_EVENT.keyStream().map(NamespacedKey::toString).toList(), "attributes", Registry.ATTRIBUTE.stream().map(a -> a.getKey().toString()).toList(),
                    "eventPresets", List.of("waves", "fireworks", "lightning", "treasure", "animals", "boss"));
        }
        return catalogCache;
    }

    public List<Map<String, Object>> plugins() {
        requireMainThread();
        return Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(p -> map("name", p.getName(), "version", p.getPluginMeta().getVersion(), "enabled", p.isEnabled(), "authors", p.getPluginMeta().getAuthors(), "description", p.getPluginMeta().getDescription())).toList();
    }

    /** Bounded inspector query near an explicitly chosen loaded location. */
    public List<Map<String, Object>> entities(Map<String, Object> params) {
        requireMainThread();
        Location center = location(params); requireLoaded(center);
        if (Bukkit.getTPS()[0] < 18) throw new IllegalStateException("Performance Guard: инспектор мобов приостановлен при TPS < 18");
        double radius = decimal(params, "radius", 1, 32, 16);
        List<Map<String, Object>> result = new ArrayList<>();
        int[] accepted = {0};
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius, e -> { if (accepted[0] >= 100 || !(e instanceof LivingEntity) || e instanceof Player) return false; accepted[0]++; return true; })) {
            if (result.size() >= 100) break;
            LivingEntity living = (LivingEntity) entity; Location l = entity.getLocation(); var max = living.getAttribute(Attribute.MAX_HEALTH);
            result.add(map("uuid", entity.getUniqueId().toString(), "type", entity.getType().name(), "name", entity.customName() == null ? "" : PlainTextComponentSerializer.plainText().serialize(entity.customName()),
                    "world", entity.getWorld().getName(), "x", l.getX(), "y", l.getY(), "z", l.getZ(), "health", living.getHealth(), "maxHealth", max == null ? null : max.getValue(), "ai", living.hasAI(),
                    "glowing", entity.isGlowing(), "invisible", living.isInvisible(), "silent", entity.isSilent(), "invulnerable", entity.isInvulnerable(), "persistent", entity.isPersistent()));
        }
        return result;
    }

    public List<Map<String, Object>> activeEvents() { requireMainThread(); return eventsEnabled ? eventEngine.activeEvents() : List.of(); }

    public Object execute(String action, Map<String, Object> p) {
        requireMainThread();
        if (closed) throw new IllegalStateException("MCC отключён");
        Objects.requireNonNull(p, "params");
        if (action.startsWith("world.")) return worldAction(action, p);
        if (action.startsWith("player.")) return playerAction(action, p);
        if (action.startsWith("inventory.")) return inventoryAction(action, p);
        if (action.startsWith("event.")) { if (!eventsEnabled) throw new IllegalStateException("Модуль игровых событий отключён"); return eventEngine.execute(action, p); }
        return switch (action) {
            case "mob.spawn" -> enqueueSpawn(p);
            case "mob.modify" -> modifyMob(p);
            case "chat.broadcast" -> { String text = string(p, "message", 1000); Bukkit.broadcast(Component.text(text)); events.accept("chat", map("at", System.currentTimeMillis(), "name", "MCC", "message", text, "source", "panel")); yield map("delivered", Bukkit.getOnlinePlayers().size()); }
            case "console.command" -> consoleCommand(p);
            case "server.settings" -> serverSettings(p);
            case "players.heal_all" -> { int count = 0; for (Player player : Bukkit.getOnlinePlayers()) { heal(player); count++; } yield map("healed", count); }
            default -> throw new IllegalArgumentException("Неизвестное действие: " + action);
        };
    }

    private Object worldAction(String action, Map<String, Object> p) {
        World w = world(p);
        switch (action) {
            case "world.time" -> w.setTime(integer(p, "time", 0, 23999));
            case "world.weather" -> {
                String weather = string(p, "weather", 20).toLowerCase(Locale.ROOT);
                if (!Set.of("clear", "rain", "thunder").contains(weather)) throw new IllegalArgumentException("weather: clear, rain, thunder");
                int duration = integer(p, "durationTicks", 20, 240000, 6000);
                w.setStorm(!weather.equals("clear")); w.setThundering(weather.equals("thunder"));
                if (weather.equals("clear")) w.setClearWeatherDuration(duration); else { w.setWeatherDuration(duration); w.setThunderDuration(duration); }
            }
            case "world.difficulty" -> w.setDifficulty(enumValue(Difficulty.class, string(p, "difficulty", 20)));
            case "world.gamerule" -> setGameRule(w, p);
            case "world.border" -> {
                double size = decimal(p, "size", 1, 59999968); double x = decimal(p, "x", -29999984, 29999984, w.getWorldBorder().getCenter().getX()); double z = decimal(p, "z", -29999984, 29999984, w.getWorldBorder().getCenter().getZ());
                int seconds = integer(p, "seconds", 0, 3600, 0);
                w.getWorldBorder().setCenter(x, z); w.getWorldBorder().changeSize(size, seconds * 20L);
            }
            case "world.spawn" -> { Location l = location(p); requireLoaded(l); if (!w.setSpawnLocation(l)) throw new IllegalStateException("Spawn не изменён сервером"); }
            case "world.pvp" -> w.setGameRule(GameRules.PVP, bool(p, "enabled", true));
            case "world.save" -> w.save();
            case "world.view_distance" -> w.setViewDistance(integer(p, "distance", 2, 32));
            case "world.simulation_distance" -> w.setSimulationDistance(integer(p, "distance", 2, 32));
            default -> throw new IllegalArgumentException("Неизвестное действие мира");
        }
        Map<String, Object> result = worldDto(w, true); events.accept("world", result); return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setGameRule(World w, Map<String, Object> p) {
        GameRule rule = Registry.GAME_RULE.get(key(string(p, "rule", 100)));
        if (rule == null) throw new IllegalArgumentException("Неизвестный gamerule");
        Object value;
        if (rule.getType() == Boolean.class) value = requiredBoolean(p, "value");
        else if (rule.getType() == Integer.class) {
            String name = rule.getKey().getKey();
            int max = switch (name) {
                case "random_tick_speed" -> 128;
                case "players_sleeping_percentage" -> 100;
                case "max_command_forks" -> 1024;
                case "max_command_sequence_length" -> 65536;
                case "max_block_modifications" -> 32768;
                case "max_entity_cramming" -> 128;
                default -> 10000;
            };
            int min = name.equals("fire_spread_radius_around_player") ? -1 : 0;
            value = integer(p, "value", min, max);
        }
        else throw new IllegalArgumentException("Этот тип gamerule пока не поддержан");
        if (!w.setGameRule(rule, value)) throw new IllegalArgumentException("Значение отклонено сервером");
    }

    private Object playerAction(String action, Map<String, Object> p) {
        if (action.equals("player.unban_ip")) {
            java.net.InetAddress address;
            try { address = java.net.InetAddress.ofLiteral(string(p, "address", 64)); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Требуется IP literal, не hostname"); }
            Bukkit.getBanList(BanListType.IP).pardon(address);
            return map("address", address.getHostAddress(), "banned", Bukkit.getBanList(BanListType.IP).isBanned(address));
        }
        UUID id = parseUuid(string(p, "uuid", 36));
        if (Set.of("player.mute", "player.unmute").contains(action)) {
            String reason = optionalString(p, "reason", 500, "");
            long expiry = 0;
            if (action.equals("player.unmute")) mutes.remove(id);
            else { int seconds = integer(p, "seconds", 0, 31536000, 0); expiry = seconds == 0 ? 0 : System.currentTimeMillis() + seconds * 1000L; mutes.put(id, expiry); }
            var state = map("uuid", id.toString(), "muted", mutes.containsKey(id), "expiresAt", expiry, "reason", reason);
            moderationPersistence.accept(id, state); events.accept("player", map("uuid", id.toString(), "moderation", state)); return state;
        }
        if (Set.of("player.ban", "player.unban", "player.op", "player.deop").contains(action)) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(id);
            switch (action) {
                case "player.ban" -> { String reason = optionalString(p, "reason", 500, "Заблокирован администратором MCC"); int seconds = integer(p, "seconds", 0, 31536000, 0); target.ban(reason, seconds == 0 ? (Duration) null : Duration.ofSeconds(seconds), "MCC"); Player live = target.getPlayer(); if (live != null) live.kick(Component.text(reason)); }
                case "player.unban" -> Bukkit.getBanList(BanListType.PROFILE).pardon(target.getPlayerProfile());
                case "player.op" -> target.setOp(true);
                case "player.deop" -> target.setOp(false);
                default -> throw new IllegalArgumentException("Недопустимое действие");
            }
            return map("uuid", id.toString(), "banned", target.isBanned(), "op", target.isOp());
        }
        Player player = online(p);
        switch (action) {
            case "player.heal" -> heal(player);
            case "player.feed" -> { player.setFoodLevel(20); player.setSaturation(20); }
            case "player.kick" -> player.kick(Component.text(optionalString(p, "reason", 500, "Отключён администратором MCC")));
            case "player.ipban", "player.ban_ip" -> {
                String reason = optionalString(p, "reason", 500, "IP заблокирован администратором MCC");
                int seconds = integer(p, "seconds", 0, 31536000, 0);
                var address = player.getAddress(); if (address == null) throw new IllegalStateException("IP игрока недоступен");
                var entry = player.banIp(reason, seconds == 0 ? (Duration) null : Duration.ofSeconds(seconds), "MCC", true);
                if (entry == null || !Bukkit.getBanList(BanListType.IP).isBanned(address.getAddress())) throw new IllegalStateException("IP ban не подтверждён сервером");
                return map("uuid", id.toString(), "address", address.getAddress().getHostAddress(), "banned", true);
            }
            case "player.kill" -> player.setHealth(0);
            case "player.ignite" -> player.setFireTicks(integer(p, "ticks", 1, 12000, 200));
            case "player.extinguish" -> player.setFireTicks(0);
            case "player.gamemode" -> player.setGameMode(enumValue(GameMode.class, string(p, "gamemode", 20)));
            case "player.teleport" -> {
                Location dest = p.containsKey("targetUuid") ? online(map("uuid", string(p, "targetUuid", 36))).getLocation() : location(p);
                requireLoaded(dest);
                if (!player.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN)) throw new IllegalStateException("Телепортация отменена сервером или другим плагином");
            }
            case "player.spawnpoint" -> { Location dest = p.containsKey("world") ? location(p) : player.getLocation(); requireLoaded(dest); player.setRespawnLocation(dest, true); }
            case "player.xp" -> { int amount = integer(p, "amount", -100000, 100000); if ((long) player.calculateTotalExperiencePoints() + amount < 0) throw new IllegalArgumentException("Опыт не может быть отрицательным"); player.giveExp(amount); }
            case "player.level" -> player.setLevel(integer(p, "level", 0, 10000));
            case "player.flight" -> { boolean enabled = requiredBoolean(p, "enabled"); player.setAllowFlight(enabled); if (!enabled) player.setFlying(false); }
            case "player.speed" -> { String kind = optionalString(p, "kind", 10, "walk"); float speed = (float) decimal(p, "speed", -1, 1); if (kind.equals("walk")) player.setWalkSpeed(speed); else if (kind.equals("fly")) player.setFlySpeed(speed); else throw new IllegalArgumentException("kind: walk или fly"); }
            case "player.effect" -> { PotionEffectType type = Registry.EFFECT.get(key(string(p, "effect", 100))); if (type == null) throw new IllegalArgumentException("Неизвестный эффект"); if (bool(p, "remove", false)) player.removePotionEffect(type); else player.addPotionEffect(new PotionEffect(type, integer(p, "duration", 1, 72000, 600), integer(p, "amplifier", 0, 9, 0), bool(p, "ambient", false), bool(p, "particles", true))); }
            case "player.clear_inventory" -> { inventoryOf(player, optionalString(p, "kind", 10, "main")).clear(); player.updateInventory(); }
            case "player.give" -> { ItemStack item = createItem(object(p, "item")); int requested = item.getAmount(); var left = player.getInventory().addItem(item); player.updateInventory(); events.accept("inventory", map("uuid", id.toString(), "kind", "main")); return map("given", requested - left.values().stream().mapToInt(ItemStack::getAmount).sum(), "leftover", left.values().stream().map(GameService::itemDto).toList()); }
            case "player.remove_item" -> { Material material = material(string(p, "material", 100)); int count = integer(p, "amount", 1, 4096, 1); int removed = 0; Inventory inv = inventoryOf(player, optionalString(p, "kind", 10, "main")); for (int i = 0; i < inv.getSize() && removed < count; i++) { ItemStack item = inv.getItem(i); if (item != null && item.getType() == material) { int n = Math.min(item.getAmount(), count - removed); removed += n; if (n == item.getAmount()) inv.setItem(i, null); else { item.setAmount(item.getAmount() - n); inv.setItem(i, item); } } } player.updateInventory(); events.accept("inventory", map("uuid", id.toString(), "kind", optionalString(p, "kind", 10, "main"))); return map("removed", removed); }
            case "player.message" -> player.sendMessage(Component.text(string(p, "message", 1000)));
            case "player.title" -> { String title = optionalString(p, "title", 200, ""); String subtitle = optionalString(p, "subtitle", 300, ""); int duration = integer(p, "seconds", 1, 30, 3); player.showTitle(Title.title(Component.text(title), Component.text(subtitle), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(duration), Duration.ofMillis(500)))); }
            case "player.actionbar" -> player.sendActionBar(Component.text(string(p, "message", 500)));
            case "player.sound" -> { Sound sound = Registry.SOUNDS.get(key(string(p, "sound", 150))); if (sound == null) throw new IllegalArgumentException("Неизвестный звук"); float volume = (float) decimal(p, "volume", 0, 4, 1); float pitch = (float) decimal(p, "pitch", 0.5, 2, 1); player.playSound(player.getLocation(), sound, volume, pitch); }
            case "player.glowing" -> player.setGlowing(requiredBoolean(p, "enabled"));
            case "player.invisible" -> player.setInvisible(requiredBoolean(p, "enabled"));
            case "player.freeze" -> frozen.add(id);
            case "player.unfreeze" -> frozen.remove(id);
            case "player.knockback" -> { double x = decimal(p, "x", -4, 4, 0); double y = decimal(p, "y", -1, 2, 0.5); double z = decimal(p, "z", -4, 4, 0); player.setVelocity(new Vector(x, y, z)); }
            case "player.firework" -> firework(player.getLocation());
            case "player.particles" -> { Particle particle = enumValue(Particle.class, optionalString(p, "particle", 80, "HAPPY_VILLAGER")); if (particle.getDataType() != Void.class) throw new IllegalArgumentException("Этому particle нужны дополнительные данные"); player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), integer(p, "count", 1, 100, 20), 0.5, 0.8, 0.5, 0); }
            default -> throw new IllegalArgumentException("Неизвестное действие игрока: " + action);
        }
        var result = player(player.getUniqueId().toString()); events.accept("player", result); return result;
    }

    private Object inventoryAction(String action, Map<String, Object> p) {
        Player player = online(p); String kind = optionalString(p, "kind", 10, "main"); Inventory inventory = inventoryOf(player, kind);
        String expected = string(p, "revision", 64);
        if (!expected.equals(revision(inventory))) throw new InventoryConflictException("Инвентарь изменился. Обновите его перед редактированием.");
        switch (action) {
            case "inventory.set" -> { int slot = integer(p, "slot", 0, inventory.getSize() - 1); ItemStack original = inventory.getItem(slot); ItemStack item = p.get("item") == null ? null : editItem(original, object(p, "item")); inventory.setItem(slot, item); }
            case "inventory.move" -> { int from = integer(p, "from", 0, inventory.getSize() - 1); int to = integer(p, "to", 0, inventory.getSize() - 1); ItemStack source = inventory.getItem(from); ItemStack target = inventory.getItem(to); inventory.setItem(from, target); inventory.setItem(to, source); }
            default -> throw new IllegalArgumentException("Неизвестная операция инвентаря");
        }
        player.updateInventory(); var result = inventory(player.getUniqueId().toString(), kind); events.accept("inventory", map("uuid", player.getUniqueId().toString(), "kind", kind)); return result;
    }

    private Object consoleCommand(Map<String, Object> p) {
        String command = string(p, "command", 1024).strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.contains("\n") || command.contains("\r") || command.indexOf('\0') >= 0) throw new IllegalArgumentException("Команда должна занимать одну строку");
        String label = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) throw new IllegalArgumentException("Namespaced команды не разрешены");
        Set<String> dangerous = Set.of("stop", "restart", "reload", "rl", "execute", "data", "function", "schedule", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "whitelist", "save-off", "forceload", "fill", "clone", "setblock", "worldborder", "permission", "luckperms", "lp", "plugman");
        if (dangerous.contains(label) || !consoleAllowed.contains(label)) throw new IllegalArgumentException("Команда отсутствует в разрешённом списке. Используйте типизированные действия MCC.");
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        var sender = Bukkit.createCommandSender(component -> { String line = PlainTextComponentSerializer.plainText().serialize(component); synchronized (output) { if (output.size() < 100) output.add(line.length() > 2000 ? line.substring(0, 2000) : line); } events.accept("console", map("at", System.currentTimeMillis(), "level", "INFO", "message", line)); });
        boolean dispatched = Bukkit.dispatchCommand(sender, command);
        return map("dispatched", dispatched, "command", command, "output", List.copyOf(output));
    }

    private Object serverSettings(Map<String, Object> p) {
        String motd = p.containsKey("motd") ? optionalString(p, "motd", 500, "") : null;
        Boolean whitelist = p.containsKey("whitelist") ? requiredBoolean(p, "whitelist") : null;
        Integer maxPlayers = p.containsKey("maxPlayers") ? integer(p, "maxPlayers", 1, 1000) : null;
        if (motd == null && whitelist == null && maxPlayers == null) throw new IllegalArgumentException("Укажите motd, whitelist или maxPlayers");
        if (motd != null) Bukkit.motd(Component.text(motd));
        if (whitelist != null) Bukkit.setWhitelist(whitelist);
        if (maxPlayers != null) Bukkit.setMaxPlayers(maxPlayers);
        return map("motd", PlainTextComponentSerializer.plainText().serialize(Bukkit.motd()), "whitelist", Bukkit.hasWhitelist(), "maxPlayers", Bukkit.getMaxPlayers(), "persistence", "MOTD/maxPlayers apply at runtime; Paper persists whitelist to server.properties");
    }

    private Object enqueueSpawn(Map<String, Object> p) {
        if (Bukkit.getTPS()[0] < 18) throw new IllegalStateException("Performance Guard: спавн приостановлен при TPS < 18");
        if (spawnJobs.size() >= 4) throw new IllegalStateException("Очередь спавна заполнена");
        EntityType type = enumValue(EntityType.class, string(p, "type", 80));
        if (!allowedMob(type)) throw new IllegalArgumentException("Этот entity type не разрешён для спавна MCC");
        Location location = p.containsKey("uuid") ? online(p).getLocation() : location(p); requireLoaded(location);
        int count = integer(p, "count", 1, Math.min(128, plugin.getConfig().getInt("game.max-spawn-count", 128)), 1);
        double radius = decimal(p, "radius", 0, 16, 0);
        MobSettings settings = MobSettings.parse(p);
        if (location.getWorld().getEntityCount() > plugin.getConfig().getInt("game.max-world-entities", 10000)) throw new IllegalStateException("Достигнут лимит сущностей мира");
        SpawnJob job = new SpawnJob(UUID.randomUUID().toString(), type, location.clone(), count, radius, settings);
        spawnJobs.add(job); events.accept("spawn", job.dto("queued")); return job.dto("queued");
    }

    private Object modifyMob(Map<String, Object> p) {
        Entity entity = Bukkit.getEntity(parseUuid(string(p, "entityUuid", 36)));
        if (!(entity instanceof LivingEntity living) || entity instanceof Player) throw new IllegalArgumentException("Нужен загруженный моб, не игрок");
        String name = p.containsKey("name") ? optionalString(p, "name", 100, "") : null;
        Double health = p.containsKey("health") ? decimal(p, "health", 0, living.getAttribute(Attribute.MAX_HEALTH).getValue()) : null;
        Boolean ai = p.containsKey("ai") ? requiredBoolean(p, "ai") : null;
        Boolean glowing = p.containsKey("glowing") ? requiredBoolean(p, "glowing") : null;
        Boolean invisible = p.containsKey("invisible") ? requiredBoolean(p, "invisible") : null;
        Boolean silent = p.containsKey("silent") ? requiredBoolean(p, "silent") : null;
        Boolean invulnerable = p.containsKey("invulnerable") ? requiredBoolean(p, "invulnerable") : null;
        boolean remove = bool(p, "remove", false);
        if (name != null) { living.customName(name.isEmpty() ? null : Component.text(name)); living.setCustomNameVisible(!name.isEmpty()); }
        if (health != null) living.setHealth(health); if (ai != null) living.setAI(ai); if (glowing != null) living.setGlowing(glowing); if (invisible != null) living.setInvisible(invisible); if (silent != null) living.setSilent(silent); if (invulnerable != null) living.setInvulnerable(invulnerable);
        if (remove) living.remove();
        return map("uuid", entity.getUniqueId().toString(), "type", entity.getType().name(), "valid", entity.isValid(), "health", living.getHealth(), "ai", living.hasAI(), "glowing", living.isGlowing());
    }

    private void tick() {
        if (closed) return;
        ticks++;
        long deadline = System.nanoTime() + 750000;
        int spawned = 0;
        while (!spawnJobs.isEmpty() && spawned < 5 && System.nanoTime() < deadline) {
            SpawnJob job = spawnJobs.peek();
            if (Bukkit.getTPS()[0] < 18) break;
            Location loc = job.location.clone().add((Math.random() - 0.5) * job.radius * 2, 0, (Math.random() - 0.5) * job.radius * 2);
            try {
                requireLoaded(loc);
                if (loc.getWorld().getEntityCount() >= plugin.getConfig().getInt("game.max-world-entities", 10000)) throw new IllegalStateException("Достигнут лимит сущностей мира");
                Entity entity = loc.getWorld().spawnEntity(loc, job.type);
                if (entity.isValid()) { try { job.settings.apply(entity); job.spawned++; job.entityUuids.add(entity.getUniqueId().toString()); } catch (RuntimeException ex) { entity.remove(); throw ex; } } else job.failed++;
            } catch (RuntimeException ex) { job.failed++; job.lastError = ex.getMessage(); }
            job.remaining--; spawned++;
            if (job.remaining == 0) { spawnJobs.remove(); events.accept("spawn", job.dto(job.failed == 0 ? "completed" : "partial")); }
        }
        if (ticks % 10 == 0 && !inventoryDirty.isEmpty()) { for (UUID id : List.copyOf(inventoryDirty)) events.accept("inventory", map("uuid", id.toString(), "kind", "main")); inventoryDirty.clear(); }
        if (ticks % 1200 == 0) {
            long now = System.currentTimeMillis();
            for (var e : List.copyOf(mutes.entrySet())) if (e.getValue() != 0 && e.getValue() <= now && mutes.remove(e.getKey(), e.getValue())) moderationPersistence.accept(e.getKey(), map("uuid", e.getKey().toString(), "muted", false, "expiresAt", 0, "reason", "Истёк срок mute"));
        }
    }

    private static final class SpawnJob {
        final String id; final EntityType type; final Location location; final int requested; final double radius; final MobSettings settings; final List<String> entityUuids = new ArrayList<>(); int remaining, spawned, failed; String lastError;
        SpawnJob(String id, EntityType type, Location location, int count, double radius, MobSettings settings) { this.id = id; this.type = type; this.location = location; this.requested = this.remaining = count; this.radius = radius; this.settings = settings; }
        Map<String, Object> dto(String status) { return map("id", id, "status", status, "type", type.name(), "requested", requested, "spawned", spawned, "failed", failed, "remaining", remaining, "entityUuids", List.copyOf(entityUuids), "error", lastError); }
    }

    private record MobSettings(String name, Double health, Boolean ai, Boolean persistent, Boolean glowing) {
        static MobSettings parse(Map<String, Object> p) { return new MobSettings(p.containsKey("name") ? optionalString(p, "name", 100, "") : null,
                p.containsKey("health") ? decimal(p, "health", 0.1, 2048) : null, p.containsKey("ai") ? requiredBoolean(p, "ai") : null,
                p.containsKey("persistent") ? requiredBoolean(p, "persistent") : null, p.containsKey("glowing") ? requiredBoolean(p, "glowing") : null); }
        void apply(Entity entity) {
            if (!(entity instanceof LivingEntity living)) throw new IllegalArgumentException("Нужен living entity");
            if (name != null) { living.customName(name.isEmpty() ? null : Component.text(name)); living.setCustomNameVisible(!name.isEmpty()); }
            if (health != null) { var attribute = living.getAttribute(Attribute.MAX_HEALTH); if (attribute == null) throw new IllegalArgumentException("Сущность не поддерживает max health"); attribute.setBaseValue(health); living.setHealth(Math.min(health, attribute.getValue())); }
            if (ai != null) living.setAI(ai);
            if (persistent != null) { living.setPersistent(persistent); living.setRemoveWhenFarAway(!persistent); }
            if (glowing != null) living.setGlowing(glowing);
        }
    }

    public static ItemStack createItem(Map<String, Object> p) {
        return editItem(null, p);
    }

    /** Clone same-material stacks so editing quantity never drops custom components. */
    private static ItemStack editItem(ItemStack original, Map<String, Object> p) {
        Material material = material(string(p, "material", 100));
        if (!material.isItem() || material.isAir()) throw new IllegalArgumentException("Нужен material предмета");
        ItemStack item = original != null && original.getType() == material ? original.clone() : new ItemStack(material);
        item.setAmount(integer(p, "amount", 1, item.getMaxStackSize(), 1));
        ItemMeta meta = item.getItemMeta();
        if (p.containsKey("name")) { String name = optionalString(p, "name", 200, ""); String existing = meta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : ""; if (!name.equals(existing)) meta.displayName(name.isEmpty() ? null : Component.text(name)); }
        if (p.containsKey("lore")) { List<?> lore = list(p, "lore", 20); List<Component> lines = lore.stream().<Component>map(x -> { if (!(x instanceof String s) || s.length() > 300) throw new IllegalArgumentException("Lore: максимум 20 строк по 300 символов"); return Component.text(s); }).toList(); List<String> existing = meta.hasLore() ? meta.lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList() : List.of(); if (!lore.equals(existing)) meta.lore(lines); }
        if (p.containsKey("unbreakable")) meta.setUnbreakable(requiredBoolean(p, "unbreakable"));
        if (p.containsKey("damage")) { int max = meta instanceof Damageable damage && damage.hasMaxDamage() ? damage.getMaxDamage() : material.getMaxDurability(); int n = integer(p, "damage", 0, max); if (meta instanceof Damageable damage) damage.setDamage(n); else if (n != 0) throw new IllegalArgumentException("Предмет не имеет прочности"); }
        if (p.containsKey("enchantments")) { var ench = object(p, "enchantments"); if (ench.size() > 20) throw new IllegalArgumentException("Максимум 20 enchantments"); Map<String,Integer> existing = new TreeMap<>(); meta.getEnchants().forEach((enchantment, level) -> existing.put(enchantment.getKey().toString(), level)); if (!existing.equals(ench)) { for (var enchantment : Set.copyOf(meta.getEnchants().keySet())) meta.removeEnchant(enchantment); for (var e : ench.entrySet()) { var type = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key(e.getKey())); if (type == null) throw new IllegalArgumentException("Неизвестное зачарование " + e.getKey()); int level = integer(map("level", e.getValue()), "level", 1, type.getMaxLevel()); if (!type.canEnchantItem(item)) throw new IllegalArgumentException("Зачарование несовместимо с предметом"); meta.addEnchant(type, level, false); } } }
        if (p.containsKey("flags")) { meta.removeItemFlags(meta.getItemFlags().toArray(ItemFlag[]::new)); for (Object flag : list(p, "flags", 15)) meta.addItemFlags(enumValue(ItemFlag.class, String.valueOf(flag))); }
        if (p.containsKey("attributes")) { meta.setAttributeModifiers(null); int i = 0; for (Object entry : list(p, "attributes", 12)) { if (!(entry instanceof Map<?, ?> raw)) throw new IllegalArgumentException("attribute должен быть объектом"); @SuppressWarnings("unchecked") Map<String,Object> a = (Map<String,Object>)raw; Attribute attribute = Registry.ATTRIBUTE.get(key(string(a, "attribute", 100))); if (attribute == null) throw new IllegalArgumentException("Неизвестный attribute"); double amount = decimal(a, "amount", -100, 100); var operation = enumValue(AttributeModifier.Operation.class, optionalString(a, "operation", 40, "ADD_NUMBER")); meta.addAttributeModifier(attribute, new AttributeModifier(new NamespacedKey("mcc", "item_attribute_" + i++), amount, operation)); } }
        item.setItemMeta(meta); return item;
    }

    private static Map<String, Object> itemDto(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        Map<String, Integer> enchantments = new TreeMap<>(); item.getEnchantments().forEach((e, level) -> enchantments.put(e.getKey().toString(), level));
        return map("material", item.getType().name(), "amount", item.getAmount(), "maxStackSize", item.getMaxStackSize(),
                "name", meta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : "",
                "lore", meta.hasLore() ? meta.lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList() : List.of(),
                "enchantments", enchantments, "damage", meta instanceof Damageable d ? d.getDamage() : 0, "unbreakable", meta.isUnbreakable());
    }

    private static String revision(Inventory inventory) {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); for (int i = 0; i < inventory.getSize(); i++) { digest.update(ByteBuffer.allocate(4).putInt(i).array()); ItemStack stack = inventory.getItem(i); if (stack == null || stack.getType().isAir()) digest.update((byte) 0); else { byte[] bytes = stack.serializeAsBytes(); digest.update((byte) 1); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); } } return HexFormat.of().formatHex(digest.digest()); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static Inventory inventoryOf(Player player, String kind) { if (kind == null || kind.equals("main")) return player.getInventory(); if (kind.equals("ender")) return player.getEnderChest(); throw new IllegalArgumentException("kind: main или ender"); }
    private static void heal(Player p) { var attribute = p.getAttribute(Attribute.MAX_HEALTH); p.setHealth(attribute == null ? 20 : attribute.getValue()); p.setFireTicks(0); }
    public static Firework firework(Location location) { requireLoaded(location); Firework rocket = location.getWorld().spawn(location, Firework.class); FireworkMeta meta = rocket.getFireworkMeta(); meta.setPower(0); meta.addEffect(FireworkEffect.builder().withColor(Color.LIME, Color.GREEN).with(FireworkEffect.Type.BALL).trail(true).build()); rocket.setFireworkMeta(meta); rocket.setSilent(true); rocket.getPersistentDataContainer().set(new NamespacedKey("mcc", "visual_firework"), org.bukkit.persistence.PersistentDataType.BYTE, (byte)1); return rocket; }
    public static boolean allowedMob(EntityType type) { return type.isAlive() && type.isSpawnable() && type != EntityType.PLAYER && type != EntityType.ENDER_DRAGON && type != EntityType.WITHER; }
    public static Player online(Map<String, Object> p) { Player player = Bukkit.getPlayer(parseUuid(string(p, "uuid", 36))); if (player == null) throw new IllegalArgumentException("Игрок не в сети"); return player; }
    public static World world(Map<String, Object> p) { World w = Bukkit.getWorld(string(p, "world", 100)); if (w == null) throw new IllegalArgumentException("Мир не загружен"); return w; }
    public static Location location(Map<String, Object> p) { World w = world(p); return new Location(w, decimal(p, "x", -29999984, 29999984), decimal(p, "y", w.getMinHeight(), w.getMaxHeight() - 1), decimal(p, "z", -29999984, 29999984), (float) decimal(p, "yaw", -360, 360, 0), (float) decimal(p, "pitch", -90, 90, 0)); }
    public static void requireLoaded(Location l) { if (l.getWorld() == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) throw new IllegalArgumentException("Chunk назначения не загружен. MCC не подгружает chunks для этой операции."); }
    public static Map<String, Object> locationDto(Location l) { return map("world", l.getWorld().getName(), "x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", l.getYaw(), "pitch", l.getPitch()); }
    public static NamespacedKey key(String input) { NamespacedKey key = NamespacedKey.fromString(input.toLowerCase(Locale.ROOT)); if (key == null) throw new IllegalArgumentException("Некорректный registry key"); return key; }
    public static Material material(String input) { Material material = Material.matchMaterial(input); if (material == null) throw new IllegalArgumentException("Неизвестный material"); return material; }
    public static UUID parseUuid(String input) { try { UUID result = UUID.fromString(input); if (!result.toString().equalsIgnoreCase(input)) throw new IllegalArgumentException(); return result; } catch (RuntimeException ex) { throw new IllegalArgumentException("Некорректный UUID"); } }
    public static String string(Map<String, Object> p, String key, int max) { Object value = p.get(key); if (!(value instanceof String s) || s.isBlank() || s.length() > max || s.indexOf('\0') >= 0) throw new IllegalArgumentException(key + ": нужна непустая строка длиной до " + max); return s; }
    public static String optionalString(Map<String, Object> p, String key, int max, String fallback) { if (!p.containsKey(key)) return fallback; Object value = p.get(key); if (!(value instanceof String s) || s.length() > max || s.indexOf('\0') >= 0) throw new IllegalArgumentException(key + ": строка длиной до " + max); return s; }
    public static int integer(Map<String, Object> p, String key, int min, int max) { double n = decimal(p, key, min, max); if (n != Math.rint(n)) throw new IllegalArgumentException(key + ": требуется целое число"); return (int) n; }
    public static int integer(Map<String, Object> p, String key, int min, int max, int fallback) { return p.containsKey(key) ? integer(p, key, min, max) : fallback; }
    public static double decimal(Map<String, Object> p, String key, double min, double max) { Object value = p.get(key); if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max) throw new IllegalArgumentException(key + ": число от " + min + " до " + max); return n.doubleValue(); }
    public static double decimal(Map<String, Object> p, String key, double min, double max, double fallback) { return p.containsKey(key) ? decimal(p, key, min, max) : fallback; }
    public static boolean requiredBoolean(Map<String, Object> p, String key) { Object value = p.get(key); if (!(value instanceof Boolean b)) throw new IllegalArgumentException(key + ": нужен boolean"); return b; }
    public static boolean bool(Map<String, Object> p, String key, boolean fallback) { return p.containsKey(key) ? requiredBoolean(p, key) : fallback; }
    @SuppressWarnings("unchecked") public static Map<String, Object> object(Map<String, Object> p, String key) { Object value = p.get(key); if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException(key + ": нужен объект"); return (Map<String, Object>) m; }
    public static List<?> list(Map<String, Object> p, String key, int max) { if (!(p.get(key) instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException(key + ": нужен список длиной до " + max); return list; }
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) { try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Недопустимое значение " + type.getSimpleName() + ": " + value); } }
    public static Map<String, Object> map(Object... pairs) { if (pairs.length % 2 != 0) throw new IllegalArgumentException("pairs"); Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]); return map; }
    public static void requireMainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Minecraft API must run on the Paper main thread"); }
    private boolean muted(UUID id) { Long expiry = mutes.get(id); return muteLookup.test(id) || expiry != null && (expiry == 0 || expiry > System.currentTimeMillis()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onMapPlace(BlockPlaceEvent e) { mapEngine.invalidateTerrain(e.getBlock().getWorld().getName(),e.getBlock().getX(),e.getBlock().getZ()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onMapBreak(BlockBreakEvent e) { mapEngine.invalidateTerrain(e.getBlock().getWorld().getName(),e.getBlock().getX(),e.getBlock().getZ()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent e) { Player p = e.getPlayer(); knownNames.put(p.getUniqueId(), p.getName()); joinedAt.put(p.getUniqueId(), System.currentTimeMillis()); events.accept("join", playerDto(p, false)); events.accept("trigger", map("trigger", "join", "uuid", p.getUniqueId().toString(), "world", p.getWorld().getName(), "name", p.getName())); }
    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e) { Player p = e.getPlayer(); events.accept("quit", map("uuid", p.getUniqueId().toString(), "name", p.getName(), "at", System.currentTimeMillis())); events.accept("trigger", map("trigger", "quit", "uuid", p.getUniqueId().toString(), "world", p.getWorld().getName(), "name", p.getName())); joinedAt.remove(p.getUniqueId()); frozen.remove(p.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onDeath(PlayerDeathEvent e) { Player p = e.getPlayer(); events.accept("death", map("uuid", p.getUniqueId().toString(), "name", p.getName(), "at", System.currentTimeMillis())); events.accept("trigger", map("trigger", "death", "uuid", p.getUniqueId().toString(), "world", p.getWorld().getName(), "name", p.getName())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onWeather(WeatherChangeEvent e) { Bukkit.getScheduler().runTask(plugin, () -> events.accept("world", worldDto(e.getWorld(), false))); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onMove(PlayerMoveEvent e) { if (frozen.contains(e.getPlayer().getUniqueId()) && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ())) { Location l = e.getFrom().clone(); l.setYaw(e.getTo().getYaw()); l.setPitch(e.getTo().getPitch()); e.setTo(l); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onChat(AsyncChatEvent e) { UUID id = e.getPlayer().getUniqueId(); if (muted(id)) { e.setCancelled(true); Bukkit.getScheduler().runTask(plugin, () -> { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(Component.text("Чат временно заблокирован администратором.")); }); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onChatRecorded(AsyncChatEvent e) { UUID id = e.getPlayer().getUniqueId(); String text = PlainTextComponentSerializer.plainText().serialize(e.message()); String name = knownNames.getOrDefault(id, id.toString()); Bukkit.getScheduler().runTask(plugin, () -> events.accept("chat", map("uuid", id.toString(), "name", name, "message", text, "at", System.currentTimeMillis(), "source", "minecraft"))); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onClick(InventoryClickEvent e) { inventoryDirty.add(e.getWhoClicked().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDrag(InventoryDragEvent e) { inventoryDirty.add(e.getWhoClicked().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent e) { inventoryDirty.add(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) { if (e.getEntity() instanceof Player player) inventoryDirty.add(player.getUniqueId()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onEventMobPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player) && e.getEntity().getPersistentDataContainer().has(new NamespacedKey("mcc", "game_event"), org.bukkit.persistence.PersistentDataType.STRING)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void onBreak(PlayerItemBreakEvent e) { inventoryDirty.add(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onEventItemMerge(org.bukkit.event.entity.ItemMergeEvent e) {
        NamespacedKey ownerKey = new NamespacedKey("mcc", "game_event");
        String source = e.getEntity().getPersistentDataContainer().get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
        String target = e.getTarget().getPersistentDataContainer().get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
        // Otherwise cleanup could remove a player's dropped stack merged into an event reward.
        if (!Objects.equals(source, target)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onVisualFireworkDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Firework firework && firework.getPersistentDataContainer().has(new NamespacedKey("mcc", "visual_firework"), org.bukkit.persistence.PersistentDataType.BYTE)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR) public void onEventEntitiesLoaded(org.bukkit.event.world.EntitiesLoadEvent e) { for (Entity entity : e.getEntities()) { String owner = entity.getPersistentDataContainer().get(new NamespacedKey("mcc", "game_event"), org.bukkit.persistence.PersistentDataType.STRING); if (owner != null && !(entity instanceof Player) && (!eventsEnabled || !eventEngine.isRunning(owner))) entity.remove(); } }

    @Override public void close() { requireMainThread(); if (closed) return; closed = true; tickTask.cancel(); eventEngine.close(); mapEngine.close(); HandlerList.unregisterAll(this); spawnJobs.clear(); frozen.clear(); inventoryDirty.clear(); }
    public static final class InventoryConflictException extends IllegalStateException { public InventoryConflictException(String message) { super(message); } }
}
