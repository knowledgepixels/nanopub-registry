package com.knowledgepixels.registry;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.util.EntityUtils;
import org.bson.Document;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.NanopubStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.knowledgepixels.registry.RegistryDB.*;

/**
 * Checks peer Nanopub Registries for new nanopublications and loads them.
 */
public class RegistryPeerConnector {

    private RegistryPeerConnector() {}

    private static final Logger logger = LoggerFactory.getLogger(RegistryPeerConnector.class);

    public static void checkPeers(ClientSession s) {
        List<String> peerUrls = new ArrayList<>(Utils.getPeerUrls());
        Collections.shuffle(peerUrls);

        for (String peerUrl : peerUrls) {
            try {
                checkPeer(s, peerUrl);
            } catch (Exception ex) {
                logger.warn("Failed to check peer {}: {} ({})", peerUrl, ex.getMessage(), ex.getClass().getSimpleName(), ex);
            }
        }
    }

    static void checkPeer(ClientSession s, String peerUrl) throws IOException {
        logger.info("Checking peer: {}", peerUrl);

        HttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpHead(peerUrl));
        int httpStatus = resp.getStatusLine().getStatusCode();
        String reason = resp.getStatusLine().getReasonPhrase();
        EntityUtils.consumeQuietly(resp.getEntity());
        if (httpStatus < 200 || httpStatus >= 300) {
            logger.warn("Failed to reach peer {}: HTTP {} {} ; skipping", peerUrl, httpStatus, reason);
            return;
        }

        if (isTestInstance(resp)) {
            logger.info("Skipping peer {} because it is a test instance", peerUrl);
            return;
        }

        String status = getHeader(resp, "Nanopub-Registry-Status");
        if (!"ready".equals(status) && !"updating".equals(status)) {
            logger.warn("Skipping peer {}: registry status is '{}' (expected 'ready' or 'updating')", peerUrl, status);
            return;
        }

        String setupHeader = getHeader(resp, "Nanopub-Registry-Setup-Id");
        String loadCounterHeader = getHeader(resp, "Nanopub-Registry-Load-Counter");
        Long peerSetupId = getHeaderLong(resp, "Nanopub-Registry-Setup-Id");
        Long peerLoadCounter = getHeaderLong(resp, "Nanopub-Registry-Load-Counter");
        if (peerSetupId == null || peerLoadCounter == null) {
            logger.warn("Skipping peer {}: missing or invalid headers. Nanopub-Registry-Setup-Id='{}', Nanopub-Registry-Load-Counter='{}'", peerUrl, setupHeader, loadCounterHeader);
            return;
        }

