package dev.mcc.integration;

import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Main-thread lifecycle discovery, memory-only reads from moderation listeners/workers. */
final class IntegrationPresence implements Listener,BooleanSupplier,AutoCloseable {
    private final AtomicBoolean advancedBan=new AtomicBoolean();
    IntegrationPresence(JavaPlugin owner){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Integration presence must start on the server thread");
        Plugin provider=Bukkit.getPluginManager().getPlugin("AdvancedBan");advancedBan.set(provider!=null&&provider.isEnabled());Bukkit.getPluginManager().registerEvents(this,owner);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void enabled(PluginEnableEvent event){if(event.getPlugin().getName().equals("AdvancedBan"))advancedBan.set(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void disabled(PluginDisableEvent event){if(event.getPlugin().getName().equals("AdvancedBan"))advancedBan.set(false);}
    @Override public boolean getAsBoolean(){return advancedBan.get();}
    @Override public void close(){HandlerList.unregisterAll(this);advancedBan.set(true);}
}
