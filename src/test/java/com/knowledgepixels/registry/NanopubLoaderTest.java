package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import net.trustyuri.TrustyUriUtils;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.jelly.MaybeNanopub;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link NanopubLoader}'s routing logic: which lists a nanopub is added to
 * depends on how far the publishing pubkey has been loaded, and the parallel stream
 * loader has to surface worker failures rather than silently dropping them.
 */
class NanopubLoaderTest {

    private static final String PUBKEY = "ABC123PUBKEY";
    private static final String PUBKEY_HASH = Utils.getHash(PUBKEY);

    private static final String NANOPUB_URI = "http://example.org/RAXH93wfOaQRwDpxwr-E_s10kCQubHZ6O19h-cz3YlNGI";
    /**
     * The trusty artifact code is what the nanopubs collection is keyed on.
     */
    private static final String ARTIFACT_CODE = TrustyUriUtils.getArtifactCode(NANOPUB_URI);

    private static Nanopub nanopub(String uri) {
        Nanopub np = mock(Nanopub.class);
        when(np.getUri()).thenReturn(Values.iri(uri));
        return np;
    }

    private static Document listQuery(String type) {
        return new Document("pubkey", PUBKEY_HASH).append("type", type);
    }

    private static Document loadedListQuery(String type) {
        return listQuery(type).append("status", "loaded");
    }

    // --- simpleLoad routing --------------------------------------------------

    @Test
    void fullyLoadedPubkeyGoesToTheAllTypesList() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Nanopub np = nanopub("http://example.org/np1");
            dbMock.when(() -> RegistryDB.has(s, "lists", loadedListQuery("$"))).thenReturn(true);

            NanopubLoader.simpleLoad(s, np, PUBKEY);

