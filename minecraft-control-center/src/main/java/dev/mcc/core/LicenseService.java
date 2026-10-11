package dev.mcc.core;

import java.util.*;

/** Local entitlements only. Signed, remote key activation is deliberately not implemented yet. */
public final class LicenseService {
    public static final List<String> TRIAL_FEATURES=List.of("overview","console","chat","settings");
    public static final List<String> FULL_FEATURES=List.of("overview","players","worlds","map","events","automations","fairPlay","plugins","integrations","entities","timeline","console","chat","settings");
    private static final Set<String> TRIAL_MODULES=Set.of("console","chat","metrics");
    private final Database db;
    private volatile Map<String,Object> selected;
    public LicenseService(Database db,boolean existingInstallation)throws Exception{
        this.db=db;
        Optional<Map<String,Object>> stored=db.document("license","state").get();
        if(stored.isPresent()){selected=validStored(stored.get());}
        else {selected=stored(existingInstallation?"temporary-full":"trial",existingInstallation?"existing-installation":"first-run",System.currentTimeMillis());db.document("license","state",selected).get();}
    }
    static Map<String,Object> stored(String mode,String source,long now){validate(mode,"");return Map.of("mode",mode,"source",source,"selectedAt",now,"schema",1);}
    static void validate(String mode,String key){
        if(!Set.of("trial","temporary-full").contains(mode))throw new IllegalArgumentException("Unknown license mode");
        if(key!=null&&!key.isBlank())throw new IllegalArgumentException("Активация ключей ещё не подключена. Выберите пробную или временную полную версию.");
    }
    private static Map<String,Object> validStored(Map<String,Object> value){String mode=Json.text(value,"mode","");validate(mode,"");return Map.copyOf(value);}
    public boolean fullAccess(){return selected!=null&&"temporary-full".equals(selected.get("mode"));}
    public boolean moduleAllowed(String key){return fullAccess()||TRIAL_MODULES.contains(key);}
    public boolean topicAllowed(String topic){return fullAccess()||Set.of("state","chat","console").contains(topic);}
    /** Authentication, user management, backups, safe cleanup and kernel settings remain accessible. */
    public void requireRoute(String path){
        if(fullAccess())return;
        if(Set.of("/api/state","/api/auth/status","/api/auth/login","/api/auth/logout","/api/auth/account","/api/setup/status","/api/setup/claim","/api/setup/profile-plan","/api/setup/complete","/api/server/health","/api/server/runtime","/api/server/restart","/api/server/profiles","/api/server/profiles/plan","/api/server/profiles/apply","/api/server/core","/api/server/core/stage","/api/settings","/api/license","/api/backups","/api/users","/api/console","/api/chat","/api/action").contains(path)||path.startsWith("/api/users/")||path.startsWith("/api/actions/"))return;
        throw new SecurityException("Эта функция доступна в полной версии MCC. Откройте настройки лицензии.");
    }
    public void requireAction(String action){if(!fullAccess()&&!Set.of("console.command","chat.broadcast","server.settings","event.stop").contains(action))throw new SecurityException("Это действие доступно в полной версии MCC");}
    public Map<String,Object> status(){
        boolean full=fullAccess();Map<String,Object> out=new LinkedHashMap<>();out.put("mode",selected.get("mode"));out.put("fullAccess",full);out.put("temporary",full);out.put("keyActivationAvailable",false);
        out.put("allowedFeatures",full?FULL_FEATURES:TRIAL_FEATURES);out.put("lockedFeatures",full?List.of():FULL_FEATURES.stream().filter(value->!TRIAL_FEATURES.contains(value)).toList());out.put("allowedModules",ModulePolicy.KEYS.stream().filter(this::moduleAllowed).toList());out.put("selectedAt",selected.getOrDefault("selectedAt",0));out.put("source",selected.getOrDefault("source",""));return Collections.unmodifiableMap(out);
    }
    public synchronized void change(String mode,String key)throws Exception{validate(mode,key);Map<String,Object> next=stored(mode,"owner-choice",System.currentTimeMillis());db.document("license","state",next).get();selected=next;}
    /** Called only after the initial Owner and this license were committed in the same transaction. */
    public synchronized void reload()throws Exception{selected=validStored(db.document("license","state").get().orElseThrow(()->new IllegalStateException("License state missing")));}
    synchronized void committed(Map<String,Object> value){selected=validStored(value);}
}
