package dev.mcc.integration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CachedPlayerDirectoryTest {
    private static final String ID="00000000-0000-0000-0000-000000000001";
    @Test void onlineIdentityReplacesOlderNameForSameUuidAndMalformedRowsAreIgnored(){var rows=CachedPlayerDirectory.merge(List.of(Map.of("name","OldName","uuid",ID),Map.of("name","Bad","uuid","invented")),List.of(Map.of("name","NewName","uuid",ID)));assertEquals(1,rows.size());assertEquals("NewName",rows.getFirst().get("name"));assertEquals(true,rows.getFirst().get("online"));assertFalse(CachedPlayerDirectory.matches(rows,"OldName",UUID.fromString(ID)));assertTrue(CachedPlayerDirectory.matches(rows,"NewName",UUID.fromString(ID)));}
    @Test void ambiguousNamesAndMismatchedProviderUuidCannotAuthorizeRecordEdits(){var rows=CachedPlayerDirectory.merge(List.of(Map.of("name","SameName","uuid",ID),Map.of("name","samename","uuid","00000000-0000-0000-0000-000000000002")),List.of());assertFalse(CachedPlayerDirectory.matches(rows,"SameName",UUID.fromString(ID)));assertEquals(Map.of(),CachedPlayerDirectory.identity(rows,"SameName",ID));var single=List.of(rows.getFirst());assertEquals(Map.of(),CachedPlayerDirectory.identity(single,"SameName","00000000-0000-0000-0000-000000000099"));}
    @Test void lookupBoundsSnapshotAndResponseWithoutResolvingUnknownNames(){List<Map<String,Object>> seed=new ArrayList<>();for(int i=0;i<3000;i++)seed.add(Map.of("name","Player"+i,"uuid",new UUID(0,i+1).toString()));var rows=CachedPlayerDirectory.merge(seed,List.of());assertEquals(2048,rows.size());var page=CachedPlayerDirectory.page(rows,Map.of("limit",50));assertEquals(50,((List<?>)page.get("items")).size());assertEquals(true,page.get("truncated"));assertEquals(List.of(),CachedPlayerDirectory.page(rows,Map.of("search","NotCached")).get("items"));}
}
