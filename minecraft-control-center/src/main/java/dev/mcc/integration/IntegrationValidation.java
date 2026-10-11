package dev.mcc.integration;

import java.util.*;

final class IntegrationValidation {
    private IntegrationValidation() {}
    static String text(Map<String,Object> body,String key,String fallback,int max){Object raw=body.getOrDefault(key,fallback);if(!(raw instanceof String s)||s.length()>max||s.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("Invalid "+key);return s;}
    static String player(Map<String,Object> body,String key){String name=text(body,key,"",16);if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Minecraft player name is required");return name;}
    static UUID uuid(String raw){try{UUID uuid=UUID.fromString(raw);if(!uuid.toString().equalsIgnoreCase(raw))throw new IllegalArgumentException();return uuid;}catch(Exception e){throw new IllegalArgumentException("Canonical player UUID is required");}}
    static String group(String raw){if(!raw.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Group must contain lowercase letters, digits, _ or -");return raw;}
    static long number(Map<String,Object> body,String key,long fallback,long min,long max){Object raw=body.getOrDefault(key,fallback);if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue()||n.longValue()<min||n.longValue()>max)throw new IllegalArgumentException("Invalid "+key+" range: "+min+"–"+max);return n.longValue();}
    static Map<String,String> contexts(Map<String,Object> body){Object raw=body.getOrDefault("contexts",Map.of());if(!(raw instanceof Map<?,?> map)||map.size()>8)throw new IllegalArgumentException("At most eight contexts are allowed");Map<String,String> out=new TreeMap<>();for(var e:map.entrySet()){if(!(e.getKey() instanceof String key)||!(e.getValue() instanceof String value)||!key.matches("[a-z0-9_.-]{1,32}")||value.isBlank()||value.length()>64||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("Invalid permission context");out.put(key,value);}return Map.copyOf(out);}
    static void confirmed(Map<String,Object> body){if(!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("Explicit confirmation is required");}
}
