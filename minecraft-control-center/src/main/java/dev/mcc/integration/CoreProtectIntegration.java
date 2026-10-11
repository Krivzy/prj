package dev.mcc.integration;

import org.bukkit.Location;
import org.bukkit.Material;
import java.util.*;

/** Real paged CoreProtect lookups only. No rollback, restore, purge or arbitrary commands. */
final class CoreProtectIntegration {
    private CoreProtectIntegration() {}
    static void validate(Object api)throws Exception {
        if(!Boolean.TRUE.equals(PublicApi.call(api,"isEnabled"))||((Number)PublicApi.call(api,"APIVersion")).intValue()<9||!PublicApi.has(api,"performPartialLookup",10)||!PublicApi.has(api,"parseResult",1))throw new UnsupportedOperationException("CoreProtect bounded lookup API is unavailable");
    }
    static Map<String,Object> lookup(IntegrationService.Binding binding,Map<String,Object> body,IntegrationService.Runtime runtime)throws Exception {
        int seconds=(int)IntegrationValidation.number(body,"seconds",3600,1,86400),radius=(int)IntegrationValidation.number(body,"radius",8,1,32),offset=(int)IntegrationValidation.number(body,"offset",0,0,10000),limit=(int)IntegrationValidation.number(body,"limit",50,1,100);
        String player=IntegrationValidation.text(body,"player","",16);if(!player.isEmpty()&&!player.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid Minecraft player name");
        String world=IntegrationValidation.text(body,"world","",128);if(world.isBlank())throw new IllegalArgumentException("A loaded world and a bounded location are required");
        int x=(int)IntegrationValidation.number(body,"x",0,-30000000,30000000),y=(int)IntegrationValidation.number(body,"y",64,-2048,2048),z=(int)IntegrationValidation.number(body,"z",0,-30000000,30000000);
        Location location=runtime.location(world,x,y,z);String block=IntegrationValidation.text(body,"block","",80);List<Object> blocks=null;
        if(!block.isEmpty()){Material material=Material.matchMaterial(block);if(material==null||!material.isBlock())throw new IllegalArgumentException("Unknown Minecraft block");blocks=List.of(material);}
        String action=IntegrationValidation.text(body,"action","all",16);List<Integer> actions=switch(action){case "all"->null;case "removed"->List.of(0);case "placed"->List.of(1);case "interaction"->List.of(2);default->throw new IllegalArgumentException("Unknown CoreProtect action filter");};
        Object api=binding.api();Object value=PublicApi.call(api,"performPartialLookup",seconds,player.isEmpty()?null:List.of(player),null,blocks,null,actions,radius,location,offset,limit+1);
        if(value==null)throw new IllegalStateException("CoreProtect lookup failed or its API was disabled");Collection<?> rows=PublicApi.collection(value);List<Map<String,Object>> items=new ArrayList<>();
        for(Object raw:rows){if(items.size()==limit)break;if(!(raw instanceof String[] record))throw new UnsupportedOperationException("Unexpected CoreProtect record");Object parsed=PublicApi.call(api,"parseResult",(Object)record);Map<String,Object> row=new LinkedHashMap<>();row.put("player",PublicApi.text(PublicApi.call(parsed,"getPlayer"),128));row.put("world",PublicApi.text(PublicApi.call(parsed,"worldName"),128));for(String axis:List.of("X","Y","Z"))row.put(axis.toLowerCase(Locale.ROOT),PublicApi.call(parsed,"get"+axis));row.put("block",PublicApi.text(PublicApi.call(parsed,"getType"),80));row.put("timestamp",((Number)PublicApi.call(parsed,"getTimestamp")).longValue()*1000L);row.put("action",PublicApi.text(PublicApi.call(parsed,"getActionString"),64));row.put("actionId",PublicApi.call(parsed,"getActionId"));row.put("rolledBack",PublicApi.call(parsed,"isRolledBack"));items.add(row);}
        return Map.of("items",items,"offset",offset,"limit",limit,"hasMore",rows.size()>limit,"readOnly",true);
    }
}
