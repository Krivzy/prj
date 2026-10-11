package dev.mcc.integration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentRecordsTest {
    private static final String UUID="00000000000000000000000000000001";
    private static final Map<String,Object> SOURCE=Map.of("id","advancedban","title","AdvancedBanZ","version","4.0.1");
    private static PunishmentRecords.Row row(int id,long start){return new PunishmentRecords.Row(id,"KnownPlayer",UUID,"Reason","Moderator",start,-1,"BAN",false);}
    private static Map<String,Object> page(List<PunishmentRecords.Row> history,List<PunishmentRecords.Row> active,boolean at,Map<String,Object> filters){return PunishmentRecords.page(history,active,false,at,filters,SOURCE,1000,true);}
    private static Map<?,?> first(Map<String,Object> page){return (Map<?,?>)((List<?>)page.get("items")).getFirst();}
    @Test void historyIdsNeverReplaceIndependentActiveIds(){var active=row(2,100);var item=first(page(List.of(row(90,100)),List.of(active),false,Map.of()));assertEquals(90,item.get("historyId"));assertEquals(2,item.get("activeId"));assertEquals("active",item.get("status"));assertEquals(active.revision(),item.get("revision"));assertEquals(SOURCE,item.get("source"));}
    @Test void sameNumericIdForDifferentSanctionCannotJoin(){var item=first(page(List.of(row(2,100)),List.of(row(2,200)),false,Map.of()));assertEquals("inactive",item.get("status"));assertEquals(0,item.get("activeId"));assertEquals("",item.get("revision"));}
    @Test void duplicateSemanticRowsCannotCertifyWhichHistoryEntryIsActive(){for(List<PunishmentRecords.Row> history:List.of(List.of(row(10,100),row(11,100)),List.of(row(10,100)))){var items=(List<?>)page(history,List.of(row(1,100),row(2,100)),false,Map.of()).get("items");for(Object value:items){Map<?,?> item=(Map<?,?>)value;assertEquals("unknown",item.get("status"));assertEquals(0,item.get("activeId"));}}}
    @Test void truncatedActiveWindowDoesNotMislabelUnmatchedHistoryAsRevoked(){var item=first(page(List.of(row(10,100)),List.of(),true,Map.of()));assertEquals("unknown",item.get("status"));assertEquals(false,item.get("revocationDetailsAvailable"));}
    @Test void providerReasonEditsKeepSemanticJoinButInvalidateRevision(){var original=row(2,100);var changed=new PunishmentRecords.Row(2,original.name(),UUID,"Edited reason",original.operator(),100,-1,"BAN",false);var item=first(page(List.of(row(90,100)),List.of(changed),false,Map.of()));assertEquals("active",item.get("status"));assertEquals(changed.revision(),item.get("revision"));assertNotEquals(original.revision(),changed.revision());}
    @Test void paginationUsesFilteredNewestRowsAndNeverInventsTotal(){List<PunishmentRecords.Row> rows=new ArrayList<>();for(int i=1;i<=90;i++)rows.add(row(i,i));var result=page(rows,List.of(),false,Map.of("offset",25,"limit",25,"player","knownplayer","type","ban","status","inactive"));assertEquals(90,result.get("matchedInWindow"));assertEquals(true,result.get("hasMore"));assertEquals(65,first(result).get("id"));assertEquals(25,((List<?>)result.get("items")).size());assertThrows(IllegalArgumentException.class,()->page(rows,List.of(),false,Map.of("limit",51)));}
    @Test void expiredAndInstantRecordsHaveHonestStatuses(){var expired=new PunishmentRecords.Row(1,"KnownPlayer",UUID,"Reason","Mod",100,900,"TEMP_MUTE",false);var kick=new PunishmentRecords.Row(2,"KnownPlayer",UUID,"Reason","Mod",200,-1,"KICK",false);var rows=(List<?>)page(List.of(expired,kick),List.of(),false,Map.of()).get("items");assertEquals("recorded",((Map<?,?>)rows.get(0)).get("status"));assertEquals("expired",((Map<?,?>)rows.get(1)).get("status"));}
    @Test void providerIpAndNameKeysNeverBecomeInventedUuids(){for(String key:List.of("127.0.0.1","knownplayer","broken"))assertEquals("",new PunishmentRecords.Row(1,"KnownPlayer",key,"R","M",1,-1,"BAN",false).uuid());assertEquals("00000000-0000-0000-0000-000000000001",row(1,1).uuid());}
}
