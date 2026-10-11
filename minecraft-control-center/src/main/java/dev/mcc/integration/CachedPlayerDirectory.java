package dev.mcc.integration;

import java.util.*;

/** Memory-only identities; no offline UUID synthesis or network profile resolution. */
final class CachedPlayerDirectory {
    static final int MAX=2048;
    static List<Map<String,Object>> merge(List<Map<String,Object>> known,List<Map<String,Object>> online){
        Map<String,Map<String,Object>> players=new LinkedHashMap<>();
        add(players,online,true);add(players,known,false);return List.copyOf(players.values());
    }
    private static void add(Map<String,Map<String,Object>> out,List<Map<String,Object>> rows,boolean online){
        int scanned=0;for(Map<String,Object> row:rows){if(++scanned>MAX)break;Object name=row.get("name"),id=row.get("uuid");if(!(name instanceof String n)||!n.matches("[A-Za-z0-9_]{1,16}")||!(id instanceof String u))continue;
            UUID uuid;try{uuid=IntegrationValidation.uuid(u);}catch(IllegalArgumentException e){continue;}String key=uuid.toString();if(out.containsKey(key))continue;if(out.size()==MAX)break;
            out.put(key,Map.of("name",n,"uuid",key,"online",online,"identitySource",online?"online":"server-cache"));
        }
    }
    static boolean matches(List<Map<String,Object>> rows,String name,UUID uuid){List<Map<String,Object>> same=rows.stream().filter(r->name.equalsIgnoreCase(String.valueOf(r.get("name")))).toList();return same.size()==1&&uuid.toString().equals(same.getFirst().get("uuid"));}
    static Map<String,Object> identity(List<Map<String,Object>> rows,String name,String providerUuid){List<Map<String,Object>> same=rows.stream().filter(r->name.equalsIgnoreCase(String.valueOf(r.get("name")))).toList();if(same.size()!=1)return Map.of();Map<String,Object> found=same.getFirst();return providerUuid.isEmpty()||providerUuid.equals(found.get("uuid"))?found:Map.of();}
    static Map<String,Object> page(List<Map<String,Object>> rows,Map<String,Object> input){
        String search=IntegrationValidation.text(input,"search","",32).trim().toLowerCase(Locale.ROOT);int offset=(int)IntegrationValidation.number(input,"offset",0,0,MAX),limit=(int)IntegrationValidation.number(input,"limit",50,1,50);
        List<Map<String,Object>> found=rows.stream().filter(r->String.valueOf(r.get("name")).toLowerCase(Locale.ROOT).contains(search)).sorted(Comparator.<Map<String,Object>,Boolean>comparing(r->Boolean.TRUE.equals(r.get("online"))).reversed().thenComparing(r->String.valueOf(r.get("name")),String.CASE_INSENSITIVE_ORDER)).toList();
        int from=Math.min(offset,found.size()),to=Math.min(from+limit,found.size());return Map.of("items",List.copyOf(found.subList(from,to)),"offset",offset,"limit",limit,"hasMore",to<found.size(),"matchedInCache",found.size(),"scope","cached-server-identities","windowLimit",MAX,"truncated",rows.size()==MAX);
    }
}
