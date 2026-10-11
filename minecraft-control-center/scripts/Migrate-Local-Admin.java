import dev.mcc.core.Auth;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;

/** Explicit offline migration of a single existing local owner; never invoked by normal plugin startup. */
class MigrateLocalAdmin {
    static String sha(Path p) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    public static void main(String[] args) throws Exception {
        if(args.length!=4||!args[0].equals("--confirmed-offline"))throw new IllegalArgumentException("Usage: java -cp MinecraftControlCenter.jar Migrate-Local-Admin.java --confirmed-offline database closed-backup expected-sha256");
        Path database=Path.of(args[1]).toAbsolutePath().normalize(),backup=Path.of(args[2]).toAbsolutePath().normalize();String expected=args[3];
        if(!expected.matches("[a-f0-9]{64}")||database.equals(backup)||Files.isSymbolicLink(database)||Files.isSymbolicLink(backup)||!Files.isRegularFile(database)||!Files.isRegularFile(backup)||Files.isSameFile(database,backup))throw new IllegalArgumentException("An existing distinct regular closed backup is required");
        if(!sha(database).equals(expected)||!sha(backup).equals(expected))throw new IllegalStateException("Database or closed backup changed");
        if(Files.exists(Path.of(database+"-wal"))||Files.exists(Path.of(database+"-journal")))throw new IllegalStateException("SQLite must be checkpointed and closed before migration");
        try(FileChannel channel=FileChannel.open(database,StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=channel.tryLock()){if(lock==null)throw new IllegalStateException("Database is in use");}
        Class.forName("org.sqlite.JDBC");String hash=Auth.hash("admin");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database)){
            c.setAutoCommit(false);
            try{
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT name,role FROM users")){if(!r.next()||!r.getString(1).equals("owner")||!r.getString(2).equals("Owner")||r.next())throw new IllegalStateException("Migration is limited to exactly one owner account; existing users must be reviewed separately");}
                try(PreparedStatement s=c.prepareStatement("UPDATE users SET name='admin',hash=? WHERE name='owner' AND role='Owner'")){s.setString(1,hash);if(s.executeUpdate()!=1)throw new IllegalStateException("Owner changed concurrently");}
                try(Statement s=c.createStatement()){
                    s.executeUpdate("INSERT INTO documents(namespace,id,json) VALUES('auth-default-account','admin','{\"name\":\"admin\"}')");
                    s.executeUpdate("INSERT OR REPLACE INTO documents(namespace,id,json) VALUES('migrations','auth-bootstrap-admin-v1','{\"created\":false,\"explicitLocalMigration\":true}')");
                }
                try(PreparedStatement s=c.prepareStatement("INSERT INTO audit(time,actor,action,status,details) VALUES(?,'local-offline-migration','users.bootstrap.migrate','ok','{\"previousUsername\":\"owner\",\"username\":\"admin\"}')")){s.setLong(1,System.currentTimeMillis());s.executeUpdate();}
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT name,hash,role FROM users")){if(!r.next()||!r.getString(1).equals("admin")||!Auth.verify("admin",r.getString(2))||!r.getString(3).equals("Owner")||r.next())throw new IllegalStateException("Migration verification failed; restore the closed backup before starting Paper");}
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT json FROM documents WHERE namespace='auth-default-account' AND id='admin'")){if(!r.next()||!r.getString(1).equals("{\"name\":\"admin\"}"))throw new IllegalStateException("Default-password marker verification failed");}
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
        if(!sha(backup).equals(expected))throw new IllegalStateException("Closed backup changed; keep Paper stopped for recovery review");
        System.out.println("{\"verified\":true,\"users\":[\"admin\"],\"historyPreserved\":true,\"defaultPasswordLocalOnly\":true}");
    }
}
