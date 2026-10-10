package dev.mcc.game;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Bounded disk-only FULL preflight, before the supported Paper async load API is used. */
final class SavedChunkStatus {
    static final int MAX_COMPRESSED_BYTES=1_048_576,MAX_NBT_BYTES=2_097_152;
    enum State { FULL, INCOMPLETE, MISSING }
    private SavedChunkStatus() {}

    static State read(Path folder,String dimension,int cx,int cz)throws IOException {
        List<Path> candidates=List.of(folder.resolve("region"),folder.resolve("dimensions/minecraft").resolve(dimension).resolve("region"),folder.resolve(dimension.equals("the_nether")?"DIM-1":dimension.equals("the_end")?"DIM1":".").resolve("region"));
        Path directory=candidates.stream().filter(path->Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);
        if(directory==null)return State.MISSING;
        Path region=directory.resolve("r."+Math.floorDiv(cx,32)+"."+Math.floorDiv(cz,32)+".mca");
        if(!Files.isRegularFile(region,LinkOption.NOFOLLOW_LINKS))return State.MISSING;
        byte[] record;
        try(FileChannel file=FileChannel.open(region,StandardOpenOption.READ)) {
            ByteBuffer location=ByteBuffer.allocate(4);readFully(file,location,(Math.floorMod(cx,32)+32L*Math.floorMod(cz,32))*4);
            byte[] entry=location.array();int offset=((entry[0]&255)<<16)|((entry[1]&255)<<8)|(entry[2]&255),sectors=entry[3]&255;
            if(offset<2||sectors==0)return State.MISSING;
            ByteBuffer length=ByteBuffer.allocate(4);readFully(file,length,offset*4096L);int size=length.flip().getInt();
            if(size<1||size>MAX_COMPRESSED_BYTES||size+4>sectors*4096||offset*4096L+size+4>file.size())throw new IOException("Invalid saved chunk record bounds");
            ByteBuffer body=ByteBuffer.allocate(size);readFully(file,body,offset*4096L+4);record=body.array();
        }
        return decode(record);
    }
    private static void readFully(FileChannel file,ByteBuffer buffer,long position)throws IOException {
        while(buffer.hasRemaining()){int count=file.read(buffer,position);if(count<0)throw new EOFException("Incomplete region record");if(count==0)throw new IOException("Region read did not advance");position+=count;}
    }
    static State decode(byte[] record)throws IOException {
        if(record.length<2||record.length>MAX_COMPRESSED_BYTES)throw new IOException("Invalid compressed chunk size");
        int codec=record[0]&255;if((codec&128)!=0)throw new IOException("External chunk records are unavailable to the map preflight");
        byte[] plain;
        try(InputStream bytes=new ByteArrayInputStream(record,1,record.length-1);
            InputStream input=switch(codec){case 1->new GZIPInputStream(bytes);case 2->new InflaterInputStream(bytes);case 3->bytes;default->throw new IOException("Unsupported saved chunk compression");}) {
            plain=input.readNBytes(MAX_NBT_BYTES+1);
        }
        if(plain.length>MAX_NBT_BYTES)throw new IOException("Saved chunk NBT exceeds map preflight budget");
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(plain))) {
            if(input.readUnsignedByte()!=10)throw new IOException("Saved chunk root must be an NBT compound");
            name(input);String status=compoundStatus(input,0,new Budget());
            return "minecraft:full".equals(status)||"full".equals(status)?State.FULL:State.INCOMPLETE;
        }
    }
    static boolean loadOnlyFull(State state,Runnable load){if(state!=State.FULL)return false;load.run();return true;}
    private static final class Budget {int tags;void tag(int depth)throws IOException{if(depth>32||++tags>131_072)throw new IOException("Saved chunk NBT nesting/tag budget exceeded");}}
    private static String compoundStatus(DataInputStream input,int depth,Budget budget)throws IOException {
        for(;;){budget.tag(depth);int type=input.readUnsignedByte();if(type==0)return null;String key=name(input);
            if(type==8&&key.equals("Status"))return name(input);
            if(type==10&&key.equals("Level")){String nested=compoundStatus(input,depth+1,budget);if(nested!=null)return nested;}
            else skip(input,type,depth+1,budget);
        }
    }
    private static String name(DataInputStream input)throws IOException {int length=input.readUnsignedShort();byte[] bytes=new byte[length];input.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);}
    private static int length(DataInputStream input)throws IOException {int length=input.readInt();if(length<0||length>262_144)throw new IOException("Invalid saved chunk NBT array length");return length;}
    private static void skip(DataInputStream input,int type,int depth,Budget budget)throws IOException {
        budget.tag(depth);
        switch(type){
            case 1->input.skipNBytes(1);case 2->input.skipNBytes(2);case 3,5->input.skipNBytes(4);case 4,6->input.skipNBytes(8);
            case 7->input.skipNBytes(length(input));case 8->input.skipNBytes(input.readUnsignedShort());
            case 9->{int child=input.readUnsignedByte(),count=length(input);if(child==0&&count!=0)throw new IOException("Invalid NBT list type");for(int i=0;i<count;i++)skip(input,child,depth+1,budget);}
            case 10->{for(;;){int child=input.readUnsignedByte();if(child==0)break;name(input);skip(input,child,depth+1,budget);}}
            case 11->input.skipNBytes(length(input)*4L);case 12->input.skipNBytes(length(input)*8L);
            default->throw new IOException("Unknown saved chunk NBT tag");
        }
    }
}
