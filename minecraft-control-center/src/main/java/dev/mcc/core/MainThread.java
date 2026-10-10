package dev.mcc.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Timeout cancels queued work; a started operation reports its actual result. */
public final class MainThread {
    private final JavaPlugin plugin;
    private final AtomicInteger pending=new AtomicInteger();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong taskNanos=new AtomicLong(),count=new AtomicLong(),maxNanos=new AtomicLong();
    public MainThread(JavaPlugin plugin){this.plugin=plugin;}
    public <T> CompletableFuture<T> call(Supplier<T> operation){
        AtomicInteger phase=new AtomicInteger();
        CompletableFuture<T> result=new CompletableFuture<>(){
            @Override public boolean cancel(boolean interrupt){return phase.compareAndSet(0,2)&&super.cancel(false);}
        };
        if(closed.get()){result.completeExceptionally(new IllegalStateException("Game queue unavailable"));return result;}
        if(pending.incrementAndGet()>256){pending.decrementAndGet();result.completeExceptionally(new IllegalStateException("Game queue unavailable"));return result;}
        try {Bukkit.getScheduler().runTask(plugin,()->{
            pending.decrementAndGet();if(!phase.compareAndSet(0,1))return;if(closed.get()){result.completeExceptionally(new IllegalStateException("MCC stopped"));return;}
            long start=System.nanoTime();try{result.complete(operation.get());}catch(Throwable e){result.completeExceptionally(e);}finally{long n=System.nanoTime()-start;taskNanos.addAndGet(n);count.incrementAndGet();maxNanos.accumulateAndGet(n,Math::max);}
        });}catch(Exception e){pending.decrementAndGet();result.completeExceptionally(e);}return result;
    }
    public <T> T await(Supplier<T> operation) throws Exception {
        CompletableFuture<T> f=call(operation);try{return f.get(10,TimeUnit.SECONDS);}catch(TimeoutException e){if(f.cancel(false))throw new IllegalStateException("Queued game request expired before execution");throw new PendingOperation(f);}
    }
    public static final class PendingOperation extends RuntimeException {
        private final CompletableFuture<?> future;
        public PendingOperation(CompletableFuture<?> future){super("Game operation has started; result is pending");this.future=future;}
        public CompletableFuture<?> future(){return future;}
    }
    public MapStats stats(){long c=count.get();return new MapStats(pending.get(),c,c==0?0:taskNanos.get()/1e6/c,maxNanos.get()/1e6);}
    public record MapStats(int pending,long tasks,double meanMs,double maxMs){}
    public void close(){closed.set(true);}
}
