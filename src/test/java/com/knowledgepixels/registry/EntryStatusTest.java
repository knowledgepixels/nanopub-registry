package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void getValue() {
        String validValue = "retrieved";
        EntryStatus status = EntryStatus.fromValue(validValue);
        assertEquals(validValue, status.getValue());
    }

}