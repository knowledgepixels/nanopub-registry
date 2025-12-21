package com.knowledgepixels.registry;

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
import java.util.stream.Stream;

import static com.knowledgepixels.registry.RegistryDB.has;

public class NanopubLoader {

    private NanopubLoader() {
    }

    public final static String INTRO_TYPE = NPX.DECLARED_BY.stringValue();
    public final static String INTRO_TYPE_HASH = Utils.getHash(INTRO_TYPE);
    public final static String ENDORSE_TYPE = Utils.APPROVES_OF.stringValue();
    public final static String ENDORSE_TYPE_HASH = Utils.getHash(ENDORSE_TYPE);
    private static final Logger log = LoggerFactory.getLogger(NanopubLoader.class);

    // TODO Distinguish and support these cases:
    //      1. Simple load: load to all core lists if pubkey is "core-loaded", or load to all lists if pubkey is "full-loaded"
    //      2. Core load: load to all core lists (initialize if needed), or load to all lists if pubkey is "full-loaded"
    //      3. Full load: load to all lists (initialize if needed)

    public static void simpleLoad(ClientSession mongoSession, String nanopubId) {
        simpleLoad(mongoSession, retrieveNanopub(mongoSession, nanopubId));
    }

    public static void simpleLoad(ClientSession mongoSession, Nanopub np) {
        String pubkey = RegistryDB.getPubkey(np);
        if (pubkey == null) {
            log.info("Ignore (not signed): {}", np.getUri());
            return;
        }
        String pubkeyHash = Utils.getHash(pubkey);
        // TODO Do we need to load anything else here, into the other DB collections?
        if (has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("status", "loaded"))) {
            RegistryDB.loadNanopub(mongoSession, np, pubkeyHash, "$");
        } else if (has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH).append("status", "loaded"))) {
            RegistryDB.loadNanopub(mongoSession, np, pubkeyHash, INTRO_TYPE, ENDORSE_TYPE);
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
        // TODO Move the code of this method to nanopub-java library.

        List<String> peerUrlsToTry = new ArrayList<>(Utils.getPeerUrls());
        Collections.shuffle(peerUrlsToTry);
        while (!peerUrlsToTry.isEmpty()) {
            String peerUrl = peerUrlsToTry.removeFirst();

            String requestUrl = peerUrl + "list/" + pubkeyHash + "/" + typeHash + ".jelly";
            log.info("Request: {}", requestUrl);
            try {
                CloseableHttpResponse resp = NanopubUtils.getHttpClient().execute(new HttpGet(requestUrl));
                int httpStatus = resp.getStatusLine().getStatusCode();
                if (httpStatus < 200 || httpStatus >= 300) {
                    log.info("Request failed: {} {}", peerUrl, httpStatus);
                    EntityUtils.consumeQuietly(resp.getEntity());
                    continue;
                }
                Header nrStatus = resp.getFirstHeader("Nanopub-Registry-Status");
                if (nrStatus == null) {
                    log.info("Nanopub-Registry-Status header not found at: {}", peerUrl);
                    EntityUtils.consumeQuietly(resp.getEntity());
                    continue;
                } else if (!nrStatus.getValue().equals("ready") && !nrStatus.getValue().equals("updating")) {
                    log.info("Peer in non-ready state: {} {}", peerUrl, nrStatus.getValue());
                    EntityUtils.consumeQuietly(resp.getEntity());
                    continue;
                }
                InputStream is = resp.getEntity().getContent();
                return NanopubStream.fromByteStream(is).getAsNanopubs();
            } catch (UnsupportedOperationException | IOException ex) {
                log.info("Request failed: ", ex);
            }
        }
        return Stream.empty();
    }

    public static Nanopub retrieveNanopub(ClientSession mongoSession, String nanopubId) {
        Nanopub np = retrieveLocalNanopub(mongoSession, nanopubId);
        int tryCount = 0;
        while (np == null) {
            if (tryCount > 10) {
                throw new RuntimeException("Could not load nanopub: " + nanopubId);
            } else if (tryCount > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    log.info("Thread was interrupted", ex);
                }
            }
            log.info("Loading {}", nanopubId);

            // TODO Reach out to other Nanopub Registries here:
            np = getNanopub(nanopubId);
            if (np != null) {
                RegistryDB.loadNanopub(mongoSession, np);
            } else {
                tryCount = tryCount + 1;
            }
        }
        return np;
    }

    public static Nanopub retrieveLocalNanopub(ClientSession mongoSession, String nanopubId) {
        String ac = TrustyUriUtils.getArtifactCode(nanopubId);
        MongoCursor<Document> cursor = RegistryDB.get(mongoSession, "nanopubs", new Document("_id", ac));
        if (!cursor.hasNext()) return null;
        try {
            // Parse from Jelly, not TriG (it's faster)
            return JellyUtils.readFromDB(((Binary) cursor.next().get("jelly")).getData());
        } catch (RDF4JException | MalformedNanopubException ex) {
            log.info("Exception reading Jelly", ex);
            return null;
        }
    }

    // TODO Provide this method in nanopub-java (GetNanopub)
    private static Nanopub getNanopub(String uriOrArtifactCode) {
        List<String> peerUrls = new ArrayList<>(Utils.getPeerUrls());
        Collections.shuffle(peerUrls);
        String ac = GetNanopub.getArtifactCode(uriOrArtifactCode);
        if (!ac.startsWith(RdfModule.MODULE_ID)) {
            throw new IllegalArgumentException("Not a trusty URI of type RA");
        }
        while (!peerUrls.isEmpty()) {
            String peerUrl = peerUrls.removeFirst();
            try {
                Nanopub np = get(ac, peerUrl, NanopubUtils.getHttpClient());
                if (np != null) {
                    return np;
                }
            } catch (IOException | RDF4JException | MalformedNanopubException ex) {
                // ignore
            }
        }
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
                throw new IOException(resp.getStatusLine().toString());
            }
            in = resp.getEntity().getContent();
            Nanopub nanopub = new NanopubImpl(in, RDFFormat.TRIG);
            if (!TrustyNanopubUtils.isValidTrustyNanopub(nanopub)) {
                throw new MalformedNanopubException("Nanopub is not trusty");
            }
            return nanopub;
        } finally {
            if (in != null) in.close();
        }
    }

    private static boolean wasSuccessful(HttpResponse resp) {
        int c = resp.getStatusLine().getStatusCode();
        return c >= 200 && c < 300;
    }

}
