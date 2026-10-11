package dev.mcc.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable provider records. History and active table IDs are deliberately separate. */
final class PunishmentRecords {
    static final int WINDOW=1000, PAGE=50;
    record Row(int id,String name,String key,String reason,String operator,long start,long end,String type,boolean expired) {
        String matchKey(){return digest(List.of(key.toLowerCase(Locale.ROOT),operator,start,end,type));}
        String revision(){return digest(List.of(id,name,key,reason,operator,start,end,type));}
        String uuid(){try{String value=key.replace("-","");if(!value.matches("[a-fA-F0-9]{32}"))return "";return UUID.fromString(value.substring(0,8)+"-"+value.substring(8,12)+"-"+value.substring(12,16)+"-"+value.substring(16,20)+"-"+value.substring(20)).toString();}catch(IllegalArgumentException e){return "";}}
    }
    static Map<String,Object> page(List<Row> history,List<Row> active,boolean historyTruncated,boolean activeTruncated,Map<String,Object> request,Map<String,Object> source,long now,boolean historyMode){
        int offset=(int)IntegrationValidation.number(request,"offset",0,0,WINDOW),limit=(int)IntegrationValidation.number(request,"limit",25,1,PAGE);
        String player=IntegrationValidation.text(request,"player","",16).trim(),kind=IntegrationValidation.text(request,"type","all",24),status=IntegrationValidation.text(request,"status","all",16);
        if(!player.isEmpty()&&!player.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid player name");
        if(!Set.of("all","ban","mute","warn","note","kick").contains(kind)||!Set.of("all","active","inactive","expired","unknown","recorded").contains(status))throw new IllegalArgumentException("Unknown punishment filter");
        Map<String,List<Row>> activeByKey=new HashMap<>();Map<String,Integer> historyCounts=new HashMap<>();
        for(Row row:active)if(!row.expired())activeByKey.computeIfAbsent(row.matchKey(),k->new ArrayList<>()).add(row);
        for(Row row:history)historyCounts.merge(row.matchKey(),1,Integer::sum);
        List<Map<String,Object>> filtered=new ArrayList<>();
        for(Row row:historyMode?history:active){
            if(!player.isEmpty()&&!row.name().equalsIgnoreCase(player)||!kind.equals("all")&&!category(row.type()).equals(kind))continue;
            Row live=historyMode?unique(activeByKey.get(row.matchKey())):row;
            boolean ambiguous=historyMode&&(historyCounts.getOrDefault(row.matchKey(),0)>1||activeByKey.getOrDefault(row.matchKey(),List.of()).size()>1);
            String state=row.expired()||row.end()>0&&row.end()<=now?"expired":row.type().equals("KICK")?"recorded":live!=null&&!ambiguous?"active":activeTruncated||ambiguous?"unknown":"inactive";
            if(!status.equals("all")&&!state.equals(status))continue;
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",row.id());item.put("historyId",historyMode?row.id():0);item.put("activeId",state.equals("active")&&live!=null?live.id():0);
            item.put("name",row.name());item.put("uuid",row.uuid());item.put("reason",row.reason());item.put("operator",row.operator());item.put("start",row.start());item.put("end",row.end());item.put("type",row.type());item.put("category",category(row.type()));item.put("status",state);item.put("expired",state.equals("expired"));item.put("source",source);
            item.put("revision",state.equals("active")&&live!=null?live.revision():"");item.put("revocationDetailsAvailable",false);
            filtered.add(Collections.unmodifiableMap(item));
        }
        filtered.sort(Comparator.<Map<String,Object>>comparingLong(r->((Number)r.get("start")).longValue()).reversed().thenComparing(r->((Number)r.get("id")).intValue(),Comparator.reverseOrder()));
        int from=Math.min(offset,filtered.size()),to=Math.min(from+limit,filtered.size());
        Map<String,Object> out=new LinkedHashMap<>();out.put("items",List.copyOf(filtered.subList(from,to)));out.put("offset",offset);out.put("limit",limit);out.put("hasMore",to<filtered.size());out.put("matchedInWindow",filtered.size());out.put("truncated",historyMode?historyTruncated:activeTruncated);out.put("activeWindowTruncated",activeTruncated);out.put("windowLimit",WINDOW);out.put("scope",historyMode?"recent-provider-history":"recent-active-punishments");out.put("provider","advancedban");out.put("source",source);out.put("observedAt",now);return Collections.unmodifiableMap(out);
    }
    private static Row unique(List<Row> rows){return rows!=null&&rows.size()==1?rows.getFirst():null;}
    static String category(String type){if(type.contains("BAN"))return "ban";if(type.contains("MUTE"))return "mute";if(type.contains("WARNING"))return "warn";if(type.equals("NOTE"))return "note";if(type.equals("KICK"))return "kick";return "other";}
    private static String digest(List<?> values){try{MessageDigest sha=MessageDigest.getInstance("SHA-256");for(Object value:values){byte[] bytes=String.valueOf(value).getBytes(StandardCharsets.UTF_8);sha.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());sha.update(bytes);}return HexFormat.of().formatHex(sha.digest());}catch(Exception e){throw new IllegalStateException("Record fingerprint unavailable",e);}}
}
