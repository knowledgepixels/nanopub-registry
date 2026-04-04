package com.knowledgepixels.registry;

import org.eclipse.rdf4j.model.IRI;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Determines whether a nanopub's types are covered by this registry's configured
 * coverage types. Core types (intro, endorsement) are always covered regardless
 * of the configuration. If no coverage types are configured, everything is covered.
 */
public final class CoverageFilter {

    private static final Logger logger = LoggerFactory.getLogger(CoverageFilter.class);

    /** Cached set of covered type hashes, or null if all types are covered. */
    private static volatile Set<String> coveredTypeHashes;
    private static volatile boolean initialized = false;

    private CoverageFilter() {}

    /**
     * Initializes the coverage filter from the REGISTRY_COVERAGE_TYPES env var.
     * Format: comma-separated type URIs (e.g. "http://example.org/TypeA,http://example.org/TypeB").
     * If unset or empty, all types are covered.
     */
    public static void init() {
        String config = Utils.getEnv("REGISTRY_COVERAGE_TYPES", null);
        if (config == null || config.isBlank()) {
            coveredTypeHashes = null;
            logger.info("Coverage filter: all types covered (no restriction)");
        } else {
            if (!config.matches("[^\\s,]+(,[^\\s,]+)*")) {
                throw new IllegalArgumentException(
                        "Invalid REGISTRY_COVERAGE_TYPES format: must be comma-separated URIs with no whitespace. Got: " + config);
            }
            Set<String> hashes = new HashSet<>();
            for (String typeUri : config.split(",")) {
                hashes.add(Utils.getHash(typeUri));
            }
            // Always include core types (intro, endorsement)
            hashes.add(NanopubLoader.INTRO_TYPE_HASH);
            hashes.add(NanopubLoader.ENDORSE_TYPE_HASH);
            coveredTypeHashes = Collections.unmodifiableSet(hashes);
            logger.info("Coverage filter: {} types covered (including core types)", hashes.size());
        }
        initialized = true;
    }

    /**
     * Returns true if all types are covered (no restriction configured).
     */
    public static boolean coversAllTypes() {
        return coveredTypeHashes == null;
    }

    /**
     * Returns the set of covered type hashes, or null if all types are covered.
     */
    public static Set<String> getCoveredTypeHashes() {
        return coveredTypeHashes;
    }

    /**
     * Returns the covered type hashes as a comma-separated string for use in
     * HTTP headers, or null if all types are covered.
     */
    public static String getCoveredTypeHashesAsString() {
        if (coveredTypeHashes == null) return null;
        return String.join(",", coveredTypeHashes);
    }

    /**
     * Returns true if the given nanopub has at least one type that is covered
     * by this registry, or if no coverage restriction is configured.
     * Core types (intro, endorsement) are always considered covered.
     */
    public static boolean isCovered(Nanopub nanopub) {
        if (coveredTypeHashes == null) return true;

        // Check nanopub's declared types
        for (IRI type : NanopubUtils.getTypes(nanopub)) {
            if (coveredTypeHashes.contains(Utils.getHash(type.stringValue()))) {
                return true;
            }
        }

        // Nanopubs with no declared types are accepted (they may be core infrastructure)
        if (NanopubUtils.getTypes(nanopub).isEmpty()) {
            return true;
        }

        return false;
    }

    /**
     * Returns true if the given type hash is covered by this registry.
     */
    public static boolean isCoveredType(String typeHash) {
        if (coveredTypeHashes == null) return true;
        if ("$".equals(typeHash)) return true;
        return coveredTypeHashes.contains(typeHash);
    }
}
