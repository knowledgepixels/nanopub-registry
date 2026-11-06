package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Determines whether a nanopub's types are covered by this registry's configured
 * coverage types. Core types (intro, endorsement) are always covered regardless
 * of the configuration. If no coverage types are configured, everything is covered.
 * A nanopub that invalidates one this registry holds is taken whatever its types.
 */
public final class CoverageFilter {

    private static final Logger logger = LoggerFactory.getLogger(CoverageFilter.class);

    /**
     * Cached set of covered type hashes, or null if all types are covered.
     */
    private static volatile Set<String> coveredTypeHashes;
    private static volatile boolean initialized = false;

    private CoverageFilter() {}

    /**
     * Initializes the coverage filter from the REGISTRY_COVERAGE_TYPES env var.
     * Format: whitespace-separated type URIs (e.g. "http://example.org/TypeA http://example.org/TypeB").
     * If unset or empty, all types are covered.
     */
    public static void init() {
        String config = Utils.getEnv("REGISTRY_COVERAGE_TYPES", null);
        if (config == null || config.isBlank() || "all".equalsIgnoreCase(config.trim())) {
            coveredTypeHashes = null;
            logger.info("Coverage filter initialized: all types covered (REGISTRY_COVERAGE_TYPES={})", config == null ? "null" : "\"" + config.trim() + "\"");
        } else {
            Set<String> hashes = new HashSet<>();
            for (String typeUri : config.trim().split("\\s+")) {
                if (!typeUri.isEmpty()) {
                    String hash = Utils.getHash(typeUri);
                    hashes.add(hash);
                    logger.debug("Configured coverage type '{}' -> hash='{}'", typeUri, hash);
                }
            }
            // Always include core types (intro, endorsement)
            hashes.add(NanopubLoader.INTRO_TYPE_HASH);
            hashes.add(NanopubLoader.ENDORSE_TYPE_HASH);
            coveredTypeHashes = Collections.unmodifiableSet(hashes);
            logger.info("Coverage filter: {} types covered (including core types)", hashes.size());
        }
        initialized = true;
        logger.debug("CoverageFilter.initialized set to true");
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
        if (coveredTypeHashes == null) {
            return null;
        }
        return String.join(",", coveredTypeHashes);
    }

    /**
     * Returns true if the given nanopub has at least one type that is covered
     * by this registry, or if no coverage restriction is configured.
     * Core types (intro, endorsement) are always considered covered.
     * <p>
     * This looks at the types only. Whether the registry takes a nanopub is answered by
     * {@link #isCovered(ClientSession, Nanopub)}.
     */
    public static boolean isCovered(Nanopub nanopub) {
        if (coveredTypeHashes == null) {
            logger.trace("isCovered: all types allowed (no restriction)");
            return true;
        }

        Set<IRI> types = NanopubUtils.getTypes(nanopub);
        logger.trace("Checking coverage for nanopub: {} declared types", types.size());

        // Check nanopub's declared types
        for (IRI type : types) {
            String typeUri = type.stringValue();
            String hash = Utils.getHash(typeUri);
            if (coveredTypeHashes.contains(hash)) {
                logger.debug("Nanopub accepted: found covered type '{}' with hash='{}'", typeUri, hash);
                return true;
            } else {
                logger.trace("Type not covered: '{}' (hash='{}')", typeUri, hash);
            }
        }

        // Nanopubs with no declared types are accepted (they may be core infrastructure)
        if (types.isEmpty()) {
            logger.debug("Nanopub has no declared types - accepted as covered");
            return true;
        }

        logger.debug("Nanopub rejected: no covered types found");
        return false;
    }

    /**
     * Whether this registry takes the given nanopub: it is covered by its types (see
     * {@link #isCovered(Nanopub)}), or it invalidates a nanopub this registry holds.
     * <p>
     * A retraction has a type of its own, not the type of what it retracts. Judged by its types
     * alone, a registry restricted to some types would take the nanopubs of those types and
     * refuse their retractions, and go on listing as valid what its peers list as invalidated.
     * The lists of the invalidated nanopub apply to the invalidating one, so it belongs here as
     * much as the one it invalidates. That only holds for the pubkey that signed the invalidated
     * nanopub, as nobody else can invalidate it.
     * <p>
     * In a parallel load this waits for the nanopub's turn (see {@link AppendOrder}) before it
     * looks for the invalidated nanopub, so that one that comes earlier in the same stream is
     * stored by then. A nanopub that arrives before the one it invalidates is refused; it is on
     * the peers' lists of the invalidated one, and comes again with the next load of those.
     */
    public static boolean isCovered(ClientSession mongoSession, Nanopub nanopub) {
        if (isCovered(nanopub)) {
            return true;
        }
        Set<IRI> invalidatedIds = Utils.getInvalidatedNanopubIds(nanopub);
        if (invalidatedIds.isEmpty()) {
            return false;
        }
        String pubkeyHash = declaredPubkeyHash(nanopub);
        if (pubkeyHash == null) {
            return false;
        }
        AppendOrder.awaitTurn();
        for (IRI invalidatedId : invalidatedIds) {
            String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
            if (invalidatedAc != null && RegistryDB.has(mongoSession, Collection.NANOPUBS.toString(),
                    new Document("_id", invalidatedAc).append("pubkey", pubkeyHash))) {
                logger.debug("Nanopub accepted: it invalidates {}, which this registry holds", invalidatedAc);
                return true;
            }
        }
        logger.debug("Nanopub rejected: no covered types found, and it invalidates nothing this registry holds");
        return false;
    }

    /**
     * The hash of the public key a nanopub says it is signed with, or null if it has none.
     * The signature is not verified here; loading the nanopub does that.
     */
    private static String declaredPubkeyHash(Nanopub nanopub) {
        try {
            NanopubSignatureElement el = SignatureUtils.getSignatureElement(nanopub);
            if (el != null && el.getPublicKeyString() != null) {
                return Utils.getHash(el.getPublicKeyString());
            }
        } catch (MalformedCryptoElementException ex) {
            logger.debug("Nanopub {} has a malformed signature element: {}", nanopub.getUri(), ex.getMessage());
        }
        return null;
    }

    /**
     * Returns true if the given type hash is covered by this registry.
     */
    public static boolean isCoveredType(String typeHash) {
        if (coveredTypeHashes == null) {
            logger.trace("isCoveredType('{}'): all types allowed", typeHash);
            return true;
        }
        if ("$".equals(typeHash)) {
            logger.trace("isCoveredType('{}'): special core token accepted", typeHash);
            return true;
        }
        boolean contains = coveredTypeHashes.contains(typeHash);
        logger.debug("isCoveredType('{}') -> {}", typeHash, contains);
        return contains;
    }
}
