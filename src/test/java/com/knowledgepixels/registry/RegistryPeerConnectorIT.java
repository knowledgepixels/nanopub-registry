package com.knowledgepixels.registry;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.junit.jupiter.api.Test;
import org.nanopub.NanopubUtils;

import static com.knowledgepixels.registry.RegistryPeerConnector.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that connect to live peer registries.
 */
class RegistryPeerConnectorIT {

    private HttpResponse headRequest(String url) throws Exception {
        return NanopubUtils.getHttpClient().execute(new HttpHead(url));
    }

    @Test
    void headRequest_petapico() throws Exception {
        HttpResponse resp = headRequest("https://registry.petapico.org/");
        assertNotNull(getHeaderLong(resp, "Nanopub-Registry-Setup-Id"), "Should have setupId");
        assertNotNull(getHeaderLong(resp, "Nanopub-Registry-Load-Counter"), "Should have loadCounter");
        assertEquals("ready", getHeader(resp, "Nanopub-Registry-Status"));
        assertTrue(getHeaderLong(resp, "Nanopub-Registry-Load-Counter") > 0, "loadCounter should be positive");
    }

    @Test
    void headRequest_knowledgepixels() throws Exception {
        HttpResponse resp = headRequest("https://registry.knowledgepixels.com/");
        assertNotNull(getHeaderLong(resp, "Nanopub-Registry-Setup-Id"), "Should have setupId");
        assertNotNull(getHeaderLong(resp, "Nanopub-Registry-Load-Counter"), "Should have loadCounter");
        assertEquals("ready", getHeader(resp, "Nanopub-Registry-Status"));
        assertTrue(getHeaderLong(resp, "Nanopub-Registry-Load-Counter") > 0, "loadCounter should be positive");
    }

    @Test
    void headRequest_invalidUrl_throws() {
        assertThrows(Exception.class, () -> headRequest("https://nonexistent.invalid.example.com/"));
    }
}
