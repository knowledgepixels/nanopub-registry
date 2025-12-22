package com.knowledgepixels.registry;

/**
 * Exception thrown when a task needs to be aborted.
 */
public class AbortingTaskException extends RuntimeException {

    /**
     * Constructs a new AbortingTaskException with the specified detail message.
     *
     * @param message the detail message
     */
    public AbortingTaskException(String message) {
        super(message);
    }

}
