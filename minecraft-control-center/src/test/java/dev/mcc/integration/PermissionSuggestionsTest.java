package dev.mcc.integration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PermissionSuggestionsTest {
    @Test void matchesPrefixFirstDeduplicatesAndKeepsDescriptions(){
        var rows=List.of(Map.<String,Object>of("key","other.minecraft.foo"),Map.<String,Object>of("key","minecraft.command.help","description","Show help","source","Minecraft"),Map.<String,Object>of("key","minecraft.command.help"),Map.<String,Object>of("key","unrelated"));
        var data=PermissionSuggestions.query(Map.of("search","MINECRAFT","limit",1),rows);
        var items=(List<?>)data.get("items");assertEquals(1,items.size());assertEquals("minecraft.command.help",((Map<?,?>)items.getFirst()).get("key"));assertEquals("Show help",((Map<?,?>)items.getFirst()).get("description"));assertEquals(true,data.get("hasMore"));
    }
    @Test void neverInventsWildcardOrCustomPermissions(){
        var data=PermissionSuggestions.query(Map.of("search","luckperms"),List.of(Map.of("key","luckperms.user.info"),Map.of("key","<script>")));
        assertEquals(List.of(Map.of("key","luckperms.user.info","description","","source","")),data.get("items"));
        assertEquals(List.of(),PermissionSuggestions.query(Map.of("search","custom.node"),List.of()).get("items"));
    }
    @Test void rejectsUnboundedOrInvalidQueries(){
        for(var body:List.of(Map.<String,Object>of("search","x".repeat(201)),Map.<String,Object>of("search","<html>"),Map.<String,Object>of("limit",41),Map.<String,Object>of("limit",1.2)))assertThrows(IllegalArgumentException.class,()->PermissionSuggestions.query(body,List.of()));
    }
    @Test void registryAndProviderTextAreBounded(){
        var registry=new ArrayList<Map<String,Object>>();for(int i=0;i<9000;i++)registry.add(Map.of("key","p."+i,"description","d".repeat(900),"source","s".repeat(100)));
        var result=PermissionSuggestions.query(Map.of("search","p.8999"),registry);assertEquals(List.of(),result.get("items"));assertEquals(true,result.get("registryLimited"));
        var item=(Map<?,?>)((List<?>)PermissionSuggestions.query(Map.of("search","p.0"),registry).get("items")).getFirst();assertEquals(400,((String)item.get("description")).length());assertEquals(80,((String)item.get("source")).length());
    }
}
