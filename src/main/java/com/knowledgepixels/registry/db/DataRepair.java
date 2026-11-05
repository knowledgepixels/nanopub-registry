package com.knowledgepixels.registry.db;

import com.knowledgepixels.registry.Collection;
import com.knowledgepixels.registry.RegistryDB;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.nanopub.NanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.setValue;

/**
 * One-time repairs of data that earlier versions stored and that the current version would
 * reject at ingest or compute differently.
 *
 * <p>These run once per database, tracked by {@code serverInfo.repairVersion}, so an operator
 * only has to upgrade — no manual database surgery. That matters for registry instances we do not
 * operate: we can neither repair them nor, in general, tell from the outside whether they are
 * affected. A freshly initialized database is stamped with the current version right away and
 * never scans.
 *
 * <p>To add a repair, bump {@link #CURRENT_REPAIR_VERSION} and run the new step for every stored
 * version below it in {@link #runIfNeeded}. Since this code deletes data unattended on machines we
 * do not control, a repair belongs here only if it meets all of the following:
 *
 * <ul>
 * <li><em>Provable from the data alone.</em> A malformed artifact code qualifies because it cannot
 *     be the hash of any content by construction, so there is no false positive to weigh against
 *     the benefit. A record that merely looks wrong does not qualify.</li>
 * <li><em>Idempotent.</em> A repair runs outside a transaction and cannot resume: if the process
 *     dies partway, the stored version stays unbumped and the whole step re-runs from scratch on
 *     the next startup.</li>
 * <li><em>Cheap enough for startup.</em> The task runner has its own thread, so serving is not
 *     blocked, but the first task waits for the repair to finish. A pass over an index is fine;
 *     re-parsing or re-hashing every nanopub is not, and wants an explicitly triggered operation
 *     instead.</li>
 * <li><em>Conservative when entangled.</em> Anything still referenced elsewhere is kept and
 *     reported at ERROR level rather than removed, so that an automated repair cannot turn a small
 *     inconsistency into a broken list chain.</li>
 * </ul>
 *
 * <p>Reshaping documents is a different problem and does not belong here: a schema change wants
 * backward-compatible reads, so that a half-migrated database still serves. Recomputing a stored
 * value that is a pure function of other stored data, such as a list's checksum chain or the
 * list entries that follow from an invalidation record, does belong here, as it meets all of
 * the above.
 */
public final class DataRepair {

    private DataRepair() {
    }

    private static final Logger logger = LoggerFactory.getLogger(DataRepair.class);

    /**
     * The repair level this version of the code knows about. Bump it when adding a repair,
     * and run the new repair for every stored version below it in {@link #runIfNeeded}.
     */
    static final int CURRENT_REPAIR_VERSION = 3;

    private static final String REPAIR_VERSION_KEY = "repairVersion";

    /**
     * Fields that reference a nanopub by its artifact code, as {@code collection -> field}.
     * A malformed entry reached by any of these is entangled with the trust state or with a
     * list's position and checksum chain, and is left for a human rather than deleted.
     *
     * <p>This is meant to be the complete set of places a nanopub is referenced by its artifact
     * code, so it has to be extended whenever a new referencing field is introduced. Accounts are
     * checked separately, as they store the full nanopub URI instead.
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
        if (applied < 2) {
            repairListChecksumChains(mongoSession);
        }
        if (applied < 3) {
            removeDoubledInvalidationRecords(mongoSession);
            completeInvalidations(mongoSession);
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

    /**
     * Recomputes the checksum chain of every list.
     *
     * <p>Versions before issue #205 was fixed claimed a list position with an atomic counter and
     * then looked up the checksum of the preceding entry separately. Under parallel loading that
     * entry was often not written yet, and the writer chained off whatever entry had the highest
     * position at that moment. Every later entry built on the wrong value, so most lists are
     * wrong from the first few positions on, and registries holding the same set of nanopubs
     * do not agree on a checksum, which defeats the checksum-based skip-ahead in peer sync.
     *
     * <p>The checksum at a position is a pure function of the entries up to it, so the repair
     * needs no judgement: the chain is recomputed from position 0 and every entry that differs
     * is rewritten. Positions are left alone. A gap in the positions is reported but does not
     * stop the chain, which continues over the entries that exist. A list with an entry whose
     * artifact code cannot be decoded, or whose rewrite is refused by the database, is reported
     * at ERROR level and left as it is.
     *
     * <p>The task runner has not started yet when this runs, but the POST handler may append to
     * a list meanwhile, chaining off a checksum this repair is about to rewrite. After rewriting
     * a list, the entries beyond the ones it saw are therefore checked again until none are left.
     */
    static void repairListChecksumChains(ClientSession mongoSession) {
        // The keys first, so that no cursor over 'lists' has to stay open while the lists are rewritten.
        List<Document> lists = collection("lists").find(mongoSession)
                .projection(new Document("pubkey", 1).append("type", 1)).into(new ArrayList<>());
        long listsRewritten = 0, entriesRewritten = 0, listsSkipped = 0;
        for (Document list : lists) {
            long rewritten = repairListChecksumChain(mongoSession, list.getString("pubkey"), list.getString("type"));
            if (rewritten < 0) {
                listsSkipped++;
            } else if (rewritten > 0) {
                listsRewritten++;
                entriesRewritten += rewritten;
            }
        }
        logger.info("Checked the checksum chains of {} list(s): rewrote {} checksum(s) in {} list(s); {} list(s) could not be repaired",
                lists.size(), entriesRewritten, listsRewritten, listsSkipped);
    }

