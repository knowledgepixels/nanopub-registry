package com.knowledgepixels.registry;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static com.knowledgepixels.registry.RegistryPeerConnector.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that connect to live peer registries.
 */
class RegistryPeerConnectorIT {

    @Test
    void fetchPeerInfo_petapico() {
        JsonObject info = fetchPeerInfo("https://registry.petapico.org/");
        assertNotNull(info, "Should get response from petapico registry");
        assertNotNull(getJsonLong(info, "setupId"), "Should have setupId");
        assertNotNull(getJsonLong(info, "loadCounter"), "Should have loadCounter");
        assertNotNull(getJsonString(info, "status"), "Should have status");
        assertEquals("ready", getJsonString(info, "status"));
        assertTrue(getJsonLong(info, "loadCounter") > 0, "loadCounter should be positive");
    }

    @Test
    void fetchPeerInfo_knowledgepixels() {
        JsonObject info = fetchPeerInfo("https://registry.knowledgepixels.com/");
        assertNotNull(info, "Should get response from knowledgepixels registry");
        assertNotNull(getJsonLong(info, "setupId"), "Should have setupId");
        assertNotNull(getJsonLong(info, "loadCounter"), "Should have loadCounter");
        assertNotNull(getJsonString(info, "status"), "Should have status");
        assertEquals("ready", getJsonString(info, "status"));
        assertTrue(getJsonLong(info, "loadCounter") > 0, "loadCounter should be positive");
    }

    @Test
    void fetchPeerInfo_invalidUrl_returnsNull() {
        JsonObject info = fetchPeerInfo("https://nonexistent.invalid.example.com/");
        assertNull(info, "Should return null for unreachable peer");
    }
}
