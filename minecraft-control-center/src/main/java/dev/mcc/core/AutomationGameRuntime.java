package dev.mcc.core;

import dev.mcc.game.GameService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Public Bukkit reads and typed actions execute only inside a cancellable main-thread callback. */
final class AutomationGameRuntime implements Automation.Runtime {
    private final MainThread main;private final GameService game;
    AutomationGameRuntime(MainThread main,GameService game){this.main=main;this.game=game;}
    public CompletableFuture<Object> action(String action,Map<String,Object> params){return action(action,params,()->true);}
    public CompletableFuture<Object> action(String action,Map<String,Object> params,BooleanSupplier allowed){return main.call(()->{authorize(allowed);return game.execute(action,params);});}
    public CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context){return read(condition,config,context,()->true);}
    public CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context,BooleanSupplier allowed){return main.call(()->{authorize(allowed);return value(condition,config,context);});}
    private static void authorize(BooleanSupplier allowed){if(!allowed.getAsBoolean())throw new CancellationException("Automation changed or was disabled before game callback");}
    static Object value(String condition,Map<String,Object> c,Map<String,Object> context){
        if(condition.equals("onlineCount"))return Bukkit.getOnlinePlayers().size();
        if(condition.equals("tps")){double[] tps=ServerPlatform.tps();if(tps.length==0)throw new UnsupportedOperationException("TPS unavailable");return tps[0];}
        if(condition.equals("chance"))return ThreadLocalRandom.current().nextDouble(100)<((Number)c.get("percent")).doubleValue();
        String id=Json.text(c,"uuid",Json.text(context,"uuid",""));Player p=null;
        if(condition.startsWith("player")){AutomationGraph.uuid(id);p=Bukkit.getPlayer(UUID.fromString(id));if(condition.equals("playerOnline"))return p!=null;if(p==null)throw new IllegalArgumentException("Condition player is offline");}
        if(p!=null){final Player player=p;return switch(condition){
            case "playerHealth"->player.getHealth();case "playerFood"->player.getFoodLevel();case "playerLevel"->player.getLevel();case "playerGamemode"->player.getGameMode().name();case "playerPermission"->player.hasPermission((String)c.get("permission"));
            case "playerHasItem"->{Material type=GameService.material((String)c.get("material"));int amount=0;for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&item.getType()==type)amount+=item.getAmount();yield amount>=((Number)c.get("amount")).intValue();}
            case "playerEffect"->{var effect=Registry.EFFECT.get(GameService.key((String)c.get("effect")));if(effect==null)throw new IllegalArgumentException("Unknown effect");yield player.hasPotionEffect(effect);}
            case "playerBiome"->{Location here=player.getLocation();GameService.requireLoaded(here);yield here.getBlock().getBiome().getKey().toString();}
            case "playerY"->player.getLocation().getY();
            case "playerDistance"->{String world=Json.text(c,"world",Json.text(context,"world",player.getWorld().getName()));if(!player.getWorld().getName().equals(world))yield 30000000d;Location target=new Location(player.getWorld(),((Number)c.get("x")).doubleValue(),((Number)c.get("y")).doubleValue(),((Number)c.get("z")).doubleValue());yield Math.min(30000000,player.getLocation().distance(target));}
            default->throw new IllegalArgumentException("Unknown player condition");
        };}
        World world=Bukkit.getWorld(Json.text(c,"world",Json.text(context,"world","")));if(world==null)throw new IllegalArgumentException("Condition world is not loaded");
        return switch(condition){case "worldTime"->world.getTime();case "weather"->world.isThundering()?"thunder":world.hasStorm()?"rain":"clear";case "worldPlayers"->world.getPlayers().size();case "difficulty"->world.getDifficulty().name();case "environment"->world.getEnvironment().name();case "pvp"->world.getPVP();default->throw new IllegalArgumentException("Unknown condition");};
    }
}
