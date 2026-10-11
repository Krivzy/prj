package dev.mcc.integration;

import java.util.*;
import java.util.concurrent.*;

/** Current GrimAPI snapshots run on the player's packet thread using public runSafely. */
final class GrimIntegration {
    private GrimIntegration() {}
    static void validate(Object api,ClassLoader loader)throws Exception {
        if(!Boolean.TRUE.equals(PublicApi.call(api,"hasStarted")))throw new IllegalStateException("Grim API is not started");
        Class<?> user=PublicApi.type(loader,"ac.grim.grimac.api.GrimUser");user.getMethod("runSafely",Runnable.class);user.getMethod("getChecks");user.getMethod("getName");
        Class<?> check=PublicApi.type(loader,"ac.grim.grimac.api.AbstractCheck");check.getMethod("getCheckName");check.getMethod("getViolations");
        if(!PublicApi.has(api,"getGrimUser",1))throw new UnsupportedOperationException();
    }
    static Map<String,Object> query(IntegrationService.Binding binding,Map<String,Object> body)throws Exception {
        UUID uuid=IntegrationValidation.uuid(IntegrationValidation.text(body,"uuid","",36));Object user=PublicApi.call(binding.api(),"getGrimUser",uuid);
        if(user==null)return Map.of("items",List.of(),"tracked",false,"uuid",uuid.toString());
        CompletableFuture<Map<String,Object>> snapshot=new CompletableFuture<>();PublicApi.call(user,"runSafely",(Runnable)()->{
            try{List<Map<String,Object>> items=new ArrayList<>();Collection<?> checks=PublicApi.collection(PublicApi.call(user,"getChecks"));for(Object check:checks){if(items.size()==128)break;Object raw=PublicApi.call(check,"getViolations");double vl=raw instanceof Number n?n.doubleValue():0;if(!Double.isFinite(vl))vl=0;items.add(Map.of("check",PublicApi.text(PublicApi.call(check,"getCheckName"),128),"violations",vl));}snapshot.complete(Map.of("items",items,"tracked",true,"uuid",uuid.toString(),"player",PublicApi.text(PublicApi.call(user,"getName"),128),"truncated",checks.size()>128));}catch(Throwable error){snapshot.completeExceptionally(error);}
        });return snapshot.get();
    }
}