    /**
     * Recomputes the checksum chain of one list and rewrites the entries that differ.
     *
     * @return the number of entries rewritten, or -1 if the list could not be repaired
     */
    static long repairListChecksumChain(ClientSession mongoSession, String pubkeyHash, String typeHash) {
        long rewritten = 0;
        ChainScan scan = new ChainScan(-1L, NanopubUtils.INIT_CHECKSUM);
        // Rewriting takes a moment, and an entry appended meanwhile chains off the value the
        // writer saw at the time. Rescanning from where the previous pass ended catches it;
        // the bound only guards against a list that is being appended to continuously.
        for (int pass = 0; pass < 10; pass++) {
            List<WriteModel<Document>> fixes = new ArrayList<>();
            Document filter = new Document("pubkey", pubkeyHash).append("type", typeHash)
                    .append("position", new Document("$gt", scan.lastPosition));
            try (MongoCursor<Document> cursor = collection("listEntries").find(mongoSession, filter)
                    .sort(new Document("position", 1))
                    .projection(new Document("position", 1).append("np", 1).append("checksum", 1)).cursor()) {
                while (cursor.hasNext()) {
                    Document entry = cursor.next();
                    long position = entry.get("position", Number.class).longValue();
                    String artifactCode = entry.getString("np");
                    if (!isWellFormedArtifactCode(artifactCode)) {
                        logger.error("Cannot repair the checksum chain of list pubkey={} type={}: the entry at position {} "
                                + "has the undecodable artifact code '{}'. This list needs manual repair.", pubkeyHash, typeHash, position, artifactCode);
                        return -1;
                    }
                    if (position != scan.lastPosition + 1) {
                        logger.warn("List pubkey={} type={} has no entries between positions {} and {}; chaining the checksums over the gap",
                                pubkeyHash, typeHash, scan.lastPosition, position);
                    }
                    String expected = NanopubUtils.updateXorChecksum(
                            SimpleValueFactory.getInstance().createIRI("https://w3id.org/np/" + artifactCode), scan.lastChecksum);
                    if (!expected.equals(entry.getString("checksum"))) {
                        fixes.add(new UpdateOneModel<>(new Document("_id", entry.get("_id")), new Document("$set", new Document("checksum", expected))));
                    }
                    scan = new ChainScan(position, expected);
                }
            }
            if (fixes.isEmpty()) {
                break;
            }
            try {
                for (int from = 0; from < fixes.size(); from += 1000) {
                    collection("listEntries").bulkWrite(mongoSession, fixes.subList(from, Math.min(from + 1000, fixes.size())));
                }
            } catch (MongoBulkWriteException e) {
                logger.error("Cannot repair the checksum chain of list pubkey={} type={}: {}. This list needs manual repair.",
                        pubkeyHash, typeHash, e.getMessage());
                return -1;
            }
            rewritten += fixes.size();
        }
        if (rewritten > 0) {
            logger.warn("Rewrote {} checksum(s) of list pubkey={} type={} (last position {})", rewritten, pubkeyHash, typeHash, scan.lastPosition);
        }
        return rewritten;
    }

