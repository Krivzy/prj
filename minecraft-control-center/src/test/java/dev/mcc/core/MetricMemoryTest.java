package dev.mcc.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MetricMemoryTest {
    @TempDir Path root;
    @Test void persistenceKeepsOnlySmallNumericTimeSeries()throws Exception {try(var db=new Database(root.resolve("test.db"))){db.metric(Map.of("tps",List.of(20,19,18),"heapUsed",1000,"worlds",List.of(Map.of("huge","x".repeat(100000))),"players",List.of("private"))).get();String raw=db.submit(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM metrics")){r.next();return r.getString(1);}}).get();assertTrue(raw.length()<120);assertFalse(raw.contains("worlds"));assertFalse(raw.contains("players"));}}
    @Test void dayOfLegacyLargeSnapshotsIsSampledBeforeJsonDecode()throws Exception {try(var db=new Database(root.resolve("test.db"))){long end=System.currentTimeMillis()-1000,start=end-86400000;db.submit(c->{c.setAutoCommit(false);try(var s=c.prepareStatement("INSERT INTO metrics VALUES(?,?)")){for(int i=0;i<8641;i++){s.setLong(1,start+i*10000L);s.setString(2,Json.write(Map.of("tps",List.of(20,20,20),"online",i,"worlds",List.of(Map.of("legacy","x".repeat(512))))));s.addBatch();}s.executeBatch();c.commit();}finally{c.setAutoCommit(true);}return null;}).get();var rows=db.metrics(start).get();assertTrue(rows.size()<=361);assertTrue(rows.size()>=350);assertEquals(end,rows.getLast().get("time"));assertEquals(8640,((Number)rows.getLast().get("online")).intValue());assertTrue(rows.stream().noneMatch(r->r.containsKey("worlds")));}}
    @Test void corruptLargeNestedMetricCannotEnterHistory(){var value=Database.compactMetric(Map.of("tps",List.of("wrong",20,19,18,17),"cpu",Double.NaN,"heapUsed",Map.of("bad",1)));assertFalse(value.containsKey("cpu"));assertFalse(value.containsKey("heapUsed"));assertEquals(List.of(20,19),value.get("tps"));}
}
