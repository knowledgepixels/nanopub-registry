package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryPeerConnector.*;
import static org.junit.jupiter.api.Assertions.*;

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
            if (session != null) session.close();
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
            assertEquals(600L, state.getLong("loadCounter"));
        }

        @Test
        void syncWithPeer_updatesStateAfterSync() {
            // First time seeing this peer (no prior state)
            syncWithPeer(session, "https://peer.example.com/", 123L, 42000L);

            Document state = getPeerState(session, "https://peer.example.com/");
            assertNotNull(state);
            assertEquals(123L, state.getLong("setupId"));
            assertEquals(42000L, state.getLong("loadCounter"));
        }

        @Test
        void discoverPubkeys_createsEncounteredIntroLists() {
            // Simulate what discoverPubkeys does: insert encountered intro lists for unknown pubkeys
            String pubkeyHash = "newPubkey123";
            Document d = new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH);
            assertFalse(RegistryDB.has(session, "lists", d));

            RegistryDB.insert(session, "lists", d.append("status", EntryStatus.encountered.getValue()));
            assertTrue(RegistryDB.has(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)));

            // Verify the status is "encountered"
            try (com.mongodb.client.MongoCursor<Document> cursor = collection("lists").find(session,
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)).cursor()) {
                assertTrue(cursor.hasNext());
                assertEquals(EntryStatus.encountered.getValue(), cursor.next().getString("status"));
            }
        }

        @Test
        void discoverPubkeys_duplicateInsertDoesNotThrow() {
            // Simulate a concurrent insert: pre-insert an encountered entry, then try inserting again
            String pubkeyHash = "racePubkey";
            Document doc = new Document("pubkey", pubkeyHash)
                    .append("type", NanopubLoader.INTRO_TYPE_HASH)
                    .append("status", EntryStatus.encountered.getValue());
            RegistryDB.insert(session, "lists", doc);

            // A second insert with the same key should be silently ignored (duplicate key)
            try {
                RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash)
                        .append("type", NanopubLoader.INTRO_TYPE_HASH)
                        .append("status", EntryStatus.encountered.getValue()));
            } catch (com.mongodb.MongoWriteException e) {
                assertEquals(11000, e.getError().getCode(), "Should be a duplicate key error");
            }

            // Should still be exactly 1 document
            assertEquals(1, collection("lists").countDocuments(session,
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)));
        }

        @Test
        void discoverPubkeys_skipsAlreadyKnownPubkeys() {
            // Pre-insert a loaded list for this pubkey
            String pubkeyHash = "existingPubkey";
            collection("lists").insertOne(session,
                    new Document("pubkey", pubkeyHash)
                            .append("type", NanopubLoader.INTRO_TYPE_HASH)
                            .append("status", "loaded"));

            // Verify has() returns true — discoverPubkeys would skip this pubkey
            assertTrue(RegistryDB.has(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)));

            // Should still be exactly 1 document
            assertEquals(1, collection("lists").countDocuments(session,
                    new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)));
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
}