            dbMock.verify(() -> RegistryDB.loadNanopubVerified(s, np, PUBKEY, PUBKEY_HASH, "$"));
        }
    }

    @Test
    void coreLoadedPubkeyGoesToTheIntroAndEndorseListsOnly() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Nanopub np = nanopub("http://example.org/np2");
            dbMock.when(() -> RegistryDB.has(s, "lists", loadedListQuery("$"))).thenReturn(false);
            dbMock.when(() -> RegistryDB.has(s, "lists", loadedListQuery(NanopubLoader.INTRO_TYPE_HASH))).thenReturn(true);

            NanopubLoader.simpleLoad(s, np, PUBKEY);

            dbMock.verify(() -> RegistryDB.loadNanopubVerified(s, np, PUBKEY, PUBKEY_HASH,
                    NanopubLoader.INTRO_TYPE, NanopubLoader.ENDORSE_TYPE));
        }
    }

    @Test
    void unknownPubkeyIsStoredAndFlaggedAsEncountered() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Nanopub np = nanopub("http://example.org/np3");
            // No list at all for this pubkey: nothing is "loaded" and no intro list exists yet.

            NanopubLoader.simpleLoad(s, np, PUBKEY);

            // The nanopub must not be lost, so it is stored without joining any list...
            dbMock.verify(() -> RegistryDB.loadNanopubVerified(s, np, PUBKEY, null));
            // ...and an "encountered" intro list makes RUN_OPTIONAL_LOAD pick the pubkey up later.
            dbMock.verify(() -> RegistryDB.insert(s, "lists",
                    listQuery(NanopubLoader.INTRO_TYPE_HASH).append("status", EntryStatus.encountered.getValue())));
        }
    }

    @Test
    void knownButUnloadedPubkeyIsNotFlaggedAgain() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Nanopub np = nanopub("http://example.org/np4");
            // An intro list already exists (e.g. still in "encountered" state).
            dbMock.when(() -> RegistryDB.has(s, "lists", listQuery(NanopubLoader.INTRO_TYPE_HASH))).thenReturn(true);

            NanopubLoader.simpleLoad(s, np, PUBKEY);

            dbMock.verify(() -> RegistryDB.loadNanopubVerified(s, np, PUBKEY, null));
            dbMock.verify(() -> RegistryDB.insert(any(ClientSession.class), anyString(), any(Document.class)), never());
        }
    }

    @Test
    void concurrentlyCreatedIntroListIsTolerated() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Nanopub np = nanopub("http://example.org/np5");
            // Two loader threads can race to create the same intro list; the loser must not fail.
            dbMock.when(() -> RegistryDB.insert(any(ClientSession.class), anyString(), any(Document.class)))
                    .thenThrow(new MongoWriteException(
                            new WriteError(11000, "duplicate key", new BsonDocument()), new ServerAddress()));

            NanopubLoader.simpleLoad(s, np, PUBKEY);

            dbMock.verify(() -> RegistryDB.loadNanopubVerified(s, np, PUBKEY, null));
        }
    }

    @Test
    void nonDuplicateWriteErrorsPropagate() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            try (ClientSession s = mock(ClientSession.class)) {
                Nanopub np = nanopub("http://example.org/np6");
                dbMock.when(() -> RegistryDB.insert(any(ClientSession.class), anyString(), any(Document.class)))
                        .thenThrow(new MongoWriteException(
                                new WriteError(121, "document validation failure", new BsonDocument()), new ServerAddress()));

                assertThrows(MongoWriteException.class, () -> NanopubLoader.simpleLoad(s, np, PUBKEY));
            }
        }
    }

    @Test
    void unsignedNanopubIsSkipped() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Nanopub np = nanopub("http://example.org/unsigned");
            dbMock.when(() -> RegistryDB.getPubkey(np)).thenReturn(null);

            NanopubLoader.simpleLoad(s, np);

            // Without a verifiable pubkey there is nowhere to file the nanopub.
            dbMock.verify(() -> RegistryDB.loadNanopubVerified(any(), any(), any(), any()), never());
        }
    }

    // --- retrieveLocalNanopub ------------------------------------------------

    @Test
    void retrieveLocalNanopubReturnsNullWhenNotStored() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            // Build the cursor before entering when(): creating mocks inside a stubbing
            // argument confuses Mockito's stubbing state machine.
            MongoCursor<Document> empty = PageMocks.cursor(List.of());
            dbMock.when(() -> RegistryDB.get(s, Collection.NANOPUBS.toString(), new Document("_id", ARTIFACT_CODE)))
                    .thenReturn(empty);

            assertNull(NanopubLoader.retrieveLocalNanopub(s, NANOPUB_URI));
        }
    }

    @Test
    void retrieveLocalNanopubTreatsUnparseableJellyAsMissing() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            Document stored = new Document("_id", ARTIFACT_CODE).append("jelly", new Binary(new byte[]{1, 2, 3}));
            MongoCursor<Document> found = PageMocks.cursor(List.of(stored));
            dbMock.when(() -> RegistryDB.get(s, Collection.NANOPUBS.toString(), new Document("_id", ARTIFACT_CODE)))
                    .thenReturn(found);

            // Corrupt stored content must degrade to "not found" rather than crash the caller.
            assertNull(NanopubLoader.retrieveLocalNanopub(s, NANOPUB_URI));
        }
    }

    // --- loadStreamInParallel ------------------------------------------------

    @Test
    void loadStreamInParallelProcessesEveryNanopub() {
        List<MaybeNanopub> stream = List.of(
                new MaybeNanopub(nanopub("http://example.org/a")),
                new MaybeNanopub(nanopub("http://example.org/b")),
                new MaybeNanopub(nanopub("http://example.org/c")));
        ConcurrentLinkedQueue<Nanopub> processed = new ConcurrentLinkedQueue<>();

        NanopubLoader.loadStreamInParallel(stream.stream(), processed::add);

        assertEquals(3, processed.size(), "every nanopub in the stream is handed to the processor");
    }

    @Test
    void loadStreamInParallelAbortsOnDownloadFailure() {
        Stream<MaybeNanopub> stream = Stream.of(
                new MaybeNanopub(nanopub("http://example.org/a")),
                new MaybeNanopub(new RuntimeException("download failed")));

        // A partial list would look like a complete one to the caller, so the task must abort.
        assertThrows(AbortingTaskException.class,
                () -> NanopubLoader.loadStreamInParallel(stream, np -> {
                }));
    }

    @Test
    void loadStreamInParallelStopsHandingOutWorkAfterAFailure() {
        Stream<MaybeNanopub> stream = Stream.of(
                new MaybeNanopub(new RuntimeException("download failed")),
                new MaybeNanopub(nanopub("http://example.org/b")),
                new MaybeNanopub(nanopub("http://example.org/c")));
        ConcurrentLinkedQueue<Nanopub> processed = new ConcurrentLinkedQueue<>();

        assertThrows(AbortingTaskException.class,
                () -> NanopubLoader.loadStreamInParallel(stream, processed::add));

        // Once the stream is known to be broken, the remaining items are not worth loading.
        assertTrue(processed.isEmpty(), "no work is dispatched after the failure");
    }

    @Test
    void loadStreamInParallelSurfacesWorkerFailures() {
        Stream<MaybeNanopub> stream = Stream.of(new MaybeNanopub(nanopub("http://example.org/a")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> NanopubLoader.loadStreamInParallel(stream, np -> {
                    throw new IllegalStateException("processing blew up");
                }));

        assertTrue(thrown instanceof IllegalStateException || thrown.getCause() instanceof IllegalStateException,
                "the original worker failure is not swallowed, got: " + thrown);
    }

    @Test
    void loadStreamInParallelHandlesAnEmptyStream() {
        ConcurrentLinkedQueue<Nanopub> processed = new ConcurrentLinkedQueue<>();

        NanopubLoader.loadStreamInParallel(Stream.of(), processed::add);

        assertTrue(processed.isEmpty());
    }

    // --- peer retrieval ------------------------------------------------------

    @Test
    void retrieveNanopubsFromPeersReturnsEmptyStreamWithoutPeers() throws Exception {
        var field = Utils.class.getDeclaredField("peerUrls");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, List.of());

            assertEquals(0, NanopubLoader.retrieveNanopubsFromPeers("typeHash", "pubkeyHash").count(),
                    "with no peers configured there is nothing to retrieve");
        } finally {
            field.set(null, previous);
        }
    }

}
