package dev.mcc.integration;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Exact enabled-plugin identities, shared by adapter discovery and the native ownership guard. */
final class PunishmentProviders {
    record Provider(String name,String id,String namespace) {
        boolean supported(){return !namespace.isEmpty();}
    }
    private static final List<Provider> KNOWN=List.of(
        new Provider("AdvancedBan","advancedban","me.leoko.advancedban"),
        new Provider("AdvancedBanZ","advancedban","net.hnt8.advancedban"),
        new Provider("LiteBans","litebans",""),
        new Provider("BanManager","banmanager",""),
        new Provider("LibertyBans","libertybans",""));
    static List<Provider> known(){return KNOWN;}
    static Provider named(String name){return KNOWN.stream().filter(p->p.name().equalsIgnoreCase(name)).findFirst().orElse(null);}
    static boolean owns(String id){return KNOWN.stream().anyMatch(p->p.id().equals(id));}
    static String namespace(String name){Provider p=named(name);if(p==null||!p.supported())throw new UnsupportedOperationException("No supported punishment API for this plugin");return p.namespace();}
    static String selected(List<IntegrationService.Binding> bindings){
        List<IntegrationService.Binding> providers=bindings.stream().filter(b->owns(b.id())).toList();
        if(providers.isEmpty())return "builtin";
        return providers.size()==1&&providers.getFirst().id().equals("advancedban")&&providers.getFirst().unavailable().isEmpty()?"advancedban":"unavailable";
    }
    /** Multiple enabled moderation systems have no unambiguous mutation target. */
    static List<IntegrationService.Binding> exclusive(List<IntegrationService.Binding> bindings){
        if(bindings.stream().filter(b->owns(b.id())).count()<2)return List.copyOf(bindings);
        Map<String,IntegrationService.Binding> out=new LinkedHashMap<>();
        for(IntegrationService.Binding b:bindings){
            if(!owns(b.id())){out.put(b.id(),b);continue;}
            IntegrationService.Binding previous=out.get(b.id());
            out.put(b.id(),new IntegrationService.Binding(b.id(),previous==null?b.title():previous.title()+" / "+b.title(),previous==null?b.version():previous.version()+" / "+b.version(),null,b.loader(),b.identity(),"Multiple punishment providers are enabled; MCC moderation is unavailable"));
        }
        return List.copyOf(out.values());
    }
    /** Lifecycle writes are on-main; listener/worker reads touch only an atomic flag. */
    static final class Presence implements BooleanSupplier {
        private final Set<String> enabled=new HashSet<>();
        private final AtomicBoolean external=new AtomicBoolean();
        private boolean closed;
        void changed(String name,boolean active){Provider p=named(name);if(p==null||closed)return;if(active)enabled.add(p.name());else enabled.remove(p.name());external.set(!enabled.isEmpty());}
        void close(){closed=true;external.set(true);}
        @Override public boolean getAsBoolean(){return external.get();}
    }
    private PunishmentProviders(){}
}
