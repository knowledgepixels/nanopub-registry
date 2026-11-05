package com.knowledgepixels.registry;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.nanopub.NanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * The list appends of one parallel load, computed in order and written in batches.
 * <p>
 * Within a load the appends happen in stream order (see {@link AppendOrder}), so positions and
 * checksums can be computed in memory from the tail of each list, read once from the database,
 * without a round trip per entry. The entries are inserted in batches, in order, so that a crash
 * leaves a list with a shorter chain but never with a hole. The {@code lists.maxPosition}
 * counters are raised once per batch.
 * <p>
 * The database remains the arbiter: if another writer, such as the POST handler, appended to a
 * list meanwhile, the batch insert stops at the first entry whose position is taken, and that
 * entry and the ones after it are appended one by one off the actual end of their lists. The
 * chain is sequential either way; only the order of those few entries can deviate from the
 * stream.
 * <p>
 * Until a batch is flushed, its entries are not visible to readers. The entries of a nanopub
 * that is invalidated meanwhile by a nanopub of the same load are marked here as well as in the
 * database, and the lists a pending nanopub is on are found here as well as in the database. An
 * invalidation that another writer records meanwhile is picked up when the batch is flushed.
 */
final class ListAppendBatch {

    private static final Logger logger = LoggerFactory.getLogger(ListAppendBatch.class);

    /** How many pending entries trigger a flush. */
    static final int FLUSH_SIZE = 100;

    private record Tail(long position, String checksum) {
    }

    private static final class Pending {
        final String pubkeyHash, typeHash, artifactCode;
        final IRI uri;
        final long position;
        final String checksum;
        boolean invalidated = false;

        Pending(String pubkeyHash, String typeHash, IRI uri, String artifactCode, long position, String checksum) {
            this.pubkeyHash = pubkeyHash;
            this.typeHash = typeHash;
            this.uri = uri;
            this.artifactCode = artifactCode;
            this.position = position;
            this.checksum = checksum;
        }

        Document toDocument() {
            return new Document("pubkey", pubkeyHash).append("type", typeHash).append("position", position)
                    .append("np", artifactCode).append("checksum", checksum).append("invalidated", invalidated);
        }
    }

    private final Map<String, Tail> tails = new HashMap<>();
    private final Set<String> knownLists = new HashSet<>();
    private final List<Pending> pending = new ArrayList<>();
    private final Map<String, List<Pending>> pendingByNanopub = new HashMap<>();

    private static String listKey(String pubkeyHash, String typeHash) {
        return pubkeyHash + "|" + typeHash;
    }

    /** Whether the list document is known to exist, so that its creation can be skipped. */
    synchronized boolean isListKnown(String pubkeyHash, String typeHash) {
        return knownLists.contains(listKey(pubkeyHash, typeHash));
    }

    synchronized void listKnown(String pubkeyHash, String typeHash) {
        knownLists.add(listKey(pubkeyHash, typeHash));
    }

    synchronized boolean isPending(String pubkeyHash, String typeHash, String artifactCode) {
        for (Pending p : pendingByNanopub.getOrDefault(pubkeyHash + "|" + artifactCode, List.of())) {
            if (p.typeHash.equals(typeHash)) {
                return true;
            }
        }
        return false;
    }

    /** The types of the lists a nanopub has pending entries on. */
    synchronized List<String> pendingTypesOf(String pubkeyHash, String artifactCode) {
        List<String> types = new ArrayList<>();
        for (Pending p : pendingByNanopub.getOrDefault(pubkeyHash + "|" + artifactCode, List.of())) {
            types.add(p.typeHash);
        }
        return types;
    }

    synchronized void markInvalidated(String pubkeyHash, String artifactCode) {
        for (Pending p : pendingByNanopub.getOrDefault(pubkeyHash + "|" + artifactCode, List.of())) {
            p.invalidated = true;
        }
    }

    synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Appends a nanopub to a list: computes its position and checksum from the list's tail and
     * queues the entry. The caller has made sure the nanopub is not on the list yet.
     */
    synchronized void append(ClientSession mongoSession, String pubkeyHash, String typeHash, IRI uri, String artifactCode) {
        String key = listKey(pubkeyHash, typeHash);
        Tail tail = tails.get(key);
        if (tail == null) {
            Document last = RegistryDB.getMaxValueDocument(mongoSession, "listEntries",
                    new Document("pubkey", pubkeyHash).append("type", typeHash), "position");
            tail = (last == null) ? new Tail(-1L, NanopubUtils.INIT_CHECKSUM)
                    : new Tail(last.getLong("position"), last.getString("checksum") == null ? NanopubUtils.INIT_CHECKSUM : last.getString("checksum"));
        }
        long position = tail.position() + 1;
        String checksum = NanopubUtils.updateXorChecksum(uri, tail.checksum());
        Pending entry = new Pending(pubkeyHash, typeHash, uri, artifactCode, position, checksum);
        pending.add(entry);
        pendingByNanopub.computeIfAbsent(pubkeyHash + "|" + artifactCode, k -> new ArrayList<>()).add(entry);
        tails.put(key, new Tail(position, checksum));
        if (pending.size() >= FLUSH_SIZE) {
            flush(mongoSession);
        }
    }

