package com.knowledgepixels.registry;

/**
 * The read-only mode switch: with {@code REGISTRY_READ_ONLY_SPARQL_URL} set, the registry
 * serves from a static registry file through that SPARQL endpoint instead of MongoDB. It
 * then accepts no nanopubs, runs no tasks and syncs with no peers.
 */
public final class ReadOnlyMode {

    private ReadOnlyMode() {
    }

    public static final String ENV = "REGISTRY_READ_ONLY_SPARQL_URL";

    public static boolean isEnabled() {
        String url = sparqlUrl();
        return url != null && !url.isBlank();
    }

    /**
     * The SPARQL endpoint URL, or null when not in read-only mode.
     */
    public static String sparqlUrl() {
        return Utils.getEnv(ENV, null);
    }

}
