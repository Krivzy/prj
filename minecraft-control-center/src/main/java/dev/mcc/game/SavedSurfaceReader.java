package dev.mcc.game;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Disk-only, bounded Anvil decoder. Never calls Bukkit or opens a chunk in Paper. */
final class SavedSurfaceReader {
    static final int MAX_RECORD=1_048_576,MAX_NBT=2_097_152;
    record Surface(int cx,int cz,int[] heights,String[] materials) {}
    static Path regionDirectory(Path folder,String dimension){
        if(!Set.of("overworld","the_nether","the_end").contains(dimension))throw new IllegalArgumentException("Unknown dimension");
        Path modern=folder.resolve("dimensions/minecraft");
        if(Files.isDirectory(modern,LinkOption.NOFOLLOW_LINKS)){Path region=modern.resolve(dimension).resolve("region");return Files.isDirectory(region,LinkOption.NOFOLLOW_LINKS)?region:null;}
        List<Path> paths=List.of(folder.resolve(dimension.equals("the_nether")?"DIM-1":dimension.equals("the_end")?"DIM1":".").resolve("region"),folder.resolve("region"));
        return paths.stream().filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);
    }
    static Surface read(Path folder,String dimension,int cx,int cz)throws IOException{
        Path directory=regionDirectory(folder,dimension);if(directory==null)return null;
        Path region=directory.resolve("r."+Math.floorDiv(cx,32)+"."+Math.floorDiv(cz,32)+".mca");
        if(!Files.isRegularFile(region,LinkOption.NOFOLLOW_LINKS))return null;
        byte[] record;long modified=Files.getLastModifiedTime(region,LinkOption.NOFOLLOW_LINKS).toMillis();
        try(FileChannel file=FileChannel.open(region,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer location=ByteBuffer.allocate(4);readFully(file,location,(Math.floorMod(cx,32)+32L*Math.floorMod(cz,32))*4);
            byte[] entry=location.array();int offset=((entry[0]&255)<<16)|((entry[1]&255)<<8)|(entry[2]&255),sectors=entry[3]&255;
            if(offset<2||sectors==0)return null;
            ByteBuffer length=ByteBuffer.allocate(4);readFully(file,length,offset*4096L);int size=length.flip().getInt();
            if(size<2||size>MAX_RECORD||size+4>sectors*4096||offset*4096L+size+4>file.size())throw new IOException("Invalid Anvil chunk bounds");
            ByteBuffer body=ByteBuffer.allocate(size);readFully(file,body,offset*4096L+4);record=body.array();
        }
        if(Files.getLastModifiedTime(region,LinkOption.NOFOLLOW_LINKS).toMillis()!=modified)throw new IOException("Region changed while reading; retry background pass");
        return decode(record,cx,cz);
    }
    private static void readFully(FileChannel file,ByteBuffer buffer,long position)throws IOException{while(buffer.hasRemaining()){int n=file.read(buffer,position);if(n<=0)throw new EOFException("Incomplete Anvil record");position+=n;}}
    static Surface decode(byte[] record,int cx,int cz)throws IOException{
        if(record.length<2||record.length>MAX_RECORD)throw new IOException("Chunk record budget exceeded");
        int codec=record[0]&255;if((codec&128)!=0)throw new IOException("External .mcc chunks are unsupported");
        byte[] plain;
        try(InputStream bytes=new ByteArrayInputStream(record,1,record.length-1);InputStream input=switch(codec){case 1->new GZIPInputStream(bytes);case 2->new InflaterInputStream(bytes);case 3->bytes;default->throw new IOException("Unsupported chunk compression");}){plain=input.readNBytes(MAX_NBT+1);}
        if(plain.length>MAX_NBT)throw new IOException("Chunk NBT budget exceeded");
        Map<?,?> root;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))){if(in.readUnsignedByte()!=10)throw new IOException("NBT root is not compound");name(in);root=(Map<?,?>)payload(in,10,0,new Budget());}
        if(root.get("Level") instanceof Map<?,?> level)root=level;
        Object status=root.get("Status");if(!"minecraft:full".equals(status)&&!"full".equals(status))return null;
        if(root.get("xPos") instanceof Number x&&x.intValue()!=cx||root.get("zPos") instanceof Number z&&z.intValue()!=cz)throw new IOException("Chunk coordinate mismatch");
        if(!(root.get("sections") instanceof List<?> sections))throw new IOException("Saved FULL chunk lacks modern sections");
        if(sections.size()>256)throw new IOException("Section budget exceeded");
        int[] heights=new int[256];Arrays.fill(heights,Integer.MIN_VALUE);String[] materials=new String[256];Set<Integer> seen=new HashSet<>();
        for(Object raw:sections){if(!(raw instanceof Map<?,?> section)||!(section.get("Y") instanceof Number number))throw new IOException("Invalid chunk section");int sy=number.intValue();if(sy<-128||sy>127||!seen.add(sy))throw new IOException("Invalid/duplicate section Y");
            if(!(section.get("block_states") instanceof Map<?,?> states))continue;
            if(!(states.get("palette") instanceof List<?> entries)||entries.isEmpty()||entries.size()>4096)throw new IOException("Invalid block palette");
            String[] palette=new String[entries.size()];for(int i=0;i<palette.length;i++)palette[i]=paletteMaterial(entries.get(i));
            int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.length-1)),perLong=64/bits;long mask=(1L<<bits)-1;long[] packed=states.get("data") instanceof long[] a?a:null;
            if(palette.length>1&&(packed==null||packed.length!=(4096+perLong-1)/perLong))throw new IOException("Invalid padded block state storage");
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){int column=x+z*16;for(int y=15;y>=0;y--){int height=sy*16+y;if(height<=heights[column])break;int index=x+z*16+y*256,id=palette.length==1?0:(int)((packed[index/perLong]>>>((index%perLong)*bits))&mask);if(id>=palette.length)throw new IOException("Palette index out of bounds");String material=palette[id];if(material.equals("minecraft:air")||material.equals("minecraft:cave_air")||material.equals("minecraft:void_air"))continue;heights[column]=height;materials[column]=material;break;}}
        }
        return new Surface(cx,cz,heights,materials);
    }
    /** 26.3 constant/dispatch codec and heterogeneous NBT-list wrapper, plus legacy Name compounds. */
    static String paletteMaterial(Object raw)throws IOException{
        if(raw instanceof Map<?,?> wrapper&&wrapper.size()==1&&wrapper.containsKey(""))raw=wrapper.get("");
        Object value=raw;
        if(raw instanceof Map<?,?> entry){if(entry.containsKey("id")&&entry.containsKey("Name"))throw new IOException("Ambiguous palette material");value=entry.containsKey("id")?entry.get("id"):entry.get("Name");}
        if(!(value instanceof String material)||material.length()>128||!material.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))throw new IOException("Invalid palette material");
        return material.contains(":")?material:"minecraft:"+material;
    }
    private static final class Budget{int tags;void check(int depth)throws IOException{if(depth>32||++tags>131_072)throw new IOException("NBT depth/tag budget exceeded");}}
    private static int length(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>262_144)throw new IOException("NBT array/list budget exceeded");return n;}
    private static String name(DataInputStream in)throws IOException{int n=in.readUnsignedShort();byte[] bytes=in.readNBytes(n);if(bytes.length!=n)throw new EOFException("Incomplete NBT string");return new String(bytes,StandardCharsets.UTF_8);}
    private static Object payload(DataInputStream in,int type,int depth,Budget budget)throws IOException{
        budget.check(depth);return switch(type){case 1->in.readByte();case 2->in.readShort();case 3->in.readInt();case 4->in.readLong();case 5->in.readFloat();case 6->in.readDouble();case 7->{byte[] a=new byte[length(in)];in.readFully(a);yield a;}case 8->name(in);
            case 9->{int child=in.readUnsignedByte(),n=length(in);if(child==0&&n!=0)throw new IOException("Invalid NBT list");List<Object> list=new ArrayList<>(n);for(int i=0;i<n;i++)list.add(payload(in,child,depth+1,budget));yield list;}
            case 10->{Map<String,Object> map=new HashMap<>();for(;;){int child=in.readUnsignedByte();if(child==0)break;String key=name(in);if(map.put(key,payload(in,child,depth+1,budget))!=null)throw new IOException("Duplicate NBT key");}yield map;}
            case 11->{int[] a=new int[length(in)];for(int i=0;i<a.length;i++)a[i]=in.readInt();yield a;}case 12->{long[] a=new long[length(in)];for(int i=0;i<a.length;i++)a[i]=in.readLong();yield a;}default->throw new IOException("Unknown NBT type");};
    }
}
