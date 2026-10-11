package dev.mcc.integration;

import org.junit.jupiter.api.Test;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentRevisionsTest {
    @Test void leaseRequiresSameSessionPluginAndUnexpiredLifetime(){AtomicLong now=new AtomicLong();var leases=new PunishmentRevisions(now::get);Object plugin=new Object();leases.issue("owner-session","fingerprint",plugin,0);leases.require("owner-session","fingerprint",plugin,0);assertThrows(ConcurrentModificationException.class,()->leases.require("other-session","fingerprint",plugin,0));assertThrows(ConcurrentModificationException.class,()->leases.require("owner-session","fingerprint",new Object(),0));now.set(300_000);assertThrows(ConcurrentModificationException.class,()->leases.require("owner-session","fingerprint",plugin,0));}
    @Test void offOnCannotReviveLeaseFromLatePreviousEpochQuery(){var leases=new PunishmentRevisions(()->0);Object plugin=new Object();leases.issue("owner","fingerprint",plugin,0);leases.clear();leases.issue("owner","fingerprint",plugin,0);assertThrows(ConcurrentModificationException.class,()->leases.require("owner","fingerprint",plugin,2));leases.issue("owner","fingerprint",plugin,2);leases.require("owner","fingerprint",plugin,2);}
    @Test void displayedFingerprintsHaveBoundedRetention(){var leases=new PunishmentRevisions(()->0);Object plugin=new Object();for(int i=0;i<2050;i++)leases.issue("owner","record"+i,plugin,0);assertThrows(ConcurrentModificationException.class,()->leases.require("owner","record0",plugin,0));leases.require("owner","record2049",plugin,0);leases.clear();assertThrows(ConcurrentModificationException.class,()->leases.require("owner","record2049",plugin,0));}
}
