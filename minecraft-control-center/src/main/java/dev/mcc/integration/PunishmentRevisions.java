package dev.mcc.integration;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, session/plugin-scoped five-minute permission to use a displayed active fingerprint. */
final class PunishmentRevisions {
    static final long TTL_MILLIS=300_000;
    private record Lease(Object plugin,long epoch,long deadline) {}
    private final LongSupplier clock;
    private final LinkedHashMap<String,Lease> leases=new LinkedHashMap<>();
    PunishmentRevisions(){this(()->System.nanoTime()/1_000_000);}
    PunishmentRevisions(LongSupplier clock){this.clock=clock;}
    synchronized void issue(String actor,String revision,Object plugin,long epoch){if(revision.isEmpty())return;long now=clock.getAsLong();leases.values().removeIf(lease->lease.deadline()<=now);String key=actor+":"+revision;leases.remove(key);while(leases.size()>=2048)leases.remove(leases.keySet().iterator().next());leases.put(key,new Lease(plugin,epoch,now+TTL_MILLIS));}
    synchronized void require(String actor,String revision,Object plugin,long epoch){Lease lease=leases.get(actor+":"+revision);if(lease==null||lease.plugin()!=plugin||lease.epoch()!=epoch||lease.deadline()<=clock.getAsLong())throw new ConcurrentModificationException("Punishment confirmation expired; refresh before revoking");}
    synchronized void clear(){leases.clear();}
}
