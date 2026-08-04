package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.JellyUtils;
import org.nanopub.testsuite.NanopubTestSuite;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests how {@link NanopubLoader} resolves a single nanopub by id: the local store first,
 * then peers one at a time, with a bounded number of retries. A peer's answer is only
 * accepted when it parses and is a valid trusty nanopub, so a peer cannot substitute
 * different content for the requested artifact code.
 * <p>
 * The nanopub served by the fake peers is a real signed one from the nanopub test suite.
 */
class NanopubLoaderRetrievalTest {

    private static final String INTRO_AC = "RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE";
    private static final String PEER_A = "https://peer-a.example.org/";
    private static final String PEER_B = "https://peer-b.example.org/";

    private static File introFile() {
        return NanopubTestSuite.getLatest().getByArtifactCode(INTRO_AC).getFirst().toFile();
    }

    private static Nanopub introNanopub() throws Exception {
        return new NanopubImpl(introFile());
    }

    private static String introTrig() throws IOException {
        return Files.readString(introFile().toPath());
    }

    private Field peerUrlsField;
    private Object previousPeerUrls;

    @BeforeEach
    void pinPeerUrls() throws Exception {
        peerUrlsField = Utils.class.getDeclaredField("peerUrls");
        peerUrlsField.setAccessible(true);
        previousPeerUrls = peerUrlsField.get(null);
    }

    @AfterEach
    void restorePeerUrls() throws Exception {
        peerUrlsField.set(null, previousPeerUrls);
    }

    private void setPeers(String... urls) throws Exception {
        peerUrlsField.set(null, List.of(urls));
    }

