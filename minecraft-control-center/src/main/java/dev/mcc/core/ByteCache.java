package dev.mcc.core;
import java.util.*;
/** Entry and byte bounded LRU. Values are immutable byte arrays owned by the caller. */
public final class ByteCache {
    private final int count,maxBytes;private long bytes;
    private final LinkedHashMap<String,byte[]> values=new LinkedHashMap<>(16,.75f,true);
    public ByteCache(int count,int maxBytes){if(count<1||maxBytes<1)throw new IllegalArgumentException();this.count=count;this.maxBytes=maxBytes;}
    public synchronized byte[] get(String key){return values.get(key);}
    public synchronized void put(String key,byte[] value){if(value.length>maxBytes)return;byte[] old=values.put(key,value);if(old!=null)bytes-=old.length;bytes+=value.length;while(values.size()>count||bytes>maxBytes){var entry=values.entrySet().iterator().next();bytes-=entry.getValue().length;values.remove(entry.getKey());}}
    public synchronized long bytes(){return bytes;}
    public synchronized int size(){return values.size();}
    public synchronized void clear(){values.clear();bytes=0;}
}
