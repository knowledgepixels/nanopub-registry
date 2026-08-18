package com.knowledgepixels.registry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.JellyUtils;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryPeerConnector.checkPeer;
import static com.knowledgepixels.registry.RegistryPeerConnector.checkPeers;
import static com.knowledgepixels.registry.RegistryPeerConnector.deletePeerState;
import static com.knowledgepixels.registry.RegistryPeerConnector.discoverPubkeys;
import static com.knowledgepixels.registry.RegistryPeerConnector.getHeader;
import static com.knowledgepixels.registry.RegistryPeerConnector.getHeaderLong;
import static com.knowledgepixels.registry.RegistryPeerConnector.getPeerState;
import static com.knowledgepixels.registry.RegistryPeerConnector.isTestInstance;
import static com.knowledgepixels.registry.RegistryPeerConnector.syncWithPeer;
import static com.knowledgepixels.registry.RegistryPeerConnector.updatePeerState;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;

import eu.neverblink.jelly.core.utils.IoUtils;

class RegistryPeerConnectorTest {

    @Nested
    class HeaderHelperTests {

        private HttpResponse makeResponse(String... headers) {
            HttpResponse resp = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
            for (int i = 0; i < headers.length; i += 2) {
                resp.setHeader(headers[i], headers[i + 1]);
            }
            return resp;
        }

        @Test
        void getHeader_returnsValue() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Status", "ready");
            assertEquals("ready", getHeader(resp, "Nanopub-Registry-Status"));
        }

        @Test
        void getHeader_returnsNullForMissingHeader() {
            HttpResponse resp = makeResponse();
            assertNull(getHeader(resp, "Nanopub-Registry-Status"));
        }

        @Test
        void getHeaderLong_returnsValue() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Load-Counter", "42000");
            assertEquals(42000L, getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
        }

        @Test
        void getHeaderLong_returnsNullForMissingHeader() {
            HttpResponse resp = makeResponse();
            assertNull(getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
        }

        @Test
        void getHeaderLong_returnsNullForNullValue() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Load-Counter", "null");
            assertNull(getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
        }

        @Test
        void getHeaderLong_returnsNullForInvalidNumber() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Load-Counter", "notanumber");
            assertNull(getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
        }

        @Test
        void isTestInstance_returnsTrueWhenHeaderIsTrue() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Test-Instance", "true");
            assertTrue(isTestInstance(resp));
        }

