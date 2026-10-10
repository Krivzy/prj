package dev.mcc.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditRedactionTest {
    @Test void masksSecretsInsideNestedMapsAndListsWithoutLosingOrdinaryValues() {
        Map<String,Object> input = Map.of(
            "world", "world",
            "attributes", List.of(Map.of("name", "ordinary", "api_key", "test-value", "nested", List.of(Map.of("private-key", "test-value", "count", 3)))),
            "password", "test-value", "passwd", "test-value", "sessionToken", "test-value", "secret", "test-value");
        Map<?,?> result = (Map<?,?>) WebServer.redact(input);
        assertEquals("world", result.get("world"));
        for (String key : List.of("password", "passwd", "sessionToken", "secret")) assertEquals("[redacted]", result.get(key));
        Map<?,?> attribute = (Map<?,?>) ((List<?>) result.get("attributes")).getFirst();
        assertEquals("ordinary", attribute.get("name"));
        assertEquals("[redacted]", attribute.get("api_key"));
        Map<?,?> nested = (Map<?,?>) ((List<?>) attribute.get("nested")).getFirst();
        assertEquals("[redacted]", nested.get("private-key"));
        assertEquals(3, nested.get("count"));
        assertEquals("test-value", input.get("password"));
    }
}
