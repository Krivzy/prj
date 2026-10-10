package dev.mcc.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SavedChunkStatusTest {
    @TempDir Path folder;
    private static byte[] nbt(String status)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(10);out.writeUTF("");out.writeByte(8);out.writeUTF("Status");out.writeUTF(status);out.writeByte(0);return bytes.toByteArray();
    }
    private static byte[] record(byte[] nbt,int codec)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(codec);
        if(codec==1){try(GZIPOutputStream compressed=new GZIPOutputStream(bytes)){compressed.write(nbt);}}
        else if(codec==2){try(DeflaterOutputStream compressed=new DeflaterOutputStream(bytes)){compressed.write(nbt);}}
        else bytes.write(nbt);return bytes.toByteArray();
    }
    private void region(String dimension,int cx,int cz,byte[] record)throws IOException {
        Path directory=Files.createDirectories(folder.resolve("dimensions/minecraft").resolve(dimension).resolve("region"));
        int sectors=(record.length+4+4095)/4096;byte[] file=new byte[8192+sectors*4096];int at=(Math.floorMod(cx,32)+32*Math.floorMod(cz,32))*4;
        file[at+2]=2;file[at+3]=(byte)sectors;ByteBuffer.wrap(file,8192,4).putInt(record.length);System.arraycopy(record,0,file,8196,record.length);
        Files.write(directory.resolve("r."+Math.floorDiv(cx,32)+"."+Math.floorDiv(cz,32)+".mca"),file);
    }
    @Test void savedProtoPresenceIsRejectedBeforePaperLoadCallback()throws Exception {
        AtomicInteger paperLoads=new AtomicInteger();
        for(String status:new String[]{"minecraft:terrain","minecraft:initialize_light","minecraft:features","minecraft:structure_starts"}) {
            region("overworld",-6,-3,record(nbt(status),2));
            var saved=SavedChunkStatus.read(folder,"overworld",-6,-3);
            assertEquals(SavedChunkStatus.State.INCOMPLETE,saved);
            assertFalse(SavedChunkStatus.loadOnlyFull(saved,paperLoads::incrementAndGet));
        }
        assertEquals(0,paperLoads.get(),"Proto chunks must never reach getChunkAtAsync, even with generate=false");
        assertFalse(SavedChunkStatus.loadOnlyFull(SavedChunkStatus.read(folder,"overworld",64000,-64000),paperLoads::incrementAndGet));
        assertEquals(0,paperLoads.get());
    }
    @Test void savedFullNegativeChunkAndModernDimensionsAdmitExactlyOneLoad()throws Exception {
        for(String dimension:new String[]{"overworld","the_nether","the_end"}) {
            region(dimension,-33,64,record(nbt("minecraft:full"),2));AtomicInteger loads=new AtomicInteger();
            assertTrue(SavedChunkStatus.loadOnlyFull(SavedChunkStatus.read(folder,dimension,-33,64),loads::incrementAndGet));assertEquals(1,loads.get());
        }
        for(int codec:new int[]{1,2,3})assertEquals(SavedChunkStatus.State.FULL,SavedChunkStatus.decode(record(nbt("full"),codec)));
    }
    @Test void unknownMissingAndUnsupportedChunkMetadataFailClosed()throws Exception {
        assertEquals(SavedChunkStatus.State.INCOMPLETE,SavedChunkStatus.decode(record(nbt("unknown"),2)));
        assertThrows(IOException.class,()->SavedChunkStatus.decode(new byte[]{(byte)130,1}));
        assertThrows(IOException.class,()->SavedChunkStatus.decode(new byte[]{4,1}));
        region("overworld",1,1,record(nbt("minecraft:full"),2));Path file=folder.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
        byte[] bytes=Files.readAllBytes(file);ByteBuffer.wrap(bytes,8192,4).putInt(SavedChunkStatus.MAX_COMPRESSED_BYTES+1);Files.write(file,bytes);
        assertThrows(IOException.class,()->SavedChunkStatus.read(folder,"overworld",1,1));
        assertEquals(SavedChunkStatus.State.MISSING,SavedChunkStatus.read(folder,"overworld",2,2));
    }
    @Test void nbtDecompressionNestingAndArrayBudgetsRejectOversizedInputs()throws Exception {
        assertThrows(IOException.class,()->SavedChunkStatus.decode(record(new byte[SavedChunkStatus.MAX_NBT_BYTES+1],2)));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF("");
        for(int i=0;i<40;i++){out.writeByte(10);out.writeUTF("nested");}out.writeByte(0);
        assertThrows(IOException.class,()->SavedChunkStatus.decode(record(bytes.toByteArray(),3)));
        bytes.reset();out.writeByte(10);out.writeUTF("");out.writeByte(12);out.writeUTF("Heightmaps");out.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class,()->SavedChunkStatus.decode(record(bytes.toByteArray(),3)));
        assertThrows(IOException.class,()->SavedChunkStatus.decode(new byte[SavedChunkStatus.MAX_COMPRESSED_BYTES+1]));
    }
}
