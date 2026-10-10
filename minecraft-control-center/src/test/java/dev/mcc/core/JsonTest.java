package dev.mcc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void requestsMustBeObjectsAndCannotUseDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> Json.object("null"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("[]"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"action\":\"safe\",\"action\":\"different\"}"));
        assertEquals("safe", Json.object("{\"action\":\"safe\"}").get("action"));
    }

    @Test void untrustedPayloadsCannotExceedJsonParserBudgets() {
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"nested\":" + "[".repeat(30) + "1" + "]".repeat(30) + "}"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"text\":\"" + "x".repeat(40_000) + "\"}"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"number\":" + "1".repeat(101) + "}"));
    }

    @Test void stringValuesCannotSilentlyCoerceNumbersOrObjects() {
        assertThrows(IllegalArgumentException.class, () -> Json.text(Json.object("{\"name\":1}"), "name", "fallback"));
        assertThrows(IllegalArgumentException.class, () -> Json.text(Json.object("{\"name\":{}}"), "name", "fallback"));
        assertEquals("fallback", Json.text(Json.object("{}"), "name", "fallback"));
    }
}
