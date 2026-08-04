package com.knowledgepixels.registry;

import com.google.gson.JsonSyntaxException;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the parts of {@link Utils} that shape HTTP responses: {@code Accept}
 * header negotiation, the reported build version and the JSON list fetch used to
 * talk to peers.
 */
class UtilsContentNegotiationTest {

    private static RoutingContext contextWithAccept(String acceptHeader) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        when(request.getHeader("Accept")).thenReturn(acceptHeader);
        return context;
    }

    // --- getMimeType ---------------------------------------------------------

    @Test
    void missingAcceptHeaderFallsBackToTheFirstSupportedType() {
        // Peers and scripts often send no Accept header; they get the machine-readable default.
        assertEquals(Utils.TYPE_JSON,
                Utils.getMimeType(contextWithAccept(null), Utils.SUPPORTED_TYPES_LIST));
        assertEquals(Utils.TYPE_TRIG,
                Utils.getMimeType(contextWithAccept(null), Utils.SUPPORTED_TYPES_NANOPUB));
    }

    @Test
    void browsersAskingForHtmlGetHtml() {
        assertEquals(Utils.TYPE_HTML,
                Utils.getMimeType(contextWithAccept("text/html"), Utils.SUPPORTED_TYPES_LIST));
    }

    @Test
    void realBrowserAcceptHeaderResolvesToHtml() {
        String firefoxAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
        assertEquals(Utils.TYPE_HTML,
                Utils.getMimeType(contextWithAccept(firefoxAccept), Utils.SUPPORTED_TYPES_NANOPUB));
    }

    @Test
    void qualityValuesAreRespected() {
        assertEquals(Utils.TYPE_JELLY,
                Utils.getMimeType(contextWithAccept("text/html;q=0.2,application/x-jelly-rdf;q=0.9"),
                        Utils.SUPPORTED_TYPES_LIST));
    }

    @Test
    void unsupportedAcceptHeaderYieldsNoMatch() {
        // MIMEParse returns an empty best-match rather than throwing when nothing fits.
        assertEquals("", Utils.getMimeType(contextWithAccept("image/png"), Utils.SUPPORTED_TYPES_LIST));
    }

    @Test
    void malformedAcceptHeaderFallsBackToTheDefault() {
        assertEquals(Utils.TYPE_JSON,
                Utils.getMimeType(contextWithAccept("this is not an accept header"), Utils.SUPPORTED_TYPES_LIST));
    }

    // --- getVersion ----------------------------------------------------------

    @Test
    void versionIsResolvedAndCached() {
        String version = Utils.getVersion();
        assertNotNull(version);
        assertFalse(version.isBlank(), "a version is always reported, even if only 'unknown'");
        assertEquals(version, Utils.getVersion(), "repeated calls return the cached value");
    }

    // --- retrieveListFromJsonUrl ---------------------------------------------

    @Test
    void retrievesAJsonStringList(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("list.json");
        Files.writeString(file, "[\"a\",\"b\",\"c\"]");

        List<String> result = Utils.retrieveListFromJsonUrl(file.toUri().toString());

        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void retrievesAnEmptyJsonList(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("empty.json");
        Files.writeString(file, "[]");

        assertTrue(Utils.retrieveListFromJsonUrl(file.toUri().toString()).isEmpty());
    }

    @Test
    void aJsonNullBodyYieldsNoList(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("null.json");
        Files.writeString(file, "null");

        // Gson maps a bare JSON null to a null list; the caller has to cope with it.
        assertNull(Utils.retrieveListFromJsonUrl(file.toUri().toString()));
    }

    @Test
    void malformedJsonIsReportedToTheCaller(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("broken.json");
        Files.writeString(file, "{not json");

        String url = file.toUri().toString();
        assertThrows(JsonSyntaxException.class, () -> Utils.retrieveListFromJsonUrl(url));
    }

    @Test
    void unreachableUrlIsReportedToTheCaller(@TempDir Path tempDir) {
        String url = tempDir.resolve("does-not-exist.json").toUri().toString();
        assertThrows(IOException.class, () -> Utils.retrieveListFromJsonUrl(url));
    }

}
