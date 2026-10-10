package dev.mcc.game;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameValidationTest {
    @Test void fractionalQuantitiesCannotBeTruncatedIntoAdministrativeActions() {
        assertThrows(IllegalArgumentException.class, () -> GameService.integer(Map.of("count", 1.9), "count", 1, 128));
        assertThrows(IllegalArgumentException.class, () -> GameService.integer(Map.of("count", 4294967356L), "count", 1, 128));
        assertEquals(128, GameService.integer(Map.of("count", 128L), "count", 1, 128));
    }

    @Test void nonFiniteCoordinatesAndOutOfRangeValuesAreRejected() {
        for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 30000000d))
            assertThrows(IllegalArgumentException.class, () -> GameService.decimal(Map.of("x", value), "x", -29999984, 29999984));
        assertThrows(IllegalArgumentException.class, () -> GameService.decimal(Map.of("x", "4.0"), "x", -29999984, 29999984));
    }

    @Test void uuidAliasesCannotAccidentallySelectAnotherPlayer() {
        assertThrows(IllegalArgumentException.class, () -> GameService.parseUuid("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> GameService.parseUuid("00000000-0000-0000-0000-000000000001extra"));
        UUID id = UUID.fromString("4a5d65b8-14ee-41ac-8050-314ad2f93aa3");
        assertEquals(id, GameService.parseUuid(id.toString().toUpperCase(Locale.ROOT)));
    }

    @Test void booleanFieldsNeverTreatTextAsPermissionOrConfirmation() {
        assertThrows(IllegalArgumentException.class, () -> GameService.requiredBoolean(Map.of("enabled", "false"), "enabled"));
        assertThrows(IllegalArgumentException.class, () -> GameService.requiredBoolean(Map.of("enabled", 1), "enabled"));
        assertFalse(GameService.requiredBoolean(Map.of("enabled", false), "enabled"));
    }
}
