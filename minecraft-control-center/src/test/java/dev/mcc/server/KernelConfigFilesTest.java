package dev.mcc.server;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KernelConfigFilesTest {
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static KernelConfigFiles.Edit edit(Object value,String...path){return new KernelConfigFiles.Edit(List.of(path),value);}
    @Test void yamlRejectsDuplicatesUnsafeTagsAliasesAndUnknownSchema(){
        for(String yaml:List.of("_version: 31\nmisc: 1\nmisc: 2\n","_version: 31\nthing: !!java.net.URL [x]\n","_version: 31\na: &a [1,2]\nb: *a\n","_version: 32\n"))assertThrows(java.io.IOException.class,()->KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PAPER,bytes(yaml)));
    }
    @Test void boundedEncodingAndTypedPathsFailClosed(){
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.PAPER,new byte[]{(byte)0xc3,(byte)0x28},List.of()));
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PAPER,new byte[KernelConfigFiles.MAX_BYTES+1]));
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.PAPER,bytes("_version: 31\n"),List.of(edit(true,"unknown","optimize-explosions"))));
        assertThrows(IllegalArgumentException.class,()->edit(100,"view-distance"));assertThrows(IllegalArgumentException.class,()->edit(null,"redstone-implementation"));
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.PAPER,bytes("_version: 31\nenvironment:\n  optimize-explosions: 'false'\n"),List.of(edit(true,"environment","optimize-explosions"))));
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.SPIGOT,bytes("config-version: 13\nworld-settings:\n  default:\n    merge-radius:\n      item: .nan\n"),List.of(edit(0.5,"world-settings","default","merge-radius","item"))));
    }
    @Test void propertiesRetainUnrelatedBytesAndRejectDuplicateManagedSyntax()throws Exception {
        String old="# header\r\nmotd=unicode Тест\r\nview-distance=10\r\nsimulation-distance=10\r\n";String next=new String(KernelConfigFiles.merge(KernelConfigFiles.Kind.PROPERTIES,bytes(old),List.of(edit(8,"view-distance"))).bytes(),StandardCharsets.UTF_8);assertEquals(old.replace("view-distance=10","view-distance=8"),next);
        assertArrayEquals(bytes(old),KernelConfigFiles.merge(KernelConfigFiles.Kind.PROPERTIES,bytes(old),List.of(edit(10,"view-distance"))).bytes());
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.PROPERTIES,bytes(old+"view-distance=12\r\n"),List.of(edit(8,"view-distance"))));
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.PROPERTIES,bytes(old+"view\\-distance=12\r\n"),List.of(edit(8,"view-distance"))));
        assertThrows(java.io.IOException.class,()->KernelConfigFiles.merge(KernelConfigFiles.Kind.PROPERTIES,bytes("view-distance=10\\\n\nsimulation-distance=10\n"),List.of(edit(8,"view-distance"))));
    }
    @Test void propertiesMixedLineEndingsRemainExactOutsideChangedScalar()throws Exception {
        String old="# unix\nview-distance=10\r\nmotd=keep\nsimulation-distance=7";assertEquals(old.replace("view-distance=10","view-distance=8"),new String(KernelConfigFiles.merge(KernelConfigFiles.Kind.PROPERTIES,bytes(old),List.of(edit(8,"view-distance"))).bytes(),StandardCharsets.UTF_8));
    }
    @Test void supportedPurpurSchemaOnlyChangesAllowlistedKeys()throws Exception {
        byte[] output=KernelConfigFiles.merge(KernelConfigFiles.Kind.PURPUR,bytes("config-version: 49\nsettings:\n  tps-catchup: true\n  unrelated: retained\nworld-settings:\n  default:\n    pig:\n      ridable: true\n"),List.of(edit(false,"settings","tps-catchup"))).bytes();Map<String,Object> parsed=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PURPUR,output);assertEquals(false,((Map<?,?>)parsed.get("settings")).get("tps-catchup"));assertNotNull(parsed.get("world-settings"));
    }
}
