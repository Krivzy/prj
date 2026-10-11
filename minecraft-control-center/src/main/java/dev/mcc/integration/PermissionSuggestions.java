package dev.mcc.integration;

import java.util.*;

/** Suggestions are server-declared names, never a claim that a wildcard is safe to grant. */
final class PermissionSuggestions {
    static final int MAX_REGISTRY = 8192;
    private PermissionSuggestions() {}

    static Map<String,Object> query(Map<String,Object> body,List<Map<String,Object>> registry) {
        String query=IntegrationValidation.text(body,"search","",200).toLowerCase(Locale.ROOT);
        if(!query.matches("[a-z0-9_.*:-]*"))throw new IllegalArgumentException("Invalid permission search");
        int limit=(int)IntegrationValidation.number(body,"limit",20,1,40);
        Map<String,Map<String,Object>> unique=new TreeMap<>();
        int scanned=0;
        for(Map<String,Object> entry:registry){
            if(scanned++==MAX_REGISTRY)break;
            Object raw=entry.get("key");if(!(raw instanceof String key)||!key.matches("[A-Za-z0-9_.*:-]{1,200}"))continue;
            if(!query.isEmpty()&&!key.toLowerCase(Locale.ROOT).contains(query))continue;
            unique.putIfAbsent(key,Map.of("key",key,"description",bounded(entry.get("description"),400),"source",bounded(entry.get("source"),80)));
        }
        List<Map<String,Object>> items=new ArrayList<>(unique.values());
        items.sort(Comparator.<Map<String,Object>>comparingInt(entry->String.valueOf(entry.get("key")).toLowerCase(Locale.ROOT).startsWith(query)?0:1).thenComparing(entry->String.valueOf(entry.get("key"))));
        return Map.of("items",List.copyOf(items.subList(0,Math.min(limit,items.size()))),"hasMore",items.size()>limit,"registryLimited",registry.size()>=MAX_REGISTRY,"scope","server-declared-permissions");
    }
    private static String bounded(Object value,int length){String text=value instanceof String s?s:"";return text.substring(0,Math.min(text.length(),length));}
}
