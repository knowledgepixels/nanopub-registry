package com.knowledgepixels.registry;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import net.trustyuri.TrustyUriUtils;
import net.trustyuri.rdf.RdfModule;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.jelly.JellyUtils;
import org.nanopub.jelly.MaybeNanopub;
import org.nanopub.jelly.NanopubStream;
import org.nanopub.trusty.TrustyNanopubUtils;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.insert;

public class NanopubLoader {

    private NanopubLoader() {
    }

    public final static String INTRO_TYPE = NPX.DECLARED_BY.stringValue();
    public final static String INTRO_TYPE_HASH = Utils.getHash(INTRO_TYPE);
    public final static String ENDORSE_TYPE = Utils.APPROVES_OF.stringValue();
    public final static String ENDORSE_TYPE_HASH = Utils.getHash(ENDORSE_TYPE);
    private static final Logger logger = LoggerFactory.getLogger(NanopubLoader.class);

    // TODO Distinguish and support these cases:
    //      1. Simple load: load to all core lists if pubkey is "core-loaded", or load to all lists if pubkey is "full-loaded"
    //      2. Core load: load to all core lists (initialize if needed), or load to all lists if pubkey is "full-loaded"
    //      3. Full load: load to all lists (initialize if needed)

    public static void simpleLoad(ClientSession mongoSession, String nanopubId) {
        simpleLoad(mongoSession, nanopubId, true);
    }

    public static void simpleLoad(ClientSession mongoSession, String nanopubId, boolean persistOnRetrieve) {
        if (persistOnRetrieve) {
            simpleLoad(mongoSession, retrieveNanopub(mongoSession, nanopubId));
        } else {
            Nanopub np = retrieveLocalNanopub(mongoSession, nanopubId);
            if (np == null) {
                logger.debug("Nanopub {} not found locally; fetching from peers without persisting on retrieve", nanopubId);
                np = getNanopub(nanopubId);
            }
            if (np != null) {
                simpleLoad(mongoSession, np);
            } else {
                logger.warn("Could not retrieve nanopub {} from any peer; skipping load", nanopubId);
            }
        }
    }

    public static void simpleLoad(ClientSession mongoSession, Nanopub np) {
        String pubkey = RegistryDB.getPubkey(np);
        if (pubkey == null) {
            logger.warn("Skipping load of nanopub {}: no valid signature found, so its public key could not be determined", np.getUri());
            return;
        }
        simpleLoad(mongoSession, np, pubkey);
    }

