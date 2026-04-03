package com.knowledgepixels.registry;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.WriteModel;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.knowledgepixels.registry.RegistryDB.*;

/**
 * Batched single-writer per list for listEntries. Multiple callers enqueue entries
 * concurrently; a single writer per (pubkey, type) list drains the queue, computes
 * the checksum chain in-memory, and does a bulk insert.
 */
public final class ListEntryWriter {

    private static final Logger logger = LoggerFactory.getLogger(ListEntryWriter.class);

    private static final int BATCH_SIZE = Integer.parseInt(
            Utils.getEnv("REGISTRY_LIST_WRITER_BATCH_SIZE", "50"));
    private static final int THREAD_COUNT = Integer.parseInt(
            Utils.getEnv("REGISTRY_LIST_WRITER_THREADS", "4"));

    private static final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    private static final ConcurrentHashMap<String, ListQueue> queues = new ConcurrentHashMap<>();

    private ListEntryWriter() {}

    private static final class WriteRequest {
        final Nanopub nanopub;
        final String ac;
        final CompletableFuture<Void> result = new CompletableFuture<>();

        WriteRequest(Nanopub nanopub, String ac) {
            this.nanopub = nanopub;
            this.ac = ac;
        }
    }

    private static final class ListQueue {
        final String pubkeyHash;
        final String typeHash;
        final LinkedBlockingQueue<WriteRequest> queue = new LinkedBlockingQueue<>();
        final AtomicBoolean processing = new AtomicBoolean(false);

        ListQueue(String pubkeyHash, String typeHash) {
            this.pubkeyHash = pubkeyHash;
            this.typeHash = typeHash;
        }
    }

    /**
     * Enqueue a nanopub to be added to the specified list. Blocks until the entry
     * is written or throws if the write fails.
     */
    static void enqueue(String pubkeyHash, String typeHash, Nanopub nanopub, String ac) {
        String key = pubkeyHash + ":" + typeHash;
        ListQueue listQueue = queues.computeIfAbsent(key, k -> new ListQueue(pubkeyHash, typeHash));

        WriteRequest request = new WriteRequest(nanopub, ac);
        listQueue.queue.add(request);
        tryScheduleDrain(key, listQueue);

        try {
            request.result.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("List entry write failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for list entry write", e);
        }
    }

    private static void tryScheduleDrain(String key, ListQueue listQueue) {
        if (listQueue.processing.compareAndSet(false, true)) {
            executor.submit(() -> drain(key, listQueue));
        }
    }

    private static void drain(String key, ListQueue listQueue) {
        try {
            List<WriteRequest> batch = new ArrayList<>(BATCH_SIZE);
            listQueue.queue.drainTo(batch, BATCH_SIZE);
            if (batch.isEmpty()) {
                return;
            }

            processBatch(listQueue, batch);
        } catch (Exception e) {
            logger.error("Unexpected error in list writer drain for {}", key, e);
        } finally {
            listQueue.processing.set(false);
            // Re-schedule if more entries arrived while we were processing
            if (!listQueue.queue.isEmpty()) {
                tryScheduleDrain(key, listQueue);
            }
        }
    }

