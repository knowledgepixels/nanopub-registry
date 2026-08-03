package com.knowledgepixels.registry;

import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.nanopub.NanopubUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests how {@link NanopubLoader} vets peers when fetching a nanopub list. A peer is
 * only used when it answers 2xx and advertises a usable registry status; otherwise the
 * loader must move on to the next peer rather than importing a partial list.
 */
class NanopubLoaderPeerTest {

    private static final String PEER_A = "https://peer-a.example.org/";
    private static final String PEER_B = "https://peer-b.example.org/";
    private static final String TYPE_HASH = "b".repeat(64);
    private static final String PUBKEY_HASH = "a".repeat(64);

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

    private static CloseableHttpResponse peerResponse(int status, String registryStatus, String body) throws IOException {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), status, "Reason"));
        when(resp.getFirstHeader("Nanopub-Registry-Status"))
                .thenReturn(registryStatus == null ? null : new BasicHeader("Nanopub-Registry-Status", registryStatus));
        HttpEntity entity = mock(HttpEntity.class);
        when(resp.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return resp;
    }

    @Test
    void requestsTheJellyListForTheGivenPubkeyAndType() throws Exception {
        setPeers(PEER_A);
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse ok = peerResponse(200, "ready", "");
            when(client.execute(any(HttpUriRequest.class))).thenReturn(ok);

            NanopubLoader.retrieveNanopubsFromPeers(TYPE_HASH, PUBKEY_HASH);

            ArgumentCaptor<HttpUriRequest> request = ArgumentCaptor.forClass(HttpUriRequest.class);
            verify(client).execute(request.capture());
            assertEquals(PEER_A + "list/" + PUBKEY_HASH + "/" + TYPE_HASH + ".jelly",
                    request.getValue().getURI().toString());
        }
    }

    @Test
    void appendsTheChecksumSkipAheadParameter() throws Exception {
        setPeers(PEER_A);
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse ok = peerResponse(200, "updating", "");
            when(client.execute(any(HttpUriRequest.class))).thenReturn(ok);

            NanopubLoader.retrieveNanopubsFromPeers(TYPE_HASH, PUBKEY_HASH, "cs1,cs2");

            ArgumentCaptor<HttpUriRequest> request = ArgumentCaptor.forClass(HttpUriRequest.class);
            verify(client).execute(request.capture());
            assertTrue(request.getValue().getURI().toString().endsWith("?afterChecksums=cs1,cs2"),
                    "the skip-ahead checksums are forwarded to the peer");
        }
    }

    @Test
    void movesOnToTheNextPeerAfterAnHttpError() throws Exception {
        setPeers(PEER_A, PEER_B);
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse failing = peerResponse(500, "ready", "");
            CloseableHttpResponse ok = peerResponse(200, "ready", "");
            when(client.execute(any(HttpUriRequest.class))).thenReturn(failing).thenReturn(ok);

            NanopubLoader.retrieveNanopubsFromPeers(TYPE_HASH, PUBKEY_HASH);

            verify(client, org.mockito.Mockito.times(2)).execute(any(HttpUriRequest.class));
        }
    }

    @Test
    void skipsPeersThatDoNotIdentifyAsARegistry() throws Exception {
        setPeers(PEER_A);
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            // No Nanopub-Registry-Status header: this is not a registry we can trust.
            CloseableHttpResponse anonymous = peerResponse(200, null, "");
            when(client.execute(any(HttpUriRequest.class))).thenReturn(anonymous);

            assertEquals(0, NanopubLoader.retrieveNanopubsFromPeers(TYPE_HASH, PUBKEY_HASH).count());
        }
    }

    @Test
    void skipsPeersThatAreNotReadyYet() throws Exception {
        setPeers(PEER_A);
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            // A launching registry has an incomplete list; importing from it would lose entries.
            CloseableHttpResponse launching = peerResponse(200, "launching", "");
            when(client.execute(any(HttpUriRequest.class))).thenReturn(launching);

            assertEquals(0, NanopubLoader.retrieveNanopubsFromPeers(TYPE_HASH, PUBKEY_HASH).count());
        }
    }

    @Test
    void networkFailureExhaustsPeersAndReturnsAnEmptyStream() throws Exception {
        setPeers(PEER_A, PEER_B);
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            when(client.execute(any(HttpUriRequest.class))).thenThrow(new IOException("connection refused"));

            assertEquals(0, NanopubLoader.retrieveNanopubsFromPeers(TYPE_HASH, PUBKEY_HASH).count());
            verify(client, atLeastOnce()).execute(any(HttpGet.class));
        }
    }

}
