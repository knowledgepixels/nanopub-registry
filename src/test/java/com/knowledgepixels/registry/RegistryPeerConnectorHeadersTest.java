package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.NanopubUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Tests for the header handling and peer-vetting logic in {@link RegistryPeerConnector}.
 * A peer is only synced from when it answers 2xx, is not a test instance, reports a
 * usable status and advertises a numeric setup id and load counter; every other case
 * must bail out before touching the database. The DB-backed sync itself is covered by
 * {@link RegistryPeerConnectorTest}.
 */
class RegistryPeerConnectorHeadersTest {

    private static final String PEER = "https://peer.example.org/";

    private static HttpResponse responseWith(int statusCode, Map<String, String> headers) {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), statusCode, "OK"));
        when(resp.getFirstHeader(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return headers.containsKey(name) ? new BasicHeader(name, headers.get(name)) : null;
        });
        return resp;
    }

    private static Map<String, String> readyHeaders() {
        return Map.of(
                "Nanopub-Registry-Status", "ready",
                "Nanopub-Registry-Setup-Id", "42",
                "Nanopub-Registry-Load-Counter", "100");
    }

    // --- header accessors ----------------------------------------------------

    @Test
    void getHeaderReturnsNullWhenAbsent() {
        HttpResponse resp = responseWith(200, Map.of());
        assertNull(RegistryPeerConnector.getHeader(resp, "Nanopub-Registry-Status"));
    }

    @Test
    void getHeaderReturnsTheFirstValue() {
        HttpResponse resp = responseWith(200, Map.of("Nanopub-Registry-Status", "ready"));
        assertEquals("ready", RegistryPeerConnector.getHeader(resp, "Nanopub-Registry-Status"));
    }

    @Test
    void getHeaderLongParsesNumbers() {
        HttpResponse resp = responseWith(200, Map.of("Nanopub-Registry-Load-Counter", "12345"));
        assertEquals(12345L, RegistryPeerConnector.getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
    }

    @Test
    void getHeaderLongRejectsNonNumericValues() {
        HttpResponse resp = responseWith(200, Map.of("Nanopub-Registry-Load-Counter", "not-a-number"));
        assertNull(RegistryPeerConnector.getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
    }

    @Test
    void getHeaderLongTreatsTheLiteralNullStringAsAbsent() {
        // A registry that has not loaded anything yet stringifies its null counter.
        HttpResponse resp = responseWith(200, Map.of("Nanopub-Registry-Load-Counter", "null"));
        assertNull(RegistryPeerConnector.getHeaderLong(resp, "Nanopub-Registry-Load-Counter"));
    }

    @Test
    void testInstancesAreRecognised() {
        assertTrue(RegistryPeerConnector.isTestInstance(
                responseWith(200, Map.of("Nanopub-Registry-Test-Instance", "true"))));
        assertFalse(RegistryPeerConnector.isTestInstance(
                responseWith(200, Map.of("Nanopub-Registry-Test-Instance", "false"))));
        assertFalse(RegistryPeerConnector.isTestInstance(responseWith(200, Map.of())));
    }

    // --- checkPeer vetting ---------------------------------------------------

    /**
     * Runs checkPeer against a peer answering with the given status code and headers,
     * and asserts that no database work happened.
     */
    private static void assertPeerSkipped(int statusCode, Map<String, String> headers) throws IOException {
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class);
             MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
            when(resp.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), statusCode, "Reason"));
            when(resp.getFirstHeader(anyString())).thenAnswer(inv -> {
                String name = inv.getArgument(0);
                return headers.containsKey(name) ? new BasicHeader(name, headers.get(name)) : null;
            });
            when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);

            RegistryPeerConnector.checkPeer(mock(ClientSession.class), PEER);

            dbMock.verify(() -> RegistryDB.collection(anyString()), never());
        }
    }

    @Test
    void unreachablePeerIsSkipped() throws IOException {
        assertPeerSkipped(503, readyHeaders());
    }

    @Test
    void testInstancePeerIsSkipped() throws IOException {
        // Syncing from a test instance would pollute a production registry.
        assertPeerSkipped(200, Map.of(
                "Nanopub-Registry-Test-Instance", "true",
                "Nanopub-Registry-Status", "ready",
                "Nanopub-Registry-Setup-Id", "42",
                "Nanopub-Registry-Load-Counter", "100"));
    }

    @Test
    void peerStillLaunchingIsSkipped() throws IOException {
        assertPeerSkipped(200, Map.of(
                "Nanopub-Registry-Status", "launching",
                "Nanopub-Registry-Setup-Id", "42",
                "Nanopub-Registry-Load-Counter", "100"));
    }

    @Test
    void peerWithoutStatusHeaderIsSkipped() throws IOException {
        assertPeerSkipped(200, Map.of(
                "Nanopub-Registry-Setup-Id", "42",
                "Nanopub-Registry-Load-Counter", "100"));
    }

    @Test
    void peerWithUnparseableSetupIdIsSkipped() throws IOException {
        assertPeerSkipped(200, Map.of(
                "Nanopub-Registry-Status", "ready",
                "Nanopub-Registry-Setup-Id", "not-a-number",
                "Nanopub-Registry-Load-Counter", "100"));
    }

    @Test
    void peerWithoutLoadCounterIsSkipped() throws IOException {
        assertPeerSkipped(200, Map.of(
                "Nanopub-Registry-Status", "ready",
                "Nanopub-Registry-Setup-Id", "42"));
    }

    // --- checkPeers ----------------------------------------------------------

    @Test
    void checkPeersDoesNothingWithoutConfiguredPeers() throws Exception {
        var field = Utils.class.getDeclaredField("peerUrls");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, List.of());
            try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
                RegistryPeerConnector.checkPeers(mock(ClientSession.class));
                httpMock.verify(NanopubUtils::getHttpClient, never());
            }
        } finally {
            field.set(null, previous);
        }
    }

    @Test
    void checkPeersKeepsGoingWhenOnePeerFails() throws Exception {
        var field = Utils.class.getDeclaredField("peerUrls");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, List.of("https://peer-a.example.org/", "https://peer-b.example.org/"));
            try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class)) {
                CloseableHttpClient client = mock(CloseableHttpClient.class);
                httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
                when(client.execute(any(HttpUriRequest.class))).thenThrow(new IOException("connection refused"));

                // One dead peer must not stop the sweep over the rest.
                RegistryPeerConnector.checkPeers(mock(ClientSession.class));

                org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).execute(any(HttpUriRequest.class));
            }
        } finally {
            field.set(null, previous);
        }
    }

}
