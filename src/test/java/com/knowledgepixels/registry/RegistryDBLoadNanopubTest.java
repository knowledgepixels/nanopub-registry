package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
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

            // And the invalidator is added to the lists of every type it declares, so the
            // replacement is discoverable from the same place as the nanopub it replaces.
            String fdoTypeHash = Utils.getHash("https://w3id.org/fdof/ontology#FAIRDigitalObject");
            assertNotNull(listEntry(pubkeyHash, fdoTypeHash, SUPERSEDER_AC));
        }

        @Test
        void skipsAnInvalidatingNanopubThatCannotBeRead() throws Exception {
            Nanopub target = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(target);
            String pubkeyHash = Utils.getHash(pubkey);

            // A stored nanopub whose Jelly payload is corrupt.
            RegistryDB.insert(session, Collection.NANOPUBS.toString(),
                    new Document("_id", "RAcorruptedArtifactCode00000000000000000000")
                            .append("fullId", "http://example.org/corrupted")
                            .append("counter", 4242L)
                            .append("jelly", new Binary("this is not a jelly frame".getBytes())));
            RegistryDB.insert(session, "invalidations",
                    new Document("invalidatingNp", "RAcorruptedArtifactCode00000000000000000000")
                            .append("invalidatingPubkey", pubkeyHash).append("invalidatedNp", SIMPLE1_AC));

            // One unreadable invalidator must not abort the load of the nanopub it invalidates.
            assertTrue(RegistryDB.loadNanopubVerified(session, target, pubkey, pubkeyHash, "$"));
            assertNotNull(nanopubDoc(SIMPLE1_AC));
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
        void fallsBackToASortedLookupWhenThePrecedingEntryIsMissing() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // The counter has been claimed up to position 5, but the entry at 5 is not
            // written yet — what a concurrent writer's half-finished work looks like.
            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 5L));
            seedListEntry(pubkeyHash, "$", 3L, "RAearlierEntry0000000000000000000000000000", checksum("three"));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            // Position 6 is claimed, and the checksum chains off the highest entry that does
            // exist rather than failing on the missing one.
            Document entry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertEquals(6L, entry.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), checksum("three")), entry.getString("checksum"));
        }

        @Test
        void fallsBackToTheInitialChecksumWhenNoEntryExistsAtAll() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 5L));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            Document entry = listEntry(pubkeyHash, "$", SIMPLE1_AC);
            assertEquals(6L, entry.getLong("position"));
            assertEquals(NanopubUtils.updateXorChecksum(nanopub.getUri(), NanopubUtils.INIT_CHECKSUM),
                    entry.getString("checksum"));
        }

        @Test
        void retriesUntilItFindsAFreePosition() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            // The counter lags behind the entries that are actually there, so the first two
            // claimed positions are already taken.
            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 0L));
            seedListEntry(pubkeyHash, "$", 1L, "RAoccupantOne00000000000000000000000000000", checksum("one"));
            seedListEntry(pubkeyHash, "$", 2L, "RAoccupantTwo00000000000000000000000000000", checksum("two"));

            RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$");

            assertEquals(3L, listEntry(pubkeyHash, "$", SIMPLE1_AC).getLong("position"));
        }

        @Test
        void givesUpAfterAHundredCollisions() throws Exception {
            Nanopub nanopub = testSuiteNanopub(SIMPLE1_AC);
            String pubkey = RegistryDB.getPubkey(nanopub);
            String pubkeyHash = Utils.getHash(pubkey);

            RegistryDB.insert(session, "lists",
                    new Document("pubkey", pubkeyHash).append("type", "$").append("maxPosition", 0L));
            // Block every position the retry loop can reach before hitting its own limit.
            for (int i = 1; i <= 101; i++) {
                seedListEntry(pubkeyHash, "$", i, "RAoccupant" + String.format("%035d", i), checksum("occupant" + i));
            }

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> RegistryDB.loadNanopubVerified(session, nanopub, pubkey, pubkeyHash, "$"));
            assertEquals("Failed to insert list entry after 101 attempts", thrown.getMessage());
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
