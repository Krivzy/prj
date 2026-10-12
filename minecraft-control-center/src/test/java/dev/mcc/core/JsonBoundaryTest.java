package dev.mcc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonBoundaryTest {
    @Test void aSingleObjectAllowsOnlyTrailingWhitespace() {
        assertEquals("stop", Json.object("{\"command\":\"stop\"}\n \t").get("command"));
    }
    @Test void appendedJsonAndGarbageCannotBeIgnored() {
        for (String suffix : new String[]{" {}", " []", " true", " null", " ignored"})
            assertThrows(IllegalArgumentException.class, () -> Json.object("{\"command\":\"stop\"}" + suffix));
    }
    @Test void duplicateKeysAndNonobjectsRemainRejected() {
        for (String raw : new String[]{"{\"command\":\"stop\",\"command\":\"restart\"}", "null", "[]", "\"value\""})
            assertThrows(IllegalArgumentException.class, () -> Json.object(raw));
    }
}
