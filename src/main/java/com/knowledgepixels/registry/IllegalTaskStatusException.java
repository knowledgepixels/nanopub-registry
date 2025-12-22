package com.knowledgepixels.registry;

/**
 * Exception thrown when an illegal task status is encountered.
 */
public class IllegalTaskStatusException extends RuntimeException {

    /**
     * Constructs a new IllegalTaskStatusException with the specified detail message.
     *
     * @param message the detail message
     */
    public IllegalTaskStatusException(String message) {
        super(message);
    }

}
