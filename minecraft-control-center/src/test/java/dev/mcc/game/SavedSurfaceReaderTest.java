package dev.mcc.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedSurfaceReaderTest {
    @Test void absentModernDimensionCannotFallBackToOverworld()throws Exception{Path world=folder.resolve("shared");Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));Files.createDirectories(world.resolve("region"));assertNull(SavedSurfaceReader.regionDirectory(world,"the_nether"));assertEquals(world.resolve("dimensions/minecraft/overworld/region"),SavedSurfaceReader.regionDirectory(world,"overworld"));}
    @TempDir Path folder;
    static void named(DataOutputStream out,int type,String name)throws IOException{out.writeByte(type);out.writeUTF(name);}
    static byte[] record(String status,int cx,int cz,int sy,boolean packed,int codec)throws IOException{
        ByteArrayOutputStream nbt=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(nbt)){
            named(out,10,"");named(out,8,"Status");out.writeUTF(status);named(out,3,"xPos");out.writeInt(cx);named(out,3,"zPos");out.writeInt(cz);
            named(out,9,"sections");out.writeByte(10);out.writeInt(1);named(out,1,"Y");out.writeByte(sy);named(out,10,"block_states");named(out,9,"palette");out.writeByte(10);out.writeInt(packed?2:1);
            if(packed){named(out,8,"Name");out.writeUTF("minecraft:air");out.writeByte(0);}named(out,8,"Name");out.writeUTF("minecraft:grass_block");out.writeByte(0);
            if(packed){long[] data=new long[256];for(int z=0;z<16;z++)for(int x=0;x<16;x++){int y=(x+z)%16,index=x+z*16+y*256;data[index/16]|=1L<<((index%16)*4);}named(out,12,"data");out.writeInt(data.length);for(long value:data)out.writeLong(value);}
            out.writeByte(0);out.writeByte(0);out.writeByte(0);
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(codec);
        if(codec==1){try(GZIPOutputStream zipped=new GZIPOutputStream(bytes)){zipped.write(nbt.toByteArray());}}
        else if(codec==2){try(DeflaterOutputStream zipped=new DeflaterOutputStream(bytes)){zipped.write(nbt.toByteArray());}}
        else bytes.write(nbt.toByteArray());return bytes.toByteArray();
    }
    static Path world(Path folder,int cx,int cz,byte[] record,int timestamp)throws IOException{
        Path region=Files.createDirectories(folder.resolve("dimensions/minecraft/overworld/region"));int sectors=(record.length+4+4095)/4096;
        byte[] bytes=new byte[8192+sectors*4096];int slot=Math.floorMod(cx,32)+32*Math.floorMod(cz,32);ByteBuffer b=ByteBuffer.wrap(bytes);b.putInt(slot*4,(2<<8)|sectors);b.putInt(4096+slot*4,timestamp);b.position(8192);b.putInt(record.length);b.put(record);
        Path file=region.resolve("r."+Math.floorDiv(cx,32)+"."+Math.floorDiv(cz,32)+".mca");Files.write(file,bytes);return file;
    }
    static byte[] modernPalette(boolean mixed)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(3);try(DataOutputStream out=new DataOutputStream(bytes)){
            named(out,10,"");named(out,8,"Status");out.writeUTF("minecraft:full");named(out,3,"xPos");out.writeInt(0);named(out,3,"zPos");out.writeInt(0);
            named(out,9,"sections");out.writeByte(10);out.writeInt(1);named(out,1,"Y");out.writeByte(4);named(out,10,"block_states");named(out,9,"palette");out.writeByte(mixed?10:8);out.writeInt(mixed?2:1);
            if(mixed){named(out,8,"");out.writeUTF("minecraft:air");out.writeByte(0);named(out,8,"id");out.writeUTF("minecraft:grass_block");named(out,10,"properties");named(out,8,"snowy");out.writeUTF("false");out.writeByte(0);out.writeByte(0);long[] data=new long[256];for(int z=0;z<16;z++)for(int x=0;x<16;x++){int index=x+z*16+(x+z)%16*256;data[index/16]|=1L<<((index%16)*4);}named(out,12,"data");out.writeInt(256);for(long value:data)out.writeLong(value);}else out.writeUTF("minecraft:grass_block");
            out.writeByte(0);out.writeByte(0);out.writeByte(0);
        }return bytes.toByteArray();
    }
    @Test void fullPackedColumnsDecodePaddedStorageAndNegativeY()throws Exception{var s=SavedSurfaceReader.decode(record("minecraft:full",-4,-3,-4,true,2),-4,-3);assertNotNull(s);assertEquals(-64,s.heights()[0]);assertEquals(-49,s.heights()[15]);assertEquals(-50,s.heights()[15+15*16]);assertEquals("minecraft:grass_block",s.materials()[0]);}
    @Test void singlePaletteUsesNoDataArrayAcrossSupportedCodecs()throws Exception{for(int codec:new int[]{1,2,3}){var s=SavedSurfaceReader.decode(record("full",0,0,3,false,codec),0,0);assertTrue(Arrays.stream(s.heights()).allMatch(y->y==63));}}
    @Test void persistedProtoIsNeverRendered()throws Exception{assertNull(SavedSurfaceReader.decode(record("minecraft:initialize_light",0,0,0,true,2),0,0));assertNull(SavedSurfaceReader.read(folder,"overworld",10,10));}
    @Test void actualAnvilNegativeCoordinatesReadWithoutAnyGameApi()throws Exception{Path region=world(folder,-4,-3,record("full",-4,-3,4,true,2),123);byte[] before=Files.readAllBytes(region);var s=SavedSurfaceReader.read(folder,"overworld",-4,-3);assertEquals(64,s.heights()[0]);assertArrayEquals(before,Files.readAllBytes(region));assertNull(SavedSurfaceReader.read(folder,"overworld",-5,-3));}
    @Test void corruptionCompressionAndCoordinateMismatchFailClosed()throws Exception{assertThrows(IOException.class,()->SavedSurfaceReader.decode(new byte[]{(byte)130,1},0,0));assertThrows(IOException.class,()->SavedSurfaceReader.decode(new byte[]{4,1},0,0));assertThrows(IOException.class,()->SavedSurfaceReader.decode(record("full",4,3,0,true,2),0,0));byte[] truncated=record("full",0,0,0,true,3);assertThrows(IOException.class,()->SavedSurfaceReader.decode(Arrays.copyOf(truncated,truncated.length-4),0,0));}
    @Test void decompressedBombAndArrayBudgetRejectBeforeLargeAllocation()throws Exception{ByteArrayOutputStream bomb=new ByteArrayOutputStream();bomb.write(2);try(DeflaterOutputStream out=new DeflaterOutputStream(bomb)){out.write(new byte[SavedSurfaceReader.MAX_NBT+1]);}assertThrows(IOException.class,()->SavedSurfaceReader.decode(bomb.toByteArray(),0,0));ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(3);try(DataOutputStream out=new DataOutputStream(bytes)){named(out,10,"");named(out,12,"large");out.writeInt(262145);}assertThrows(IOException.class,()->SavedSurfaceReader.decode(bytes.toByteArray(),0,0));}
    @Test void paper263ConstantStringPaletteDecodesRealNbtStorage()throws Exception{var surface=SavedSurfaceReader.decode(modernPalette(false),0,0);assertTrue(Arrays.stream(surface.heights()).allMatch(h->h==79));assertEquals("minecraft:grass_block",surface.materials()[0]);}
    @Test void paper263MixedListWrappersAndDispatchIdPropertiesDecodePackedColumns()throws Exception{var surface=SavedSurfaceReader.decode(modernPalette(true),0,0);assertEquals(64,surface.heights()[0]);assertEquals(79,surface.heights()[15]);assertEquals("minecraft:grass_block",surface.materials()[15]);}
    @Test void newPaletteFormsStayStrictAndKeepLegacyCompatibility()throws Exception{
        assertEquals("minecraft:air",SavedSurfaceReader.paletteMaterial("air"));assertEquals("minecraft:deepslate",SavedSurfaceReader.paletteMaterial(Map.of("","minecraft:deepslate")));assertEquals("minecraft:oak_fence",SavedSurfaceReader.paletteMaterial(Map.of("id","minecraft:oak_fence","properties",Map.of("north","true"))));assertEquals("minecraft:dirt",SavedSurfaceReader.paletteMaterial(Map.of("Name","minecraft:dirt")));
        for(Object value:List.of(123,Map.of(),Map.of("id",123),Map.of("id","minecraft:stone","Name","minecraft:dirt"),Map.of("","minecraft:dirt","unexpected",1),"minecraft:stone[foo=bar]","minecraft:bad:name"))assertThrows(IOException.class,()->SavedSurfaceReader.paletteMaterial(value));
    }
}
