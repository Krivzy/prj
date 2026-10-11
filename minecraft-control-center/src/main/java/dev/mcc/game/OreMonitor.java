package dev.mcc.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Passive ore observation. No punishments, block edits, chunk loading, or async Bukkit access. */
public final class OreMonitor implements Listener, AutoCloseable {
    public static final int MAX_PENDING = 2048;
    public static final int VERIFY_PER_TICK = 128;
    private sealed interface Pending permits Break, Place { long eligibleTick(); }
    private record Break(long eligibleTick, long occurredAt, BlockBreakEvent event, OreLedger.BlockKey key, String material, UUID player, String name, String world, boolean survival) implements Pending {}
    private record Place(long eligibleTick, long occurredAt, BlockPlaceEvent event, OreLedger.BlockKey key, String material) implements Pending {}
    private final JavaPlugin plugin;
    private final OreLedger ledger;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private volatile Map<String, Object> cached;
    private BukkitTask task;
    private long tick;
    private boolean closed;

    public OreMonitor(JavaPlugin plugin, ConfigurationSection configuration) {
        requireMain(); this.plugin = plugin;
        EnumMap<OreKind, OreLedger.Threshold> thresholds = new EnumMap<>(OreKind.class);
        for (OreKind ore : OreKind.values()) {
            int yellow = configuration == null ? ore.yellow : configuration.getInt("thresholds." + ore.id() + ".yellow", ore.yellow);
            int red = configuration == null ? ore.red : configuration.getInt("thresholds." + ore.id() + ".red", ore.red);
            // Reject invalid configuration instead of silently claiming an applied threshold.
            thresholds.put(ore, new OreLedger.Threshold(yellow, red));
        }
        long baseMillis = System.currentTimeMillis(), baseNanos = System.nanoTime();
        ledger = new OreLedger(OreLedger.Limits.defaults(), thresholds, () -> baseMillis + (System.nanoTime() - baseNanos) / 1_000_000);
        cached = ledger.snapshot(0);
    }
    public void start() {
        requireMain(); if (closed) throw new IllegalStateException("Ore monitor closed"); if (task != null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::advance, 1, 1);
    }
    /** Off-thread safe: this is a previously published immutable DTO, never a Bukkit query. */
    public Map<String, Object> snapshot() { return cached; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (closed) return;
        Block block = event.getBlock(); String material = block.getType().name();
        OreLedger.BlockKey key = key(block);
        boolean survival = event.getPlayer().getGameMode() == GameMode.SURVIVAL;
        if (OreKind.fromMaterial(material) == null && !ledger.isTracked(key)) return;
        offer(new Break(tick + 1, ledger.observationTime(), event, key, material, event.getPlayer().getUniqueId(), event.getPlayer().getName(), block.getWorld().getName(), survival));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (closed || !event.canBuild()) return;
        Block block = event.getBlockPlaced(); String material = block.getType().name(); OreLedger.BlockKey key = key(block);
        offer(new Place(tick + 1, ledger.observationTime(), event, key, material));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { ledger.online(event.getPlayer().getUniqueId(), true); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { ledger.online(event.getPlayer().getUniqueId(), false); }

    private void offer(Pending value) { if (pending.size() >= MAX_PENDING) ledger.droppedPending(); else pending.addLast(value); }
    private void advance() {
        requireMain(); tick++;
        long deadline = System.nanoTime() + 750_000;
        for (int processed = 0; processed < VERIFY_PER_TICK && !pending.isEmpty() && System.nanoTime() < deadline; processed++) {
            if (pending.peekFirst().eligibleTick() > tick) break;
            Pending action = pending.removeFirst();
            if (action instanceof Place place) {
                // FIFO preserves place -> break in the same tick. Reading the final location here
                // would miss that valid placement once the subsequent break has changed it to air.
                if (!place.event.isCancelled() && place.event.canBuild()) ledger.placed(place.key, place.material, place.occurredAt);
            } else if (action instanceof Break broken) {
                if (broken.event.isCancelled()) continue;
                World world = Bukkit.getWorld(broken.key.world());
                if (world == null || !world.isChunkLoaded(Math.floorDiv(broken.key.x(), 16), Math.floorDiv(broken.key.z(), 16))) { ledger.skippedVerification(); continue; }
                // MONITOR fires before native removal. Confirm next tick, also catching a later
                // MONITOR cancellation. Same-material replacement is conservatively not counted.
                if (world.getBlockAt(broken.key.x(), broken.key.y(), broken.key.z()).getType().name().equals(broken.material)) { ledger.skippedVerification(); continue; }
                if (broken.survival && OreKind.fromMaterial(broken.material) != null) { ledger.mined(broken.player, broken.name, broken.world, broken.key, broken.material, broken.occurredAt); ledger.online(broken.player, Bukkit.getPlayer(broken.player) != null); }
                else ledger.forgetPlaced(broken.key);
            }
        }
        if (tick % 100 == 0) cached = ledger.snapshot(pending.size());
    }
    private static OreLedger.BlockKey key(Block block) { return new OreLedger.BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    private static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Ore monitor requires server main thread"); }
    @Override public void close() { requireMain(); if (closed) return; closed = true; if (task != null) task.cancel(); HandlerList.unregisterAll(this); pending.clear(); cached = ledger.snapshot(0); }
}
