package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.NanopubUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batched list appends of a parallel load: entries are computed off the list tail in memory,
 * written in order, and placed one by one off the actual end of the list when another writer
 * got a position first.
 */
@Testcontainers
class ListAppendBatchTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    private static final String PUBKEY_HASH = "batchTestPubkey";

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        session = RegistryDB.getClient().startSession();
        RegistryDB.insert(session, "lists", new Document("pubkey", PUBKEY_HASH).append("type", "$").append("maxPosition", -1L));
    }

    @AfterEach
    void tearDown() {
        session.close();
        RegistryDB.getDB().drop();
        RegistryDB.getClient().close();
        fakeEnv.reset();
    }

    private static String artifactCode(String seed) throws Exception {
        return "RA" + net.trustyuri.TrustyUriUtils.getBase64(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static IRI uri(String artifactCode) {
        return SimpleValueFactory.getInstance().createIRI("https://w3id.org/np/" + artifactCode);
    }

    private List<Document> entries() {
        return RegistryDB.collection("listEntries").find(session, new Document("pubkey", PUBKEY_HASH).append("type", "$"))
                .sort(new Document("position", 1)).into(new ArrayList<>());
    }

    private long maxPosition() {
        return RegistryDB.collection("lists").find(session, new Document("pubkey", PUBKEY_HASH).append("type", "$")).first().getLong("maxPosition");
    }

    private static void assertSequentialChain(List<Document> entries) {
        String checksum = NanopubUtils.INIT_CHECKSUM;
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i, entries.get(i).getLong("position"));
            checksum = NanopubUtils.updateXorChecksum(uri(entries.get(i).getString("np")), checksum);
            assertEquals(checksum, entries.get(i).getString("checksum"), "entry " + i);
        }
    }

    @Test
    void writesTheEntriesInOrderAndRaisesTheCounterOnFlush() throws Exception {
        ListAppendBatch batch = new ListAppendBatch();
        for (int i = 0; i < 5; i++) {
            String ac = artifactCode("np" + i);
            batch.append(session, PUBKEY_HASH, "$", uri(ac), ac);
        }
        assertTrue(batch.hasPending());
        assertTrue(entries().isEmpty(), "nothing is written before the flush");
        assertTrue(batch.isPending(PUBKEY_HASH, "$", artifactCode("np3")));
        assertFalse(batch.isPending(PUBKEY_HASH, "other", artifactCode("np3")));

        batch.flush(session);

        assertFalse(batch.hasPending());
        List<Document> entries = entries();
        assertEquals(5, entries.size());
        assertSequentialChain(entries);
        assertEquals(4L, maxPosition());
        assertFalse(batch.isPending(PUBKEY_HASH, "$", artifactCode("np3")), "flushed entries are no longer pending");
    }

    @Test
    void continuesTheChainFromExistingEntriesAndFlushesAtTheBatchSize() throws Exception {
        String existing = artifactCode("existing");
        RegistryDB.appendDirectly(session, uri(existing), existing, PUBKEY_HASH, "$", false);

        ListAppendBatch batch = new ListAppendBatch();
        for (int i = 0; i < ListAppendBatch.FLUSH_SIZE; i++) {
            String ac = artifactCode("np" + i);
            batch.append(session, PUBKEY_HASH, "$", uri(ac), ac);
        }

        assertFalse(batch.hasPending(), "reaching the batch size flushes");
        List<Document> entries = entries();
        assertEquals(ListAppendBatch.FLUSH_SIZE + 1, entries.size());
        assertEquals(existing, entries.get(0).getString("np"));
        assertSequentialChain(entries);
        assertEquals(ListAppendBatch.FLUSH_SIZE, maxPosition());
    }

    @Test
    void placesTheRestOneByOneWhenAnotherWriterTookAPosition() throws Exception {
        ListAppendBatch batch = new ListAppendBatch();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String ac = artifactCode("np" + i);
            codes.add(ac);
            batch.append(session, PUBKEY_HASH, "$", uri(ac), ac);
        }
        // Someone else appends at position 3 while the batch is waiting.
        String competitor = artifactCode("competitor");
        RegistryDB.insert(session, "listEntries", new Document("pubkey", PUBKEY_HASH).append("type", "$")
                .append("position", 3L).append("np", competitor).append("checksum", "WrongButUniqueChecksum0000000000000000000000").append("invalidated", false));

        batch.flush(session);

        List<Document> entries = entries();
        assertEquals(7, entries.size(), "every pending entry is placed, after the competitor");
        List<String> expectedOrder = new ArrayList<>(codes.subList(0, 3));
        expectedOrder.add(competitor);
        expectedOrder.addAll(codes.subList(3, 6));
        assertEquals(expectedOrder, entries.stream().map(e -> e.getString("np")).toList());
        // The entries placed one by one chain off whatever is actually there, so the chain is
        // sequential from the competitor on (whose own checksum is the seeded one).
        String checksum = entries.get(3).getString("checksum");
        for (int i = 4; i < 7; i++) {
            checksum = NanopubUtils.updateXorChecksum(uri(entries.get(i).getString("np")), checksum);
            assertEquals(checksum, entries.get(i).getString("checksum"), "entry " + i);
            assertEquals(i, entries.get(i).getLong("position"));
        }
        assertEquals(6L, maxPosition());
        assertFalse(batch.hasPending());
    }

    @Test
    void marksPendingEntriesInvalidatedAndKnowsTheirLists() throws Exception {
        ListAppendBatch batch = new ListAppendBatch();
        String ac = artifactCode("np");
        batch.append(session, PUBKEY_HASH, "$", uri(ac), ac);
        batch.append(session, PUBKEY_HASH, "someType", uri(ac), ac);
        batch.append(session, PUBKEY_HASH, "$", uri(artifactCode("other")), artifactCode("other"));

        assertEquals(List.of("$", "someType"), batch.pendingTypesOf(PUBKEY_HASH, ac));
        batch.markInvalidated(PUBKEY_HASH, ac);
        batch.flush(session);

        List<Document> entries = entries();
        assertTrue(entries.get(0).getBoolean("invalidated"));
        assertFalse(entries.get(1).getBoolean("invalidated"), "only the invalidated nanopub's entries are marked");
        assertTrue(RegistryDB.collection("listEntries").find(session, new Document("pubkey", PUBKEY_HASH).append("type", "someType"))
                .first().getBoolean("invalidated"));
    }

    @Test
    void marksEntriesWhoseInvalidationWasRecordedWhileTheyWerePending() throws Exception {
        ListAppendBatch batch = new ListAppendBatch();
        String ac = artifactCode("np");
        String untouched = artifactCode("untouched");
        String invalidatedByAnotherPubkey = artifactCode("invalidatedByAnotherPubkey");
        batch.append(session, PUBKEY_HASH, "$", uri(ac), ac);
        batch.append(session, PUBKEY_HASH, "$", uri(untouched), untouched);
        batch.append(session, PUBKEY_HASH, "$", uri(invalidatedByAnotherPubkey), invalidatedByAnotherPubkey);

        // Another writer records invalidations meanwhile; it finds no entries to mark.
        RegistryDB.insert(session, "invalidations", new Document("invalidatingNp", artifactCode("invalidating"))
                .append("invalidatingPubkey", PUBKEY_HASH).append("invalidatedNp", ac));
        RegistryDB.insert(session, "invalidations", new Document("invalidatingNp", artifactCode("alsoInvalidating"))
                .append("invalidatingPubkey", "anotherPubkey").append("invalidatedNp", invalidatedByAnotherPubkey));
        batch.flush(session);

        List<Document> entries = entries();
        assertTrue(entries.get(0).getBoolean("invalidated"), "the invalidation reaches the entry once it is written");
        assertFalse(entries.get(1).getBoolean("invalidated"));
        assertFalse(entries.get(2).getBoolean("invalidated"), "an invalidation only counts on the lists of the pubkey that made it");
    }

}
