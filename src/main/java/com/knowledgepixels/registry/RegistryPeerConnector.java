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

        if (isTestInstance(resp)) {
            log.info("Skipping peer {} because it is a test instance", peerUrl);
            return;
        }

        String status = getHeader(resp, "Nanopub-Registry-Status");
        if (!"ready".equals(status) && !"updating".equals(status)) {
            log.info("Peer {} in non-ready state: {}", peerUrl, status);
            return;
        }

        Long peerSetupId = getHeaderLong(resp, "Nanopub-Registry-Setup-Id");
        // TODO(transition): Remove Load-Counter fallback after all peers upgraded
        Long peerSeqNum = getHeaderLong(resp, "Nanopub-Registry-SeqNum");
        if (peerSeqNum == null) {
            peerSeqNum = getHeaderLong(resp, "Nanopub-Registry-Load-Counter");
        }
        if (peerSetupId == null || peerSeqNum == null) {
            log.info("Peer {} missing setupId or seqNum headers", peerUrl);
            return;
        }

        syncWithPeer(s, peerUrl, peerSetupId, peerSeqNum);
    }

    static void syncWithPeer(ClientSession s, String peerUrl, long peerSetupId, long peerSeqNum) {
        Document peerState = getPeerState(s, peerUrl);
        Long lastSetupId = peerState != null ? peerState.getLong("setupId") : null;
        // TODO(transition): Remove loadCounter fallback after all peers upgraded
        Long lastSeqNum = peerState != null ? peerState.getLong("seqNum") : null;
        if (lastSeqNum == null && peerState != null) {
            lastSeqNum = peerState.getLong("loadCounter");
        }

        if (lastSetupId != null && !lastSetupId.equals(peerSetupId)) {
            log.info("Peer {} was reset (setupId changed), resetting tracking", peerUrl);
            deletePeerState(s, peerUrl);
            lastSeqNum = null;
        }

        long effectiveSeqNum = lastSeqNum != null ? lastSeqNum : 0;

        if (lastSeqNum != null && lastSeqNum.equals(peerSeqNum)) {
            log.info("Peer {} has no new nanopubs (seqNum unchanged: {})", peerUrl, peerSeqNum);
        } else if (lastSeqNum != null) {
            // Fetch all nanopubs added since our last known position.
            log.info("Peer {} has new nanopubs (seqNum {} -> {}), fetching recent", peerUrl, lastSeqNum, peerSeqNum);
            long lastReceived = loadRecentNanopubs(s, peerUrl, lastSeqNum);
            if (lastReceived > 0) {
                effectiveSeqNum = lastReceived;
            }
            // Only discover new pubkeys when the peer has new data
            discoverPubkeys(s, peerUrl);
        } else {
            log.info("Peer {} is new, pubkey discovery will handle initial sync", peerUrl);
            discoverPubkeys(s, peerUrl);
        }
        updatePeerState(s, peerUrl, peerSetupId, effectiveSeqNum);
    }

    /**
     * Fetches nanopubs from a peer after the given seqNum.
     * @return the seqNum of the last successfully received nanopub, or -1 if none were received
     */
    private static long loadRecentNanopubs(ClientSession s, String peerUrl, long afterSeqNum) {
        // TODO(transition): Remove afterCounter param after all peers upgraded
        String requestUrl = peerUrl + "nanopubs.jelly?afterSeqNum=" + afterSeqNum + "&afterCounter=" + afterSeqNum;
        log.info("Fetching recent nanopubs from: {}", requestUrl);
        AtomicLong lastReceivedCounter = new AtomicLong(-1);
        try {
            HttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
            int httpStatus = resp.getStatusLine().getStatusCode();
            if (httpStatus < 200 || httpStatus >= 300) {
                EntityUtils.consumeQuietly(resp.getEntity());
                log.info("Request failed: {} {}", requestUrl, httpStatus);
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
                            if (!CoverageFilter.isCovered(np)) return;
                            try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                                String pubkey = RegistryDB.getPubkey(np);
                                if (pubkey != null) {
                                    String pubkeyHash = Utils.getHash(pubkey);
                                    if (!AgentFilter.isAllowed(workerSession, pubkeyHash)) return;
                                    if (AgentFilter.isOverQuota(workerSession, pubkeyHash)) return;
                                    RegistryDB.loadNanopubVerified(workerSession, np, pubkey, null);
                                    NanopubLoader.simpleLoad(workerSession, np, pubkey);
                                }
                            }
                        });
            }
        } catch (IOException ex) {
            log.info("Failed to fetch recent nanopubs from {}: {}", peerUrl, ex.getMessage());
        }
        log.info("Last received counter from {}: {}", peerUrl, lastReceivedCounter.get());
        return lastReceivedCounter.get();
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

    static void updatePeerState(ClientSession s, String peerUrl, long setupId, long seqNum) {
        collection(Collection.PEER_STATE.toString()).updateOne(s,
                new Document("_id", peerUrl),
                new Document("$set", new Document("_id", peerUrl)
                        .append("setupId", setupId)
                        .append("seqNum", seqNum)
                        // TODO(transition): Remove loadCounter after all peers upgraded
                        .append("loadCounter", seqNum)
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
        if (value == null || "null".equals(value)) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
