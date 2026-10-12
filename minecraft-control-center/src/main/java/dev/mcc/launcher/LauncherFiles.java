package dev.mcc.launcher;

import dev.mcc.core.Json;
import dev.mcc.core.PrivateFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.OpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/** Local private control files. No path from a command record is ever opened. */
final class LauncherFiles {
    private LauncherFiles() {}
    static void ordinary(Path root, Path file, boolean directory) throws IOException {
        Path base = root.toAbsolutePath().normalize(), path = file.toAbsolutePath().normalize();
        if (!path.startsWith(base) || !base.toRealPath().equals(base)) throw new IOException("Unsafe server scope");
        Path cursor = base;
        for (Path part : base.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Linked launcher path refused");
        }
        if (!path.toRealPath().equals(path) || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) :
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("Unexpected launcher file type");
    }
    static Path control(Path root, boolean create) throws IOException {
        ordinary(root, root, true); Path control = root.resolve(".mcc-launcher");
        if (create && !Files.exists(control, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(control);
        ordinary(root, control, true);
        if (create) PrivateFiles.restrict(control, true);
        return control;
    }
    static void createPrivateFile(Path root, Path file) throws IOException {
        ordinary(root, file.getParent(), true);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.createFile(file);
        ordinary(root, file, false); PrivateFiles.restrict(file, false);
    }
    static Map<String, Object> status(Path root, Path control) throws IOException {
        Path file = control.resolve("status.json"); ordinary(root, file, false);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw = input.readNBytes(16385);
            if (raw.length > 16384) throw new IOException("Launcher status exceeds budget");
            try { return Json.object(new String(raw, StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid launcher status"); }
        }
    }
    static void atomicStatus(Path root, Path control, Map<String, Object> status) throws IOException {
        ordinary(root, control, true); Path target = control.resolve("status.json");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) ordinary(root, target, false);
        Path temporary = control.resolve("status-" + UUID.randomUUID() + ".tmp");
        byte[] bytes = Json.write(status).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) throw new IOException("Launcher status exceeds budget");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            PrivateFiles.restrict(temporary, false);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            for (int attempt = 0; ; attempt++) {
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return; }
                catch (java.nio.file.AccessDeniedException busy) {
                    if (attempt >= 19) throw busy;
                    try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Status delivery interrupted"); }
                }
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    static long pid(Object value) throws IOException {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0) throw new IOException("Invalid launcher PID");
        return ((Number) value).longValue();
    }
    static void previousProcessesOffline(Path root, Path control) throws IOException {
        if (!Files.exists(control.resolve("status.json"), LinkOption.NOFOLLOW_LINKS)) return;
        Map<String, Object> previous = status(root, control);
        for (String field : new String[] {"javaPid", "daemonPid"}) {
            Object value = previous.get(field);
            if (value != null && ProcessHandle.of(pid(value)).map(ProcessHandle::isAlive).orElse(false))
                throw new IOException("Previous server or launcher is still alive; stop it before migration");
        }
    }
    static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[262144];
            for (int count; (count = input.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Object fileKey(Path root, Path file) throws IOException {
        ordinary(root, file, false);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object key = attributes.fileKey();
        // Windows' JDK provider exposes no native key. Creation time detects ordinary replacement;
        // it is not an inode proof against the already-trusted OS owner preserving timestamps.
        return key == null ? new CreationIdentity(file.toRealPath(), attributes.creationTime()) : key;
    }
    private record CreationIdentity(Path canonicalPath, FileTime creationTime) {}
    static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"); }
    static FileChannel retainedChannel(Path file, StandardOpenOption access) throws IOException {
        HashSet<OpenOption> options = new HashSet<>(); options.add(access); options.add(LinkOption.NOFOLLOW_LINKS);
        if (windows()) {
            try {
                Object guard = Class.forName("com.sun.nio.file.ExtendedOpenOption").getField("NOSHARE_DELETE").get(null);
                if (!(guard instanceof OpenOption option)) throw new ReflectiveOperationException("Unsupported Windows file option");
                options.add(option);
            } catch (ReflectiveOperationException | LinkageError unsupported) { throw new IOException("Windows retained-file deletion guard is unavailable"); }
        }
        try { return FileChannel.open(file, options); }
        catch (UnsupportedOperationException unsupported) { throw new IOException("Filesystem does not support the retained-file deletion guard"); }
    }
    static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        Lease(Path root, Path file) throws IOException {
            createPrivateFile(root, file); FileChannel opened = retainedChannel(file, StandardOpenOption.WRITE);
            FileLock acquired = null;
            try {
                if (opened.size() == 0) { opened.write(ByteBuffer.wrap(new byte[] {'0'})); opened.force(true); }
                try { acquired = opened.tryLock(0, 1, false); }
                catch (OverlappingFileLockException busy) { throw new IOException("Another launcher owns the lease"); }
                if (acquired == null) throw new IOException("Another launcher owns the lease");
                channel = opened; lock = acquired;
            } catch (IOException | RuntimeException failure) {
                if (acquired != null) acquired.close(); opened.close(); throw failure;
            }
        }
        @Override public void close() throws IOException { try { lock.close(); } finally { channel.close(); } }
    }
}
