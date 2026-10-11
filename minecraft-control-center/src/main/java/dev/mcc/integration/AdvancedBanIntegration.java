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
    static boolean supportsHistory(IntegrationService.Binding binding){try{Class<?> query=PublicApi.type(binding.loader(),PunishmentProviders.namespace(binding.title())+".utils.SQLQuery");query.getField("SELECT_ALL_PUNISHMENTS_LIMIT");query.getField("SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT");return query.isEnum()&&Collection.class.isAssignableFrom(binding.api().getClass().getMethod("getPunishments",query,Object[].class).getReturnType());}catch(Exception|LinkageError e){return false;}}
    private Map<String,Object> source(){return Map.of("id",binding.id(),"title",binding.title(),"version",binding.version());}
    private List<PunishmentRecords.Row> limited(boolean history)throws Exception {
        Class<?> query=PublicApi.type(binding.loader(),PunishmentProviders.namespace(binding.title())+".utils.SQLQuery");Object selected=query.getField(history?"SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT":"SELECT_ALL_PUNISHMENTS_LIMIT").get(null);
        Collection<?> rows=PublicApi.collection(PublicApi.call(manager,"getPunishments",selected,new Object[]{PunishmentRecords.WINDOW+1}));
        if(rows.size()>PunishmentRecords.WINDOW+1)throw new UnsupportedOperationException("Provider ignored its bounded history query");
        List<PunishmentRecords.Row> out=new ArrayList<>();for(Object row:rows)out.add(record(row));return List.copyOf(out);
    }
    Map<String,Object> query(Map<String,Object> body)throws Exception {
        String action=IntegrationValidation.text(body,"action",body.containsKey("player")?"active":"history",16);if(!Set.of("active","history").contains(action))throw new IllegalArgumentException("Unknown punishment query");
        boolean history=action.equals("history");long now=((Number)PublicApi.call(time,"getTime")).longValue();
        if(!supportsHistory(binding)){
            if(history)throw new UnsupportedOperationException("This provider version has no supported bounded history API");
            String target=target(body);Collection<?> found=PublicApi.collection(PublicApi.call(manager,"getPunishments",target,null,true));List<PunishmentRecords.Row> rows=new ArrayList<>();for(Object row:found){if(rows.size()==PunishmentRecords.WINDOW)break;rows.add(record(row));}
            return PunishmentRecords.page(List.of(),rows,false,found.size()>PunishmentRecords.WINDOW,body,source(),now,false);
        }
        List<PunishmentRecords.Row> active=limited(false),past=history?limited(true):List.of();boolean at=active.size()>PunishmentRecords.WINDOW,ht=past.size()>PunishmentRecords.WINDOW;
        return PunishmentRecords.page(past.subList(0,Math.min(past.size(),PunishmentRecords.WINDOW)),active.subList(0,Math.min(active.size(),PunishmentRecords.WINDOW)),ht,at,body,source(),now,history);
    }
    Map<String,Object> mutate(Map<String,Object> body,String operator,Runnable guard)throws Exception {
        String action=IntegrationValidation.text(body,"action","",16),target=target(body);
        if(action.equals("revoke")){
            int id=(int)IntegrationValidation.number(body,"punishmentId",0,1,Integer.MAX_VALUE);Object row=PublicApi.call(manager,"getPunishment",id);if(row==null)throw new IllegalStateException("Punishment is no longer active");if(!String.valueOf(PublicApi.call(row,"getUuid")).equalsIgnoreCase(target))throw new IllegalArgumentException("Punishment belongs to another player");if(String.valueOf(PublicApi.call(row,"getType")).equals("KICK"))throw new IllegalArgumentException("Kicks cannot be revoked");
            String expected=IntegrationValidation.text(body,"expectedRevision","",64);if(expected.isEmpty()||!record(row).revision().equals(expected))throw new ConcurrentModificationException("Punishment changed; refresh before revoking");
            guard.run();Object latest=PublicApi.call(manager,"getPunishment",id);if(latest==null||!record(latest).revision().equals(expected))throw new ConcurrentModificationException("Punishment changed; refresh before revoking");PublicApi.call(latest,"delete","MCC:"+operator,false,true);if(PublicApi.call(manager,"getPunishment",id)!=null)throw new IllegalStateException("AdvancedBan did not confirm revocation");return Map.of("ok",true,"provider","advancedban","source",source(),"revokedId",id);
        }
        if(!action.equals("issue"))throw new IllegalArgumentException("Unknown AdvancedBan action");
        String requested=IntegrationValidation.text(body,"type","ban",16);long seconds=IntegrationValidation.number(body,"seconds",0,0,315360000);String selected=switch(requested){case "ban"->seconds==0?"BAN":"TEMP_BAN";case "mute"->seconds==0?"MUTE":"TEMP_MUTE";case "warn"->seconds==0?"WARNING":"TEMP_WARNING";case "note"->"NOTE";default->throw new IllegalArgumentException("Supported punishments: ban, mute, warn, note");};
        if(requested.equals("note")&&seconds!=0)throw new IllegalArgumentException("Notes do not have a duration");
        String reason=IntegrationValidation.text(body,"reason","",256).trim();if(reason.isEmpty()||reason.startsWith("@")||reason.startsWith("~")||reason.contains("'"))throw new IllegalArgumentException("A plain reason is required; layout substitutions are unavailable");
        long start=((Number)PublicApi.call(time,"getTime")).longValue(),end=seconds==0?-1L:Math.addExact(start,Math.multiplyExact(seconds,1000L));Object kind=type.getField(selected).get(null);
        Object row=punishment.getConstructor(String.class,String.class,String.class,String.class,type,long.class,long.class,String.class,int.class).newInstance(IntegrationValidation.player(body,"player"),target,reason,"MCC:"+operator,kind,start,end,null,-1);
        guard.run();PublicApi.call(row,"create",true); // silent suppresses broadcast, not actual enforcement/events/history.
        int id=((Number)PublicApi.call(row,"getId")).intValue();if(id<=0||PublicApi.call(manager,"getPunishment",id)==null)throw new IllegalStateException("AdvancedBan did not confirm saving the punishment; refresh before retrying");
        return Map.of("ok",true,"provider","advancedban","source",source(),"punishment",view(row));
    }
    private PunishmentRecords.Row record(Object row)throws Exception {return new PunishmentRecords.Row(((Number)PublicApi.call(row,"getId")).intValue(),PublicApi.text(PublicApi.call(row,"getName"),128),PublicApi.text(PublicApi.call(row,"getUuid"),128),PublicApi.text(PublicApi.call(row,"getReason"),512),PublicApi.text(PublicApi.call(row,"getOperator"),128),((Number)PublicApi.call(row,"getStart")).longValue(),((Number)PublicApi.call(row,"getEnd")).longValue(),PublicApi.text(PublicApi.call(row,"getType"),24),Boolean.TRUE.equals(PublicApi.call(row,"isExpired")));}
    private Map<String,Object> view(Object row)throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();for(String name:List.of("Id","Name","Reason","Operator","Start","End","Type")){Object value=PublicApi.call(row,"get"+name);out.put(Character.toLowerCase(name.charAt(0))+name.substring(1),value instanceof Number?value:PublicApi.text(value,name.equals("Reason")?512:128));}out.put("expired",PublicApi.call(row,"isExpired"));out.put("source",source());out.put("activeId",out.get("id"));out.put("uuid",record(row).uuid());out.put("revision",record(row).revision());return Collections.unmodifiableMap(out);
    }
}
