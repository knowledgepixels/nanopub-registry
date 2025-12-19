package com.knowledgepixels.registry;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UtilsTest {

    @Test
    void getHash() {
        String resourceToHash = "https://example.com/resource";
        String expectedHash = Hashing.sha256().hashString(resourceToHash, Charsets.UTF_8).toString();
        String actualHash = Utils.getHash(resourceToHash);
        assertEquals(expectedHash, actualHash);
    }

    @Test
    void getAgentLabelReplacesOrcidPrefix() {
        String agentId = "https://orcid.org/0000-0002-1825-0097";
        String expectedLabel = "orcid:0000-0002-1825-0097";
        assertEquals(expectedLabel, Utils.getAgentLabel(agentId));
    }

    @Test
    void getAgentLabelThrowsException() {
        String agentId1 = "";
        assertThrows(IllegalArgumentException.class, () -> Utils.getAgentLabel(agentId1));

        String agentId2 = null;
        assertThrows(IllegalArgumentException.class, () -> Utils.getAgentLabel(agentId2));
    }

}