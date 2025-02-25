package com.knowledgepixels.registry;

/**
 * Represents the status of a Task in the DB.
 */
public enum ServerStatus {
    launching,
    coreLoading,
    updating,
    ready,
    coreReady,
}
