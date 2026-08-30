package com.knowledgepixels.registry.db;

import com.knowledgepixels.registry.Collection;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.setValue;

/**
 * One-time repairs of data that earlier versions stored and that the current version would
 * reject at ingest.
 *
 * <p>These run once per database, tracked by {@code serverInfo.repairVersion}, so an operator
 * only has to upgrade — no manual database surgery. A freshly initialized database is stamped
 * with the current version right away and never scans.
 */
public final class DataRepair {

    private DataRepair() {
    }

    private static final Logger logger = LoggerFactory.getLogger(DataRepair.class);

    /**
     * The repair level this version of the code knows about. Bump it when adding a repair,
     * and run the new repair for every stored version below it in {@link #runIfNeeded}.
     */
    static final int CURRENT_REPAIR_VERSION = 1;

    private static final String REPAIR_VERSION_KEY = "repairVersion";

    /**
     * Fields that reference a nanopub by its artifact code, as {@code collection -> field}.
     * A malformed entry reached by any of these is entangled with the trust state or with a
     * list's position and checksum chain, and is left for a human rather than deleted.
     */
    private static final String[][] ARTIFACT_CODE_REFERENCES = {
            {"listEntries", "np"},
            {"trustEdges", "source"},
            {"endorsements", "source"},
            {"endorsements", "endorsedNanopub"},
            {"endorsements_loading", "source"},
            {"endorsements_loading", "endorsedNanopub"},
            {"trustPaths", "source"},
            {"trustPaths_loading", "source"},
    };

    /**
     * Applies every repair the given database has not seen yet.
     *
     * @param mongoSession the MongoDB client session
     */
    public static void runIfNeeded(ClientSession mongoSession) {
        int applied = getRepairVersion(mongoSession);
        if (applied >= CURRENT_REPAIR_VERSION) {
            logger.debug("No data repair needed (repairVersion={})", applied);
            return;
        }
        logger.info("Applying data repairs (stored repairVersion={}, current={})", applied, CURRENT_REPAIR_VERSION);
        if (applied < 1) {
            removeMalformedNanopubs(mongoSession);
        }
        markUpToDate(mongoSession);
    }

    /**
     * Records that the database needs no repairs, which is the case for a freshly initialized one.
     *
     * @param mongoSession the MongoDB client session
     */
    public static void markUpToDate(ClientSession mongoSession) {
        setValue(mongoSession, Collection.SERVER_INFO.toString(), REPAIR_VERSION_KEY, CURRENT_REPAIR_VERSION);
        logger.debug("Marked database as repaired up to version {}", CURRENT_REPAIR_VERSION);
    }

    static int getRepairVersion(ClientSession mongoSession) {
        Object value = getValue(mongoSession, Collection.SERVER_INFO.toString(), REPAIR_VERSION_KEY);
        return value instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Removes nanopubs stored under an artifact code that cannot be the hash of any content.
     *
     * <p>Versions before the ingest check accepted these: a valid signature says nothing about the
     * artifact code, because signature verification normalizes the code out of all URIs. Such an
     * entry is counted and emitted into the Jelly stream, but {@code /np/} cannot serve it, which
     * stalls consumers that stop at the first entry they cannot resolve.
     *
     * <p>The nanopub counter is deliberately left alone. It must never move backwards: consumers
     * read a decreasing load counter as a registry reset and would resynchronize from scratch. The
     * resulting gap is harmless, as the stream is served with a "greater than" filter.
     */
    static void removeMalformedNanopubs(ClientSession mongoSession) {
        List<Document> malformed = new ArrayList<>();
        try (MongoCursor<Document> cursor = collection(Collection.NANOPUBS.toString())
                .find(mongoSession).projection(new Document("_id", 1).append("fullId", 1)).cursor()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                if (!isWellFormedArtifactCode(doc.getString("_id"))) {
                    malformed.add(doc);
                }
            }
        }
        if (malformed.isEmpty()) {
            logger.info("Checked stored nanopubs for malformed artifact codes; none found");
            return;
        }

        logger.warn("Found {} stored nanopub(s) with a malformed artifact code", malformed.size());
        for (Document doc : malformed) {
            String ac = doc.getString("_id");
            String referencedBy = findReference(mongoSession, ac, doc.getString("fullId"));
            if (referencedBy != null) {
                logger.error("Keeping nanopub '{}' despite its malformed artifact code: it is still referenced by {}. "
                        + "Removing it would break the position and checksum chain of the referencing list, so this "
                        + "needs manual repair.", ac, referencedBy);
                continue;
            }
            collection(Collection.NANOPUBS.toString()).deleteOne(mongoSession, new Document("_id", ac));
            long invalidations = collection("invalidations").deleteMany(mongoSession,
                    new Document("$or", List.of(new Document("invalidatingNp", ac), new Document("invalidatedNp", ac)))
            ).getDeletedCount();
            logger.warn("Removed nanopub '{}' with a malformed artifact code, along with {} invalidation record(s)", ac, invalidations);
        }
    }

    /**
     * Whether the given artifact code is well-formed for its Trusty URI module. This is the cheap
     * half of the ingest check: a code that fails here cannot be the hash of any content, so no
     * stored nanopub has to be re-hashed to find the broken ones.
     */
    private static boolean isWellFormedArtifactCode(String artifactCode) {
        // getModuleId() reads the first two characters unguarded, so anything shorter is malformed
        // by definition and must not reach it.
        return artifactCode != null && artifactCode.length() > 2 && TrustyUriUtils.isPotentialArtifactCode(artifactCode);
    }

    /**
     * Returns a description of the first reference to the given nanopub, or null if it is orphaned.
     */
    private static String findReference(ClientSession mongoSession, String artifactCode, String fullId) {
        for (String[] reference : ARTIFACT_CODE_REFERENCES) {
            long count = collection(reference[0]).countDocuments(mongoSession, new Document(reference[1], artifactCode));
            if (count > 0) {
                return count + " document(s) in '" + reference[0] + "' via '" + reference[1] + "'";
            }
        }
        if (fullId != null) {
            // Accounts store the full nanopub URI rather than the artifact code.
            long count = collection(Collection.ACCOUNTS.toString())
                    .countDocuments(mongoSession, new Document("introNanopub", fullId));
            if (count > 0) {
                return count + " account(s) via 'introNanopub'";
            }
        }
        return null;
    }

}
