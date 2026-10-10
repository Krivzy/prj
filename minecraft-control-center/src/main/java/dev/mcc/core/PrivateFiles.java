package dev.mcc.core;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.io.IOException;
import java.util.*;

public final class PrivateFiles {
    private PrivateFiles() {}
    public static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view != null) {
                UserPrincipal owner = Files.getOwner(path);
                List<AclEntry> entries = new ArrayList<>();
                Set<AclEntryFlag> flags = directory ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) : Set.of();
                entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build());
                UserPrincipal system = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
                entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build());
                view.setAcl(entries);
            }
        }
    }
}
