package dev.mcc.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Bounded, transient capabilities. Genuine server identities never come from the browser. */
public final class SetupService implements AutoCloseable {
    public static final long TTL_MILLIS=600_000;
    public static final int MAX_CAPABILITIES=32;
    @FunctionalInterface public interface Authority {boolean authorized(UUID uuid,String name)throws Exception;}
    public record PlayerIdentity(UUID uuid,String name){public PlayerIdentity{Objects.requireNonNull(uuid);Auth.validateUsername(name);}}
    public record Grant(String id,String csrf,PlayerIdentity player,long expiresAt){@Override public String toString(){return "SetupGrant[redacted]";}}
    public record Completion(Auth.Session session,String profile,String planId,String core){@Override public String toString(){return "SetupCompletion[redacted]";}}
    private record Invitation(PlayerIdentity player,long expiresAt){}
    private final Auth auth;private final Database db;private final LicenseService license;private final Clock clock;private final Authority authority;
    private final Map<String,Invitation> invitations=new LinkedHashMap<>();
    private final Map<String,Grant> grants=new LinkedHashMap<>();
    private record ProfilePlan(String id,String profile,long expiresAt){}
    private final Map<String,ProfilePlan> profilePlans=new HashMap<>();
    private final Map<UUID,Long> lastIssued=new LinkedHashMap<>();
    private boolean closed,completing;
    public SetupService(Auth auth,Database db,LicenseService license,Authority authority){this(auth,db,license,authority,Clock.systemUTC());}
    SetupService(Auth auth,Database db,LicenseService license,Authority authority,Clock clock){this.auth=auth;this.db=db;this.license=license;this.authority=authority;this.clock=clock;}
    /** Called only from the Bukkit thread with permission checked at issuance. Contains no JDBC/KDF work. */
    public synchronized String issue(UUID uuid,String name,boolean authorized){
        cleanup();if(closed||completing||!auth.setupRequired())throw new IllegalStateException("Первичная настройка уже завершена или сохраняется");if(!authorized)throw new SecurityException("Only a server operator or mcc.setup permission may initialize MCC");
        PlayerIdentity player=new PlayerIdentity(uuid,name);long now=clock.millis();Long previous=lastIssued.get(uuid);if(previous!=null&&now-previous<10_000)throw new IllegalStateException("Ссылка уже отправлена. Подождите несколько секунд.");
        if(invitations.size()+grants.size()>=MAX_CAPABILITIES||lastIssued.size()>=128&&!lastIssued.containsKey(uuid))throw new IllegalStateException("Setup link limit reached; retry after expiry");
        invitations.values().removeIf(value->value.player.uuid.equals(uuid));grants.values().removeIf(value->{boolean same=value.player.uuid.equals(uuid);if(same)profilePlans.remove(digest(value.id));return same;});
        String token=Auth.token();invitations.put(digest(token),new Invitation(player,now+TTL_MILLIS));lastIssued.put(uuid,now);return token;
    }
    public Grant claim(String token)throws Exception{
        Invitation invitation;synchronized(this){cleanup();required();invitation=invitations.remove(digestChecked(token));}
        if(invitation==null||invitation.expiresAt<=clock.millis())throw new SecurityException("Ссылка уже использована или устарела. Запросите /mcc setup в игре.");
        if(!authority.authorized(invitation.player.uuid,invitation.player.name))throw new SecurityException("Игрок должен быть онлайн и иметь права на первичную настройку");
        synchronized(this){required();if(invitation.expiresAt<=clock.millis())throw new SecurityException("Setup link expired");if(invitations.size()+grants.size()>=MAX_CAPABILITIES)throw new IllegalStateException("Setup link limit reached");Grant grant=new Grant(Auth.token(),Auth.token(),invitation.player,invitation.expiresAt);grants.put(digest(grant.id),grant);return grant;}
    }
    public synchronized Grant grant(String cookie){cleanup();if(cookie==null||closed||!auth.setupRequired())return null;return grants.get(digestChecked(cookie));}
    public Grant require(String cookie,String csrf)throws Exception{
        Grant grant;synchronized(this){required();grant=grant(cookie);if(grant==null||!Auth.constant(grant.csrf,csrf))throw new SecurityException("Первичная сессия устарела или CSRF token неверен");}
        // Never hold our capability lock while waiting for Bukkit: join callbacks may issue another link.
        if(!authority.authorized(grant.player.uuid,grant.player.name))throw new SecurityException("Игрок должен быть онлайн и иметь права на первичную настройку");
        synchronized(this){required();if(grant(cookie)!=grant)throw new SecurityException("Setup session expired");}return grant;
    }
    public Completion complete(String cookie,String csrf,Map<String,Object> values)throws Exception{
        Grant grant=require(cookie,csrf);
        if(!Set.of("password","profile","planId","confirmed","licenseMode","licenseKey","core").containsAll(values.keySet()))throw new IllegalArgumentException("Unknown setup field; player identity is supplied by the server");
        String password=Json.text(values,"password","");Auth.validateCredentials(grant.player.name,password);
        String profile=Json.text(values,"profile","");if(!Set.of("custom","vanilla","vanilla-performance","vanilla-throughput","optimized","maximum-optimized").contains(profile))throw new IllegalArgumentException("Unknown kernel profile");
        String mode=Json.text(values,"licenseMode","");LicenseService.validate(mode,Json.text(values,"licenseKey",""));
        String core=Json.text(values,"core","keep");if(!Set.of("keep","mcc-core").contains(core))throw new IllegalArgumentException("Unknown core choice");
        String planId=Json.text(values,"planId","");UUID.fromString(planId);if(!Boolean.TRUE.equals(values.get("confirmed")))throw new IllegalArgumentException("Confirm the selected kernel preset");
        synchronized(this){required();if(grant(cookie)!=grant)throw new SecurityException("Setup session expired");ProfilePlan prepared=profilePlans.get(digest(grant.id));if(prepared==null||!prepared.id.equals(planId)||!prepared.profile.equals(profile)||prepared.expiresAt<=clock.millis())throw new SecurityException("Create a current preset plan for this setup session before completing");completing=true;}
        Map<String,Object> preferences=Map.of("completed",true,"playerUuid",grant.player.uuid.toString(),"playerName",grant.player.name,"profile",profile,"core",core,"coreInstallation","offline-plan-required","completedAt",clock.millis());
        // KDF and SQLite remain on the HTTP worker, outside the lock used by Minecraft join callbacks.
        try{Map<String,Object> selected=LicenseService.stored(mode,"first-run-choice",clock.millis());Auth.Session session=auth.completeSetup(grant.player.uuid,grant.player.name,password,selected,preferences,()->stillAuthorized(grant));license.committed(selected);synchronized(this){invitations.clear();grants.clear();profilePlans.clear();lastIssued.clear();completing=false;}return new Completion(session,profile,planId,core);}
        catch(Exception error){synchronized(this){completing=false;}throw error;}
    }
    private boolean stillAuthorized(Grant grant)throws Exception{ synchronized(this){if(closed||!auth.setupRequired()||clock.millis()>=grant.expiresAt||grants.get(digest(grant.id))!=grant)return false;}if(!authority.authorized(grant.player.uuid,grant.player.name))return false;synchronized(this){return !closed&&auth.setupRequired()&&clock.millis()<grant.expiresAt&&grants.get(digest(grant.id))==grant;}}
    public synchronized void profilePlan(Grant grant,Map<String,Object> plan){if(grants.get(digest(grant.id))!=grant||!auth.setupRequired())throw new SecurityException("Setup session expired");String id=Json.text(plan,"planId","");UUID.fromString(id);String profile=Json.text(plan,"profile","");if(!(plan.get("expiresAt") instanceof Number expires))throw new IllegalArgumentException("Invalid profile plan expiry");profilePlans.put(digest(grant.id),new ProfilePlan(id,profile,expires.longValue()));}
    public Map<String,Object> status(Grant grant){Map<String,Object> out=new LinkedHashMap<>();out.put("required",auth.setupRequired());out.put("linkRequired",auth.setupRequired()&&grant==null);out.put("license",license.status());out.put("linkLifetimeSeconds",TTL_MILLIS/1000);out.put("identitySource","authorized-online-Minecraft-player");if(grant!=null){out.put("player",Map.of("name",grant.player.name,"uuid",grant.player.uuid.toString()));out.put("csrf",grant.csrf);out.put("expiresAt",grant.expiresAt);}return out;}
    public String actor(Grant grant){return digest(grant.id);}
    private void required(){if(closed||completing||!auth.setupRequired())throw new SecurityException("Первичная настройка уже завершена или сохраняется");}
    public synchronized void cleanup(){long now=clock.millis();invitations.values().removeIf(value->value.expiresAt<=now);grants.values().removeIf(value->{boolean expired=value.expiresAt<=now;if(expired)profilePlans.remove(digest(value.id));return expired;});lastIssued.values().removeIf(value->now-value>=TTL_MILLIS);}
    private static String digestChecked(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{43}"))throw new SecurityException("Invalid setup capability");return digest(value);}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public synchronized void close(){closed=true;invitations.clear();grants.clear();profilePlans.clear();lastIssued.clear();}
}
