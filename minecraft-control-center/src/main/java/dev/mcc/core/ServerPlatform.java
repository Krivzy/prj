package dev.mcc.core;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Public Bukkit surface plus optional documented public methods. No NMS or Paper linkage. */
public final class ServerPlatform {
    private ServerPlatform() {}
    private record MethodKey(Class<?> type, String name) {}
    private static final Map<MethodKey, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Pattern VERSION = Pattern.compile("^([0-9]+(?:\\.[0-9]+){1,2})(?:[-+].*)?$");
    private static final TickRate ticks = new TickRate();
    private static volatile long observedTick;
    private static final Map<UUID, Count> entityCounts = new HashMap<>();
    private record Count(long at, int count) {}
    public static String minecraftVersion() {
        Object optional = optional(Bukkit.getServer(), "getMinecraftVersion");
        if (optional instanceof String value && VERSION.matcher(value).matches()) return parseMinecraftVersion(value);
        return parseMinecraftVersion(Bukkit.getBukkitVersion());
    }
    public static String parseMinecraftVersion(String value) { var match = VERSION.matcher(value == null ? "" : value); return match.matches() ? match.group(1) : "unknown"; }
    public static String brand() { return displayBrand(Bukkit.getName(),dev.mcc.server.MccCoreLink.active()); }
    /** The Core brand requires the in-process handshake; a property or a filename is not proof. */
    static String displayBrand(String reported,boolean verifiedCore) { return verifiedCore ? "MCC Core" : reported == null || reported.isBlank() ? "Unknown Bukkit server" : reported; }
    /** Main-thread tick observation is a truthful fallback, never an MSPT measurement. */
    public static void tick() { observedTick++; ticks.tick(System.nanoTime()); }
    public static long currentTick() { Object api = optional(Bukkit.getServer(), "getCurrentTick"); return api instanceof Number number ? number.longValue() : observedTick; }
    public static double[] tps() { Object api = optional(Bukkit.getServer(), "getTPS"); double[] values = validTps(api); return values.length == 0 ? ticks.values() : values; }
    public static Double mspt() { Object api = optional(Bukkit.getServer(), "getAverageTickTime"); return api instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0 ? number.doubleValue() : null; }
    public static boolean lowTps() { double[] values = tps(); return values.length > 0 && values[0] < 18; }
    public static boolean performanceGuard() { Double mspt = mspt(); return lowTps() || mspt != null && mspt > 45; }
    public static Map<String, Object> metrics() {
        double[] nativeTps = validTps(optional(Bukkit.getServer(), "getTPS")); double[] values = nativeTps.length > 0 ? nativeTps : ticks.values(); Double mspt = mspt();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("tps", Arrays.stream(values).boxed().toList()); result.put("mspt", mspt);
        result.put("tpsAvailable", values.length > 0); result.put("tpsSource", nativeTps.length > 0 ? "server-api" : values.length > 0 ? "scheduler-observation" : "unavailable");
        result.put("msptAvailable", mspt != null); result.put("msptSource", mspt == null ? "unavailable" : "server-api"); result.put("brand", brand()); result.put("minecraftVersion", minecraftVersion());
        return Collections.unmodifiableMap(result);
    }
    static double[] validTps(Object value) { if (!(value instanceof double[] values) || values.length == 0 || Arrays.stream(values).anyMatch(v -> !Double.isFinite(v) || v < 0)) return new double[0]; return Arrays.stream(values).limit(3).map(v -> Math.min(20, v)).toArray(); }
    public static Object optional(Object target, String name) {
        if (target == null) return null;
        try { Optional<Method> method = METHODS.computeIfAbsent(new MethodKey(target.getClass(), name), key -> { try { return Optional.of(key.type.getMethod(key.name)); } catch (NoSuchMethodException | SecurityException error) { return Optional.empty(); } }); return method.isEmpty() ? null : method.get().invoke(target); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException error) { return null; }
    }
    /** Optional Paper public world setter; never silently substitutes a different setting. */
    public static void worldDistance(World world, boolean simulation, int value) {
        String method = simulation ? "setSimulationDistance" : "setViewDistance";
        try { world.getClass().getMethod(method, int.class).invoke(world, value); }
        catch (NoSuchMethodException | IllegalAccessException error) { throw new UnsupportedOperationException("This server does not expose a public world distance setter", error); }
        catch (InvocationTargetException error) { if (error.getCause() instanceof RuntimeException cause) throw cause; throw new IllegalStateException("World distance update failed", error.getCause()); }
    }
    /** Historical renderer only. If unavailable, reject; synchronous chunk loading is forbidden. */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Chunk> asyncExistingChunk(World world, int x, int z) {
        try { Object result = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class, boolean.class).invoke(world, x, z, false, false); if (result instanceof CompletableFuture<?> future) return (CompletableFuture<Chunk>) future; return CompletableFuture.failedFuture(new UnsupportedOperationException("Public asynchronous chunk API returned no future")); }
        catch (ReflectiveOperationException error) { return CompletableFuture.failedFuture(new UnsupportedOperationException("Public asynchronous chunk loading is unavailable; no synchronous fallback", error)); }
    }
    /** Native O(1) getter where offered; Bukkit loaded-entity fallback cached for 500 ms. */
    public static int entityCount(World world) {
        Object value = optional(world, "getEntityCount"); if (value instanceof Number number) return number.intValue();
        long now = System.nanoTime(); Count cached = entityCounts.get(world.getUID()); if (cached != null && now - cached.at < 500_000_000L) return cached.count;
        int count = world.getEntities().size(); if (entityCounts.size() >= 64 && !entityCounts.containsKey(world.getUID())) entityCounts.clear(); entityCounts.put(world.getUID(), new Count(now, count)); return count;
    }
    public static int totalExperience(Player player) {
        Object nativeValue = optional(player, "calculateTotalExperiencePoints"); if (nativeValue instanceof Number number) return number.intValue();
        int level = player.getLevel(); double total = level <= 16 ? level * (double) level + 6 * level : level <= 31 ? 2.5 * level * level - 40.5 * level + 360 : 4.5 * level * level - 162.5 * level + 2220;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.round(total + player.getExp() * player.getExpToLevel())));
    }
    public static byte[] itemBytes(ItemStack item) {
        Object nativeValue = optional(item, "serializeAsBytes"); if (nativeValue instanceof byte[] bytes) return bytes;
        try { var bytes = new ByteArrayOutputStream(); try (var stream = new BukkitObjectOutputStream(bytes)) { stream.writeObject(item); } return bytes.toByteArray(); }
        catch (IOException error) { throw new IllegalStateException("Cannot compute inventory revision", error); }
    }
    /** Paper covariantly changes OfflinePlayer#getPlayerProfile; avoid its binary descriptor. */
    public static PlayerProfile profile(OfflinePlayer player) {
        Object profile = optional(player, "getPlayerProfile");
        if (profile instanceof PlayerProfile value) return value;
        throw new UnsupportedOperationException("This server does not expose a public Bukkit player profile");
    }
    /** Resolve only online players or a documented runtime cache; never fetch or scan disk profiles. */
    public static OfflinePlayer cachedPlayer(String name) {
        if(name==null||name.isBlank()||name.length()>100)return null;
        Player online=Bukkit.getPlayerExact(name);if(online!=null)return online;
        try { Object cached=Bukkit.getServer().getClass().getMethod("getOfflinePlayerIfCached",String.class).invoke(Bukkit.getServer(),name);return cached instanceof OfflinePlayer player?player:null; }
        catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return null;}
    }
    /** Fixed-size one-second buckets; fallback measures scheduler throughput, including pauses. */
    static final class TickRate {
        private final long[] epochs = new long[900]; private final int[] counts = new int[900];
        private long start, last, cachedSecond = Long.MIN_VALUE;
        private boolean started;
        private volatile double[] cached = new double[0];
        void tick(long nanos) {
            long second = Math.floorDiv(nanos, 1_000_000_000L); if (!started) { start = last = nanos; started = true; } if (nanos < last) return; last = nanos;
            int index = Math.floorMod(second, 900); if (epochs[index] != second) { epochs[index] = second; counts[index] = 0; } counts[index]++;
            if (second == cachedSecond) return; cachedSecond = second;
            double elapsed = (nanos - start) / 1_000_000_000.0; if (elapsed < 5) return;
            double[] values = new double[3]; int[] windows = {60, 300, 900};
            for (int i = 0; i < windows.length; i++) { long total = 0; for (int j = 0; j < counts.length; j++) if (epochs[j] > second - windows[i] && epochs[j] <= second) total += counts[j]; values[i] = Math.min(20, total / Math.min(windows[i], elapsed)); }
            cached = values;
        }
        double[] values() { return cached.clone(); }
    }
}
