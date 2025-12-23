package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbortingTaskExceptionTest {

    @Test
    void throwsExceptionWithProvidedMessage() {
        String message = "Task aborted due to an error.";
        AbortingTaskException exception = new AbortingTaskException(message);
        assertEquals(message, exception.getMessage());
    }

    @Test
    void throwsExceptionWithNullMessage() {
        AbortingTaskException exception = new AbortingTaskException(null);
        assertNull(exception.getMessage());
    }

}