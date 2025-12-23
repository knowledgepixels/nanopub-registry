package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UnsupportedEntryStatusValueExceptionTest {

    @Test
    void throwsExceptionWithProvidedMessage() {
        String message = "Unsupported status value";
        UnsupportedEntryStatusValueException exception = new UnsupportedEntryStatusValueException(message);
        assertEquals(message, exception.getMessage());
    }

    @Test
    void throwsExceptionWithNullMessage() {
        UnsupportedEntryStatusValueException exception = new UnsupportedEntryStatusValueException(null);
        assertNull(exception.getMessage());
    }

}