package dev.mcc.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ByteCacheTest {
    @Test void byteBudgetEvictsLeastRecentlyUsed(){var c=new ByteCache(10,8);c.put("a",new byte[3]);c.put("b",new byte[3]);c.get("a");c.put("c",new byte[3]);assertNull(c.get("b"));assertNotNull(c.get("a"));assertEquals(6,c.bytes());}
    @Test void replacementAndOversizeKeepAccounting(){var c=new ByteCache(2,8);c.put("a",new byte[4]);c.put("a",new byte[2]);c.put("huge",new byte[9]);assertEquals(2,c.bytes());assertEquals(1,c.size());c.put("b",new byte[1]);c.put("c",new byte[1]);assertNull(c.get("a"));c.clear();assertEquals(0,c.bytes());}
}