    /**
     * Writes the pending entries, in order, and raises the list counters. If the database
     * refuses an entry because its position (or the nanopub) was taken by another writer
     * meanwhile, that entry and the ones after it are appended one by one off the actual end
     * of their lists.
     */
    synchronized void flush(ClientSession mongoSession) {
        if (pending.isEmpty()) {
            return;
        }
        List<Document> docs = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            docs.add(p.toDocument());
        }
        int written;
        try {
            collection("listEntries").insertMany(mongoSession, docs, new InsertManyOptions().ordered(true));
            written = docs.size();
        } catch (MongoBulkWriteException e) {
            for (BulkWriteError error : e.getWriteErrors()) {
                if (error.getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    logger.error("Batch insert of list entries failed: {}", e.getMessage(), e);
                    throw e;
                }
            }
            // Ordered insert: everything before the first error is in.
            written = e.getWriteResult().getInsertedCount();
            logger.info("Batch insert of {} list entries stopped after {} because another writer got there first; appending the rest one by one", docs.size(), written);
        }
        List<Pending> flushed = new ArrayList<>(pending);
        List<Pending> rest = new ArrayList<>(pending.subList(written, pending.size()));
        raiseCounters(mongoSession, pending.subList(0, written));
        pending.clear();
        pendingByNanopub.clear();
        if (!rest.isEmpty()) {
            // The tails are stale for the lists involved; read them again as needed.
            tails.clear();
            for (Pending p : rest) {
                RegistryDB.appendDirectly(mongoSession, p.uri, p.artifactCode, p.pubkeyHash, p.typeHash, p.invalidated);
            }
        }
        applyInvalidationsRecordedMeanwhile(mongoSession, flushed);
        logger.debug("Flushed {} list entries ({} in a batch, {} one by one)", docs.size(), written, rest.size());
    }

    /**
     * Marks the entries just written whose nanopub was invalidated while they were pending.
     * <p>
     * A writer outside this load, such as the POST handler, records the invalidation and marks
     * the entries it finds in the database. It cannot see the entries waiting here, and the
     * nanopub they belong to looked for invalidation records before that one existed. So the
     * records are looked up once more now that the entries are in the database: an
     * invalidation recorded before this point is found here, and one recorded after it finds
     * the entries itself.
     */
    private static void applyInvalidationsRecordedMeanwhile(ClientSession mongoSession, List<Pending> flushed) {
        Map<String, Set<String>> pubkeysByNanopub = new HashMap<>();
        for (Pending p : flushed) {
            if (!p.invalidated) {
                pubkeysByNanopub.computeIfAbsent(p.artifactCode, k -> new HashSet<>()).add(p.pubkeyHash);
            }
        }
        if (pubkeysByNanopub.isEmpty()) {
            return;
        }
        List<Document> records = collection("invalidations")
                .find(mongoSession, new Document("invalidatedNp", new Document("$in", new ArrayList<>(pubkeysByNanopub.keySet()))))
                .into(new ArrayList<>());
        Set<String> marked = new HashSet<>();
        for (Document record : records) {
            String artifactCode = record.getString("invalidatedNp");
            String pubkeyHash = record.getString("invalidatingPubkey");
            // An invalidation only counts on the lists of the pubkey that made it.
            if (pubkeysByNanopub.get(artifactCode).contains(pubkeyHash) && marked.add(pubkeyHash + "|" + artifactCode)) {
                collection("listEntries").updateMany(mongoSession, new Document("np", artifactCode).append("pubkey", pubkeyHash),
                        new Document("$set", new Document("invalidated", true)));
                logger.debug("Marked the entries of nanopub {} as invalidated; the invalidation was recorded while they were pending", artifactCode);
            }
        }
    }

    private static void raiseCounters(ClientSession mongoSession, List<Pending> written) {
        Map<String, Pending> last = new LinkedHashMap<>();
        for (Pending p : written) {
            last.put(listKey(p.pubkeyHash, p.typeHash), p);
        }
        if (last.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> updates = new ArrayList<>(last.size());
        for (Pending p : last.values()) {
            updates.add(new UpdateOneModel<>(new Document("pubkey", p.pubkeyHash).append("type", p.typeHash),
                    new Document("$max", new Document("maxPosition", p.position))));
        }
        collection("lists").bulkWrite(mongoSession, updates, new BulkWriteOptions().ordered(false));
    }

}