    private static void processBatch(ListQueue listQueue, List<WriteRequest> batch) {
        try (ClientSession session = getClient().startSession()) {
            String pubkeyHash = listQueue.pubkeyHash;
            String typeHash = listQueue.typeHash;

            // Initialize maxPosition for legacy lists (one-time migration)
            initListPositionIfNeeded(session, pubkeyHash, typeHash);

            // Filter out entries already in the list and deduplicate within batch
            LinkedHashSet<String> seenAcs = new LinkedHashSet<>();
            List<WriteRequest> toInsert = new ArrayList<>(batch.size());
            for (WriteRequest req : batch) {
                if (!seenAcs.add(req.ac)) {
                    // Duplicate within this batch — complete as success
                    req.result.complete(null);
                    continue;
                }
                if (has(session, "listEntries",
                        new Document("pubkey", pubkeyHash).append("type", typeHash).append("np", req.ac))) {
                    req.result.complete(null);
                    continue;
                }
                toInsert.add(req);
            }

            if (toInsert.isEmpty()) return;

            // Read current maxPosition and the checksum at that position
            Document listDoc = collection("lists").find(session,
                    new Document("pubkey", pubkeyHash).append("type", typeHash)).first();
            long maxPosition = (listDoc != null && listDoc.get("maxPosition") != null)
                    ? listDoc.getLong("maxPosition") : -1L;

            String prevChecksum;
            if (maxPosition < 0) {
                prevChecksum = NanopubUtils.INIT_CHECKSUM;
            } else {
                Document prevEntry = collection("listEntries").find(session,
                        new Document("pubkey", pubkeyHash).append("type", typeHash)
                                .append("position", maxPosition)).first();
                prevChecksum = (prevEntry != null) ? prevEntry.getString("checksum") : NanopubUtils.INIT_CHECKSUM;
            }

            // Build all insert documents with sequential positions and chained checksums
            List<WriteModel<Document>> writes = new ArrayList<>(toInsert.size());
            long nextPosition = maxPosition + 1;
            String checksum = prevChecksum;

            for (int i = 0; i < toInsert.size(); i++) {
                WriteRequest req = toInsert.get(i);
                checksum = NanopubUtils.updateXorChecksum(req.nanopub.getUri(), checksum);

                writes.add(new InsertOneModel<>(new Document("pubkey", pubkeyHash)
                        .append("type", typeHash)
                        .append("position", nextPosition + i)
                        .append("np", req.ac)
                        .append("checksum", checksum)
                        .append("invalidated", false)));
            }

            // Bulk insert
            try {
                collection("listEntries").bulkWrite(session, writes);
            } catch (MongoBulkWriteException e) {
                // Some entries may have been inserted by a concurrent path (e.g. POST handler
                // duplicate check raced). Fail the whole batch and let callers retry.
                for (WriteRequest req : toInsert) {
                    req.result.completeExceptionally(e);
                }
                return;
            }

            // Update maxPosition to the final position
            long finalPosition = nextPosition + toInsert.size() - 1;
            collection("lists").updateOne(session,
                    new Document("pubkey", pubkeyHash).append("type", typeHash),
                    new Document("$set", new Document("maxPosition", finalPosition)));

            // Complete all futures
            for (WriteRequest req : toInsert) {
                req.result.complete(null);
            }

            logger.debug("Batch inserted {} entries for list {}:{} (positions {}-{})",
                    toInsert.size(), pubkeyHash, typeHash, nextPosition, finalPosition);

        } catch (Exception e) {
            // Complete all pending futures with the error
            for (WriteRequest req : batch) {
                if (!req.result.isDone()) {
                    req.result.completeExceptionally(e);
                }
            }
        }
    }

    /**
     * Lazily initializes the maxPosition field on a lists document for lists
     * created before this field existed.
     */
    private static void initListPositionIfNeeded(ClientSession mongoSession, String pubkeyHash, String typeHash) {
        Document listDoc = collection("lists").find(mongoSession,
                new Document("pubkey", pubkeyHash).append("type", typeHash)).first();
        if (listDoc == null || listDoc.get("maxPosition") != null) return;

        Document maxDoc = getMaxValueDocument(mongoSession, "listEntries",
                new Document("pubkey", pubkeyHash).append("type", typeHash), "position");
        long maxPos = (maxDoc != null) ? maxDoc.getLong("position") : -1L;

        collection("lists").updateOne(mongoSession,
                new Document("pubkey", pubkeyHash).append("type", typeHash)
                        .append("maxPosition", new Document("$exists", false)),
                new Document("$set", new Document("maxPosition", maxPos)));
    }

    /**
     * Gracefully shuts down the writer, draining any remaining queues.
     */
    public static void shutdown() {
        logger.info("Shutting down ListEntryWriter...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("ListEntryWriter did not terminate in 30s, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        logger.info("ListEntryWriter shutdown complete");
    }
}
