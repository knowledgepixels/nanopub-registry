package com.knowledgepixels.registry.store;

/**
 * A backend the registry can serve its read API from. See {@link RegistryStores} for the
 * one in use.
 */
public interface RegistryStore extends AutoCloseable {

    /**
     * A reader for one request that may stream a large result (no transaction).
     */
    RegistryReader reader();

    /**
     * A reader for one request that should see a consistent snapshot (a transaction on
     * MongoDB; the same as {@link #reader()} on an immutable backend).
     */
    RegistryReader snapshotReader();

    @Override
    void close();

}
