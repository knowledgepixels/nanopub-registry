package com.knowledgepixels.registry;

/**
 * Represents the status of a Task in the DB.
 */
public enum ServerStatus {
    // Note: If we want to fulfill Java Naming Conventions the way to go was:
    // @BsonProperty(value="launching")
    launching,
    coreLoading,
    updating,
    ready,
    coreReady,
}
