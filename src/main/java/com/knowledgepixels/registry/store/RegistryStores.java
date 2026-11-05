package com.knowledgepixels.registry.store;

/**
 * Holds the {@link RegistryStore} the running registry serves from. {@code MainVerticle}
 * sets it at startup; until then, and in tests that do not set one, it is the MongoDB store,
 * which resolves its client through {@code RegistryDB} on every use.
 */
public final class RegistryStores {

    private static volatile RegistryStore store = new MongoRegistryStore();

    private RegistryStores() {
    }

    public static RegistryStore get() {
        return store;
    }

    public static void set(RegistryStore s) {
        store = s;
    }

}
