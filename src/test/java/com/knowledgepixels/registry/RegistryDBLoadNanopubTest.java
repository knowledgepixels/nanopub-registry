package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/**
 * Covers the parts of {@link RegistryDB} that decide whether a nanopub is stored,
 * how it is placed on the pubkey/type lists, and how invalidations are propagated.
 * <p>
 * These paths are only observable against a real MongoDB: they depend on the unique
 * indexes created by {@link com.knowledgepixels.registry.db.IndexInitializer} (duplicate
 * keys are the normal signal for "another thread got here first") and on server-side
 * atomic updates. The container is shared by all tests; the database is dropped after
 * each one so nothing leaks between them.
 */
@Testcontainers
class RegistryDBLoadNanopubTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    /** simple1.trig: valid signature, types npx:ExampleNanopub and ex:transmits. */
    private static final String SIMPLE1_AC = "RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY";
    /** example8.trig: signed with the same key as simple1, supersedes {@link #SUPERSEDED_AC}. */
    private static final String SUPERSEDER_AC = "RAR7wdfw9trX-4V5LnHGuXPXrNGTn4qFZKGs3MO_cwIHw";
    /** Target of example8's npx:supersedes; not itself part of the test suite. */
    private static final String SUPERSEDED_AC = "RAYoA93MB3r8lo-Dj2FWlazMQ9fq5HjTT0iiUbf6iiQRQ";
    /** example3.trig: a trusty but entirely unsigned nanopub. */
    private static final String UNSIGNED_AC = "RA1sViVmXf-W2aZW4Qk74KTaiD9gpLBPe2LhMsinHKKz8";

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        // Other test classes may have left a restrictive coverage filter behind in this JVM.
        CoverageFilter.init();
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

    private static Nanopub testSuiteNanopub(String artifactCode) throws Exception {
        return new NanopubImpl(NanopubTestSuite.getLatest().getByArtifactCode(artifactCode).getFirst().toFile());
    }

    /**
     * Rewrites a nanopub's artifact code, leaving everything else untouched. The signature stays
     * valid: it is computed with the artifact code normalized out of all URIs, so a valid
     * signature says nothing about whether the code belongs to this content.
     */
    private static Nanopub withArtifactCode(Nanopub nanopub, String replacementCode) throws Exception {
        String originalCode = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        String trig = NanopubUtils.writeToString(nanopub, RDFFormat.TRIG);
        return new NanopubImpl(trig.replace(originalCode, replacementCode), RDFFormat.TRIG);
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    /**
     * A distinct but syntactically valid XOR checksum. The chaining code rejects anything
     * shorter than 32 bytes, so placeholder strings cannot be used for seeded entries.
     */
    private static String checksum(String seed) {
        return NanopubUtils.updateXorChecksum(iri("http://example.org/np/" + artifactCode(seed)),
                NanopubUtils.INIT_CHECKSUM);
    }

    /** What {@code NanopubUtils.updateXorChecksum} computes, for use where that method is mocked. */
    private static String rawXorChecksum(String artifactCode, String checksum) {
        byte[] result = TrustyUriUtils.getBase64Bytes(checksum);
        byte[] code = TrustyUriUtils.getBase64Bytes(artifactCode.substring(2));
        for (int i = 0; i < 32; i++) {
            result[i] = (byte) (result[i] ^ code[i]);
        }
        return TrustyUriUtils.getBase64(result);
    }

    /** Turns an arbitrary label into a well-formed (but made-up) Trusty artifact code. */
    private static String artifactCode(String seed) {
        return "RA" + (seed.replaceAll("[^A-Za-z0-9]", "") + "a".repeat(43)).substring(0, 43);
    }

    private Document nanopubDoc(String artifactCode) {
        return RegistryDB.collection(Collection.NANOPUBS.toString())
                .find(session, new Document("_id", artifactCode)).first();
    }

    private Document listEntry(String pubkeyHash, String typeHash, String artifactCode) {
        return RegistryDB.collection("listEntries").find(session,
                new Document("pubkey", pubkeyHash).append("type", typeHash).append("np", artifactCode)).first();
    }

    private void seedListEntry(String pubkeyHash, String typeHash, long position, String artifactCode, String checksum) {
        Document doc = new Document("pubkey", pubkeyHash).append("type", typeHash)
                .append("position", position).append("np", artifactCode).append("invalidated", false);
        if (checksum != null) {
            doc.append("checksum", checksum);
        }
        RegistryDB.insert(session, "listEntries", doc);
    }

    /**
     * Makes every write to the given collection fail server-side with a document
     * validation error (code 121), which is a {@code MongoWriteException} that is
     * <em>not</em> a duplicate key — the case the production code has to rethrow.
     */
    private void rejectAllWritesTo(String collectionName) {
        RegistryDB.getDB().runCommand(new Document("collMod", collectionName)
                .append("validator", new Document("$jsonSchema", new Document("bsonType", "object")
                        .append("required", List.of("aFieldThatIsNeverWritten"))))
                .append("validationLevel", "strict"));
    }

    // --- rejection guards ----------------------------------------------------

    /**
     * The guards below are reached through {@code loadNanopubVerified}, not through
     * {@code loadNanopub}: the latter derives the pubkey from the signature first and
     * bails out before any of these checks when the signature cannot be verified.
     */
    @Nested
    class RejectionGuards {

        @Test
        void rejectsANanopubWithTooManyTriples() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(1201).when(nanopub).getTripleCount();

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
            assertNull(nanopubDoc(SIMPLE1_AC));
        }

        @Test
        void rejectsAnOversizedNanopub() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(1_000_001L).when(nanopub).getByteCount();

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
            assertNull(nanopubDoc(SIMPLE1_AC));
        }

        @Test
        void acceptsANanopubWhoseTimestampCannotBeParsed() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doThrow(new RuntimeException("malformed dc:created")).when(nanopub).getCreationTime();

            // A broken timestamp is logged and dropped, not treated as a rejection reason.
            assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
            assertNotNull(nanopubDoc(SIMPLE1_AC));
        }

        @Test
        void rejectsANanopubDatedInTheFuture() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            Calendar future = Calendar.getInstance();
            future.add(Calendar.HOUR, 1);
            doReturn(future).when(nanopub).getCreationTime();

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
            assertNull(nanopubDoc(SIMPLE1_AC));
        }

        @Test
        void toleratesATimestampInsideTheAllowedClockSkew() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            Calendar barelyAhead = Calendar.getInstance();
            barelyAhead.add(Calendar.SECOND, 30);  // within the 60s tolerance
            doReturn(barelyAhead).when(nanopub).getCreationTime();

            assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
        }

        @Test
        void rejectsAGraphUriOutsideTheNanopub() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(Set.of(iri("http://elsewhere.example.org/np/Head"))).when(nanopub).getGraphUris();

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
            assertNull(nanopubDoc(SIMPLE1_AC));
        }

        @Test
        void rejectsAMismatchedPubkeyHash() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, "not-the-right-hash"));
            assertNull(nanopubDoc(SIMPLE1_AC));
        }

        @Test
        void rejectsAUriWithoutAnArtifactCode() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(iri("http://example.org/not-a-trusty-uri")).when(nanopub).getUri();
            doReturn(Set.<IRI>of()).when(nanopub).getGraphUris();

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, "some-pubkey", null));
        }

        /**
         * The incident behind issue #164: an artifact code that cannot be the hash of any content,
         * because it has the wrong length for its module. Accepting it hands out a counter and a
         * slot in the replication stream for an id that {@code /np/} cannot resolve.
         */
        @Test
        void rejectsADegenerateArtifactCode() throws Exception {
            String degenerateCode = "RA" + "A".repeat(40);
            Nanopub nanopub = withArtifactCode(testSuiteNanopub(SIMPLE1_AC), degenerateCode);

            String pubkey = RegistryDB.getPubkey(nanopub);
            assertNotNull(pubkey, "rewriting the artifact code has to leave the signature verifiable, "
                    + "otherwise this test would pass for the wrong reason");

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));
            assertNull(nanopubDoc(degenerateCode));
        }

        /**
         * Same, for a code that is well-formed for its module but is simply not the hash of this
         * nanopub's content — the length pre-check cannot catch this one.
         */
        @Test
        void rejectsAWellFormedArtifactCodeThatDoesNotMatchTheContent() throws Exception {
            String wrongCode = artifactCode("notTheHashOfThisContent");
            assertTrue(TrustyUriUtils.isPotentialArtifactCode(wrongCode));
            Nanopub nanopub = withArtifactCode(testSuiteNanopub(SIMPLE1_AC), wrongCode);

            String pubkey = RegistryDB.getPubkey(nanopub);
            assertNotNull(pubkey, "rewriting the artifact code has to leave the signature verifiable, "
                    + "otherwise this test would pass for the wrong reason");

            assertFalse(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));
            assertNull(nanopubDoc(wrongCode));
        }

        @Test
        void propagatesASerializationFailure() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);

            try (MockedStatic<NanopubUtils> nanopubUtils = mockStatic(NanopubUtils.class, CALLS_REAL_METHODS)) {
                nanopubUtils.when(() -> NanopubUtils.writeToString(org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                        .thenThrow(new IOException("disk on fire"));

                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));
                assertInstanceOfIoException(thrown);
            }
        }

        private static void assertInstanceOfIoException(RuntimeException thrown) {
            assertTrue(thrown.getCause() instanceof IOException,
                    "the serialization failure has to survive as the cause, got: " + thrown.getCause());
        }
    }

    /**
     * The same guards seen through {@code loadNanopubOrReject}, which is what the POST handler
     * calls: issue #187 is that each of them has to arrive at the publisher as its own reason and
     * its own status, rather than as one {@code false} the HTTP layer can only render as
     * "Nanopublication not supported".
     */
    @Nested
    class RejectionReasons {

        private NanopubRejectedException rejectionOf(Nanopub nanopub, String pubkey, String pubkeyHash) {
            return assertThrows(NanopubRejectedException.class,
                    () -> RegistryDB.loadNanopubOrReject(session, nanopub, pubkey, pubkeyHash));
        }

        @Test
        void reportsTooManyTriplesAsContentTooLarge() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(1201).when(nanopub).getTripleCount();

            NanopubRejectedException ex = rejectionOf(nanopub, "some-pubkey", null);
            assertEquals(413, ex.getStatusCode());
            assertEquals("triple count 1201 exceeds limit of 1200", ex.getMessage());
        }

        @Test
        void reportsAnOversizedNanopubAsContentTooLarge() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(1_000_001L).when(nanopub).getByteCount();

            NanopubRejectedException ex = rejectionOf(nanopub, "some-pubkey", null);
            assertEquals(413, ex.getStatusCode());
            assertEquals("size 1000001 bytes exceeds limit of 1000000", ex.getMessage());
        }

        /**
         * The reason from the issue report: the registry knew the timestamp was in the future and
         * said so in its log, while the publisher got the same sentence as every other refusal.
         */
        @Test
        void reportsAFutureTimestampAsUnprocessable() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            Calendar future = Calendar.getInstance();
            future.add(Calendar.HOUR, 2);
            doReturn(future).when(nanopub).getCreationTime();

            NanopubRejectedException ex = rejectionOf(nanopub, "some-pubkey", null);
            assertEquals(422, ex.getStatusCode());
            assertTrue(ex.getMessage().contains("is more than 60s in the future"), ex.getMessage());
            assertTrue(ex.getMessage().contains(future.toInstant().toString()), ex.getMessage());
        }

        @Test
        void reportsAGraphUriOutsideTheNanopubAsUnprocessable() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(Set.of(iri("http://elsewhere.example.org/np/Head"))).when(nanopub).getGraphUris();

            NanopubRejectedException ex = rejectionOf(nanopub, "some-pubkey", null);
            assertEquals(422, ex.getStatusCode());
            assertEquals("graph URI http://elsewhere.example.org/np/Head does not start with the nanopub base URI",
                    ex.getMessage());
        }

        @Test
        void reportsAMismatchedPubkeyHashAsUnprocessable() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);

            NanopubRejectedException ex = rejectionOf(nanopub, pubkey, "not-the-right-hash");
            assertEquals(422, ex.getStatusCode());
            assertTrue(ex.getMessage().startsWith("provided pubkey hash not-the-right-hash does not match computed hash "),
                    ex.getMessage());
        }

        @Test
        void reportsAMissingArtifactCodeAsUnprocessable() throws Exception {
            Nanopub nanopub = spy(testSuiteNanopub(SIMPLE1_AC));
            doReturn(iri("http://example.org/not-a-trusty-uri")).when(nanopub).getUri();
            doReturn(Set.<IRI>of()).when(nanopub).getGraphUris();

            NanopubRejectedException ex = rejectionOf(nanopub, "some-pubkey", null);
            assertEquals(422, ex.getStatusCode());
            assertEquals("could not extract artifact code from Trusty URI", ex.getMessage());
        }

        @Test
        void reportsAnArtifactCodeThatDoesNotMatchTheContentAsUnprocessable() throws Exception {
            String wrongCode = artifactCode("notTheHashOfThisContent");
            Nanopub nanopub = withArtifactCode(testSuiteNanopub(SIMPLE1_AC), wrongCode);

            NanopubRejectedException ex = rejectionOf(nanopub, RegistryDB.getPubkey(nanopub), null);
            assertEquals(422, ex.getStatusCode());
            assertEquals("artifact code " + wrongCode + " does not verify against its content", ex.getMessage());
        }

        @Test
        void saysNothingWhenTheNanopubIsAcceptable() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            RegistryDB.loadNanopubOrReject(session, nanopub, RegistryDB.getPubkey(nanopub), null);
            assertNotNull(nanopubDoc(SIMPLE1_AC));
        }

        /**
         * Only a refusal is a {@link NanopubRejectedException}; a storage failure still comes out
         * as itself, so the POST handler does not answer 4xx for a problem on our side.
         */
        @Test
        void doesNotDressUpAStorageFailureAsARejection() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);

            try (MockedStatic<NanopubUtils> nanopubUtils = mockStatic(NanopubUtils.class, CALLS_REAL_METHODS)) {
                nanopubUtils.when(() -> NanopubUtils.writeToString(org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                        .thenThrow(new IOException("disk on fire"));

                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> RegistryDB.loadNanopubOrReject(session, nanopub, pubkey, null));
                assertFalse(thrown instanceof NanopubRejectedException);
                assertTrue(thrown.getCause() instanceof IOException,
                        "the serialization failure has to survive as the cause, got: " + thrown.getCause());
            }
        }
    }


    // --- concurrent-insert handling -----------------------------------------

    @Nested
    class ConcurrentInsertHandling {

        @Test
        void skipsANanopubWhoseFullIdWasClaimedConcurrently() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);

            // Stand in for a parallel worker that stored the same nanopub under a different _id
            // between our existence check and our insert: 'fullId' is uniquely indexed.
            RegistryDB.insert(session, Collection.NANOPUBS.toString(),
                    new Document("_id", "RAsomeOtherArtifactCode000000000000000000000")
                            .append("fullId", nanopub.getUri().stringValue())
                            .append("counter", 999_999L));

            // The duplicate is swallowed: the nanopub is considered present, so this still succeeds.
            assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));
            assertNull(nanopubDoc(SIMPLE1_AC), "our own insert must not have landed");
        }

        @Test
        void rethrowsAWriteErrorThatIsNotADuplicateKey() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            rejectAllWritesTo(Collection.NANOPUBS.toString());

            // Anything other than a duplicate key is a real failure and must not be swallowed.
            assertThrows(com.mongodb.MongoWriteException.class,
                    () -> RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));
        }

        @Test
        void recordHashIgnoresAConcurrentlyInsertedHash() {
            String value = "a-public-key-string";
            // 'hash' is uniquely indexed, so a row carrying our hash under a different value
            // makes our upsert fail exactly the way a parallel writer would.
            RegistryDB.insert(session, "hashes",
                    new Document("value", "some-other-value").append("hash", Utils.getHash(value)));

            RegistryDB.recordHash(session, value);  // must not throw

            assertEquals(1, RegistryDB.collection("hashes")
                    .countDocuments(session, new Document("hash", Utils.getHash(value))));
        }

        @Test
        void recordHashRethrowsAWriteErrorThatIsNotADuplicateKey() {
            rejectAllWritesTo("hashes");

            assertThrows(com.mongodb.MongoWriteException.class,
                    () -> RegistryDB.recordHash(session, "a-public-key-string"));
        }
    }

    // --- invalidations -------------------------------------------------------

    @Nested
    class Invalidations {

        @Test
        void addsAnInvalidatingNanopubToTheInvalidatedNanopubsLists() throws Exception {
            Nanopub superseder = testSuiteNanopub(SUPERSEDER_AC);
            String pubkey = RegistryDB.getPubkey(superseder);
            String pubkeyHash = Utils.getHash(pubkey);
            String typeHash = "some-type-hash";

            // The superseded nanopub is already listed under this pubkey/type.
            seedListEntry(pubkeyHash, typeHash, 0L, SUPERSEDED_AC, checksum("old-entry"));

            assertTrue(RegistryDB.loadNanopubVerified(session, superseder, pubkey, null));

            // The invalidation is recorded ...
            assertNotNull(RegistryDB.collection("invalidations").find(session,
                    new Document("invalidatingNp", SUPERSEDER_AC).append("invalidatedNp", SUPERSEDED_AC)).first());
            // ... the old entry is flagged ...
            Document oldEntry = listEntry(pubkeyHash, typeHash, SUPERSEDED_AC);
            assertTrue(oldEntry.getBoolean("invalidated"),
                    "the superseded nanopub's entry has to be marked invalidated");
            // ... and the superseding nanopub inherits its place on the same list, so consumers
            // following that list see the replacement.
            Document newEntry = listEntry(pubkeyHash, typeHash, SUPERSEDER_AC);
            assertNotNull(newEntry, "the superseding nanopub has to be appended to the same list");
            assertEquals(1L, newEntry.getLong("position"));
        }

        /**
         * A trust edge is taken back by invalidating its source, and only whoever signed the
         * source can do that. Somebody else's nanopub naming the same source changes nothing.
         */
        @Test
        void invalidatesATrustEdgeOnlyForThePubkeyThatSignedItsSource() throws Exception {
            Nanopub superseder = testSuiteNanopub(SUPERSEDER_AC);
            String pubkey = RegistryDB.getPubkey(superseder);
            String pubkeyHash = Utils.getHash(pubkey);
            RegistryDB.insert(session, "trustEdges", new Document("fromAgent", "signer").append("fromPubkey", pubkeyHash)
                    .append("toAgent", "A").append("toPubkey", "P").append("source", SUPERSEDED_AC).append("invalidated", false));
            RegistryDB.insert(session, "trustEdges", new Document("fromAgent", "somebodyElse").append("fromPubkey", "theHashOfAnotherPubkey")
                    .append("toAgent", "A").append("toPubkey", "P").append("source", SUPERSEDED_AC).append("invalidated", false));

            assertTrue(RegistryDB.loadNanopubVerified(session, superseder, pubkey, pubkeyHash, "$"));

            assertTrue(RegistryDB.collection("trustEdges").find(session, new Document("fromAgent", "signer")).first().getBoolean("invalidated"));
            assertFalse(RegistryDB.collection("trustEdges").find(session, new Document("fromAgent", "somebodyElse")).first().getBoolean("invalidated"),
                    "an endorsement cannot be taken back by somebody else");
        }

        @Test
        void marksListEntriesInvalidatedWhenTheInvalidatingNanopubIsAlreadyKnown() throws Exception {
            Nanopub invalidator = testSuiteNanopub(SUPERSEDER_AC);
            Nanopub target = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(target);
            String pubkeyHash = Utils.getHash(pubkey);

            // The invalidator arrived first and is already stored ...
            assertTrue(RegistryDB.loadNanopubVerified(session, invalidator, RegistryDB.getPubkey(invalidator), null));
            // ... and its invalidation of the not-yet-loaded target was recorded then.
            RegistryDB.insert(session, "invalidations", new Document("invalidatingNp", SUPERSEDER_AC)
                    .append("invalidatingPubkey", pubkeyHash).append("invalidatedNp", SIMPLE1_AC));

            assertTrue(RegistryDB.loadNanopubVerified(session, target, pubkey, pubkeyHash, "$"));

            // The target lands on the list already invalidated — it must never appear as current.
            Document targetEntry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertNotNull(targetEntry);
            assertTrue(targetEntry.getBoolean("invalidated"));

            // And the invalidator joins the lists the target is on, so the replacement is
            // discoverable from the same place as the nanopub it replaces, just as if it had
            // arrived second.
            List<String> listsOfTarget = RegistryDB.collection("listEntries")
                    .find(session, new Document("pubkey", pubkeyHash).append("np", SIMPLE1_AC))
                    .map(e -> e.getString("type")).into(new java.util.ArrayList<>());
            assertTrue(listsOfTarget.size() > 1, "the target is on the lists of its types as well");
            for (String typeHash : listsOfTarget) {
                Document invalidatorEntry = listEntry(pubkeyHash, typeHash, SUPERSEDER_AC);
                assertNotNull(invalidatorEntry, "the invalidator has to be on list " + typeHash);
                assertFalse(invalidatorEntry.getBoolean("invalidated"));
            }
        }
    }

    // --- list placement ------------------------------------------------------

    @Nested
    class ListPlacement {

        @Test
        void expandsTheCoreTypeIntoTheNanopubsOwnTypes() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));

            // "$" lists the nanopub under the core list and, because every type is covered by
            // default, under one list per declared type as well.
            assertNotNull(listEntry(pubkeyHash, "$", SIMPLE1_AC));
            assertNotNull(listEntry(pubkeyHash, Utils.getHash("http://purl.org/nanopub/x/ExampleNanopub"), SIMPLE1_AC));
            assertNotNull(listEntry(pubkeyHash, Utils.getHash("http://example.org/transmits"), SIMPLE1_AC));
        }

        @Test
        void listsANanopubUnderAnExplicitTypeWithoutExpanding() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);
            String requestedType = "http://example.org/some-type";

            assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, requestedType));

            // Only "$" triggers the expansion into the nanopub's own types; a named type
            // lists it exactly once, under that type.
            assertNotNull(listEntry(pubkeyHash, Utils.getHash(requestedType), SIMPLE1_AC));
            assertNull(listEntry(pubkeyHash, Utils.getHash("http://purl.org/nanopub/x/ExampleNanopub"), SIMPLE1_AC),
                    "a named type must not pull in the nanopub's declared types");
            assertEquals(1, RegistryDB.collection("listEntries")
                    .countDocuments(session, new Document("pubkey", pubkeyHash).append("np", SIMPLE1_AC)));
        }

        @Test
        void stopsWhenAConcurrentWriterListedTheNanopubFirst() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // Computed up front: inside the static mock below, updateXorChecksum is stubbed.
            String winnersChecksum = checksum("written-by-the-other-thread");

            // Drop a competing entry in after the "is it already listed?" check has passed but
            // before our own insert — the exact window the retry loop's duplicate handling is for.
            // Checksum chaining is the last thing that happens in between, so it is the hook.
            try (MockedStatic<NanopubUtils> nanopubUtils = mockStatic(NanopubUtils.class, CALLS_REAL_METHODS)) {
                nanopubUtils.when(() -> NanopubUtils.updateXorChecksum(
                                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                        .thenAnswer(invocation -> {
                            if (listEntry(pubkeyHash, "$", SIMPLE1_AC) == null) {
                                seedListEntry(pubkeyHash, "$", 0L, SIMPLE1_AC, winnersChecksum);
                            }
                            return invocation.callRealMethod();
                        });

                // The loser of the race gives up quietly instead of retrying forever.
                assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
            }

            assertEquals(1, RegistryDB.collection("listEntries").countDocuments(session,
                    new Document("pubkey", pubkeyHash).append("type", "$").append("np", SIMPLE1_AC)));
            assertEquals(winnersChecksum, listEntry(pubkeyHash, "$", SIMPLE1_AC).getString("checksum"),
                    "the winner's entry has to be left untouched");
        }

        @Test
        void reusesTheListDocumentForASecondNanopub() throws Exception {
            Nanopub first = testSuiteNanopub(SIMPLE1_AC);
            Nanopub second = testSuiteNanopub(SUPERSEDER_AC);
            String pubkey = RegistryDB.getPubkey(first);
            String pubkeyHash = Utils.getHash(pubkey);
            // Both test suite nanopubs are signed with the same key.
            assertEquals(pubkey, RegistryDB.getPubkey(second));

            RegistryDB.loadNanopubVerified(session, first, pubkey, pubkeyHash, "$");
            RegistryDB.loadNanopubVerified(session, second, pubkey, pubkeyHash, "$");

            // The second insert of the list document is a duplicate key and has to be ignored,
            // leaving exactly one list holding both entries at consecutive positions.
            assertEquals(1, RegistryDB.collection("lists")
                    .countDocuments(session, new Document("pubkey", pubkeyHash).append("type", "$")));
            assertEquals(0L, listEntry(pubkeyHash, "$", SIMPLE1_AC).getLong("position"));
            assertEquals(1L, listEntry(pubkeyHash, "$", SUPERSEDER_AC).getLong("position"));
            assertEquals(1L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
        }

        @Test
        void doesNotListTheSameNanopubTwice() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");
            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            assertEquals(1, RegistryDB.collection("listEntries").countDocuments(session,
                    new Document("pubkey", pubkeyHash).append("type", "$").append("np", SIMPLE1_AC)));
            // The position counter must not advance for a nanopub that was already listed.
            assertEquals(0L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
        }

        @Test
        void adoptsTheHighestPositionOfALegacyListWithoutAMaxPosition() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // A list written before 'maxPosition' existed: entries but no counter.
            RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash).append("type", "$"));
            seedListEntry(pubkeyHash, "$", 0L, "RAlegacyEntryZero00000000000000000000000000", checksum("zero"));
            seedListEntry(pubkeyHash, "$", 1L, "RAlegacyEntryOne000000000000000000000000000", checksum("one"));
            seedListEntry(pubkeyHash, "$", 2L, "RAlegacyEntryTwo000000000000000000000000000", checksum("two"));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            // The counter is back-filled from the existing entries, so the new entry continues
            // the sequence instead of colliding with position 0.
            assertEquals(3L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
            assertEquals(3L, listEntry(pubkeyHash, "$", SIMPLE1_AC).getLong("position"));
        }

        @Test
        void startsALegacyListWithoutEntriesAtPositionZero() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // A list document with neither a counter nor any entries.
            RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash).append("type", "$"));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            assertEquals(0L, listEntry(pubkeyHash, "$", SIMPLE1_AC).getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), NanopubUtils.INIT_CHECKSUM),
                    listEntry(pubkeyHash, "$", SIMPLE1_AC).getString("checksum"),
                    "the first entry chains off the initial checksum");
        }

        @Test
        void continuesTheChainFromTheLastEntryRatherThanTheCounter() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // The counter claims to be at 5, but the entries end at 3 — what a crash between an
            // old-style position claim and its insert left behind.
            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 5L));
            seedListEntry(pubkeyHash, "$", 3L, "RAearlierEntry0000000000000000000000000000", checksum("three"));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            // The chain is what counts: the new entry follows the last entry that exists and
            // chains off its checksum. The counter is only ever raised, never lowered.
            Document entry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertEquals(4L, entry.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), checksum("three")), entry.getString("checksum"));
            assertEquals(5L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
        }

        @Test
        void startsAtPositionZeroWhenTheCounterIsAheadOfAnEmptyList() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 5L));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            Document entry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertEquals(0L, entry.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), NanopubUtils.INIT_CHECKSUM),
                    entry.getString("checksum"));
        }

        @Test
        void raisesALaggingCounterToTheNewPosition() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // The counter lags behind the entries that are actually there.
            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 0L));
            seedListEntry(pubkeyHash, "$", 1L, "RAoccupantOne00000000000000000000000000000", checksum("one"));
            seedListEntry(pubkeyHash, "$", 2L, "RAoccupantTwo00000000000000000000000000000", checksum("two"));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            Document entry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertEquals(3L, entry.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), checksum("two")), entry.getString("checksum"));
            assertEquals(3L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
        }

        /**
         * Seeds a competing entry at the position the writer is about to insert at. The hook
         * sits in the checksum computation, which happens after the writer has read the end of
         * the chain and before it inserts — exactly the window a concurrent writer wins in.
         */
        private MockedStatic<NanopubUtils> competitorTakingThePosition(String pubkeyHash, int howManyTimes) {
            MockedStatic<NanopubUtils> nanopubUtils = mockStatic(NanopubUtils.class, CALLS_REAL_METHODS);
            int[] calls = {0};
            nanopubUtils.when(() -> NanopubUtils.updateXorChecksum(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> {
                        int call = calls[0]++;
                        if (call < howManyTimes) {
                            Document last = RegistryDB.getMaxValueDocument(session, "listEntries",
                                    new Document("pubkey", pubkeyHash).append("type", "$"), "position");
                            long next = (last == null) ? 0L : last.getLong("position") + 1;
                            // Not via NanopubUtils: that is the method being stubbed here.
                            seedListEntry(pubkeyHash, "$", next, artifactCode("competitor" + call),
                                    rawXorChecksum(artifactCode("competitor" + call), NanopubUtils.INIT_CHECKSUM));
                        }
                        return invocation.callRealMethod();
                    });
            return nanopubUtils;
        }

        @Test
        void chainsOffTheCompetitorThatTookThePositionFirst() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);
            seedListEntry(pubkeyHash, "$", 0L, "RAexistingEntry000000000000000000000000000", checksum("zero"));

            try (MockedStatic<NanopubUtils> ignored = competitorTakingThePosition(pubkeyHash, 1)) {
                assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
            }

            // The competitor got position 1, so our entry sits at 2 and its checksum is computed
            // from the competitor's, not from the entry we had read before losing the race. That
            // is the whole point: the chain stays sequential no matter who wins.
            Document competitor = RegistryDB.collection("listEntries").find(session,
                    new Document("pubkey", pubkeyHash).append("type", "$").append("position", 1L)).first();
            assertNotNull(competitor);
            Document entry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertEquals(2L, entry.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), competitor.getString("checksum")), entry.getString("checksum"));
            assertEquals(2L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
        }

        @Test
        void givesUpAfterAHundredLostRaces() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            try (MockedStatic<NanopubUtils> ignored = competitorTakingThePosition(pubkeyHash, Integer.MAX_VALUE)) {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
                assertEquals("Failed to insert list entry after 101 attempts", thrown.getMessage());
            }
            assertNull(listEntry(pubkeyHash, "$", SIMPLE1_AC));
        }

        private static List<Nanopub> trustyNanopubs(int count) throws Exception {
            List<Nanopub> nanopubs = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                NanopubCreator creator = new NanopubCreator("http://example.org/chain-test/" + i + "/");
                creator.addAssertionStatement(iri("http://example.org/thing/" + i), RDF.TYPE, iri("http://example.org/Thing"));
                creator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), iri("http://example.org/someone"));
                creator.addPubinfoStatement(iri("http://purl.org/dc/terms/description"), SimpleValueFactory.getInstance().createLiteral("chain test " + i));
                nanopubs.add(creator.finalizeTrustyNanopub());
            }
            return nanopubs;
        }

        /**
         * What a load from a peer looks like: the parallel loader verifies and stores on several
         * workers, but the list must come out in the order the peer served the nanopubs, so
         * that the two registries' checksums agree at every position.
         */
        @Test
        void aParallelLoadKeepsTheStreamOrderOnTheList() throws Exception {
            String pubkey = "stream-order-test-key";
            String pubkeyHash = Utils.getHash(pubkey);
            // More than one batch of appends, so that the order holds across flushes as well.
            List<Nanopub> nanopubs = trustyNanopubs(2 * ListAppendBatch.FLUSH_SIZE + 50);

            loadInParallel(nanopubs.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);

            assertListFollows(nanopubs, pubkeyHash);
        }

        private static void loadInParallel(Stream<org.nanopub.jelly.MaybeNanopub> stream, String pubkey, String pubkeyHash) {
            NanopubLoader.loadStreamInParallel(stream, nanopub -> {
                try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                    assertTrue(RegistryDB.loadNanopubVerified(workerSession, nanopub, pubkey, pubkeyHash, "$"));
                }
            });
        }

        /** The "$" list of the pubkey holds exactly these nanopubs, in this order, as one chain. */
        private void assertListFollows(List<Nanopub> nanopubs, String pubkeyHash) {
            List<Document> entries = RegistryDB.collection("listEntries")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$"))
                    .sort(new Document("position", 1)).into(new java.util.ArrayList<>());
            List<String> expected = nanopubs.stream().map(np -> TrustyUriUtils.getArtifactCode(np.getUri().stringValue())).toList();
            assertEquals(expected, entries.stream().map(e -> e.getString("np")).toList(), "the list follows the stream");
            String checksum = NanopubUtils.INIT_CHECKSUM;
            for (int i = 0; i < nanopubs.size(); i++) {
                checksum = NanopubUtils.updateXorChecksum(nanopubs.get(i).getUri(), checksum);
                assertEquals(i, entries.get(i).getLong("position"));
                assertEquals(checksum, entries.get(i).getString("checksum"));
            }
        }

        /**
         * A stream that breaks ends the load, but the nanopubs served before the break are
         * stored, and their entries must not be dropped with the batch they were waiting in.
         */
        @Test
        void aParallelLoadWritesThePendingEntriesWhenTheStreamBreaks() throws Exception {
            String pubkey = "broken-stream-test-key";
            String pubkeyHash = Utils.getHash(pubkey);
            // Fewer than a batch, so that nothing is written before the stream breaks.
            List<Nanopub> nanopubs = trustyNanopubs(10);
            Stream<org.nanopub.jelly.MaybeNanopub> broken = Stream.concat(
                    nanopubs.stream().map(org.nanopub.jelly.MaybeNanopub::new),
                    Stream.<org.nanopub.jelly.MaybeNanopub>generate(() -> {
                        throw new IllegalStateException("the peer went away");
                    }).limit(1));

            assertThrows(IllegalStateException.class, () -> loadInParallel(broken, pubkey, pubkeyHash));

            assertListFollows(nanopubs, pubkeyHash);
        }

        /**
         * Loading what is stored and listed already changes nothing, and what is stored but
         * missing from the list is appended, in stream order.
         */
        @Test
        void aParallelReloadAddsOnlyWhatIsMissing() throws Exception {
            String pubkey = "reload-test-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> nanopubs = trustyNanopubs(ListAppendBatch.FLUSH_SIZE + 50);
            loadInParallel(nanopubs.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);

            // As if the registry had stopped before the last entries were written.
            RegistryDB.collection("listEntries").deleteMany(session, new Document("pubkey", pubkeyHash).append("type", "$")
                    .append("position", new Document("$gte", (long) nanopubs.size() - 20)));
            loadInParallel(nanopubs.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            assertListFollows(nanopubs, pubkeyHash);

            loadInParallel(nanopubs.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            assertListFollows(nanopubs, pubkeyHash);
        }

        private static final IRI TYPE_OF_INVALIDATED = iri("http://example.org/OnlyTheInvalidatedHasThisType");

        /** A nanopub with a type of its own, and one of another type that supersedes it. */
        private static List<Nanopub> invalidatedAndInvalidating() throws Exception {
            NanopubCreator invalidatedCreator = new NanopubCreator("http://example.org/invalidated/");
            invalidatedCreator.addAssertionStatement(iri("http://example.org/thing"), RDF.TYPE, iri("http://example.org/Thing"));
            invalidatedCreator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), iri("http://example.org/someone"));
            invalidatedCreator.addPubinfoStatement(RDF.TYPE, TYPE_OF_INVALIDATED);
            Nanopub invalidated = invalidatedCreator.finalizeTrustyNanopub();

            NanopubCreator invalidatingCreator = new NanopubCreator("http://example.org/invalidating/");
            invalidatingCreator.addAssertionStatement(iri("http://example.org/thing"), RDF.TYPE, iri("http://example.org/OtherThing"));
            invalidatingCreator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), iri("http://example.org/someone"));
            invalidatingCreator.addPubinfoStatement(org.nanopub.vocabulary.NPX.SUPERSEDES, invalidated.getUri());
            return List.of(invalidated, invalidatingCreator.finalizeTrustyNanopub());
        }

        private long entryCount(String pubkeyHash) {
            return RegistryDB.collection("listEntries").countDocuments(session, new Document("pubkey", pubkeyHash));
        }

        /**
         * The invalidation of the pair above is on record once and shows on the lists, which
         * hold as many entries as after a load that nothing interfered with.
         */
        private void assertInvalidationApplied(List<Nanopub> pair, String pubkeyHash, long entriesOfACleanLoad) {
            String invalidatedAc = TrustyUriUtils.getArtifactCode(pair.get(0).getUri().stringValue());
            String invalidatingAc = TrustyUriUtils.getArtifactCode(pair.get(1).getUri().stringValue());
            String typeHash = Utils.getHash(TYPE_OF_INVALIDATED.stringValue());
            assertEquals(1L, RegistryDB.collection("invalidations").countDocuments(session,
                    new Document("invalidatingNp", invalidatingAc).append("invalidatedNp", invalidatedAc)), "the invalidation is on record, once");
            for (String type : List.of("$", typeHash)) {
                Document invalidatedEntry = listEntry(pubkeyHash, type, invalidatedAc);
                assertNotNull(invalidatedEntry);
                assertTrue(invalidatedEntry.getBoolean("invalidated"), "the invalidated nanopub's entries are marked");
                Document invalidatingEntry = listEntry(pubkeyHash, type, invalidatingAc);
                assertNotNull(invalidatingEntry, "the invalidating nanopub is on the lists of the invalidated one");
                assertFalse(invalidatingEntry.getBoolean("invalidated"));
            }
            assertEquals(entriesOfACleanLoad, entryCount(pubkeyHash));
        }

        /**
         * A registry that is stopped between storing an invalidating nanopub and applying the
         * invalidation is left with the nanopub and none of its effects. The nanopub counts as
         * known from then on, so the next load has to apply the invalidation all the same.
         */
        @Test
        void aParallelReloadAppliesAnInvalidationThatWasNeverApplied() throws Exception {
            String pubkey = "unapplied-invalidation-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> pair = invalidatedAndInvalidating();
            loadInParallel(pair.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            // What is left when the registry stops right after storing the two nanopubs.
            long entriesOfACleanLoad = entryCount(pubkeyHash);
            RegistryDB.collection("invalidations").deleteMany(session, new Document());
            RegistryDB.collection("listEntries").deleteMany(session, new Document());
            RegistryDB.collection("lists").deleteMany(session, new Document());

            loadInParallel(pair.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            assertInvalidationApplied(pair, pubkeyHash, entriesOfACleanLoad);

            // And applying it again changes nothing.
            loadInParallel(pair.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            assertInvalidationApplied(pair, pubkeyHash, entriesOfACleanLoad);
        }

        /** The same outside a parallel load, where the entries are written directly. */
        @Test
        void aDirectReloadAppliesAnInvalidationThatWasNeverApplied() throws Exception {
            String pubkey = "unapplied-invalidation-direct-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> pair = invalidatedAndInvalidating();
            for (Nanopub nanopub : pair) {
                assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
            }
            long entriesOfACleanLoad = entryCount(pubkeyHash);
            RegistryDB.collection("invalidations").deleteMany(session, new Document());
            RegistryDB.collection("listEntries").deleteMany(session, new Document());
            RegistryDB.collection("lists").deleteMany(session, new Document());

            for (int round = 0; round < 2; round++) {
                for (Nanopub nanopub : pair) {
                    assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
                }
                assertInvalidationApplied(pair, pubkeyHash, entriesOfACleanLoad);
            }
        }

        /**
         * A registry that is stopped with entries waiting in a batch has the invalidation on
         * record, but not the entries it was applied to. When the next load lists the
         * invalidated nanopub again, the invalidating one has to join its lists again too.
         */
        @Test
        void aParallelReloadAppliesARecordedInvalidationToEntriesThatWereLost() throws Exception {
            String pubkey = "lost-entries-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> pair = invalidatedAndInvalidating();
            loadInParallel(pair.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            long entriesOfACleanLoad = entryCount(pubkeyHash);
            RegistryDB.collection("listEntries").deleteMany(session, new Document());
            RegistryDB.collection("lists").deleteMany(session, new Document());

            loadInParallel(pair.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
            assertInvalidationApplied(pair, pubkeyHash, entriesOfACleanLoad);
        }

        /**
         * A writer outside the load, such as the POST handler, cannot see the entries waiting
         * in the load's batch. An invalidation it records meanwhile still has to reach them.
         */
        @Test
        void anInvalidationFromAnotherWriterReachesEntriesOfARunningLoad() throws Exception {
            String pubkey = "outside-invalidation-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> pair = invalidatedAndInvalidating();
            Nanopub invalidated = pair.get(0);
            // Keeps the load, and with it the batch holding the invalidated nanopub's entries, open.
            Nanopub straggler = trustyNanopubs(1).get(0);
            java.util.concurrent.CountDownLatch invalidatedLoaded = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

            Thread load = new Thread(() -> NanopubLoader.loadStreamInParallel(
                    Stream.of(invalidated, straggler).map(org.nanopub.jelly.MaybeNanopub::new), nanopub -> {
                        if (nanopub == straggler) {
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                            RegistryDB.loadNanopubVerified(workerSession, nanopub, pubkey, pubkeyHash, "$");
                        }
                        if (nanopub == invalidated) {
                            invalidatedLoaded.countDown();
                        }
                    }));
            load.start();
            try {
                assertTrue(invalidatedLoaded.await(30, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(RegistryDB.loadNanopubVerified(session, pair.get(1), pubkey, pubkeyHash, "$"));
            } finally {
                release.countDown();
                load.join();
            }

            String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidated.getUri().stringValue());
            for (String type : List.of("$", Utils.getHash(TYPE_OF_INVALIDATED.stringValue()))) {
                Document entry = listEntry(pubkeyHash, type, invalidatedAc);
                assertNotNull(entry);
                assertTrue(entry.getBoolean("invalidated"), "the invalidation reaches the entries written after it was recorded");
                assertNotNull(listEntry(pubkeyHash, type, TrustyUriUtils.getArtifactCode(pair.get(1).getUri().stringValue())),
                        "the invalidating nanopub joins the lists of entries written after it was recorded");
            }
        }

        /** Three versions of a nanopub, each superseding the one before; only the first has a type of its own. */
        private static List<Nanopub> threeVersions() throws Exception {
            List<Nanopub> versions = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                NanopubCreator creator = new NanopubCreator("http://example.org/version/" + i + "/");
                creator.addAssertionStatement(iri("http://example.org/thing"), RDF.TYPE, iri("http://example.org/Thing"));
                creator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), iri("http://example.org/someone"));
                creator.addPubinfoStatement(iri("http://purl.org/dc/terms/description"), SimpleValueFactory.getInstance().createLiteral("version " + i));
                if (i == 0) {
                    creator.addPubinfoStatement(RDF.TYPE, TYPE_OF_INVALIDATED);
                } else {
                    creator.addPubinfoStatement(org.nanopub.vocabulary.NPX.SUPERSEDES, versions.get(i - 1).getUri());
                }
                versions.add(creator.finalizeTrustyNanopub());
            }
            return versions;
        }

        /** What is on the lists of a pubkey, order aside: list -> the nanopubs on it, with their invalidated flag. */
        private java.util.Map<String, Set<String>> listContents(String pubkeyHash) {
            java.util.Map<String, Set<String>> contents = new java.util.TreeMap<>();
            for (Document entry : RegistryDB.collection("listEntries").find(session, new Document("pubkey", pubkeyHash))) {
                contents.computeIfAbsent(entry.getString("type"), k -> new java.util.TreeSet<>())
                        .add(entry.getString("np") + (entry.getBoolean("invalidated") ? " (invalidated)" : ""));
            }
            return contents;
        }

        private static final int[][] ARRIVAL_ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

        /**
         * The types of an invalidated nanopub also apply to the one that invalidates it, down
         * a chain of versions. That is a statement about the nanopubs, not about the order in
         * which a registry happens to receive them, so every order has to give the same lists.
         */
        @Test
        void theListsDoNotDependOnTheOrderOfArrival() throws Exception {
            List<Nanopub> versions = threeVersions();
            List<String> acs = versions.stream().map(np -> TrustyUriUtils.getArtifactCode(np.getUri().stringValue())).toList();
            java.util.Map<String, Set<String>> expected = null;
            for (int[] order : ARRIVAL_ORDERS) {
                String pubkey = "arrival-order-key-" + java.util.Arrays.toString(order);
                String pubkeyHash = Utils.getHash(pubkey);
                for (int i : order) {
                    assertTrue(RegistryDB.loadNanopubVerified(session, versions.get(i), pubkey, pubkeyHash, "$"));
                }
                java.util.Map<String, Set<String>> contents = listContents(pubkeyHash);
                if (expected == null) {
                    // The order of publication, which earlier versions handled as well.
                    expected = contents;
                    String typeHash = Utils.getHash(TYPE_OF_INVALIDATED.stringValue());
                    assertEquals(Set.of(acs.get(0) + " (invalidated)", acs.get(1) + " (invalidated)", acs.get(2)), contents.get(typeHash),
                            "every version is on the list of the first one's type, and only the last is current");
                    assertEquals(contents.get(typeHash), contents.get("$"));
                } else {
                    assertEquals(expected, contents, "arrival order " + java.util.Arrays.toString(order));
                }
            }
        }

        /** The same for a parallel load, where the entries of the stream wait in a batch. */
        @Test
        void theListsOfAParallelLoadDoNotDependOnTheOrderOfTheStream() throws Exception {
            List<Nanopub> versions = threeVersions();
            java.util.Map<String, Set<String>> expected = null;
            for (int[] order : ARRIVAL_ORDERS) {
                String pubkey = "stream-order-key-" + java.util.Arrays.toString(order);
                String pubkeyHash = Utils.getHash(pubkey);
                loadInParallel(java.util.Arrays.stream(order).mapToObj(versions::get).map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);
                java.util.Map<String, Set<String>> contents = listContents(pubkeyHash);
                if (expected == null) {
                    expected = contents;
                    assertEquals(3, contents.get(Utils.getHash(TYPE_OF_INVALIDATED.stringValue())).size());
                } else {
                    assertEquals(expected, contents, "stream order " + java.util.Arrays.toString(order));
                }
                // Every list is one sequential chain, whoever was appended when.
                for (String typeHash : contents.keySet()) {
                    String checksum = NanopubUtils.INIT_CHECKSUM;
                    long position = 0;
                    for (Document entry : RegistryDB.collection("listEntries").find(session, new Document("pubkey", pubkeyHash).append("type", typeHash))
                            .sort(new Document("position", 1))) {
                        checksum = NanopubUtils.updateXorChecksum(iri("https://w3id.org/np/" + entry.getString("np")), checksum);
                        assertEquals(position++, entry.getLong("position"));
                        assertEquals(checksum, entry.getString("checksum"));
                    }
                }
            }
        }

        /**
         * Several writers loading the same invalidating nanopub at the same moment, as a peer
         * load and a publisher's POST can, put the invalidation on record once.
         */
        @Test
        void concurrentLoadsRecordAnInvalidationOnce() throws Exception {
            String pubkey = "concurrent-invalidation-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> pair = invalidatedAndInvalidating();
            assertTrue(RegistryDB.loadNanopubVerified(session, pair.get(0), pubkey, pubkeyHash, "$"));

            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(executor.submit(() -> {
                        try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                            return RegistryDB.loadNanopubVerified(workerSession, pair.get(1), pubkey, pubkeyHash, "$");
                        }
                    }));
                }
                for (Future<Boolean> result : results) {
                    assertTrue(result.get());
                }
            } finally {
                executor.shutdownNow();
            }

            assertInvalidationApplied(pair, pubkeyHash, entryCount(pubkeyHash));
        }

        /**
         * In a parallel load the entries of a nanopub may still be waiting in the batch when a
         * later nanopub of the stream invalidates it. The invalidation has to reach those
         * entries, and the invalidating nanopub has to land on the invalidated one's lists.
         */
        @Test
        void aParallelLoadAppliesInvalidationsToPendingEntries() throws Exception {
            String pubkey = "pending-invalidation-key";
            String pubkeyHash = Utils.getHash(pubkey);
            List<Nanopub> pair = invalidatedAndInvalidating();
            Nanopub invalidated = pair.get(0);
            Nanopub invalidating = pair.get(1);
            String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidated.getUri().stringValue());
            String invalidatingAc = TrustyUriUtils.getArtifactCode(invalidating.getUri().stringValue());

            loadInParallel(pair.stream().map(org.nanopub.jelly.MaybeNanopub::new), pubkey, pubkeyHash);

            String typeHash = Utils.getHash(TYPE_OF_INVALIDATED.stringValue());
            Document invalidatedOnDollar = listEntry(pubkeyHash, "$", invalidatedAc);
            Document invalidatedOnType = listEntry(pubkeyHash, typeHash, invalidatedAc);
            assertNotNull(invalidatedOnDollar);
            assertNotNull(invalidatedOnType);
            assertTrue(invalidatedOnDollar.getBoolean("invalidated"), "the invalidation reaches an entry that was still pending");
            assertTrue(invalidatedOnType.getBoolean("invalidated"));
            Document invalidatingOnType = listEntry(pubkeyHash, typeHash, invalidatingAc);
            assertNotNull(invalidatingOnType, "the invalidating nanopub joins the lists of the invalidated one");
            assertFalse(invalidatingOnType.getBoolean("invalidated"));
            assertEquals(0L, invalidatedOnType.getLong("position"));
            assertEquals(1L, invalidatingOnType.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(invalidating.getUri(), invalidatedOnType.getString("checksum")),
                    invalidatingOnType.getString("checksum"));
        }

        /**
         * The scenario of issue #205: many writers appending to the same list at once, without
         * the loader's ordering. The chain has to come out sequential regardless of the order
         * the writers happen to win in.
         */
        @Test
        void concurrentWritersProduceOneSequentialChain() throws Exception {
            String pubkey = "concurrency-test-key";
            String pubkeyHash = Utils.getHash(pubkey);
            int count = 48;
            List<Nanopub> nanopubs = trustyNanopubs(count);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> futures = new java.util.ArrayList<>();
                for (Nanopub nanopub : nanopubs) {
                    futures.add(executor.submit(() -> {
                        try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                            assertTrue(RegistryDB.loadNanopubVerified(workerSession, nanopub, pubkey, pubkeyHash, "$"));
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }

            List<Document> entries = RegistryDB.collection("listEntries")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$"))
                    .sort(new Document("position", 1)).into(new java.util.ArrayList<>());
            assertEquals(count, entries.size());
            String expected = NanopubUtils.INIT_CHECKSUM;
            for (int i = 0; i < count; i++) {
                Document entry = entries.get(i);
                assertEquals(i, entry.getLong("position"), "positions are contiguous");
                expected = NanopubUtils.updateXorChecksum(iri("http://example.org/np/" + entry.getString("np")), expected);
                assertEquals(expected, entry.getString("checksum"), "entry " + i + " chains off its predecessor");
            }
            // The XOR is order-independent, so the end of the chain is the checksum of the set,
            // whatever order the writers won in.
            String ofTheSet = NanopubUtils.INIT_CHECKSUM;
            for (Nanopub nanopub : nanopubs) {
                ofTheSet = NanopubUtils.updateXorChecksum(nanopub.getUri(), ofTheSet);
            }
            assertEquals(ofTheSet, entries.getLast().getString("checksum"));
            assertEquals(count - 1L, RegistryDB.collection("lists")
                    .find(session, new Document("pubkey", pubkeyHash).append("type", "$")).first()
                    .getLong("maxPosition"));
        }

        @Test
        void rethrowsAListEntryWriteErrorThatIsNotADuplicateKey() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);
            rejectAllWritesTo("listEntries");

            assertThrows(com.mongodb.MongoWriteException.class,
                    () -> RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
        }

        @Test
        void rethrowsAListWriteErrorThatIsNotADuplicateKey() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);
            rejectAllWritesTo("lists");

            assertThrows(com.mongodb.MongoWriteException.class,
                    () -> RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
        }
    }

    // --- signature handling --------------------------------------------------

    @Nested
    class SignatureHandling {

        @Test
        void returnsNoPubkeyForAnUnsignedNanopub() throws Exception {
            // A trusty but unsigned nanopub has no signature element at all.
            assertNull(RegistryDB.getPubkey(testSuiteNanopub(UNSIGNED_AC)));
        }

        @Test
        void returnsNoPubkeyWhenVerificationFails() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);

            try (MockedStatic<SignatureUtils> signatureUtils = mockStatic(SignatureUtils.class, CALLS_REAL_METHODS)) {
                signatureUtils.when(() -> SignatureUtils.getSignatureElement(nanopub))
                        .thenThrow(new MalformedCryptoElementException("two signature elements"));

                // A crypto failure is a rejection, not a crash: the nanopub is simply ignored.
                assertNull(RegistryDB.getPubkey(nanopub));
            }
        }

        @Test
        void loadNanopubIgnoresANanopubWithoutAVerifiablePubkey() throws Exception {
            Nanopub nanopub = testSuiteNanopub(UNSIGNED_AC);

            assertFalse(RegistryDB.loadNanopub(session, nanopub));
            assertNull(nanopubDoc(UNSIGNED_AC));
        }
    }

    // --- reads ---------------------------------------------------------------

    @Nested
    class Reads {

        @Test
        void getValueReturnsNullForADocumentWithoutAValueField() {
            RegistryDB.insert(session, "testCollection", new Document("_id", "keyWithoutValue"));

            assertNull(RegistryDB.getValue(session, "testCollection", "keyWithoutValue"));
        }

        @Test
        void buildChecksumFallbacksSkipsAMissingPosition() {
            String pubkeyHash = "pk";
            String typeHash = "ty";
            for (int i = 0; i <= 24; i++) {
                seedListEntry(pubkeyHash, typeHash, i, "np" + i, "chk" + i);
            }
            // Punch a hole exactly where the first geometric fallback (24 - 10) would look.
            RegistryDB.collection("listEntries").deleteOne(session,
                    new Document("pubkey", pubkeyHash).append("type", typeHash).append("position", 14L));

            // The missing offset is dropped rather than emitted as an empty or null checksum.
            assertEquals("chk24", RegistryDB.buildChecksumFallbacks(session, pubkeyHash, typeHash));
        }

        @Test
        void initSkipsCollectionSetupOnAnAlreadyInitialisedDatabase() throws Exception {
            RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "setupId", 1234L);
            assertTrue(RegistryDB.isInitialized(session));
            session.close();

            // Simulate a restart against the same database.
            RegistryDB.getClient().close();
            TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
            RegistryDB.init();
            session = RegistryDB.getClient().startSession();

            assertTrue(RegistryDB.isInitialized(session), "the existing setupId has to be left alone");
            assertNotEquals(0L, RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "setupId"));
        }
    }

}
