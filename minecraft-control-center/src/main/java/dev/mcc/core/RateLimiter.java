package dev.mcc.core;

import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private record Bucket(long window,int count) {}
    private final ConcurrentHashMap<String,Bucket> buckets=new ConcurrentHashMap<>();
    public boolean allow(String key,int limit,long periodMillis){
        long now=System.currentTimeMillis();
        if(buckets.size()>4096){buckets.entrySet().removeIf(e->now-e.getValue().window()>periodMillis*2);if(buckets.size()>4096)return false;}
        Bucket b=buckets.compute(key,(k,v)->v==null||now-v.window()>=periodMillis?new Bucket(now,1):new Bucket(v.window(),v.count()+1));return b.count()<=limit;
    }
}