    /**
     * Removes invalidation records that are on record more than once, and creates the unique
     * index that keeps it that way.
     *
     * <p>Without the index, two writers loading the same invalidating nanopub at the same moment
     * could both record its invalidation. Nothing reads a doubled record differently from a
     * single one, so removing all but one of a set of identical records loses nothing. A
     * database created by this version has the index from the start.
     *
     * <p>The POST handler may record an invalidation while this runs; if that doubles a record
     * between the cleanup and the creation of the index, the cleanup is repeated. If the index
     * still cannot be created, that is reported and the registry runs without it, as before.
     */
    static void removeDoubledInvalidationRecords(ClientSession mongoSession) {
        long removed = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Document> doubled = collection("invalidations").aggregate(mongoSession, List.of(
                    new Document("$group", new Document("_id", new Document("invalidatingNp", "$invalidatingNp")
                            .append("invalidatedNp", "$invalidatedNp").append("invalidatingPubkey", "$invalidatingPubkey"))
                            .append("ids", new Document("$push", "$_id"))),
                    new Document("$match", new Document("ids.1", new Document("$exists", true)))
            )).allowDiskUse(true).into(new ArrayList<>());
            for (Document group : doubled) {
                List<?> ids = group.getList("ids", Object.class);
                removed += collection("invalidations").deleteMany(mongoSession,
                        new Document("_id", new Document("$in", ids.subList(1, ids.size())))).getDeletedCount();
            }
            try {
                IndexInitializer.initUniqueInvalidationIndex(mongoSession);
                logger.info("Checked the invalidation records for doubles: removed {}; an invalidation can now be on record only once", removed);
                return;
            } catch (MongoException e) {
                logger.warn("Could not create the unique index on the invalidation records (attempt {}): {}", attempt + 1, e.getMessage());
            }
        }
        logger.error("Giving up on the unique index on the invalidation records after removing {} doubled record(s). "
                + "The registry works without it, but an invalidation may be recorded twice.", removed);
    }

    /**
     * Makes the lists reflect every invalidation record.
     *
     * <p>If a nanopublication invalidates another, the lists the invalidated one is on also
     * apply to the invalidating one. Earlier versions only acted on that when the invalidating
     * nanopub arrived second, and under parallel loading not reliably even then, so registries
     * hold invalidating nanopubs that are missing from lists of the nanopubs they invalidate,
     * and possibly entries of invalidated nanopubs that are not marked as such. Which lists a
     * nanopub belongs on by an invalidation is a pure function of the records and the entries
     * that are there, so the repair adds what is missing and marks what is unmarked. Nothing is
     * removed. New entries are appended to the end of their lists like any other.
     */
    static void completeInvalidations(ClientSession mongoSession) {
        long records = 0, incomplete = 0, changes = 0;
        try (MongoCursor<Document> cursor = collection("invalidations").find(mongoSession).noCursorTimeout(true).cursor()) {
            while (cursor.hasNext()) {
                Document record = cursor.next();
                records++;
                String invalidatingAc = record.getString("invalidatingNp");
                String pubkeyHash = record.getString("invalidatingPubkey");
                String invalidatedAc = record.getString("invalidatedNp");
                if (invalidatingAc == null || pubkeyHash == null || invalidatedAc == null || !isWellFormedArtifactCode(invalidatingAc)) {
                    logger.error("Skipping the malformed invalidation record {}", record.toJson());
                    continue;
                }
                long changed = RegistryDB.completeInvalidation(mongoSession, invalidatingAc, pubkeyHash, invalidatedAc);
                if (changed > 0) {
                    incomplete++;
                    changes += changed;
                    logger.debug("Completed the invalidation of {} by {}: {} list entries added or marked", invalidatedAc, invalidatingAc, changed);
                }
            }
        }
        if (changes > 0) {
            logger.warn("Checked {} invalidation record(s): {} were not fully reflected in the lists; added or marked {} list entries", records, incomplete, changes);
        } else {
            logger.info("Checked {} invalidation record(s): all fully reflected in the lists", records);
        }
    }

    /** Where a pass over a list's entries ended: the last position seen and its correct checksum. */
    private record ChainScan(long lastPosition, String lastChecksum) {
    }

}
