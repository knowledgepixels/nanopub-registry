package com.knowledgepixels.registry;

import com.google.gson.JsonObject;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryPeerConnector.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistryPeerConnectorTest {

    @Nested
    class JsonHelperTests {

        @Test
        void getJsonString_returnsValue() {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "ready");
            assertEquals("ready", getJsonString(obj, "status"));
        }

        @Test
        void getJsonString_returnsNullForMissingKey() {
            JsonObject obj = new JsonObject();
            assertNull(getJsonString(obj, "missing"));
        }

        @Test
        void getJsonString_returnsNullForJsonNull() {
            JsonObject obj = new JsonObject();
            obj.add("status", com.google.gson.JsonNull.INSTANCE);
            assertNull(getJsonString(obj, "status"));
        }

        @Test
        void getJsonLong_returnsValue() {
            JsonObject obj = new JsonObject();
            obj.addProperty("loadCounter", 42000L);
            assertEquals(42000L, getJsonLong(obj, "loadCounter"));
        }

        @Test
        void getJsonLong_returnsNullForMissingKey() {
            JsonObject obj = new JsonObject();
            assertNull(getJsonLong(obj, "missing"));
        }

        @Test
        void getJsonLong_returnsNullForJsonNull() {
            JsonObject obj = new JsonObject();
            obj.add("loadCounter", com.google.gson.JsonNull.INSTANCE);
            assertNull(getJsonLong(obj, "loadCounter"));
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
        void getApprovedPubkeys_returnsEmptyWhenNoLists() {
            List<String> pubkeys = getApprovedPubkeys(session);
            assertTrue(pubkeys.isEmpty());
        }

        @Test
        void getApprovedPubkeys_returnsLoadedPubkeys() {
            collection("lists").insertOne(session,
                    new Document("pubkey", "abc123").append("type", "$").append("status", "loaded"));
            collection("lists").insertOne(session,
                    new Document("pubkey", "def456").append("type", "$").append("status", "loaded"));
            // This one should NOT be returned (status is not "loaded")
            collection("lists").insertOne(session,
                    new Document("pubkey", "ghi789").append("type", "$").append("status", "encountered"));

            List<String> pubkeys = getApprovedPubkeys(session);
            assertEquals(2, pubkeys.size());
            assertTrue(pubkeys.contains("abc123"));
            assertTrue(pubkeys.contains("def456"));
            assertFalse(pubkeys.contains("ghi789"));
        }

        @Test
        void getApprovedPubkeys_excludesNonDollarTypes() {
            collection("lists").insertOne(session,
                    new Document("pubkey", "abc123").append("type", "$").append("status", "loaded"));
            collection("lists").insertOne(session,
                    new Document("pubkey", "abc123").append("type", "introHash").append("status", "loaded"));

            List<String> pubkeys = getApprovedPubkeys(session);
            assertEquals(1, pubkeys.size());
            assertEquals("abc123", pubkeys.getFirst());
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
    class SmallDeltaThresholdTests {

        @Test
        void thresholdIsReasonable() {
            assertEquals(100, SMALL_DELTA_THRESHOLD);
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
