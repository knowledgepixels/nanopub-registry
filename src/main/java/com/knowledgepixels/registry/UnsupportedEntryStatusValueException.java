package com.knowledgepixels.registry;

/**
 * Exception thrown when an unsupported entry status value is encountered.
 */
public class UnsupportedEntryStatusValueException extends RuntimeException {

    /**
     * Constructs a new UnsupportedEntryStatusValueException with the specified detail message.
     *
     * @param message the detail message
     */
    public UnsupportedEntryStatusValueException(String message) {
        super(message);
    }

}
