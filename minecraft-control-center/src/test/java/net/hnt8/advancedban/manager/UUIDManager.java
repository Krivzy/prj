package net.hnt8.advancedban.manager;

/** Isolated offline-mode provider fixture. */
public final class UUIDManager {
    public enum Mode {DISABLED}
    private static final UUIDManager INSTANCE=new UUIDManager();
    public static UUIDManager get(){return INSTANCE;}
    public Mode getMode(){return Mode.DISABLED;}
    public String getInMemoryUUID(String name){return null;}
}
