package dev.mcc.integration;

import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.BooleanSupplier;

/** Main-thread lifecycle discovery, memory-only reads from moderation listeners/workers. */
final class IntegrationPresence implements Listener,BooleanSupplier,AutoCloseable {
    private final PunishmentProviders.Presence providers=new PunishmentProviders.Presence();
    IntegrationPresence(JavaPlugin owner){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Integration presence must start on the server thread");
        for(PunishmentProviders.Provider known:PunishmentProviders.known()){
            Plugin provider=Bukkit.getPluginManager().getPlugin(known.name());providers.changed(known.name(),provider!=null&&provider.isEnabled());
        }
        Bukkit.getPluginManager().registerEvents(this,owner);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void enabled(PluginEnableEvent event){providers.changed(event.getPlugin().getName(),true);}
    @EventHandler(priority=EventPriority.MONITOR) public void disabled(PluginDisableEvent event){providers.changed(event.getPlugin().getName(),false);}
    @Override public boolean getAsBoolean(){return providers.getAsBoolean();}
    @Override public void close(){HandlerList.unregisterAll(this);providers.close();}
}
