package dev.mcc.core;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.function.Supplier;

/** Bounded main-thread observation; no context allocations at all without an enabled subscriber. */
public final class AutomationTriggers implements Listener,AutoCloseable {
    private final Automation engine;private final ArrayDeque<Pending> pending=new ArrayDeque<>();private final BukkitTask task;private boolean closed;
    private record Pending(String name,Cancellable event,Supplier<Map<String,Object>> context){}
    public AutomationTriggers(JavaPlugin plugin,Automation engine){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");this.engine=engine;Bukkit.getPluginManager().registerEvents(this,plugin);task=Bukkit.getScheduler().runTaskTimer(plugin,this::drain,1,1);}
    private void enqueue(String name,Cancellable event,Supplier<Map<String,Object>> context){if(closed||!engine.hasSubscribers(name)||pending.size()>=128)return;pending.addLast(new Pending(name,event,context));}
    private void drain(){for(int i=0;i<16&&!pending.isEmpty();i++){Pending value=pending.removeFirst();if(value.event()!=null&&value.event().isCancelled()||!engine.hasSubscribers(value.name()))continue;Map<String,Object> context=value.context().get();if(context!=null)engine.trigger(value.name(),context);}}
    private static Map<String,Object> context(Player p){return new LinkedHashMap<>(Map.of("uuid",p.getUniqueId().toString(),"name",p.getName(),"world",p.getWorld().getName()));}
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e){if(!engine.hasSubscribers("respawn"))return;enqueue("respawn",null,()->context(e.getPlayer()));}
    @EventHandler(priority=EventPriority.MONITOR) public void world(PlayerChangedWorldEvent e){if(engine.hasSubscribers("worldChange"))engine.trigger("worldChange",context(e.getPlayer()));}
    @EventHandler(priority=EventPriority.MONITOR) public void advancement(PlayerAdvancementDoneEvent e){if(!engine.hasSubscribers("advancement"))return;Map<String,Object> c=context(e.getPlayer());c.put("advancement",e.getAdvancement().getKey().toString());engine.trigger("advancement",c);}
    @EventHandler(priority=EventPriority.MONITOR) public void kill(EntityDeathEvent e){if(!engine.hasSubscribers("mobKill")||e.getEntity() instanceof Player||e.getEntity().getKiller()==null)return;Map<String,Object> c=context(e.getEntity().getKiller());c.put("mob",e.getEntity().getType().name());engine.trigger("mobKill",c);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(!engine.hasSubscribers("blockBreak"))return;String material=e.getBlock().getType().name();enqueue("blockBreak",e,()->{var block=e.getBlock();if(!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4)||block.getType().name().equals(material))return null;Map<String,Object> c=context(e.getPlayer());c.put("material",material);return c;});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e){if(!engine.hasSubscribers("blockPlace"))return;String material=e.getBlock().getType().name();enqueue("blockPlace",e,()->{var block=e.getBlock();if(!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4)||!block.getType().name().equals(material))return null;Map<String,Object> c=context(e.getPlayer());c.put("material",material);return c;});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void bed(PlayerBedEnterEvent e){if(e.getBedEnterResult()==PlayerBedEnterEvent.BedEnterResult.OK)enqueue("bedEnter",e,()->context(e.getPlayer()));}
    @EventHandler(priority=EventPriority.MONITOR) public void level(PlayerLevelChangeEvent e){if(!engine.hasSubscribers("levelChange"))return;Map<String,Object> c=context(e.getPlayer());c.put("level",e.getNewLevel());engine.trigger("levelChange",c);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void weather(WeatherChangeEvent e){enqueue("weatherChange",e,()->Map.of("world",e.getWorld().getName()));}
    @Override public void close(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");closed=true;task.cancel();pending.clear();HandlerList.unregisterAll(this);}
}
