package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentFilterTest {

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() {
        fakeEnv = TestUtils.setupFakeEnv();
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
    }

    @Test
    void defaultsToViaSetting() {
        fakeEnv.build();
        AgentFilter.init();
        assertTrue(AgentFilter.usesViaSetting());
        assertTrue(AgentFilter.getExplicitPubkeys().isEmpty());
    }

    @Test
    void viaSettingExplicit() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        assertTrue(AgentFilter.usesViaSetting());
        assertTrue(AgentFilter.getExplicitPubkeys().isEmpty());
    }

    @Test
    void explicitPubkeyOnly() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "abc123:5000").build();
        AgentFilter.init();
        assertFalse(AgentFilter.usesViaSetting());
        assertEquals(1, AgentFilter.getExplicitPubkeys().size());
        assertEquals(5000, AgentFilter.getExplicitPubkeys().get("abc123"));
    }

    @Test
    void viaSettingPlusExplicitPubkeys() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting,abc123:5000,def456:10000").build();
        AgentFilter.init();
        assertTrue(AgentFilter.usesViaSetting());
        assertEquals(2, AgentFilter.getExplicitPubkeys().size());
        assertEquals(5000, AgentFilter.getExplicitPubkeys().get("abc123"));
        assertEquals(10000, AgentFilter.getExplicitPubkeys().get("def456"));
    }

    @Test
    void explicitPubkeysOnlyNoViaSetting() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "abc123:100,def456:200").build();
        AgentFilter.init();
        assertFalse(AgentFilter.usesViaSetting());
        assertEquals(2, AgentFilter.getExplicitPubkeys().size());
    }

    @Test
    void rejectsInvalidFormat() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "notAValidEntry").build();
        assertThrows(IllegalArgumentException.class, AgentFilter::init);
    }

    @Test
    void rejectsInvalidQuota() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "abc123:notanumber").build();
        assertThrows(IllegalArgumentException.class, AgentFilter::init);
    }

    @Test
    void toleratesWhitespace() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting , abc123:5000 , def456:10000").build();
        AgentFilter.init();
        assertTrue(AgentFilter.usesViaSetting());
        assertEquals(5000, AgentFilter.getExplicitPubkeys().get("abc123"));
        assertEquals(10000, AgentFilter.getExplicitPubkeys().get("def456"));
    }
}
