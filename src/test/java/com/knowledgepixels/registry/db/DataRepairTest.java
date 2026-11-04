package com.knowledgepixels.registry.db;

import com.knowledgepixels.registry.Collection;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.nanopub.NanopubUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the startup repairs: the removal of nanopubs an earlier version stored under an artifact
 * code that cannot be the hash of any content, and the recomputation of list checksum chains that
 * concurrent appends had corrupted (issue #205).
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

    // --- checksum chains -----------------------------------------------------

    /**
     * Turns a label into a well-formed (but made-up) Trusty artifact code. The codes are hashes
     * of the label, so that XORs of different combinations of them do not collide on the unique
     * checksum index, as codes that differ in a single character would.
     */
    private static String artifactCode(String seed) {
        try {
            return "RA" + TrustyUriUtils.getBase64(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String chain(String artifactCode, String previousChecksum) {
        return NanopubUtils.updateXorChecksum(
                SimpleValueFactory.getInstance().createIRI("https://w3id.org/np/" + artifactCode), previousChecksum);
    }

    private void seedListEntry(String pubkeyHash, long position, String artifactCode, String checksum) {
        RegistryDB.insert(session, "listEntries", new Document("pubkey", pubkeyHash).append("type", "$")
                .append("position", position).append("np", artifactCode).append("checksum", checksum).append("invalidated", false));
    }

    private List<Document> entriesOf(String pubkeyHash) {
        return RegistryDB.collection("listEntries").find(session, new Document("pubkey", pubkeyHash).append("type", "$"))
                .sort(new Document("position", 1)).into(new ArrayList<>());
    }

    private static void assertSequentialChain(List<Document> entries) {
        String expected = NanopubUtils.INIT_CHECKSUM;
        for (Document entry : entries) {
            expected = chain(entry.getString("np"), expected);
            assertEquals(expected, entry.getString("checksum"), "entry at position " + entry.getLong("position"));
        }
    }

    /**
     * The damage of issue #205: entries chained off a stale entry instead of their predecessor,
     * here positions 2 and 3 both off position 0, with everything after built on those values.
     */
    @Test
    void rewritesAChecksumChainBrokenByConcurrentAppends() {
        String pubkeyHash = "brokenChainPubkey";
        RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 5L));
        String c0 = chain(artifactCode("np0"), NanopubUtils.INIT_CHECKSUM);
        String c1 = chain(artifactCode("np1"), c0);
        String c2 = chain(artifactCode("np2"), c0); // stale
        String c3 = chain(artifactCode("np3"), c0); // stale
        String c4 = chain(artifactCode("np4"), c3);
        String c5 = chain(artifactCode("np5"), c4);
        seedListEntry(pubkeyHash, 0L, artifactCode("np0"), c0);
        seedListEntry(pubkeyHash, 1L, artifactCode("np1"), c1);
        seedListEntry(pubkeyHash, 2L, artifactCode("np2"), c2);
        seedListEntry(pubkeyHash, 3L, artifactCode("np3"), c3);
        seedListEntry(pubkeyHash, 4L, artifactCode("np4"), c4);
        seedListEntry(pubkeyHash, 5L, artifactCode("np5"), c5);

        DataRepair.runIfNeeded(session);

        List<Document> entries = entriesOf(pubkeyHash);
        assertEquals(6, entries.size());
        assertSequentialChain(entries);
        assertEquals(c0, entries.get(0).getString("checksum"), "a correct prefix is left as it is");
        assertEquals(c1, entries.get(1).getString("checksum"));
        for (int i = 0; i < 6; i++) {
            assertEquals(i, entries.get(i).getLong("position"), "positions are never changed");
            assertEquals(artifactCode("np" + i), entries.get(i).getString("np"));
        }
        assertEquals(DataRepair.CURRENT_REPAIR_VERSION, DataRepair.getRepairVersion(session));
    }

    @Test
    void chainsOverAGapInThePositions() {
        String pubkeyHash = "gappyPubkey";
        RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 3L));
        String c0 = chain(artifactCode("np0"), NanopubUtils.INIT_CHECKSUM);
        seedListEntry(pubkeyHash, 0L, artifactCode("np0"), c0);
        seedListEntry(pubkeyHash, 1L, artifactCode("np1"), chain(artifactCode("np1"), c0));
        // Position 2 is missing; position 3 was chained off position 0.
        seedListEntry(pubkeyHash, 3L, artifactCode("np3"), chain(artifactCode("np3"), c0));

        DataRepair.runIfNeeded(session);

        List<Document> entries = entriesOf(pubkeyHash);
        assertEquals(List.of(0L, 1L, 3L), entries.stream().map(e -> e.getLong("position")).toList());
        assertSequentialChain(entries);
    }

    @Test
    void leavesAListWithAnUndecodableArtifactCodeAlone() {
        String pubkeyHash = "undecodablePubkey";
        RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 1L));
        seedListEntry(pubkeyHash, 0L, MALFORMED_AC, chain(artifactCode("whatever"), NanopubUtils.INIT_CHECKSUM));
        String wrong = chain(artifactCode("np1"), NanopubUtils.INIT_CHECKSUM);
        seedListEntry(pubkeyHash, 1L, artifactCode("np1"), wrong);

        // Must not throw: the chain cannot be computed through an entry that cannot be decoded,
        // so the list is reported and left for manual repair.
        DataRepair.runIfNeeded(session);

        assertEquals(wrong, entriesOf(pubkeyHash).get(1).getString("checksum"));
        assertEquals(DataRepair.CURRENT_REPAIR_VERSION, DataRepair.getRepairVersion(session),
                "one unrepairable list does not keep the repair from being marked as done");
    }

    @Test
    void doesNotRecomputeChainsOnceTheRepairHasRun() {
        DataRepair.runIfNeeded(session);

        String pubkeyHash = "lateBrokenPubkey";
        RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 0L));
        String wrong = chain(artifactCode("other"), NanopubUtils.INIT_CHECKSUM);
        seedListEntry(pubkeyHash, 0L, artifactCode("np0"), wrong);
        DataRepair.runIfNeeded(session);

        assertEquals(wrong, entriesOf(pubkeyHash).get(0).getString("checksum"));
    }

}
