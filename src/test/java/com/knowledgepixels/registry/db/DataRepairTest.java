package com.knowledgepixels.registry.db;

import com.knowledgepixels.registry.Collection;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the startup repair that removes nanopubs an earlier version stored under an artifact
 * code that cannot be the hash of any content.
 */
@Testcontainers
class DataRepairTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    /** The code from issue #164: the right module, but too short to be an RA hash. */
    private static final String MALFORMED_AC = "RA" + "A".repeat(40);
    private static final String VALID_AC = "RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY";

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        session = RegistryDB.getClient().startSession();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        if (RegistryDB.getDB() != null) {
            RegistryDB.getDB().drop();
        }
        if (RegistryDB.getClient() != null) {
            RegistryDB.getClient().close();
        }
        fakeEnv.reset();
    }

    // --- helpers -------------------------------------------------------------

    private void seedNanopub(String artifactCode, long counter) {
        RegistryDB.insert(session, Collection.NANOPUBS.toString(),
                new Document("_id", artifactCode)
                        .append("fullId", "https://w3id.org/np/" + artifactCode)
                        .append("counter", counter));
    }

    private Document nanopubDoc(String artifactCode) {
        return RegistryDB.collection(Collection.NANOPUBS.toString())
                .find(session, new Document("_id", artifactCode)).first();
    }

    // --- tests ---------------------------------------------------------------

    @Test
    void removesAnOrphanedNanopubWithAMalformedArtifactCode() {
        seedNanopub(MALFORMED_AC, 88951L);
        seedNanopub(VALID_AC, 88952L);

        DataRepair.runIfNeeded(session);

        assertNull(nanopubDoc(MALFORMED_AC));
        assertNotNull(nanopubDoc(VALID_AC), "a well-formed entry must not be touched");
    }

    @Test
    void leavesTheCounterAloneSoConsumersDoNotSeeAReset() {
        // init() already created the counter document, so this moves it rather than inserting it.
        RegistryDB.collection("counters").updateOne(session, new Document("_id", "nanopubs"),
                new Document("$set", new Document("value", 88952L)));
        seedNanopub(MALFORMED_AC, 88951L);

        DataRepair.runIfNeeded(session);

        Document counter = RegistryDB.collection("counters").find(session, new Document("_id", "nanopubs")).first();
        assertNotNull(counter);
        assertEquals(88952L, counter.getLong("value"),
                "a decreasing load counter reads as a registry reset and would force a full resync downstream");
    }

    @Test
    void removesInvalidationRecordsOfARemovedNanopub() {
        seedNanopub(MALFORMED_AC, 88951L);
        RegistryDB.insert(session, "invalidations",
                new Document("invalidatingNp", MALFORMED_AC).append("invalidatedNp", VALID_AC));

        DataRepair.runIfNeeded(session);

        assertNull(nanopubDoc(MALFORMED_AC));
        assertEquals(0, RegistryDB.collection("invalidations")
                .countDocuments(session, new Document("invalidatingNp", MALFORMED_AC)));
    }

    /**
     * Deleting an entry that holds a list position would break that list's position and checksum
     * chain, which is a repair no startup task should attempt on its own.
     */
    @Test
    void keepsAMalformedNanopubThatStillHoldsAListPosition() {
        seedNanopub(MALFORMED_AC, 88951L);
        RegistryDB.insert(session, "listEntries",
                new Document("pubkey", "somePubkeyHash").append("type", "someTypeHash")
                        .append("position", 0L).append("np", MALFORMED_AC));

        DataRepair.runIfNeeded(session);

        assertNotNull(nanopubDoc(MALFORMED_AC), "an entangled entry has to be left for manual repair");
    }

    @Test
    void keepsAMalformedNanopubThatStillSourcesATrustEdge() {
        seedNanopub(MALFORMED_AC, 88951L);
        RegistryDB.insert(session, "trustEdges", new Document("source", MALFORMED_AC).append("invalidated", false));

        DataRepair.runIfNeeded(session);

        assertNotNull(nanopubDoc(MALFORMED_AC));
    }

    @Test
    void keepsAMalformedNanopubThatStillIntroducesAnAccount() {
        seedNanopub(MALFORMED_AC, 88951L);
        RegistryDB.insert(session, Collection.ACCOUNTS.toString(),
                new Document("introNanopub", "https://w3id.org/np/" + MALFORMED_AC));

        DataRepair.runIfNeeded(session);

        assertNotNull(nanopubDoc(MALFORMED_AC));
    }

    @Test
    void doesNotScanAgainOnceTheRepairHasRun() {
        DataRepair.runIfNeeded(session);
        assertEquals(DataRepair.CURRENT_REPAIR_VERSION, DataRepair.getRepairVersion(session));

        // An entry appearing after the repair is not the repair's business: the ingest check keeps
        // it out in the first place, and rescanning every startup would grow with the collection.
        seedNanopub(MALFORMED_AC, 88951L);
        DataRepair.runIfNeeded(session);

        assertNotNull(nanopubDoc(MALFORMED_AC));
    }

    @Test
    void markingUpToDateSkipsTheRepairEntirely() {
        seedNanopub(MALFORMED_AC, 88951L);
        DataRepair.markUpToDate(session);

        DataRepair.runIfNeeded(session);

        assertNotNull(nanopubDoc(MALFORMED_AC), "a freshly initialized database must not be scanned");
    }

}
