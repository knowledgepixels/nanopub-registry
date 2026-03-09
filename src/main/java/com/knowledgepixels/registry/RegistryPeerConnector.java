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
import org.nanopub.Nanopub;
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

    private static final Logger log = LoggerFactory.getLogger(RegistryPeerConnector.class);

    public static void checkPeers(ClientSession s) {
        List<String> peerUrls = new ArrayList<>(Utils.getPeerUrls());
        Collections.shuffle(peerUrls);

        for (String peerUrl : peerUrls) {
            try {
                checkPeer(s, peerUrl);
            } catch (Exception ex) {
                log.info("Error checking peer {}: {}", peerUrl, ex.getMessage());
            }
        }
    }

    static void checkPeer(ClientSession s, String peerUrl) throws IOException {
        log.info("Checking peer: {}", peerUrl);

        HttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpHead(peerUrl));
        int httpStatus = resp.getStatusLine().getStatusCode();
        EntityUtils.consumeQuietly(resp.getEntity());
        if (httpStatus < 200 || httpStatus >= 300) {
            log.info("Failed to reach peer {}: {}", peerUrl, httpStatus);
            return;
        }

        String status = getHeader(resp, "Nanopub-Registry-Status");
        if (!"ready".equals(status) && !"updating".equals(status)) {
            log.info("Peer {} in non-ready state: {}", peerUrl, status);
            return;
        }

        Long peerSetupId = getHeaderLong(resp, "Nanopub-Registry-Setup-Id");
        Long peerLoadCounter = getHeaderLong(resp, "Nanopub-Registry-Load-Counter");
        if (peerSetupId == null || peerLoadCounter == null) {
            log.info("Peer {} missing setupId or loadCounter headers", peerUrl);
            return;
        }

        syncWithPeer(s, peerUrl, peerSetupId, peerLoadCounter);
    }

    static void syncWithPeer(ClientSession s, String peerUrl, long peerSetupId, long peerLoadCounter) {
        Document peerState = getPeerState(s, peerUrl);
        Long lastSetupId = peerState != null ? peerState.getLong("setupId") : null;
        Long lastLoadCounter = peerState != null ? peerState.getLong("loadCounter") : null;
        Boolean fullFetchDone = peerState != null ? peerState.getBoolean("fullFetchDone") : null;

        if (lastSetupId != null && !lastSetupId.equals(peerSetupId)) {
            log.info("Peer {} was reset (setupId changed), resetting tracking", peerUrl);
            deletePeerState(s, peerUrl);
            lastLoadCounter = null;
            fullFetchDone = null;
        }

        if (lastLoadCounter != null && lastLoadCounter.equals(peerLoadCounter)) {
            log.info("Peer {} has no new nanopubs (loadCounter unchanged: {})", peerUrl, peerLoadCounter);
        } else if (lastLoadCounter != null) {
            // Fetch all nanopubs added since our last known position.
            // This works for any delta size; the full fetch covers the first-sync case.
            // TODO Add per-pubkey afterCounter tracking for more targeted incremental sync
            long delta = peerLoadCounter - lastLoadCounter;
            log.info("Peer {} has {} new nanopubs, fetching recent", peerUrl, delta);
            loadRecentNanopubs(s, peerUrl, lastLoadCounter);
        } else {
            log.info("Peer {} is new, full fetch will handle initial sync", peerUrl);
        }

        // TODO Remove full fetch once incremental sync covers all nanopubs (including non-approved pubkeys)
        boolean fullFetchSucceeded = fullFetchDone != null && fullFetchDone;
        if (!fullFetchSucceeded) {
            Long fullFetchPosition = peerState != null ? peerState.getLong("fullFetchPosition") : null;
            long afterCounter = fullFetchPosition != null ? fullFetchPosition : -1;
            fullFetchSucceeded = loadAllNanopubs(s, peerUrl, afterCounter);
        }

        discoverPubkeys(s, peerUrl);
        updatePeerState(s, peerUrl, peerSetupId, peerLoadCounter, fullFetchSucceeded);
    }

    private static boolean loadAllNanopubs(ClientSession s, String peerUrl, long afterCounter) {
        String requestUrl = peerUrl + "nanopubs.jelly?afterCounter=" + afterCounter;
        log.info("Full fetch of all nanopubs from: {} (resuming after counter {})", requestUrl, afterCounter);
        AtomicLong lastCounter = new AtomicLong(afterCounter);
        boolean completed = false;
        try {
            HttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
            int httpStatus = resp.getStatusLine().getStatusCode();
            if (httpStatus < 200 || httpStatus >= 300) {
                EntityUtils.consumeQuietly(resp.getEntity());
                log.info("Request failed: {} {}", requestUrl, httpStatus);
                return false;
            }
            // Use a dedicated session outside any wrapping transaction to avoid
            // MongoDB transaction timeout on large streams.
            try (InputStream is = resp.getEntity().getContent();
                 ClientSession loadSession = RegistryDB.getClient().startSession()) {
                NanopubStream.fromByteStream(is).getAsNanopubs().forEach(m -> {
                    if (m.isSuccess()) {
                        Nanopub np = m.getNanopub();
                        RegistryDB.loadNanopub(loadSession, np);
                        NanopubLoader.simpleLoad(loadSession, np);
                    }
                    if (m.getCounter() > 0) {
                        lastCounter.set(m.getCounter());
                    }
                });
            }
            completed = true;
            return true;
        } catch (IOException ex) {
            log.info("Failed to fetch all nanopubs from {}: {}", peerUrl, ex.getMessage());
            return false;
        } finally {
            if (!completed && lastCounter.get() > afterCounter) {
                log.info("Full fetch interrupted at counter {}; saving position for resume", lastCounter.get());
                saveFullFetchPosition(s, peerUrl, lastCounter.get());
            }
        }
    }

    private static void saveFullFetchPosition(ClientSession s, String peerUrl, long position) {
        collection(Collection.PEER_STATE.toString()).updateOne(s,
                new Document("_id", peerUrl),
                new Document("$set", new Document("fullFetchPosition", position)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    private static void loadRecentNanopubs(ClientSession s, String peerUrl, long afterCounter) {
        String requestUrl = peerUrl + "nanopubs.jelly?afterCounter=" + afterCounter;
        log.info("Fetching recent nanopubs from: {}", requestUrl);
        try {
            HttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
            int httpStatus = resp.getStatusLine().getStatusCode();
            if (httpStatus < 200 || httpStatus >= 300) {
                EntityUtils.consumeQuietly(resp.getEntity());
                log.info("Request failed: {} {}", requestUrl, httpStatus);
                return;
            }
            try (InputStream is = resp.getEntity().getContent()) {
                NanopubStream.fromByteStream(is).getAsNanopubs().forEach(m -> {
                    if (m.isSuccess()) {
                        Nanopub np = m.getNanopub();
                        RegistryDB.loadNanopub(s, np);
                        NanopubLoader.simpleLoad(s, np);
                    }
                });
            }
        } catch (IOException ex) {
            log.info("Failed to fetch recent nanopubs from {}: {}", peerUrl, ex.getMessage());
        }
    }

    static void discoverPubkeys(ClientSession s, String peerUrl) {
        log.info("Discovering pubkeys from peer: {}", peerUrl);
        try {
            List<String> peerPubkeys = Utils.retrieveListFromJsonUrl(peerUrl + "pubkeys.json");
            int discovered = 0;
            for (String pubkeyHash : peerPubkeys) {
                Document filter = new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH);
                if (!has(s, "lists", filter)) {
                    try {
                        insert(s, "lists", new Document("pubkey", pubkeyHash)
                                .append("type", NanopubLoader.INTRO_TYPE_HASH)
                                .append("status", EntryStatus.encountered.getValue()));
                    } catch (MongoWriteException e) {
                        if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw e;
                    }
                    discovered++;
                } else if (!has(s, "lists", new Document(filter).append("status", EntryStatus.loaded.getValue()))) {
                    // Set status to encountered if not already loaded (fixes null-status entries from older code)
                    collection("lists").updateMany(s, filter,
                            new Document("$set", new Document("status", EntryStatus.encountered.getValue())));
                    discovered++;
                }
            }
            log.info("Discovered {} new pubkeys from peer {}", discovered, peerUrl);
        } catch (Exception ex) {
            log.info("Failed to discover pubkeys from {}: {}", peerUrl, ex.getMessage());
        }
    }

    static Document getPeerState(ClientSession s, String peerUrl) {
        try (MongoCursor<Document> cursor = collection(Collection.PEER_STATE.toString())
                .find(s, new Document("_id", peerUrl)).cursor()) {
            return cursor.hasNext() ? cursor.next() : null;
        }
    }

    static void updatePeerState(ClientSession s, String peerUrl, long setupId, long loadCounter, boolean fullFetchDone) {
        collection(Collection.PEER_STATE.toString()).updateOne(s,
                new Document("_id", peerUrl),
                new Document("$set", new Document("_id", peerUrl)
                        .append("setupId", setupId)
                        .append("loadCounter", loadCounter)
                        .append("fullFetchDone", fullFetchDone)
                        .append("lastChecked", System.currentTimeMillis())),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    static void deletePeerState(ClientSession s, String peerUrl) {
        collection(Collection.PEER_STATE.toString()).deleteOne(s, new Document("_id", peerUrl));
    }

    static String getHeader(HttpResponse resp, String name) {
        return resp.getFirstHeader(name) != null ? resp.getFirstHeader(name).getValue() : null;
    }

    static Long getHeaderLong(HttpResponse resp, String name) {
        String value = getHeader(resp, name);
        if (value == null || "null".equals(value)) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
