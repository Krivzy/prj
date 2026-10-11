package dev.mcc.integration;

import java.util.*;

/** AdvancedBan/AdvancedBanZ public API. The provider remains the sole enforcement owner. */
final class AdvancedBanIntegration {
    private final IntegrationService.Binding binding;
    private final Object manager,uuidManager,time;
    private final Class<?> punishment,type;
    AdvancedBanIntegration(IntegrationService.Binding binding)throws Exception {this.binding=binding;String namespace=PunishmentProviders.namespace(binding.title());manager=binding.api();uuidManager=PublicApi.call(PublicApi.type(binding.loader(),namespace+".manager.UUIDManager"),"get");time=PublicApi.type(binding.loader(),namespace+".manager.TimeManager");punishment=PublicApi.type(binding.loader(),namespace+".utils.Punishment");type=PublicApi.type(binding.loader(),namespace+".utils.PunishmentType");}
    static void validate(Object manager,ClassLoader loader,String name)throws Exception {validateTypes(manager.getClass(),loader,PunishmentProviders.namespace(name));}
    /** Signature inspection also permits offline JAR verification without initializing the provider. */
    static void validateTypes(Class<?> manager,ClassLoader loader,String namespace)throws Exception {
        if(PunishmentProviders.known().stream().noneMatch(p->p.supported()&&p.namespace().equals(namespace)))throw new UnsupportedOperationException("Unknown punishment API namespace");
        Class<?> type=PublicApi.type(loader,namespace+".utils.PunishmentType"),punishment=PublicApi.type(loader,namespace+".utils.Punishment");
        punishment.getConstructor(String.class,String.class,String.class,String.class,type,long.class,long.class,String.class,int.class);
        if(!type.isEnum()||!Collection.class.isAssignableFrom(manager.getMethod("getPunishments",String.class,type,boolean.class).getReturnType())||!punishment.isAssignableFrom(manager.getMethod("getPunishment",int.class).getReturnType()))throw new UnsupportedOperationException();
        punishment.getMethod("create",boolean.class);punishment.getMethod("delete",String.class,boolean.class,boolean.class);
        for(String getter:List.of("Id","Name","Reason","Operator","Start","End","Type","Uuid"))punishment.getMethod("get"+getter);
        punishment.getMethod("isExpired");
        Class<?> uuid=PublicApi.type(loader,namespace+".manager.UUIDManager");uuid.getMethod("get");uuid.getMethod("getMode");uuid.getMethod("getInMemoryUUID",String.class);PublicApi.type(loader,namespace+".manager.TimeManager").getMethod("getTime");
    }
    private String target(Map<String,Object> body)throws Exception {
        String name=IntegrationValidation.player(body,"player");UUID uuid=IntegrationValidation.uuid(IntegrationValidation.text(body,"uuid","",36));String mode=String.valueOf(PublicApi.call(uuidManager,"getMode"));
        if(mode.equals("DISABLED"))return name.toLowerCase(Locale.ROOT); // Official offline mode keys punishments by name, not UUID.
        Object cached=PublicApi.call(uuidManager,"getInMemoryUUID",name);
        if(cached!=null&&!String.valueOf(cached).replace("-","").equalsIgnoreCase(uuid.toString().replace("-","")))throw new IllegalArgumentException("AdvancedBan player identity does not match the supplied UUID");
        // No provider REST fetch. Use its cached identity or the verified server UUID supplied by MCC.
        return cached==null?uuid.toString().replace("-",""):String.valueOf(cached);
    }
    Map<String,Object> query(Map<String,Object> body)throws Exception {
        String target=target(body);Collection<?> rows=PublicApi.collection(PublicApi.call(manager,"getPunishments",target,null,true));List<Map<String,Object>> items=new ArrayList<>();for(Object row:rows){if(items.size()==100)break;items.add(view(row));}return Map.of("items",items,"truncated",rows.size()>100,"scope","active-player-punishments","provider","advancedban");
    }
    Map<String,Object> mutate(Map<String,Object> body,String operator,Runnable guard)throws Exception {
        String action=IntegrationValidation.text(body,"action","",16),target=target(body);
        if(action.equals("revoke")){
            int id=(int)IntegrationValidation.number(body,"punishmentId",0,1,Integer.MAX_VALUE);Object row=PublicApi.call(manager,"getPunishment",id);if(row==null)throw new IllegalStateException("Punishment is no longer active");if(!String.valueOf(PublicApi.call(row,"getUuid")).equalsIgnoreCase(target))throw new IllegalArgumentException("Punishment belongs to another player");if(String.valueOf(PublicApi.call(row,"getType")).equals("KICK"))throw new IllegalArgumentException("Kicks cannot be revoked");guard.run();PublicApi.call(row,"delete","MCC:"+operator,false,true);if(PublicApi.call(manager,"getPunishment",id)!=null)throw new IllegalStateException("AdvancedBan did not confirm revocation");return Map.of("ok",true,"provider","advancedban","revokedId",id);
        }
        if(!action.equals("issue"))throw new IllegalArgumentException("Unknown AdvancedBan action");
        String requested=IntegrationValidation.text(body,"type","ban",16);long seconds=IntegrationValidation.number(body,"seconds",0,0,315360000);String selected=switch(requested){case "ban"->seconds==0?"BAN":"TEMP_BAN";case "mute"->seconds==0?"MUTE":"TEMP_MUTE";case "warn"->seconds==0?"WARNING":"TEMP_WARNING";case "note"->"NOTE";default->throw new IllegalArgumentException("Supported punishments: ban, mute, warn, note");};
        if(requested.equals("note")&&seconds!=0)throw new IllegalArgumentException("Notes do not have a duration");
        String reason=IntegrationValidation.text(body,"reason","",256).trim();if(reason.isEmpty()||reason.startsWith("@")||reason.startsWith("~")||reason.contains("'"))throw new IllegalArgumentException("A plain reason is required; layout substitutions are unavailable");
        long start=((Number)PublicApi.call(time,"getTime")).longValue(),end=seconds==0?-1L:Math.addExact(start,Math.multiplyExact(seconds,1000L));Object kind=type.getField(selected).get(null);
        Object row=punishment.getConstructor(String.class,String.class,String.class,String.class,type,long.class,long.class,String.class,int.class).newInstance(IntegrationValidation.player(body,"player"),target,reason,"MCC:"+operator,kind,start,end,null,-1);
        guard.run();PublicApi.call(row,"create",true); // silent suppresses broadcast, not actual enforcement/events/history.
        int id=((Number)PublicApi.call(row,"getId")).intValue();if(id<=0||PublicApi.call(manager,"getPunishment",id)==null)throw new IllegalStateException("AdvancedBan did not confirm saving the punishment; refresh before retrying");
        return Map.of("ok",true,"provider","advancedban","punishment",view(row));
    }
    private Map<String,Object> view(Object row)throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();for(String name:List.of("Id","Name","Reason","Operator","Start","End","Type")){Object value=PublicApi.call(row,"get"+name);out.put(Character.toLowerCase(name.charAt(0))+name.substring(1),value instanceof Number?value:PublicApi.text(value,name.equals("Reason")?512:128));}out.put("expired",PublicApi.call(row,"isExpired"));return out;
    }
}