        syncWithPeer(s, peerUrl, peerSetupId, peerLoadCounter);
    }

    static void syncWithPeer(ClientSession s, String peerUrl, long peerSetupId, long peerLoadCounter) {
        Document peerState = getPeerState(s, peerUrl);
        Long lastSetupId = peerState != null ? peerState.getLong("setupId") : null;
        Long lastLoadCounter = peerState != null ? peerState.getLong("loadCounter") : null;

        if (lastSetupId != null && !lastSetupId.equals(peerSetupId)) {
            logger.info("Peer {} was reset: setupId changed from {} -> {}; resetting tracking state", peerUrl, lastSetupId, peerSetupId);
            deletePeerState(s, peerUrl);
            lastLoadCounter = null;
        }

        long effectiveCounter = lastLoadCounter != null ? lastLoadCounter : 0;

        if (lastLoadCounter != null && lastLoadCounter.equals(peerLoadCounter)) {
            logger.info("Peer {} has no new nanopubs (loadCounter unchanged: {})", peerUrl, peerLoadCounter);
        } else if (lastLoadCounter != null) {
            // Fetch all nanopubs added since our last known position.
            logger.info("Peer {} has new nanopubs (loadCounter {} -> {}), fetching recent", peerUrl, lastLoadCounter, peerLoadCounter);
            long lastReceived = loadRecentNanopubs(s, peerUrl, lastLoadCounter);
            if (lastReceived > 0) {
                effectiveCounter = lastReceived;
                logger.info("Updated effective counter for {} to {}", peerUrl, effectiveCounter);
            } else {
                logger.info("No nanopubs were successfully received from {} when fetching recent entries", peerUrl);
            }
            // Only discover new pubkeys when the peer has new data
            discoverPubkeys(s, peerUrl);
        } else {
            logger.info("Peer {} is new to this registry; starting pubkey discovery and initial sync", peerUrl);
            discoverPubkeys(s, peerUrl);
        }
        updatePeerState(s, peerUrl, peerSetupId, effectiveCounter);
        logger.debug("Peer {} state updated: setupId={}, loadCounter={}", peerUrl, peerSetupId, effectiveCounter);
    }

    /**
     * Fetches nanopubs from a peer after the given counter.
     *
     * @return the counter of the last successfully received nanopub, or -1 if none were received
     */
    private static long loadRecentNanopubs(ClientSession s, String peerUrl, long afterCounter) {
        String requestUrl = peerUrl + "nanopubs.jelly?afterCounter=" + afterCounter;
        logger.info("Fetching recent nanopubs from {} (afterCounter={})", peerUrl, afterCounter);
        AtomicLong lastReceivedCounter = new AtomicLong(-1);
        try {
            HttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
            int httpStatus = resp.getStatusLine().getStatusCode();
            String reason = resp.getStatusLine().getReasonPhrase();
            if (httpStatus < 200 || httpStatus >= 300) {
                EntityUtils.consumeQuietly(resp.getEntity());
                logger.warn("Fetching recent nanopubs from {} failed: HTTP {} {} ; skipping", requestUrl, httpStatus, reason);
                return -1;
            }
            try (InputStream is = resp.getEntity().getContent()) {
                NanopubLoader.loadStreamInParallel(
                        NanopubStream.fromByteStream(is).getAsNanopubs().peek(m -> {
                            // Track counter in the main thread as items are consumed from the stream
                            if (m.isSuccess() && m.getCounter() > 0) {
                                lastReceivedCounter.set(m.getCounter());
                            }
                        }),
                        np -> {
                            if (!CoverageFilter.isCovered(np)) {
                                return;
                            }
                            try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                                String pubkey = RegistryDB.getPubkey(np);
                                if (pubkey != null) {
                                    NanopubLoader.simpleLoad(workerSession, np, pubkey);
                                }
                            }
                        });
            }
        } catch (IOException ex) {
            logger.warn("Failed to fetch recent nanopubs from {} (request: {}): {} ({})", peerUrl, requestUrl, ex.getMessage(), ex.getClass().getSimpleName(), ex);
        }
        logger.info("Last received counter from {}: {}", peerUrl, lastReceivedCounter.get());
        return lastReceivedCounter.get();
    }

    static void discoverPubkeys(ClientSession s, String peerUrl) {
        logger.info("Discovering pubkeys from peer: {}", peerUrl);
        try {
            List<String> peerPubkeys = Utils.retrieveListFromJsonUrl(peerUrl + "pubkeys.json");
            int discovered = 0;
            logger.debug("Retrieved {} pubkeys from {} for discovery", peerPubkeys.size(), peerUrl);
            for (String pubkeyHash : peerPubkeys) {
                Document filter = new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH);
                if (!has(s, "lists", filter)) {
                    try {
                        insert(s, "lists", new Document("pubkey", pubkeyHash)
                                .append("type", NanopubLoader.INTRO_TYPE_HASH)
                                .append("status", EntryStatus.encountered.getValue()));
                    } catch (MongoWriteException e) {
                        if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                            throw e;
                        } else {
                            logger.debug("Pubkey {} was inserted concurrently by another worker", pubkeyHash);
                        }
                    }
                    discovered++;
                } else if (!has(s, "lists", new Document(filter).append("status", EntryStatus.loaded.getValue()))) {
                    // Set status to encountered if not already loaded (fixes null-status entries from older code)
                    collection("lists").updateMany(s, filter,
                            new Document("$set", new Document("status", EntryStatus.encountered.getValue())));
                    discovered++;
                }
            }
            logger.info("Discovered {} new pubkeys from peer {}", discovered, peerUrl);
        } catch (Exception ex) {
            logger.warn("Failed to discover pubkeys from {}: {} ({})", peerUrl, ex.getMessage(), ex.getClass().getSimpleName(), ex);
        }
    }

    static Document getPeerState(ClientSession s, String peerUrl) {
        try (MongoCursor<Document> cursor = collection(Collection.PEER_STATE.toString())
                .find(s, new Document("_id", peerUrl)).cursor()) {
            return cursor.hasNext() ? cursor.next() : null;
        }
    }

    static void updatePeerState(ClientSession s, String peerUrl, long setupId, long loadCounter) {
        collection(Collection.PEER_STATE.toString()).updateOne(s,
                new Document("_id", peerUrl),
                new Document("$set", new Document("_id", peerUrl)
                        .append("setupId", setupId)
                        .append("loadCounter", loadCounter)
                        .append("lastChecked", System.currentTimeMillis())),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    static void deletePeerState(ClientSession s, String peerUrl) {
        collection(Collection.PEER_STATE.toString()).deleteOne(s, new Document("_id", peerUrl));
    }

    static boolean isTestInstance(HttpResponse resp) {
        return "true".equals(getHeader(resp, "Nanopub-Registry-Test-Instance"));
    }

    static String getHeader(HttpResponse resp, String name) {
        return resp.getFirstHeader(name) != null ? resp.getFirstHeader(name).getValue() : null;
    }

    static Long getHeaderLong(HttpResponse resp, String name) {
        String value = getHeader(resp, name);
        if (value == null || "null".equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            logger.debug("Failed to parse header {} value '{}' as long", name, value);
            return null;
        }
    }

}
