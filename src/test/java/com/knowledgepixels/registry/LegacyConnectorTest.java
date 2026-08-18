package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.NanopubUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;

/**
 * Tests for {@link LegacyConnector}, which polls the first-generation nanopub-server
 * instances. It walks backwards through their {@code Link: rel="prev"} pagination and
 * keeps an in-memory cache so a poll does not re-load nanopubs it already saw.
 */
class LegacyConnectorTest {

    @BeforeEach
    void clearLoadedCache() {
        // The cache is static and shared across polls; each test needs a clean slate.
        TestSupport.setLoadedCache(new HashMap<>());
    }

    /**
     * Reflection helper kept separate so the intent above stays readable.
     */
    private static final class TestSupport {
        static void setLoadedCache(Map<String, Boolean> cache) {
            try {
                var field = LegacyConnector.class.getDeclaredField("loadedCache");
                field.setAccessible(true);
                field.set(null, cache);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static CloseableHttpResponse response(String body, String prevLink) throws IOException {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        Header[] headers = prevLink == null
                ? new Header[0]
                : new Header[]{new BasicHeader("Link", "<" + prevLink + ">; rel=\"prev\"")};
        when(resp.getHeaders("Link")).thenReturn(headers);
        HttpEntity entity = mock(HttpEntity.class);
        when(resp.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return resp;
    }

    @Test
    void loadsEveryListedNanopubAndFollowsThePrevLink() throws Exception {
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class);
             MockedStatic<NanopubLoader> loaderMock = mockStatic(NanopubLoader.class)) {

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            // Both responses are built before stubbing: creating mocks inside a stubbing
            // argument confuses Mockito's stubbing state machine.
            CloseableHttpResponse first = response("http://example.org/np1\nhttp://example.org/np2\n", "page1");
            CloseableHttpResponse second = response("http://example.org/np3\n", null);
            when(client.execute(any(HttpUriRequest.class))).thenReturn(first).thenReturn(second);

            ClientSession session = mock(ClientSession.class);
            LegacyConnector.checkForNewNanopubs(session);

            loaderMock.verify(() -> NanopubLoader.simpleLoad(session, "http://example.org/np1", false));
            loaderMock.verify(() -> NanopubLoader.simpleLoad(session, "http://example.org/np2", false));
            // The prev link is followed exactly once, so the previous page is loaded too.
            loaderMock.verify(() -> NanopubLoader.simpleLoad(session, "http://example.org/np3", false));
        }
    }

    @Test
    void stopsAfterOnePageWhenThereIsNoPrevLink() throws Exception {
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class);
             MockedStatic<NanopubLoader> loaderMock = mockStatic(NanopubLoader.class)) {

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse only = response("http://example.org/np1\n", null);
            when(client.execute(any(HttpUriRequest.class))).thenReturn(only);

            ClientSession session = mock(ClientSession.class);
            LegacyConnector.checkForNewNanopubs(session);

            verify(client, times(1)).execute(any(HttpUriRequest.class));
        }
    }

    @Test
    void alreadySeenNanopubsAreNotReloaded() throws Exception {
        TestSupport.setLoadedCache(new HashMap<>(Map.of("http://example.org/np1", true)));

        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class);
             MockedStatic<NanopubLoader> loaderMock = mockStatic(NanopubLoader.class)) {

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse page = response("http://example.org/np1\nhttp://example.org/np2\n", null);
            when(client.execute(any(HttpUriRequest.class))).thenReturn(page);

            ClientSession session = mock(ClientSession.class);
            LegacyConnector.checkForNewNanopubs(session);

            // The cache exists to avoid 1000+ redundant DB round-trips on every poll.
            loaderMock.verify(() -> NanopubLoader.simpleLoad(session, "http://example.org/np1", false), never());
            loaderMock.verify(() -> NanopubLoader.simpleLoad(session, "http://example.org/np2", false));
        }
    }

    @Test
    void networkFailureIsSwallowed() throws Exception {
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class);
             MockedStatic<NanopubLoader> loaderMock = mockStatic(NanopubLoader.class)) {

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            when(client.execute(any(HttpUriRequest.class))).thenThrow(new IOException("connection refused"));

            ClientSession session = mock(ClientSession.class);
            // Legacy servers go down regularly; that must not abort the update cycle.
            LegacyConnector.checkForNewNanopubs(session);

            loaderMock.verify(() -> NanopubLoader.simpleLoad(any(), anyString(), anyBoolean()), never());
        }
    }

    @Test
    void nonPrevLinkHeadersAreIgnored() throws Exception {
        try (MockedStatic<NanopubUtils> httpMock = mockStatic(NanopubUtils.class);
             MockedStatic<NanopubLoader> loaderMock = mockStatic(NanopubLoader.class)) {

            CloseableHttpClient client = mock(CloseableHttpClient.class);
            httpMock.when(NanopubUtils::getHttpClient).thenReturn(client);
            CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
            when(resp.getHeaders(eq("Link")))
                    .thenReturn(new Header[]{new BasicHeader("Link", "<page9>; rel=\"next\"")});
            HttpEntity entity = mock(HttpEntity.class);
            when(resp.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream("http://example.org/np1\n".getBytes(StandardCharsets.UTF_8)));
            when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);

            ClientSession session = mock(ClientSession.class);
            LegacyConnector.checkForNewNanopubs(session);

            // Only rel="prev" drives the backwards walk; rel="next" must not be followed.
            Mockito.verify(client, times(1)).execute(any(HttpUriRequest.class));
        }
    }

}
