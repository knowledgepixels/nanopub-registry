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
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting abc123:5000 def456:10000").build();
        AgentFilter.init();
        assertTrue(AgentFilter.usesViaSetting());
        assertEquals(2, AgentFilter.getExplicitPubkeys().size());
        assertEquals(5000, AgentFilter.getExplicitPubkeys().get("abc123"));
        assertEquals(10000, AgentFilter.getExplicitPubkeys().get("def456"));
    }

    @Test
    void explicitPubkeysOnlyNoViaSetting() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "abc123:100 def456:200").build();
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
    void noQuotaAuthoritiesByDefault() {
        fakeEnv.build();
        AgentFilter.init();
        assertTrue(AgentFilter.getQuotaAuthorities().isEmpty());
    }

    @Test
    void readsQuotaAuthorities() {
        String a = "403d674304853131343af2fd56c48a808c8754d0a7aa32ffb35aa0960cdbd97b";
        String b = "5cbb6723f232f7f716bc687ec2f4e4247650cae2a23e804504c235f590a21db1";
        fakeEnv.addVariable("REGISTRY_QUOTA_AUTHORITIES", " " + a + "\n" + b + " ").build();
        AgentFilter.init();
        assertEquals(java.util.Set.of(a, b), AgentFilter.getQuotaAuthorities());
    }

    @Test
    void rejectsAQuotaAuthorityThatIsNotAPubkeyHash() {
        // A quota, as in REGISTRY_COVERAGE_AGENTS, does not belong here: an authority's own
        // quota comes from the trust network, an explicit entry, or a grant.
        fakeEnv.addVariable("REGISTRY_QUOTA_AUTHORITIES",
                "403d674304853131343af2fd56c48a808c8754d0a7aa32ffb35aa0960cdbd97b:100000").build();
        assertThrows(IllegalArgumentException.class, AgentFilter::init);
    }

    @Test
    void viaSettingStaysEnabledWhenTrustCalculationIsOff() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting")
                .addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false").build();
        AgentFilter.init();

        // The combination is accepted but useless — no agent is ever discovered — so init
        // warns rather than failing, and the configuration is kept as written.
        assertTrue(AgentFilter.usesViaSetting());
        assertTrue(AgentFilter.getExplicitPubkeys().isEmpty());
    }

    @Test
    void explicitPubkeysStillWorkWithoutTrustCalculation() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "abc123:5000")
                .addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false").build();
        AgentFilter.init();

        assertFalse(AgentFilter.usesViaSetting());
        assertEquals(5000, AgentFilter.getExplicitPubkeys().get("abc123"));
    }

    @Test
    void toleratesVariousWhitespace() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting  abc123:5000\tabc123:5000\ndef456:10000").build();
        AgentFilter.init();
        assertTrue(AgentFilter.usesViaSetting());
        assertEquals(5000, AgentFilter.getExplicitPubkeys().get("abc123"));
        assertEquals(10000, AgentFilter.getExplicitPubkeys().get("def456"));
    }
}
