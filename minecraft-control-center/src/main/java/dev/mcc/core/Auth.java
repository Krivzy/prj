package dev.mcc.core;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Auth {
    public record Session(String id,String name,String role,String csrf,long expires,boolean defaultPassword) {}
    private static final SecureRandom RANDOM=new SecureRandom();
    private final Database db;
    private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final AtomicBoolean setupRequired=new AtomicBoolean();
    private final Path tokenFile;
    private volatile String setupToken;
    private final Semaphore hashing = new Semaphore(2);
    private final Set<String> defaultAccounts=ConcurrentHashMap.newKeySet();
    public Auth(Database db,Path directory) throws Exception {
        this(db,directory,false,true);
    }
    public Auth(Database db,Path directory,boolean bootstrapAdmin,boolean loopbackOnly) throws Exception {
        this.db=db;tokenFile=directory.resolve(".setup-token");
        // A public default may bootstrap only an empty loopback installation. Existing accounts are preserved.
        if(bootstrapAdmin&&loopbackOnly&&db.userCount().get()==0)db.bootstrapAdmin(limitedHash("admin")).get();
        for(Map<String,Object> account:db.documents("auth-default-account").get())defaultAccounts.add((String)account.get("name"));
        setupRequired.set(db.userCount().get()==0);
        if(setupRequired.get()){
            setupToken=token();Files.writeString(tokenFile,setupToken,StandardCharsets.UTF_8);
            PrivateFiles.restrict(tokenFile,false);
        } else if(Files.exists(tokenFile)) {Files.delete(tokenFile);}
    }
    public static String token(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static void validateCredentials(String name,String password){
        validateUsername(name);
        if(password==null||password.length()<12||password.length()>128)throw new IllegalArgumentException("Password must contain 12–128 characters");
    }
    public static void validateUsername(String name){if(name==null||!name.matches("[A-Za-z0-9_.-]{3,32}"))throw new IllegalArgumentException("Username must contain 3–32 letters, digits, _, . or -");}
    public static String hash(String password){
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);byte[] out=derive(password,salt);
        return "argon2id$v=19$m=65536,t=3,p=1$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(out);
    }
    private static byte[] derive(String password,byte[] salt){
        Argon2BytesGenerator g=new Argon2BytesGenerator();
        g.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19).withMemoryAsKB(65536).withIterations(3).withParallelism(1).withSalt(salt).build());
        byte[] out=new byte[32];char[] chars=password.toCharArray();try{g.generateBytes(chars,out);return out;}finally{Arrays.fill(chars,'\0');}
    }
    public static boolean verify(String password,String encoded){try{
        String[] parts=encoded.split("\\$");if(parts.length!=5||!parts[0].equals("argon2id")||!parts[1].equals("v=19")||!parts[2].equals("m=65536,t=3,p=1"))return false;
        byte[] salt=Base64.getDecoder().decode(parts[3]),expected=Base64.getDecoder().decode(parts[4]);if(salt.length!=16||expected.length!=32)return false;
        return MessageDigest.isEqual(derive(password,salt),expected);
    }catch(Exception e){return false;}}
    private String limitedHash(String password){if(!hashing.tryAcquire())throw new IllegalStateException("Authentication busy, retry shortly");try{return hash(password);}finally{hashing.release();}}
    public synchronized Session setup(String token,String name,String password,boolean local) throws Exception {
        if(!local||!setupRequired.get()||!constant(token,setupToken))throw new SecurityException("Local setup token is required");
        validateCredentials(name,password);db.createUser(name,limitedHash(password),"Owner",true).get();
        setupRequired.set(false);setupToken=null;Files.deleteIfExists(tokenFile);return createSession(name,"Owner");
    }
    public synchronized Session login(String name,String password) throws Exception {
        return login(name,password,true);
    }
    public synchronized Session login(String name,String password,boolean local) throws Exception {
        if(name==null||password==null||name.length()>32||password.length()>128)throw new SecurityException("Invalid credentials");
        if(!hashing.tryAcquire())throw new IllegalStateException("Authentication busy, retry shortly");
        try{Map<String,Object> user=db.user(name).get();String stored=user==null?"":(String)user.get("hash");
            // Unknown users still pay a KDF cost to reduce username enumeration.
            boolean match=user==null?constant(hash(password),stored):verify(password,stored);
            if(!match)throw new SecurityException("Invalid credentials");
            if(defaultAccounts.contains(name)&&!local)throw new SecurityException("Change the initial password locally before remote access");
            return createSession(name,(String)user.get("role"));
        }finally{hashing.release();}
    }
    public synchronized void createUser(String name,String password,String role) throws Exception {
        validateCredentials(name,password);if(!Set.of("Owner","Admin","Moderator","Viewer").contains(role))throw new IllegalArgumentException("Invalid role");
        if(db.userCount().get()>=100)throw new IllegalStateException("User limit reached");db.createUser(name,limitedHash(password),role,false).get();
    }
    public synchronized void createUser(Session actor,String name,String password,String role)throws Exception {
        if(actor==null||session(actor.id())!=actor||!actor.role().equals("Owner"))throw new SecurityException("Full administrator access is required");
        createUser(name,password,role);
    }
    /** Delete on the serialized DB writer, then revoke every login for that account. Audit/history remain intact. */
    public synchronized void deleteUser(Session actor,String name) throws Exception {
        if(actor==null||session(actor.id())!=actor||!actor.role().equals("Owner"))throw new SecurityException("Full administrator access is required");
        validateUsername(name);if(actor.name().equals(name))throw new IllegalArgumentException("You cannot delete your own account");
        if(!db.deleteUser(name).get())throw new IllegalArgumentException("User does not exist");
        sessions.values().removeIf(existing->existing.name().equals(name));defaultAccounts.remove(name);
    }
    /** Re-authenticate, update atomically, revoke every prior login, and return a rotated session. */
    public synchronized Session changeAccount(Session session,String currentPassword,String newUsername,String newPassword)throws Exception {
        if(session==null||session(session.id())!=session)throw new SecurityException("An active session is required");
        if(currentPassword==null||currentPassword.length()>128)throw new SecurityException("Current password is incorrect");
        String name=newUsername==null||newUsername.isBlank()?session.name():newUsername;validateUsername(name);
        boolean changePassword=newPassword!=null&&!newPassword.isEmpty();if(changePassword)validateCredentials(name,newPassword);
        if(!hashing.tryAcquire())throw new IllegalStateException("Authentication busy, retry shortly");
        try {
            Map<String,Object> stored=db.user(session.name()).get();if(stored==null||!verify(currentPassword,(String)stored.get("hash")))throw new SecurityException("Current password is incorrect");
            if(!name.equals(session.name())&&db.user(name).get()!=null)throw new IllegalArgumentException("Username already exists");
            String oldHash=(String)stored.get("hash"),newHash=changePassword?hash(newPassword):oldHash;
            if(!db.changeUser(session.name(),oldHash,name,newHash,changePassword).get())throw new SecurityException("Account changed concurrently; log in again");
            boolean hadDefault=defaultAccounts.remove(session.name());if(hadDefault&&!changePassword)defaultAccounts.add(name);
            sessions.values().removeIf(existing->existing.name().equals(session.name()));
            return createSession(name,(String)stored.get("role"));
        }finally{hashing.release();}
    }
    private Session createSession(String name,String role){cleanup();if(sessions.size()>=512)throw new IllegalStateException("Session limit reached");Session s=new Session(token(),name,role,token(),System.currentTimeMillis()+TimeUnit.HOURS.toMillis(8),defaultAccounts.contains(name));sessions.put(s.id(),s);return s;}
    public Session session(String token){if(token==null)return null;Session s=sessions.get(token);if(s!=null&&s.expires()>System.currentTimeMillis())return s;sessions.remove(token);return null;}
    public void logout(Session s){if(s!=null)sessions.remove(s.id());}
    public void cleanup(){long now=System.currentTimeMillis();sessions.values().removeIf(s->s.expires()<now);}
    public boolean setupRequired(){return setupRequired.get();}
    public static boolean constant(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    public Map<String,Object> status(Session s){Map<String,Object> out=new LinkedHashMap<>();out.put("setupRequired",setupRequired());out.put("authenticated",s!=null);out.put("defaultAdminAvailable",s==null&&defaultAccounts.contains("admin"));out.put("defaultPassword",s!=null&&s.defaultPassword());out.put("user",s==null?null:Map.of("name",s.name(),"role",s.role()));out.put("csrf",s==null?null:s.csrf());return out;}
}
