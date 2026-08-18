package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.PageMocks;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentFilter}'s runtime decisions — quota lookup, publish
 * authorization and quota exhaustion. {@link AgentFilterTest} covers config parsing.
 * <p>
 * Quota enforcement is read from {@code REGISTRY_ENFORCE_QUOTA} via
 * {@code System.getenv} rather than the injectable env reader, so it is toggled
 * here by writing the resolved static field directly.
 */
class AgentFilterQuotaTest {

    private static final String PUBKEY = "pubkeyhash1234";
    private static final String EXPLICIT_PUBKEY = "explicitpubkey";

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() {
        fakeEnv = TestUtils.setupFakeEnv();
    }

    @AfterEach
    void tearDown() {
        setEnforceQuota(false);
        fakeEnv.reset();
    }

    private static void setEnforceQuota(boolean value) {
        TestUtils.clearStaticFields(AgentFilter.class, Map.of("enforceQuota", value));
    }

    /**
     * Stubs the {@code accounts} lookup that {@code getQuota} performs for a status.
     */
    private static void stubAccount(PageMocks.Db db, String status, Document result) {
        MongoCollection<Document> accounts = db.collection(Collection.ACCOUNTS.toString());
        FindIterable<Document> it = PageMocks.findIterable(result == null ? List.of() : List.of(result));
        when(accounts.find(eq(db.session), eq(new Document("pubkey", PUBKEY).append("status", status)))).thenReturn(it);
    }

    private static void stubDollarList(PageMocks.Db db, Document result) {
        MongoCollection<Document> lists = db.collection("lists");
        FindIterable<Document> it = PageMocks.findIterable(result == null ? List.of() : List.of(result));
        when(lists.find(eq(db.session), eq(new Document("pubkey", PUBKEY).append("type", "$")))).thenReturn(it);
    }

    // --- getQuota ------------------------------------------------------------

    @Test
    void explicitPubkeyQuotaTakesPrecedence() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting " + EXPLICIT_PUBKEY + ":5000").build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);

            // No account lookup should be needed at all for an explicitly configured pubkey.
            assertEquals(5000, AgentFilter.getQuota(db.session, EXPLICIT_PUBKEY));
        }
    }

    @Test
    void loadedAccountQuotaIsUsedWhenViaSetting() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", new Document("pubkey", PUBKEY).append("quota", 1234));

            assertEquals(1234, AgentFilter.getQuota(db.session, PUBKEY));
        }
    }

    @Test
    void approvedButNotYetLoadedAccountsAlsoGetTheirQuota() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", null);
            stubAccount(db, "toLoad", new Document("pubkey", PUBKEY).append("quota", 99));

            // An agent approved by the trust network can publish before its backlog is loaded.
            assertEquals(99, AgentFilter.getQuota(db.session, PUBKEY));
        }
    }

    @Test
    void unknownPubkeyHasNoQuota() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", null);
            stubAccount(db, "toLoad", null);

            assertEquals(-1, AgentFilter.getQuota(db.session, PUBKEY));
        }
    }

    @Test
    void accountsAreNotConsultedWhenViaSettingIsOff() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", EXPLICIT_PUBKEY + ":10").build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            // Deliberately no account stubs: touching the collection would NPE.

            assertEquals(-1, AgentFilter.getQuota(db.session, PUBKEY));
        }
    }

    // --- isAllowed -----------------------------------------------------------

    @Test
    void everyoneIsAllowedWhenEnforcementIsOff() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(false);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);

            // No DB stubs needed: the check short-circuits before any lookup.
            assertTrue(AgentFilter.isAllowed(db.session, PUBKEY));
        }
    }

    @Test
    void unknownPubkeyIsRejectedWhenEnforcementIsOn() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", null);
            stubAccount(db, "toLoad", null);

            assertFalse(AgentFilter.isAllowed(db.session, PUBKEY));
        }
    }

    @Test
    void knownPubkeyIsAllowedWhenEnforcementIsOn() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", new Document("pubkey", PUBKEY).append("quota", 10));

            assertTrue(AgentFilter.isAllowed(db.session, PUBKEY));
        }
    }

    // --- isOverQuota ---------------------------------------------------------

    @Test
    void nobodyIsOverQuotaWhenEnforcementIsOff() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(false);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);

            assertFalse(AgentFilter.isOverQuota(db.session, PUBKEY));
        }
    }

    @Test
    void disallowedPubkeyCountsAsOverQuota() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", null);
            stubAccount(db, "toLoad", null);

            assertTrue(AgentFilter.isOverQuota(db.session, PUBKEY));
        }
    }

    @Test
    void pubkeyUnderItsQuotaMayStillPublish() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", new Document("pubkey", PUBKEY).append("quota", 10));
            // maxPosition is zero-based, so 8 means 9 nanopubs published.
            stubDollarList(db, new Document("maxPosition", 8L));

            assertFalse(AgentFilter.isOverQuota(db.session, PUBKEY));
        }
    }

    @Test
    void pubkeyAtItsQuotaIsOver() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", new Document("pubkey", PUBKEY).append("quota", 10));
            // maxPosition 9 means 10 nanopubs, which exactly fills a quota of 10.
            stubDollarList(db, new Document("maxPosition", 9L));

            assertTrue(AgentFilter.isOverQuota(db.session, PUBKEY));
        }
    }

    @Test
    void pubkeyWithNoListYetIsUnderQuota() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubAccount(db, "loaded", new Document("pubkey", PUBKEY).append("quota", 10));
            stubDollarList(db, null);

            assertFalse(AgentFilter.isOverQuota(db.session, PUBKEY));
        }
    }

    @Test
    void zeroQuotaBlocksImmediately() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", PUBKEY + ":0").build();
        AgentFilter.init();
        setEnforceQuota(true);

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubDollarList(db, null);

            // A quota of 0 is still "allowed" (>= 0) but leaves no room to publish.
            assertTrue(AgentFilter.isAllowed(db.session, PUBKEY));
            assertTrue(AgentFilter.isOverQuota(db.session, PUBKEY));
        }
    }

}
