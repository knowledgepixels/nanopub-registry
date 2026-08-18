package com.knowledgepixels.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class EntryStatusTest {

    @Test
    void fromValueWithValidValue() {
        String validValue = "retrieved";
        EntryStatus status = EntryStatus.fromValue(validValue);
        assertEquals(EntryStatus.retrieved, status);
    }

    @Test
    void fromValueWithInvalidValue() {
        String invalidValue = "invalid-status";
        assertThrows(UnsupportedEntryStatusValueException.class, () -> EntryStatus.fromValue(invalidValue));
    }

    @Test
    void fromValueWithNullValue() {
        IllegalArgumentException exception
                = assertThrows(IllegalArgumentException.class, () -> EntryStatus.fromValue(null));
        assertEquals("EntryStatus value must not be null", exception.getMessage());
    }

    @Test
    void fromValueReportsTheOffendingValue() {
        UnsupportedEntryStatusValueException exception = assertThrows(
                UnsupportedEntryStatusValueException.class, () -> EntryStatus.fromValue("to-retrieve"));
        // The old hyphenated spelling is no longer accepted; the message has to name it
        // so a stale database entry can be traced back.
        assertEquals("Unsupported EntryStatus value: 'to-retrieve'.", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "RETRIEVED", "Retrieved", "retrieved ", "to-retrieve", "unknown"})
    void fromValueOnlyAcceptsExactSpellings(String value) {
        assertThrows(UnsupportedEntryStatusValueException.class, () -> EntryStatus.fromValue(value));
    }

    @Test
    void getValue() {
        String validValue = "retrieved";
        EntryStatus status = EntryStatus.fromValue(validValue);
        assertEquals(validValue, status.getValue());
    }

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void everyStatusRoundTripsThroughItsStoredValue(EntryStatus status) {
        // getValue() is what gets written to MongoDB, so it has to be exactly what
        // fromValue() reads back.
        assertEquals(status.name(), status.getValue());
        assertSame(status, EntryStatus.fromValue(status.getValue()));
    }

}