    /**
     * Loads a nanopub to the appropriate lists, using a pre-verified public key
     * to skip redundant signature verification.
     */
    public static void simpleLoad(ClientSession mongoSession, Nanopub np, String verifiedPubkey) {
        String pubkeyHash = Utils.getHash(verifiedPubkey);
        // TODO Do we need to load anything else here, into the other DB collections?
        if (has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("status", "loaded"))) {
            logger.debug("Loading nanopub {} into full-loaded lists for pubkey {}", np.getUri(), pubkeyHash);
            RegistryDB.loadNanopubVerified(mongoSession, np, verifiedPubkey, pubkeyHash, "$");
        } else if (has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH).append("status", "loaded"))) {
            logger.debug("Loading nanopub {} into core lists (intro/endorse) for pubkey {}", np.getUri(), pubkeyHash);
            RegistryDB.loadNanopubVerified(mongoSession, np, verifiedPubkey, pubkeyHash, INTRO_TYPE, ENDORSE_TYPE);
        } else {
            // Pubkey not yet loaded (unknown or in transitional "encountered" state): store the
            // nanopub in the nanopubs collection so it is not lost. RUN_OPTIONAL_LOAD will add it
            // to the appropriate lists once the pubkey's intro/endorse have been fetched.
            logger.debug("Pubkey {} not yet loaded; storing nanopub {} without adding it to any list yet", pubkeyHash, np.getUri());
            RegistryDB.loadNanopubVerified(mongoSession, np, verifiedPubkey, null);
            if (!has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH))) {
                // Unknown pubkey: create encountered intro list so RUN_OPTIONAL_LOAD picks it up
                try {
                    logger.info("Encountered new pubkey {}; creating intro list entry so it can be processed by RUN_OPTIONAL_LOAD", pubkeyHash);
                    insert(mongoSession, "lists", new Document("pubkey", pubkeyHash)
                            .append("type", INTRO_TYPE_HASH)
                            .append("status", EntryStatus.encountered.getValue()));
                } catch (MongoWriteException e) {
                    if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                        throw e;
                    }
                    logger.debug("Intro list entry for pubkey {} was already created concurrently; ignoring duplicate-key error", pubkeyHash);
                }
            }
        }
    }

    private static final int LOAD_PARALLELISM = Integer.parseInt(
            Utils.getEnv("REGISTRY_LOAD_PARALLELISM", String.valueOf(Runtime.getRuntime().availableProcessors())));

    /**
     * Processes a stream of nanopubs in parallel using a thread pool.
     * Each worker thread uses its own MongoDB ClientSession.
     * Backpressure is applied via a semaphore to avoid unbounded memory growth.
     *
     * @param stream    the nanopub stream to process
     * @param processor consumer that processes each nanopub (called with its own ClientSession)
     */
    public static void loadStreamInParallel(Stream<MaybeNanopub> stream, Consumer<Nanopub> processor) {
        if (LOAD_PARALLELISM <= 1) {
            // Fall back to sequential processing
            logger.debug("REGISTRY_LOAD_PARALLELISM={}; processing nanopub stream sequentially", LOAD_PARALLELISM);
            stream.forEach(m -> {
                if (!m.isSuccess()) {
                    logger.error("Failed to download a nanopub from the stream; aborting task");
                    throw new AbortingTaskException("Failed to download nanopub; aborting task...");
                }
                processor.accept(m.getNanopub());
            });
            return;
        }

        logger.debug("Processing nanopub stream in parallel with {} worker threads", LOAD_PARALLELISM);
        ExecutorService executor = Executors.newFixedThreadPool(LOAD_PARALLELISM);
        Semaphore semaphore = new Semaphore(LOAD_PARALLELISM * 2);
        AtomicReference<Exception> error = new AtomicReference<>();

        try {
            stream.forEach(m -> {
                if (error.get() != null) {
                    return;
                }
                if (!m.isSuccess()) {
                    logger.error("Failed to download a nanopub from the stream; aborting remaining work");
                    error.compareAndSet(null, new AbortingTaskException("Failed to download nanopub; aborting task..."));
                    return;
                }
                Nanopub np = m.getNanopub();
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while waiting for a free worker slot; aborting parallel load", e);
                    error.compareAndSet(null, e);
                    return;
                }
                executor.submit(() -> {
                    try {
                        processor.accept(np);
                    } catch (Exception e) {
                        logger.error("Worker thread failed while processing nanopub {}: {}", np.getUri(), e.getMessage(), e);
                        error.compareAndSet(null, e);
                    } finally {
                        semaphore.release();
                    }
                });
            });
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                    logger.warn("Worker pool did not terminate within the 1-hour timeout after shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for worker pool to terminate", e);
            }
        }

        if (error.get() != null) {
            logger.error("Parallel nanopub loading failed: {}", error.get().getMessage());
            if (error.get() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Parallel loading failed", error.get());
        }
    }

    /**
     * Retrieve Nanopubs from the peers of this Nanopub Registry.
     *
     * @param typeHash   The hash of the type of the Nanopub to retrieve.
     * @param pubkeyHash The hash of the pubkey of the Nanopub to retrieve.
     * @return A stream of MaybeNanopub objects, or an empty stream if no peer is available.
     */
    public static Stream<MaybeNanopub> retrieveNanopubsFromPeers(String typeHash, String pubkeyHash) {
        return retrieveNanopubsFromPeers(typeHash, pubkeyHash, null);
    }

    /**
     * Retrieve Nanopubs from the peers, optionally skipping ahead using checksums.
     *
     * @param typeHash       The hash of the type of the Nanopub to retrieve.
     * @param pubkeyHash     The hash of the pubkey of the Nanopub to retrieve.
     * @param afterChecksums Comma-separated checksums for skip-ahead (geometric fallback), or null for full fetch.
     * @return A stream of MaybeNanopub objects, or an empty stream if no peer is available.
     */
    public static Stream<MaybeNanopub> retrieveNanopubsFromPeers(String typeHash, String pubkeyHash, String afterChecksums) {
        // TODO Move the code of this method to nanopub-java library.

        List<String> peerUrlsToTry = new ArrayList<>(Utils.getPeerUrls());
        Collections.shuffle(peerUrlsToTry);
        if (peerUrlsToTry.isEmpty()) {
            logger.warn("No peers configured; cannot retrieve nanopub list for pubkey {} / type {}", pubkeyHash, typeHash);
        }
        while (!peerUrlsToTry.isEmpty()) {
            String peerUrl = peerUrlsToTry.removeFirst();

            String requestUrl = peerUrl + "list/" + pubkeyHash + "/" + typeHash + ".jelly";
            if (afterChecksums != null) {
                requestUrl += "?afterChecksums=" + afterChecksums;
            }
            logger.debug("Fetching nanopub list from peer: {}", requestUrl);
            try {
                CloseableHttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
                int httpStatus = resp.getStatusLine().getStatusCode();
                if (httpStatus < 200 || httpStatus >= 300) {
                    logger.warn("Peer {} returned HTTP {} for nanopub list request {}; trying next peer", peerUrl, httpStatus, requestUrl);
                    EntityUtils.consumeQuietly(resp.getEntity());
                    continue;
                }
                Header nrStatus = resp.getFirstHeader("Nanopub-Registry-Status");
                if (nrStatus == null) {
                    logger.warn("Peer {} did not return a Nanopub-Registry-Status header for {}; trying next peer", peerUrl, requestUrl);
                    EntityUtils.consumeQuietly(resp.getEntity());
                    continue;
                } else if (!nrStatus.getValue().equals("ready") && !nrStatus.getValue().equals("updating")) {
                    logger.warn("Skipping peer {}: registry status is '{}' (expected 'ready' or 'updating'); trying next peer", peerUrl, nrStatus.getValue());
                    EntityUtils.consumeQuietly(resp.getEntity());
                    continue;
                }
                logger.debug("Successfully fetched nanopub list from peer {} (status: {})", peerUrl, nrStatus.getValue());
                InputStream is = resp.getEntity().getContent();
                return NanopubStream.fromByteStream(is).getAsNanopubs().onClose(() -> {
                    try {
                        resp.close();
                    } catch (IOException e) {
                        logger.debug("Error closing HTTP response from peer {}", peerUrl, e);
                    }
                });
            } catch (UnsupportedOperationException | IOException ex) {
                logger.warn("Failed to fetch nanopub list from peer {} ({}): {}", peerUrl, requestUrl, ex.getMessage(), ex);
            }
        }
        logger.warn("Exhausted all peers without successfully retrieving nanopub list for pubkey {} / type {}", pubkeyHash, typeHash);
        return Stream.empty();
    }

    public static Nanopub retrieveNanopub(ClientSession mongoSession, String nanopubId) {
        Nanopub np = retrieveLocalNanopub(mongoSession, nanopubId);
        int tryCount = 0;
        while (np == null) {
            if (tryCount > 10) {
                logger.error("Giving up on retrieving nanopub {} after {} attempts", nanopubId, tryCount);
                throw new RuntimeException("Could not load nanopub: " + nanopubId);
            } else if (tryCount > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    logger.warn("Thread interrupted while waiting to retry nanopub retrieval for {}", nanopubId, ex);
                }
            }
            logger.info("Nanopub {} not found locally; fetching from peers (attempt {} of 10)", nanopubId, tryCount + 1);

            // TODO Reach out to other Nanopub Registries here:
            np = getNanopub(nanopubId);
            if (np != null) {
                logger.debug("Retrieved nanopub {} from a peer; persisting it locally", nanopubId);
                RegistryDB.loadNanopub(mongoSession, np);
            } else {
                logger.debug("Attempt {} to retrieve nanopub {} from peers failed", tryCount + 1, nanopubId);
                tryCount = tryCount + 1;
            }
        }
        return np;
    }

    public static Nanopub retrieveLocalNanopub(ClientSession mongoSession, String nanopubId) {
        String ac = TrustyUriUtils.getArtifactCode(nanopubId);
        MongoCursor<Document> cursor = RegistryDB.get(mongoSession, Collection.NANOPUBS.toString(), new Document("_id", ac));
        if (!cursor.hasNext()) {
            return null;
        }
        try {
            // Parse from Jelly, not TriG (it's faster)
            return JellyUtils.readFromDB(((Binary) cursor.next().get("jelly")).getData());
        } catch (RDF4JException | MalformedNanopubException ex) {
            logger.error("Failed to parse locally stored Jelly content for nanopub '{}'; treating it as missing", nanopubId, ex);
            return null;
        }
    }

    // TODO Provide this method in nanopub-java (GetNanopub)
    private static Nanopub getNanopub(String uriOrArtifactCode) {
        List<String> peerUrls = new ArrayList<>(Utils.getPeerUrls());
        Collections.shuffle(peerUrls);
        String ac = GetNanopub.getArtifactCode(uriOrArtifactCode).toString();
        if (!ac.startsWith(RdfModule.MODULE_ID)) {
            throw new IllegalArgumentException("Not a trusty URI of type RA");
        }
        if (peerUrls.isEmpty()) {
            logger.warn("No peers configured; cannot fetch nanopub {}", ac);
        }
        while (!peerUrls.isEmpty()) {
            String peerUrl = peerUrls.removeFirst();
            try {
                Nanopub np = get(ac, peerUrl, NanopubUtils.getHttpClient());
                if (np != null) {
                    logger.debug("Successfully fetched nanopub {} from peer {}", ac, peerUrl);
                    return np;
                }
            } catch (IOException | RDF4JException | MalformedNanopubException ex) {
                logger.debug("Failed to fetch nanopub {} from peer {}: {}", ac, peerUrl, ex.getMessage(), ex);
            }
        }
        logger.warn("Could not fetch nanopub {} from any of the {} configured peer(s)", ac, Utils.getPeerUrls().size());
        return null;
    }

    // TODO Provide this method in nanopub-java (GetNanopub)
    private static Nanopub get(String artifactCode, String registryUrl, HttpClient httpClient)
            throws IOException, RDF4JException, MalformedNanopubException {
        HttpGet get = null;
        // TODO Get in Jelly format:
        String getUrl = registryUrl + "np/" + artifactCode;
        try {
            get = new HttpGet(getUrl);
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid URL: " + getUrl);
        }
        get.setHeader("Accept", "application/trig");
        InputStream in = null;
        try {
            HttpResponse resp = httpClient.execute(get);
            if (!wasSuccessful(resp)) {
                EntityUtils.consumeQuietly(resp.getEntity());
                throw new IOException("Request to " + getUrl + " failed: " + resp.getStatusLine());
            }
            in = resp.getEntity().getContent();
            Nanopub nanopub = new NanopubImpl(in, RDFFormat.TRIG);
            if (!TrustyNanopubUtils.isValidTrustyNanopub(nanopub)) {
                throw new MalformedNanopubException("Nanopub retrieved from " + registryUrl + " is not a valid trusty nanopub");
            }
            return nanopub;
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    private static boolean wasSuccessful(HttpResponse resp) {
        int c = resp.getStatusLine().getStatusCode();
        return c >= 200 && c < 300;
    }

}