    private static CloseableHttpResponse response(int status, String body) throws IOException {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), status, "Reason"));
        HttpEntity entity = mock(HttpEntity.class);
        when(resp.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return resp;
    }

    /**
     * Only {@code getHttpClient} is faked; the rest of NanopubUtils has to keep working
     * because trusty validation depends on it.
     */
    private static MockedStatic<NanopubUtils> mockHttpClient(CloseableHttpClient client) {
        MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class, CALLS_REAL_METHODS);
        httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
        return httpMock;
    }

    /**
     * Makes the local nanopub store answer with the given nanopub, or with nothing.
     */
    private static void stubLocalStore(MockedStatic<RegistryDB> dbMock, ClientSession s, Nanopub stored) {
        MongoCursor<Document> cursor = stored == null
                ? PageMocks.cursor(List.of())
                : PageMocks.cursor(List.of(new Document("_id", INTRO_AC)
                        .append("jelly", new Binary(JellyUtils.writeNanopubForDB(stored)))));
        dbMock.when(() -> RegistryDB.get(s, Collection.NANOPUBS.toString(), new Document("_id", INTRO_AC)))
                .thenReturn(cursor);
    }

    // --- retrieveLocalNanopub ------------------------------------------------

    @Test
    void aLocallyStoredNanopubIsReadBackFromItsJelly() throws Exception {
        Nanopub stored = introNanopub();
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, stored);

            Nanopub found = NanopubLoader.retrieveLocalNanopub(s, stored.getUri().stringValue());

            assertNotNull(found);
            assertEquals(stored.getUri(), found.getUri());
        }
    }

    // --- retrieveNanopub -----------------------------------------------------

    @Test
    void retrieveNanopubPrefersTheLocalCopyOverPeers() throws Exception {
        Nanopub stored = introNanopub();
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, stored);
            CloseableHttpClient client = mock(CloseableHttpClient.class);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                Nanopub found = NanopubLoader.retrieveNanopub(s, stored.getUri().stringValue());
                assertEquals(stored.getUri(), found.getUri());
            }

            verify(client, never()).execute(any(HttpUriRequest.class));
            dbMock.verify(() -> RegistryDB.loadNanopub(any(), any()), never());
        }
    }

    @Test
    void retrieveNanopubFetchesFromAPeerAndPersistsIt() throws Exception {
        Nanopub expected = introNanopub();
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            CloseableHttpResponse ok = response(200, introTrig());
            when(client.execute(any(HttpUriRequest.class))).thenReturn(ok);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                Nanopub found = NanopubLoader.retrieveNanopub(s, expected.getUri().stringValue());
                assertEquals(expected.getUri(), found.getUri());
            }

            ArgumentCaptor<HttpUriRequest> request = ArgumentCaptor.forClass(HttpUriRequest.class);
            verify(client).execute(request.capture());
            assertEquals(PEER_A + "np/" + INTRO_AC, request.getValue().getURI().toString());
            // A nanopub fetched from a peer is cached locally so the next lookup is a hit.
            dbMock.verify(() -> RegistryDB.loadNanopub(eq(s), any(Nanopub.class)));
        }
    }

    @Test
    void retrieveNanopubMovesOnToTheNextPeerAfterAnHttpError() throws Exception {
        Nanopub expected = introNanopub();
        setPeers(PEER_A, PEER_B);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            CloseableHttpResponse notFound = response(404, "");
            CloseableHttpResponse ok = response(200, introTrig());
            when(client.execute(any(HttpUriRequest.class))).thenReturn(notFound).thenReturn(ok);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                assertEquals(expected.getUri(), NanopubLoader.retrieveNanopub(s, expected.getUri().stringValue()).getUri());
            }

            verify(client, times(2)).execute(any(HttpUriRequest.class));
        }
    }

    @Test
    void aPeerCannotSubstituteDifferentContentForAnArtifactCode() throws Exception {
        Nanopub expected = introNanopub();
        setPeers(PEER_A);
        // A structurally valid nanopub whose URI is not a trusty URI: its content cannot be
        // verified against the requested artifact code, so it has to be rejected.
        String untrusted = """
                @prefix : <http://example.org/np1#> .
                @prefix np: <http://www.nanopub.org/nschema#> .
                @prefix prov: <http://www.w3.org/ns/prov#> .
                :Head {
                  <http://example.org/np1> a np:Nanopublication ;
                    np:hasAssertion :assertion ;
                    np:hasProvenance :provenance ;
                    np:hasPublicationInfo :pubinfo .
                }
                :assertion { <http://example.org/s> <http://example.org/p> <http://example.org/o> . }
                :provenance { :assertion prov:wasAttributedTo <http://example.org/someone> . }
                :pubinfo { <http://example.org/np1> prov:generatedAtTime "2026-01-01T00:00:00Z" . }
                """;
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            when(client.execute(any(HttpUriRequest.class))).thenAnswer(inv -> response(200, untrusted));

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                // Every attempt is refused, so the loader eventually gives up.
                assertThrows(RuntimeException.class,
                        () -> NanopubLoader.retrieveNanopub(s, expected.getUri().stringValue()));
            }

            dbMock.verify(() -> RegistryDB.loadNanopub(any(), any()), never());
        }
    }

    @Test
    void retrieveNanopubGivesUpAfterExhaustingItsRetries() throws Exception {
        Nanopub expected = introNanopub();
        setPeers();
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);

            // Nowhere to fetch from: the loader must fail loudly rather than loop forever.
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> NanopubLoader.retrieveNanopub(s, expected.getUri().stringValue()));
            assertEquals("Could not load nanopub: " + expected.getUri().stringValue(), ex.getMessage());
        }
    }

    // --- simpleLoad by id ----------------------------------------------------

    @Test
    void simpleLoadByIdPersistsTheNanopubItRetrieves() throws Exception {
        Nanopub expected = introNanopub();
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            dbMock.when(() -> RegistryDB.getPubkey(any())).thenReturn("PUBKEY");
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            CloseableHttpResponse ok = response(200, introTrig());
            when(client.execute(any(HttpUriRequest.class))).thenReturn(ok);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                NanopubLoader.simpleLoad(s, expected.getUri().stringValue());
            }

            // The default is to cache what was fetched, then file it into the lists.
            dbMock.verify(() -> RegistryDB.loadNanopub(eq(s), any(Nanopub.class)));
            dbMock.verify(() -> RegistryDB.loadNanopubVerified(eq(s), any(Nanopub.class), eq("PUBKEY"), any()));
        }
    }

    @Test
    void simpleLoadWithoutPersistingUsesTheLocalCopy() throws Exception {
        Nanopub stored = introNanopub();
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, stored);
            dbMock.when(() -> RegistryDB.getPubkey(any())).thenReturn("PUBKEY");
            CloseableHttpClient client = mock(CloseableHttpClient.class);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                NanopubLoader.simpleLoad(s, stored.getUri().stringValue(), false);
            }

            verify(client, never()).execute(any(HttpUriRequest.class));
            dbMock.verify(() -> RegistryDB.loadNanopub(any(), any()), never());
            dbMock.verify(() -> RegistryDB.loadNanopubVerified(eq(s), any(Nanopub.class), eq("PUBKEY"), any()));
        }
    }

    @Test
    void simpleLoadWithoutPersistingStillFetchesFromAPeer() throws Exception {
        Nanopub expected = introNanopub();
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            dbMock.when(() -> RegistryDB.getPubkey(any())).thenReturn("PUBKEY");
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            CloseableHttpResponse ok = response(200, introTrig());
            when(client.execute(any(HttpUriRequest.class))).thenReturn(ok);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                NanopubLoader.simpleLoad(s, expected.getUri().stringValue(), false);
            }

            verify(client).execute(any(HttpUriRequest.class));
            // "Do not persist on retrieve" means exactly that: no unconditional caching...
            dbMock.verify(() -> RegistryDB.loadNanopub(any(), any()), never());
            // ...but the nanopub is still filed into the lists it belongs to.
            dbMock.verify(() -> RegistryDB.loadNanopubVerified(eq(s), any(Nanopub.class), eq("PUBKEY"), any()));
        }
    }

    @Test
    void simpleLoadWithoutPersistingSkipsWhatItCannotFind() throws Exception {
        Nanopub expected = introNanopub();
        setPeers();
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);

            // Not local and no peer has it: skipped without retrying, unlike the persisting
            // variant, which would keep trying and then throw.
            NanopubLoader.simpleLoad(s, expected.getUri().stringValue(), false);

            dbMock.verify(() -> RegistryDB.loadNanopubVerified(any(), any(), any(), any()), never());
        }
    }

    @Test
    void aTrustyUriOfTheWrongKindIsRejected() throws Exception {
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            // "FA..." is a trusty URI for a file, not for RDF, so it can never name a nanopub.
            String fileUri = "http://example.org/FATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE";
            MongoCursor<Document> empty = PageMocks.cursor(List.of());
            dbMock.when(() -> RegistryDB.get(eq(s), eq(Collection.NANOPUBS.toString()), any(Document.class)))
                    .thenReturn(empty);

            assertThrows(IllegalArgumentException.class, () -> NanopubLoader.simpleLoad(s, fileUri, false));
        }
    }

    @Test
    void anUnusablePeerUrlIsSkipped() throws Exception {
        Nanopub expected = introNanopub();
        // A peer URL that cannot be turned into a request; the loader treats it like any
        // other unreachable peer instead of failing the whole load.
        setPeers("ht tp://not a url/");
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            CloseableHttpClient client = mock(CloseableHttpClient.class);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                NanopubLoader.simpleLoad(s, expected.getUri().stringValue(), false);
            }

            verify(client, never()).execute(any(HttpUriRequest.class));
            dbMock.verify(() -> RegistryDB.loadNanopubVerified(any(), any(), any(), any()), never());
        }
    }

    @Test
    void anInformationalResponseIsNotTreatedAsSuccess() throws Exception {
        Nanopub expected = introNanopub();
        setPeers(PEER_A);
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            ClientSession s = mock(ClientSession.class);
            stubLocalStore(dbMock, s, null);
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            // Only 2xx counts: a 1xx body is not the nanopub we asked for.
            CloseableHttpResponse informational = response(100, introTrig());
            when(client.execute(any(HttpUriRequest.class))).thenReturn(informational);

            try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
                NanopubLoader.simpleLoad(s, expected.getUri().stringValue(), false);
            }

            dbMock.verify(() -> RegistryDB.loadNanopubVerified(any(), any(), any(), any()), never());
        }
    }

    @Test
    void aPeerAnsweringWithAnInformationalStatusIsSkippedForLists() throws Exception {
        setPeers(PEER_A);
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse informational = response(100, "");
        when(client.execute(any(HttpUriRequest.class))).thenReturn(informational);

        try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
            assertEquals(0, NanopubLoader.retrieveNanopubsFromPeers("t".repeat(64), "p".repeat(64)).count());
        }
    }

    // --- stream lifecycle ----------------------------------------------------

    @Test
    void closingTheListStreamReleasesThePeerResponse() throws Exception {
        setPeers(PEER_A);
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "OK"));
        when(resp.getFirstHeader("Nanopub-Registry-Status"))
                .thenReturn(new org.apache.http.message.BasicHeader("Nanopub-Registry-Status", "ready"));
        HttpEntity entity = mock(HttpEntity.class);
        when(resp.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);

        try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
            try (var stream = NanopubLoader.retrieveNanopubsFromPeers("t".repeat(64), "p".repeat(64))) {
                assertNotNull(stream);
            }
        }

        // Leaking the connection would exhaust the pool over a long sync.
        verify(resp).close();
    }

    @Test
    void aFailingResponseCloseDoesNotPropagate() throws Exception {
        setPeers(PEER_A);
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "OK"));
        when(resp.getFirstHeader("Nanopub-Registry-Status"))
                .thenReturn(new org.apache.http.message.BasicHeader("Nanopub-Registry-Status", "updating"));
        HttpEntity entity = mock(HttpEntity.class);
        when(resp.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(new byte[0]));
        org.mockito.Mockito.doThrow(new IOException("already closed")).when(resp).close();
        when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);

        try (MockedStatic<NanopubUtils> ignored = mockHttpClient(client)) {
            // Closing is best-effort cleanup; a failure there must not surface to the caller.
            try (var stream = NanopubLoader.retrieveNanopubsFromPeers("t".repeat(64), "p".repeat(64))) {
                assertNotNull(stream);
            }
        }

        verify(resp).close();
    }

    // --- constants -----------------------------------------------------------

    @Test
    void coreTypeHashesMatchTheirTypeUris() {
        // These hashes key every list in the database, so they must track the type URIs.
        assertEquals(Utils.getHash(NanopubLoader.INTRO_TYPE), NanopubLoader.INTRO_TYPE_HASH);
        assertEquals(Utils.getHash(NanopubLoader.ENDORSE_TYPE), NanopubLoader.ENDORSE_TYPE_HASH);
    }

}
