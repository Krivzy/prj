package dev.mcc.integration;

import dev.mcc.core.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** LuckPerms 5 public API; edits normal nodes only and preserves transient/contextual nodes. */
final class LuckPermsIntegration {
    private static final int MAX_HOLDERS=200,MAX_NODES=500;
    private final IntegrationService.Binding binding;
    private final Object api,groups,users;
    private final Class<?> permissionContract,inheritanceContract;
    LuckPermsIntegration(IntegrationService.Binding binding)throws Exception {this(binding,PublicApi.type(binding.loader(),"net.luckperms.api.node.types.PermissionNode"),PublicApi.type(binding.loader(),"net.luckperms.api.node.types.InheritanceNode"));}
    /** Public-contract injection for regression tests without loading an optional server plugin. */
    LuckPermsIntegration(IntegrationService.Binding binding,Class<?> permissionContract,Class<?> inheritanceContract)throws Exception {this.binding=binding;this.permissionContract=permissionContract;this.inheritanceContract=inheritanceContract;api=binding.api();groups=PublicApi.call(api,"getGroupManager");users=PublicApi.call(api,"getUserManager");}
    static void validate(Object api,ClassLoader loader)throws Exception {
        Object groups=PublicApi.call(api,"getGroupManager"),users=PublicApi.call(api,"getUserManager");
        for(String name:List.of("loadAllGroups","getLoadedGroups"))if(!PublicApi.has(groups,name,0))throw new UnsupportedOperationException();
        if(!PublicApi.has(groups,"saveGroup",1)||!PublicApi.has(users,"loadUser",1)||!PublicApi.has(users,"saveUser",1)||!PublicApi.has(users,"getLoadedUsers",0)||!PublicApi.has(users,"lookupUniqueId",1))throw new UnsupportedOperationException();
        PublicApi.type(loader,"net.luckperms.api.node.types.PermissionNode");PublicApi.type(loader,"net.luckperms.api.node.types.InheritanceNode");
    }
    Map<String,Object> query(Map<String,Object> body)throws Exception {
        String action=IntegrationValidation.text(body,"action","groups",32);
        if(action.equals("holder"))return holderView(holder(body),IntegrationValidation.text(body,"holderType","group",16));
        Collection<?> source;if(action.equals("groups")){PublicApi.readFuture(PublicApi.call(groups,"loadAllGroups"));source=PublicApi.collection(PublicApi.call(groups,"getLoadedGroups"));}
        else if(action.equals("users"))source=PublicApi.collection(PublicApi.call(users,"getLoadedUsers"));else throw new IllegalArgumentException("Unknown LuckPerms query");
        List<Map<String,Object>> rows=new ArrayList<>();for(Object holder:source){if(rows.size()==MAX_HOLDERS)break;Map<String,Object> row=new LinkedHashMap<>();row.put("name",PublicApi.text(PublicApi.call(holder,"getFriendlyName"),128));if(action.equals("users")){row.put("target",String.valueOf(PublicApi.call(holder,"getUniqueId")));row.put("primaryGroup",PublicApi.text(PublicApi.call(holder,"getPrimaryGroup"),64));}else row.put("target",PublicApi.text(PublicApi.call(holder,"getName"),64));rows.add(row);}rows.sort(Comparator.comparing(row->String.valueOf(row.get("name"))));
        return Map.of("items",rows,"truncated",source.size()>MAX_HOLDERS,"scope",action.equals("users")?"loaded-users":"all-groups","limit",MAX_HOLDERS);
    }
    private Object holder(Map<String,Object> body)throws Exception {
        String type=IntegrationValidation.text(body,"holderType","group",16),target=IntegrationValidation.text(body,"target","",64);
        if(type.equals("group")){IntegrationValidation.group(target);PublicApi.readFuture(PublicApi.call(groups,"loadAllGroups"));Object group=PublicApi.call(groups,"getGroup",target);if(group==null)throw new IllegalArgumentException("Group does not exist");return group;}
        if(!type.equals("user"))throw new IllegalArgumentException("Unknown permission holder");UUID uuid;
        if(target.matches("[A-Za-z0-9_]{1,16}")){Object found=PublicApi.readFuture(PublicApi.call(users,"lookupUniqueId",target));if(!(found instanceof UUID id))throw new IllegalArgumentException("LuckPerms has no UUID for that player; use a verified UUID");uuid=id;}
        else uuid=IntegrationValidation.uuid(target);
        return PublicApi.readFuture(PublicApi.call(users,"loadUser",uuid));
    }
    Map<String,Object> mutate(Map<String,Object> body,Runnable guard)throws Exception {
        String action=IntegrationValidation.text(body,"action","",32),type=IntegrationValidation.text(body,"holderType","group",16);
        if(action.equals("createGroup")){String name=IntegrationValidation.group(IntegrationValidation.text(body,"target","",64));PublicApi.readFuture(PublicApi.call(groups,"loadAllGroups"));if(PublicApi.call(groups,"getGroup",name)!=null)throw new IllegalArgumentException("Group already exists");guard.run();Object group=PublicApi.readFuture(PublicApi.call(groups,"createAndLoadGroup",name));PublicApi.saveFuture(PublicApi.call(groups,"saveGroup",group));return Map.of("ok",true,"holder",holderView(group,"group"));}
        if(!Set.of("setNode","removeNode").contains(action))throw new IllegalArgumentException("Unknown LuckPerms edit");
        Object holder=holder(body);Map<String,Object> before=holderView(holder,type);String revision=IntegrationValidation.text(body,"expectedRevision","",64);
        if(revision.isEmpty()||!revision.equals(before.get("revision")))throw new IllegalStateException("Permissions changed or are too large to edit. Refresh the holder first.");
        String nodeType=IntegrationValidation.text(body,"nodeType","permission",16),key=IntegrationValidation.text(body,"key","",200);
        if(!Set.of("permission","inheritance").contains(nodeType))throw new IllegalArgumentException("Only permission and inheritance nodes can be edited");
        if(nodeType.equals("inheritance")){IntegrationValidation.group(key);if(!action.equals("removeNode")){if(PublicApi.call(groups,"getGroup",key)==null)throw new IllegalArgumentException("Parent group does not exist");if(type.equals("group"))checkCycle(String.valueOf(PublicApi.call(holder,"getName")),key,new HashSet<>());}}
        else if(!key.matches("[A-Za-z0-9_.*:-]{1,200}")||key.startsWith("group.")||key.startsWith("meta.")||key.startsWith("prefix.")||key.startsWith("suffix.")||key.startsWith("weight."))throw new IllegalArgumentException("Invalid permission node");
        Map<String,String> contexts=IntegrationValidation.contexts(body);long expires=IntegrationValidation.number(body,"expiresAt",0,0,Instant.now().plusSeconds(315360000).getEpochSecond());
        if(action.equals("setNode")&&expires!=0&&expires<=Instant.now().getEpochSecond())throw new IllegalArgumentException("Permission expiry must be in the future");
        Object value=body.getOrDefault("value",true);if(!(value instanceof Boolean))throw new IllegalArgumentException("Permission value must be boolean");
        String storedKey=nodeType.equals("inheritance")?"group."+key:key;Object data=PublicApi.call(holder,"data");List<Object> old=new ArrayList<>();
        for(Object node:PublicApi.collection(PublicApi.call(data,"toCollection"))){Map<String,Object> view=nodeView(node);if(storedKey.equals(view.get("key"))&&contexts.equals(view.get("contexts"))&&Long.valueOf(expires).equals(view.get("expiresAt")))old.add(node);}
        Object replacement=null;if(action.equals("setNode")){Object builder=PublicApi.call(nodeType.equals("inheritance")?inheritanceContract:permissionContract,"builder",key);PublicApi.call(builder,"value",value);for(var context:contexts.entrySet())PublicApi.call(builder,"withContext",context.getKey(),context.getValue());if(expires!=0)PublicApi.call(builder,"expiry",Instant.ofEpochSecond(expires));replacement=PublicApi.call(builder,"build");}
        guard.run(); // Re-check the session/plugin after asynchronous loading, before the first mutation.
        for(Object node:old)PublicApi.call(data,"remove",node);if(replacement!=null)PublicApi.call(data,"add",replacement);
        // Official save receipt is awaited off-main. Failed saves may leave the provider cache changed.
        PublicApi.saveFuture(PublicApi.call(type.equals("group")?groups:users,type.equals("group")?"saveGroup":"saveUser",holder));
        return Map.of("ok",true,"holder",holderView(holder,type));
    }
    private void checkCycle(String target,String parent,Set<String> visited)throws Exception {
        if(parent.equals(target))throw new IllegalArgumentException("Group inheritance would create a cycle");if(!visited.add(parent))return;if(visited.size()>MAX_HOLDERS)throw new IllegalStateException("Inheritance graph is too large to validate");
        Object group=PublicApi.call(groups,"getGroup",parent);if(group==null)return;Collection<?> nodes=PublicApi.collection(PublicApi.call(PublicApi.call(group,"data"),"toCollection"));if(nodes.size()>MAX_NODES)throw new IllegalStateException("Parent group is too large to validate");
        for(Object node:nodes){String key=String.valueOf(PublicApi.call(node,"getKey"));if(key.startsWith("group.")&&Boolean.TRUE.equals(PublicApi.call(node,"getValue")))checkCycle(target,key.substring(6),visited);}
    }
    private Map<String,Object> holderView(Object holder,String type)throws Exception {
        Collection<?> source=PublicApi.collection(PublicApi.call(PublicApi.call(holder,"data"),"toCollection"));List<Map<String,Object>> nodes=new ArrayList<>();for(Object node:source){if(nodes.size()==MAX_NODES)break;nodes.add(nodeView(node));}nodes.sort(Comparator.comparing(Json::write));boolean truncated=source.size()>MAX_NODES;
        Map<String,Object> view=new LinkedHashMap<>();view.put("type",type);view.put("name",PublicApi.text(PublicApi.call(holder,"getFriendlyName"),128));view.put("target",String.valueOf(PublicApi.call(holder,type.equals("user")?"getUniqueId":"getName")));view.put("nodes",nodes);view.put("truncated",truncated);view.put("revision",truncated?"":HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(nodes).getBytes(StandardCharsets.UTF_8))));if(type.equals("user"))view.put("primaryGroup",PublicApi.text(PublicApi.call(holder,"getPrimaryGroup"),64));return view;
    }
    private Map<String,Object> nodeView(Object node)throws Exception {
        String key=PublicApi.text(PublicApi.call(node,"getKey"),512);Object expiry=PublicApi.call(node,"getExpiry");Map<String,Object> contexts=new TreeMap<>();for(Object context:PublicApi.collection(PublicApi.call(PublicApi.call(node,"getContexts"),"toSet"))){String k=String.valueOf(PublicApi.call(context,"getKey")),v=String.valueOf(PublicApi.call(context,"getValue"));Object previous=contexts.putIfAbsent(k,v);if(previous!=null){if(previous instanceof List<?> list){List<Object> next=new ArrayList<>(list);next.add(v);next.sort(Comparator.comparing(String::valueOf));contexts.put(k,next);}else {List<String> list=new ArrayList<>(List.of(String.valueOf(previous),v));list.sort(String::compareTo);contexts.put(k,list);}}}
        String kind=inheritanceContract.isInstance(node)?"inheritance":permissionContract.isInstance(node)?"permission":"metadata";
        Map<String,Object> out=new LinkedHashMap<>();out.put("key",key);out.put("value",PublicApi.call(node,"getValue"));out.put("expiresAt",expiry instanceof Instant instant?instant.getEpochSecond():0L);out.put("contexts",contexts);out.put("nodeType",kind);return out;
    }
}