        @Test
        void isTestInstance_returnsFalseWhenHeaderIsFalse() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Test-Instance", "false");
            assertFalse(isTestInstance(resp));
        }

        @Test
        void isTestInstance_returnsFalseWhenHeaderMissing() {
            HttpResponse resp = makeResponse();
            assertFalse(isTestInstance(resp));
        }
    }

    @Nested
    @Testcontainers
    class PeerStateTests {

        private FakeEnv fakeEnv;
        private ClientSession session;

        @Container
        private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

        @BeforeEach
        void setUp() throws Exception {
            fakeEnv = TestUtils.setupFakeEnv();
            TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistryTest");
            TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
            RegistryDB.init();
            session = RegistryDB.getClient().startSession();
        }

        @AfterEach
        void tearDown() throws Exception {
            if (session != null) {
                session.close();
            }
            TestUtils.cleanupDataDir();
            fakeEnv.reset();
        }

        @Test
        void getPeerState_returnsNullForUnknownPeer() {
            assertNull(getPeerState(session, "https://unknown.example.com/"));
        }

        @Test
        void updatePeerState_createsPeerState() {
            updatePeerState(session, "https://peer.example.com/", 123L, 42000L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertNotNull(state);
            assertEquals(123L, state.getLong("setupId"));
            assertEquals(42000L, state.getLong("loadCounter"));
            assertNotNull(state.getLong("lastChecked"));
        }

        @Test
        void updatePeerState_updatesExistingState() {
            updatePeerState(session, "https://peer.example.com/", 123L, 100L);
            updatePeerState(session, "https://peer.example.com/", 123L, 200L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertEquals(200L, state.getLong("loadCounter"));
            assertEquals(1, collection(Collection.PEER_STATE.toString()).countDocuments(session));
        }

        @Test
        void deletePeerState_removesState() {
            updatePeerState(session, "https://peer.example.com/", 123L, 42000L);
            assertNotNull(getPeerState(session, "https://peer.example.com/"));

            deletePeerState(session, "https://peer.example.com/");
            assertNull(getPeerState(session, "https://peer.example.com/"));
        }

        @Test
        void syncWithPeer_skipsWhenLoadCounterUnchanged() {
            updatePeerState(session, "https://peer.example.com/", 123L, 500L);

            syncWithPeer(session, "https://peer.example.com/", 123L, 500L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertEquals(500L, state.getLong("loadCounter"));
        }

        @Test
        void syncWithPeer_resetsOnSetupIdChange() {
            updatePeerState(session, "https://peer.example.com/", 100L, 500L);

            // Sync with a different setupId — should reset and treat as new peer
            // This will try to load by pubkeys (which will find none), then update state
            syncWithPeer(session, "https://peer.example.com/", 200L, 600L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertEquals(200L, state.getLong("setupId"));
            // After reset, no nanopubs were actually received, so loadCounter stays at 0
            assertEquals(0L, state.getLong("loadCounter"));
        }

        @Test
        void syncWithPeer_updatesStateAfterSync() {
            // First time seeing this peer (no prior state)
            syncWithPeer(session, "https://peer.example.com/", 123L, 42000L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertNotNull(state);
            assertEquals(123L, state.getLong("setupId"));
            // No nanopubs were actually received, so loadCounter reflects that
            assertEquals(0L, state.getLong("loadCounter"));
        }

        /**
         * Publishes a pubkeys.json for a fake peer and returns a peer URL
         * pointing at it, so discoverPubkeys performs its real fetch instead of
         * failing on the network.
         */
        private String peerServing(Path dir, String... pubkeyHashes) throws Exception {
            String json = Arrays.stream(pubkeyHashes)
                    .map(h -> "\"" + h + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            Files.writeString(dir.resolve("pubkeys.json"), json);
            return dir.toUri().toString();
        }

        private Document introList(String pubkeyHash) {
            return collection("lists").find(session,
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)).first();
        }

        @Test
        void discoverPubkeys_createsEncounteredIntroLists(@TempDir Path dir) throws Exception {
            String peerUrl = peerServing(dir, "newPubkey123", "newPubkey456");

            discoverPubkeys(session, peerUrl);

            // Every pubkey the peer knows about becomes a candidate for optional loading.
            assertEquals(EntryStatus.encountered.getValue(), introList("newPubkey123").getString("status"));
            assertEquals(EntryStatus.encountered.getValue(), introList("newPubkey456").getString("status"));
        }

        @Test
        void discoverPubkeys_leavesLoadedPubkeysAlone(@TempDir Path dir) throws Exception {
            String pubkeyHash = "existingPubkey";
            collection("lists").insertOne(session,
                    new Document("pubkey", pubkeyHash)
                            .append("type", NanopubLoader.INTRO_TYPE_HASH)
                            .append("status", EntryStatus.loaded.getValue()));
            String peerUrl = peerServing(dir, pubkeyHash);

            discoverPubkeys(session, peerUrl);

            // Already loaded: rediscovering it must not send it back through core loading.
            assertEquals(EntryStatus.loaded.getValue(), introList(pubkeyHash).getString("status"));
            assertEquals(1, collection("lists").countDocuments(session,
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)));
        }

        @Test
        void discoverPubkeys_repairsListsLeftWithoutAStatus(@TempDir Path dir) throws Exception {
            // Older code left intro lists with no status at all; rediscovery repairs them.
            String pubkeyHash = "statuslessPubkey";
            collection("lists").insertOne(session,
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH));
            String peerUrl = peerServing(dir, pubkeyHash);

            discoverPubkeys(session, peerUrl);

            assertEquals(EntryStatus.encountered.getValue(), introList(pubkeyHash).getString("status"));
        }

        @Test
        void discoverPubkeys_isIdempotent(@TempDir Path dir) throws Exception {
            String peerUrl = peerServing(dir, "racePubkey");

            discoverPubkeys(session, peerUrl);
            discoverPubkeys(session, peerUrl);

            // Discovery runs on every sync, so a repeat must not duplicate or throw.
            assertEquals(1, collection("lists").countDocuments(session,
                    new Document("pubkey", "racePubkey").append("type", NanopubLoader.INTRO_TYPE_HASH)));
        }

        @Test
        void discoverPubkeys_survivesAnUnreachablePeer() {
            // A peer that cannot be reached must not abort the surrounding sync.
            discoverPubkeys(session, "https://peer.invalid.example.org/");

            assertEquals(0, collection("lists").countDocuments(session));
        }

        // --- syncing from a peer ---------------------------------------------
        /**
         * Fakes only the HTTP client; the rest of NanopubUtils has to keep
         * working because signature checks depend on it.
         */
        private MockedStatic<NanopubUtils> mockHttp(CloseableHttpClient client) {
            MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class, CALLS_REAL_METHODS);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            return httpMock;
        }

        private CloseableHttpResponse healthyHead(long setupId, long loadCounter) {
            CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
            when(resp.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
            when(resp.getFirstHeader("Nanopub-Registry-Status"))
                    .thenReturn(new BasicHeader("Nanopub-Registry-Status", "ready"));
            when(resp.getFirstHeader("Nanopub-Registry-Setup-Id"))
                    .thenReturn(new BasicHeader("Nanopub-Registry-Setup-Id", String.valueOf(setupId)));
            when(resp.getFirstHeader("Nanopub-Registry-Load-Counter"))
                    .thenReturn(new BasicHeader("Nanopub-Registry-Load-Counter", String.valueOf(loadCounter)));
            return resp;
        }

        private CloseableHttpResponse bodyResponse(int status, byte[] body) throws IOException {
            CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
            when(resp.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "Reason"));
            HttpEntity entity = mock(HttpEntity.class);
            when(resp.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(body));
            return resp;
        }

        /**
         * A Jelly byte stream carrying one nanopub, framed the way a peer's
         * {@code nanopubs.jelly} endpoint frames it.
         */
        private byte[] jellyStreamOf(Nanopub np) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IoUtils.writeFrameAsDelimited(JellyUtils.writeNanopubForDB(np), out);
            return out.toByteArray();
        }

        private Nanopub testSuiteNanopub() throws Exception {
            return new NanopubImpl(NanopubTestSuite.getLatest()
                    .getByArtifactCode("RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE").getFirst().toFile());
        }

        @Test
        void checkPeer_syncsWithAHealthyPeer(@TempDir Path dir) throws Exception {
            String peerUrl = peerServing(dir, "discoveredPubkey");
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            // Built before stubbing: creating mocks inside a thenReturn argument confuses
            // Mockito's stubbing state machine.
            CloseableHttpResponse head = healthyHead(777L, 42L);
            when(client.execute(any(HttpUriRequest.class))).thenReturn(head);

            try (MockedStatic<NanopubUtils> ignored = mockHttp(client)) {
                checkPeer(session, peerUrl);
            }

            // A peer seen for the first time is recorded and its pubkeys are discovered,
            // but no back catalogue is fetched yet.
            Document state = getPeerState(session, peerUrl);
            assertNotNull(state);
            assertEquals(777L, state.getLong("setupId"));
            assertEquals(0L, state.getLong("loadCounter"));
            assertEquals(EntryStatus.encountered.getValue(), introList("discoveredPubkey").getString("status"));
        }

        @Test
        void syncWithPeer_loadsNanopubsAddedSinceTheLastSync(@TempDir Path dir) throws Exception {
            String peerUrl = peerServing(dir, "discoveredPubkey");
            updatePeerState(session, peerUrl, 123L, 500L);
            Nanopub published = testSuiteNanopub();

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            CloseableHttpResponse served = bodyResponse(200, jellyStreamOf(published));
            when(client.execute(any(HttpUriRequest.class))).thenReturn(served);

            try (MockedStatic<NanopubUtils> ignored = mockHttp(client)) {
                // The peer's counter moved on, so the gap since 500 is fetched.
                syncWithPeer(session, peerUrl, 123L, 600L);
            }

            ArgumentCaptor<HttpUriRequest> request = ArgumentCaptor.forClass(HttpUriRequest.class);
            verify(client).execute(request.capture());
            assertTrue(request.getValue().getURI().toString().endsWith("nanopubs.jelly?afterCounter=500"),
                    "the fetch resumes from the last known counter");

            assertEquals(1, collection(Collection.NANOPUBS.toString()).countDocuments(session),
                    "the nanopub the peer served is stored locally");
            // New data also triggers pubkey discovery.
            assertEquals(EntryStatus.encountered.getValue(), introList("discoveredPubkey").getString("status"));
        }

        @Test
        void syncWithPeer_keepsItsPositionWhenTheFetchFails(@TempDir Path dir) throws Exception {
            String peerUrl = peerServing(dir);
            updatePeerState(session, peerUrl, 123L, 500L);

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            CloseableHttpResponse unavailable = bodyResponse(503, new byte[0]);
            when(client.execute(any(HttpUriRequest.class))).thenReturn(unavailable);

            try (MockedStatic<NanopubUtils> ignored = mockHttp(client)) {
                syncWithPeer(session, peerUrl, 123L, 600L);
            }

            // Nothing was received, so the recorded position must not advance past what we
            // actually hold — otherwise the gap would be skipped forever.
            assertEquals(500L, getPeerState(session, peerUrl).getLong("loadCounter"));
        }

        @Test
        void syncWithPeer_survivesANetworkFailureMidFetch(@TempDir Path dir) throws Exception {
            String peerUrl = peerServing(dir);
            updatePeerState(session, peerUrl, 123L, 500L);

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            when(client.execute(any(HttpUriRequest.class))).thenThrow(new IOException("connection reset"));

            try (MockedStatic<NanopubUtils> ignored = mockHttp(client)) {
                syncWithPeer(session, peerUrl, 123L, 600L);
            }

            assertEquals(500L, getPeerState(session, peerUrl).getLong("loadCounter"));
        }

        @Test
        void checkPeers_movesOnAfterSkippingAnUnhealthyPeer(@TempDir Path dir) throws Exception {
            Object previousPeerUrls = peerUrlsField().get(null);
            try {
                peerUrlsField().set(null, List.of(dir.toUri().toString()));
                CloseableHttpClient client = mock(CloseableHttpClient.class);
                CloseableHttpResponse unavailable = mock(CloseableHttpResponse.class);
                when(unavailable.getStatusLine())
                        .thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, 503, "Service Unavailable"));
                when(client.execute(any(HttpUriRequest.class))).thenReturn(unavailable);

                try (MockedStatic<NanopubUtils> ignored = mockHttp(client)) {
                    checkPeers(session);
                }

                // Skipping is a normal outcome, not an error: nothing is recorded for the peer.
                assertNull(getPeerState(session, dir.toUri().toString()));
            } finally {
                peerUrlsField().set(null, previousPeerUrls);
            }
        }

        private Field peerUrlsField() throws Exception {
            Field f = Utils.class.getDeclaredField("peerUrls");
            f.setAccessible(true);
            return f;
        }

        @Test
        void multiplePeers_trackedIndependently() {
            updatePeerState(session, "https://peer1.example.com/", 100L, 500L);
            updatePeerState(session, "https://peer2.example.com/", 200L, 600L);

            Document state1 = getPeerState(session, "https://peer1.example.com/");
            Document state2 = getPeerState(session, "https://peer2.example.com/");

            assertEquals(100L, state1.getLong("setupId"));
            assertEquals(200L, state2.getLong("setupId"));
            assertEquals(500L, state1.getLong("loadCounter"));
            assertEquals(600L, state2.getLong("loadCounter"));
        }
    }

    @Nested
    class CollectionEnumTests {

        @Test
        void peerStateCollectionName() {
            assertEquals("peerState", Collection.PEER_STATE.toString());
        }
    }

    @Nested
    class HeaderLoadCounterTests {

        private HttpResponse makeResponse(String... headers) {
            HttpResponse resp = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
            for (int i = 0; i < headers.length; i += 2) {
                resp.setHeader(headers[i], headers[i + 1]);
            }
            return resp;
        }

        @Test
        void getHeaderLong_readsLoadCounterHeader() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Load-Counter", "5000");
            assertEquals(5000L, getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
        }

        @Test
        void getHeaderLong_readsNanopubCountHeader() {
            HttpResponse resp = makeResponse("Nanopub-Registry-Nanopub-Count", "4900");
            assertEquals(4900L, getHeaderLong(resp, "Nanopub-Registry-Nanopub-Count"));
        }
    }

    @Nested
    @Testcontainers
    class PeerStateLoadCounterTests {

        private FakeEnv fakeEnv;
        private ClientSession session;

        @Container
        private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

        @BeforeEach
        void setUp() throws Exception {
            fakeEnv = TestUtils.setupFakeEnv();
            TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistryTest");
            TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
            RegistryDB.init();
            session = RegistryDB.getClient().startSession();
        }

        @AfterEach
        void tearDown() throws Exception {
            if (session != null) {
                session.close();
            }
            TestUtils.cleanupDataDir();
            fakeEnv.reset();
        }

        @Test
        void updatePeerState_storesLoadCounter() {
            updatePeerState(session, "https://peer.example.com/", 123L, 42000L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertNotNull(state);
            assertEquals(42000L, state.getLong("loadCounter"), "peerState should store loadCounter field");
        }

        @Test
        void syncWithPeer_skipsWhenLoadCounterUnchanged() {
            updatePeerState(session, "https://peer.example.com/", 123L, 500L);

            syncWithPeer(session, "https://peer.example.com/", 123L, 500L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertEquals(500L, state.getLong("loadCounter"), "loadCounter should remain unchanged");
        }

        @Test
        void multiplePeers_trackedIndependentlyWithLoadCounter() {
            updatePeerState(session, "https://peer1.example.com/", 100L, 500L);
            updatePeerState(session, "https://peer2.example.com/", 200L, 600L);

            Document state1 = getPeerState(session, "https://peer1.example.com/");
            Document state2 = getPeerState(session, "https://peer2.example.com/");

            assertEquals(500L, state1.getLong("loadCounter"));
            assertEquals(600L, state2.getLong("loadCounter"));
        }
    }

    /**
     * A peer is only synced from when it answers 2xx, is not a test instance,
     * reports a usable status and advertises a numeric setup id and load
     * counter. Every other case must bail out before touching the database,
     * which is what these assert.
     */
    @Nested
    class CheckPeerTests {

        private static final String PEER = "https://peer.example.org/";

        /**
         * Runs checkPeer against a peer answering with the given status code
         * and headers, and asserts that no database work happened.
         */
        private void assertPeerSkipped(int statusCode, String... headers) throws IOException {
            try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class); MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {

                CloseableHttpClient client = mock(CloseableHttpClient.class);
                httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
                CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
                when(resp.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, statusCode, "Reason"));
                for (int i = 0; i < headers.length; i += 2) {
                    when(resp.getFirstHeader(headers[i])).thenReturn(new BasicHeader(headers[i], headers[i + 1]));
                }
                when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);

                checkPeer(mock(ClientSession.class), PEER);

                dbMock.verify(() -> RegistryDB.collection(anyString()), never());
            }
        }

        @Test
        void unreachablePeerIsSkipped() throws IOException {
            assertPeerSkipped(503,
                    "Nanopub-Registry-Status", "ready",
                    "Nanopub-Registry-Setup-Id", "42",
                    "Nanopub-Registry-Load-Counter", "100");
        }

        @Test
        void testInstancePeerIsSkipped() throws IOException {
            // Syncing from a test instance would pollute a production registry.
            assertPeerSkipped(200,
                    "Nanopub-Registry-Test-Instance", "true",
                    "Nanopub-Registry-Status", "ready",
                    "Nanopub-Registry-Setup-Id", "42",
                    "Nanopub-Registry-Load-Counter", "100");
        }

        @Test
        void peerStillLaunchingIsSkipped() throws IOException {
            assertPeerSkipped(200,
                    "Nanopub-Registry-Status", "launching",
                    "Nanopub-Registry-Setup-Id", "42",
                    "Nanopub-Registry-Load-Counter", "100");
        }

        @Test
        void peerWithoutStatusHeaderIsSkipped() throws IOException {
            assertPeerSkipped(200,
                    "Nanopub-Registry-Setup-Id", "42",
                    "Nanopub-Registry-Load-Counter", "100");
        }

        @Test
        void peerWithUnparseableSetupIdIsSkipped() throws IOException {
            assertPeerSkipped(200,
                    "Nanopub-Registry-Status", "ready",
                    "Nanopub-Registry-Setup-Id", "not-a-number",
                    "Nanopub-Registry-Load-Counter", "100");
        }

        @Test
        void peerWithoutLoadCounterIsSkipped() throws IOException {
            assertPeerSkipped(200,
                    "Nanopub-Registry-Status", "ready",
                    "Nanopub-Registry-Setup-Id", "42");
        }
    }

    @Nested
    class CheckPeersTests {

        private Object previousPeerUrls;

        @BeforeEach
        void pinPeerUrls() throws Exception {
            previousPeerUrls = peerUrlsField().get(null);
        }

        @AfterEach
        void restorePeerUrls() throws Exception {
            peerUrlsField().set(null, previousPeerUrls);
        }

        private Field peerUrlsField() throws Exception {
            Field f = Utils.class.getDeclaredField("peerUrls");
            f.setAccessible(true);
            return f;
        }

        @Test
        void doesNothingWithoutConfiguredPeers() throws Exception {
            peerUrlsField().set(null, List.of());

            try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
                checkPeers(mock(ClientSession.class));
                httpMock.verify(NanopubUtils::getHttpClient, never());
            }
        }

        @Test
        void keepsGoingWhenOnePeerFails() throws Exception {
            peerUrlsField().set(null, List.of("https://peer-a.example.org/", "https://peer-b.example.org/"));

            try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
                CloseableHttpClient client = mock(CloseableHttpClient.class);
                httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
                when(client.execute(any(HttpUriRequest.class))).thenThrow(new IOException("connection refused"));

                // One dead peer must not stop the sweep over the rest.
                checkPeers(mock(ClientSession.class));

                verify(client, times(2)).execute(any(HttpUriRequest.class));
            }
        }
    }
}
