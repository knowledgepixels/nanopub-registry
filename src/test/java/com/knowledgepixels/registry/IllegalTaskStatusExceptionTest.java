package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IllegalTaskStatusExceptionTest {

    @Test
    void throwsExceptionWithProvidedMessage() {
        String message = "Illegal task status encountered.";
        IllegalTaskStatusException exception = new IllegalTaskStatusException(message);
        assertEquals(message, exception.getMessage());
    }

    @Test
    void throwsExceptionWithNullMessage() {
        IllegalTaskStatusException exception = new IllegalTaskStatusException(null);
        assertNull(exception.getMessage());
    }

}