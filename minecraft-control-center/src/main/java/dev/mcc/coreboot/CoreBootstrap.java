package dev.mcc.coreboot;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/** MCC-owned startup layer for the pinned, genuinely patched Paper-compatible engine. */
public final class CoreBootstrap {
    public static final String NAME="MCC Core",VERSION="0.2.0",PROTOCOL="1";
    public static final String PAPER_SHA="185d9f4267afca48959d6a56150cf8803b6645aee10de3ed147df7ab1ff781fb";
    public static final String ENGINE_SHA="86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223";
    public static final long ENGINE_BYTES=55_980_330L;
    public static final String SOURCE_COMMIT="46d4834e8e2ec4af9dcfa028076fb16dfb8a3911";
    public static final String PATCHSET_SHA="3d42ace4d8f3a14bb7765cb173da0bcd06aa7a6533bd33e4e8892a50d0ca3c6f";
    public static final String ENGINE_RESOURCE="mcc-core/engine-0.2.0.jar";
    public static final String ENGINE_FEATURES="bounded-ore-scratch,nbt-fast-entries,contiguous-long-encoding,chunk-snapshot-loops,palette-layout";
    private CoreBootstrap(){}
    public static void main(String[] args)throws Exception {
        Path root=Path.of("").toRealPath();
        Properties config=configuration();
        Path plugin=requiredPlugin(root,config.getProperty("plugin.sha256"));
        // Binding is local to this JVM and original MCC artifact; no public-origin network request or key server.
        CoreBridge.initialize(root,plugin,config.getProperty("plugin.sha256"));
        System.out.println("[MCC Core] "+VERSION+" / Paper 26.3 source "+SOURCE_COMMIT.substring(0,12)+"; bounded allocation/encoding patches, vanilla tick and Paper plugin API.");
        Set<Long> before=new HashSet<>();Thread.getAllStackTraces().keySet().forEach(t->before.add(t.threadId()));
        Class.forName("io.papermc.paperclip.Paperclip").getMethod("main",String[].class).invoke(null,(Object)args);
        for(Thread thread:Thread.getAllStackTraces().keySet())
            if((thread.getName().equals("ServerMain")||thread.getName().equals("Server thread"))&&!before.contains(thread.threadId()))CoreBridge.runtimeLoader(thread.getContextClassLoader());
        CoreBridge.watch();
    }
    static Properties configuration()throws IOException {
        Properties p;try(InputStream stream=CoreBootstrap.class.getResourceAsStream("/META-INF/mcc-core.properties")){
            if(stream==null)throw new IOException("MCC Core distribution metadata is absent");p=properties(stream);
        }
        if(!PROTOCOL.equals(p.getProperty("protocol"))||!VERSION.equals(p.getProperty("version"))||!PAPER_SHA.equals(p.getProperty("upstream.sha256"))||
            !ENGINE_SHA.equals(p.getProperty("foundation.sha256"))||!PATCHSET_SHA.equals(p.getProperty("engine.patchset"))||
            !p.getProperty("plugin.sha256","").matches("[a-f0-9]{64}"))throw new IOException("MCC Core distribution metadata is invalid");
        try(InputStream stream=CoreBootstrap.class.getResourceAsStream("/META-INF/mcc-engine.properties")){
            if(stream==null)throw new IOException("MCC Core engine provenance is absent");validateEngine(properties(stream));
        }
        return p;
    }
    public static Properties properties(InputStream stream)throws IOException {
        byte[] bytes=stream.readNBytes(4097);if(bytes.length>4096)throw new IOException("MCC Core metadata exceeds bound");
        Properties p=new Properties();p.load(new ByteArrayInputStream(bytes));return p;
    }
    public static void validateEngine(Properties p)throws IOException {
        if(!NAME.equals(p.getProperty("engine.name"))||!VERSION.equals(p.getProperty("engine.version"))||
            !"Paper".equals(p.getProperty("upstream.name"))||!"26.3".equals(p.getProperty("upstream.minecraft"))||
            !SOURCE_COMMIT.equals(p.getProperty("upstream.commit"))||!PATCHSET_SHA.equals(p.getProperty("patchset.sha256"))||
            !"paper-main-thread".equals(p.getProperty("tick.owner"))||!ENGINE_FEATURES.equals(p.getProperty("features")))
            throw new IOException("MCC Core engine source, patchset or execution contract mismatch");
    }
    public static void engineMetadata(JarFile jar)throws IOException {
        JarEntry entry=jar.getJarEntry("META-INF/mcc-engine.properties");if(entry==null||entry.getSize()>4096)throw new IOException("MCC Core engine provenance is absent or oversized");
        try(InputStream in=jar.getInputStream(entry)){validateEngine(properties(in));}
    }
    public static Path requiredPlugin(Path root,String expected)throws IOException {
        Path plugin=root.resolve("plugins/MinecraftControlCenter.jar");ordinary(root,plugin);
        if(!sha(plugin).equals(expected))throw new IOException("MCC Core requires its matching Minecraft Control Center plugin; stage the core again after updating MCC");
        try(JarFile jar=new JarFile(plugin.toFile())){
            if(jar.getJarEntry("dev/mcc/MccPlugin.class")==null||jar.getJarEntry("dev/mcc/server/MccCoreLink.class")==null)
                throw new IOException("Required MCC plugin entrypoint is absent");
            JarEntry contract=jar.getJarEntry("META-INF/mcc-core-plugin.properties");if(contract==null||contract.getSize()>1024)throw new IOException("MCC Core plugin contract is absent");
            Properties p;try(InputStream input=jar.getInputStream(contract)){p=properties(input);}
            if(!PROTOCOL.equals(p.getProperty("protocol"))||!"dev.mcc.MccPlugin".equals(p.getProperty("main")))throw new IOException("Unsupported MCC Core plugin contract");
        }
        return plugin;
    }
    public static String sha(Path file)throws IOException {
        try {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=new byte[262144];for(int read;(read=in.read(bytes))>=0;)if(read>0)md.update(bytes,0,read);
        }return HexFormat.of().formatHex(md.digest());}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static void ordinary(Path root,Path path)throws IOException {
        Path base=root.toAbsolutePath().normalize(),value=path.toAbsolutePath().normalize();if(!value.startsWith(base))throw new IOException("Path escaped MCC server root");
        for(Path part=base;part!=null&&value.startsWith(part);){if(Files.isSymbolicLink(part))throw new IOException("Symbolic links are unsupported for MCC Core");if(part.equals(value))break;part=part.resolve(part.relativize(value).getName(0));}
        if(!Files.isRegularFile(value,LinkOption.NOFOLLOW_LINKS)||!value.toRealPath().equals(value))throw new IOException("Required regular MCC Core artifact is absent");
    }
}
