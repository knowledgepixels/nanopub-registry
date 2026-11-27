package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.PageMocks;
import com.knowledgepixels.registry.utils.TestUtils;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MainPage}, the registry's landing page. It is the endpoint that
 * monitoring and peer registries poll, so both the JSON envelope and the HTML
 * rendering (including the "still loading" variants) are covered.
 */
class MainPageTest {

    private static final String SETTING_ID = "RAsettingArtifactCode1234567890";

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() {
        // Registered but not built: the default reader stays in place until a test opts in.
        fakeEnv = TestUtils.setupFakeEnv();
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
        // Back to the default coverage and no quota authorities, for the tests that set some.
        CoverageFilter.init();
        AgentFilter.init();
    }

    private static PageMocks.Db mockDbWithStatus(MockedStatic<RegistryDB> dbMock, String status) {
        List<Document> serverInfo = new ArrayList<>(PageMocks.DEFAULT_SERVER_INFO);
        serverInfo.removeIf(d -> "status".equals(d.getString("_id")));
        serverInfo.add(new Document("_id", "status").append("value", status));

        PageMocks.Db db = PageMocks.mockDb(dbMock, serverInfo);
        dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "original")).thenReturn(SETTING_ID);
        dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "current")).thenReturn(SETTING_ID);
        when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(3L);
        when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(7L);
        when(db.collection(Collection.NANOPUBS.toString()).estimatedDocumentCount()).thenReturn(11L);
        when(db.collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(db.session)).thenReturn(2L);
        return db;
    }

    @Test
    void jsonServesTheRegistryInfoEnvelope() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.context("/.json");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("\"status\":\"ready\""), "status is reported");
            assertTrue(body.contains("\"setupId\":1"), "setupId is reported");
            assertTrue(body.contains("\"currentSetting\":\"" + SETTING_ID + "\""), "current setting is reported");
            assertTrue(body.contains("\"accountCount\":7"), "account count is reported");
            assertTrue(body.contains("\"nanopubCount\":11"), "nanopub count is reported");
            verify(ctx.response).putHeader("Nanopub-Registry-Nanopub-Count", "11");
            assertTrue(body.contains("\"registryVersion\""), "the registry version is reported");
            verify(ctx.response).putHeader("Content-Type", "application/json");
        }
    }

    @Test
    void htmlRendersServerSectionWhenReady() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Nanopub Registry</h1>"), "renders the main heading");
            assertTrue(body.contains("<em>status:</em> ready"), "status is rendered");
            assertTrue(body.contains("<em>coverageTypes:</em> all"), "coverage defaults to all types");
            assertTrue(body.contains("<em>coverageAgents:</em> viaSetting"), "coverage defaults to viaSetting");
            assertTrue(body.contains("<em>quotaAuthorities:</em> none"), "no quota authorities unless configured");
            // The fractional seconds are trimmed off the timestamp for readability.
            assertTrue(body.contains("<em>lastTrustStateUpdate:</em> 2026-04-15T12:00:00"), "timestamp is trimmed");
            assertFalse(body.contains("12:00:00.123"), "fractional seconds are not shown");
            assertTrue(body.contains("<em>trustStateHash:</em> abcdef1234"), "hash is truncated to 10 chars");
            assertTrue(body.contains("<em>trustNetworkHash:</em> fedcba0987"), "the network hash is shown the same way");
            assertTrue(body.contains("Count: 3"), "agent count is rendered");
            assertTrue(body.contains("Accounts: 7"), "account count is rendered");
            assertTrue(body.contains("Retained snapshots: 2"), "snapshot count is rendered");
            assertFalse(body.contains("(loading...)"), "nothing is marked as loading when ready");
        }
    }

    @Test
    void htmlHidesCountsWhileStillLoading() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "coreLoading");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<em>status:</em> coreLoading"), "status is rendered");
            assertTrue(body.contains("(loading...)"), "counts are replaced by a loading marker");
            assertFalse(body.contains("Accounts: 7"), "account count is withheld while loading");
        }
    }

    /**
     * A registry without trust paths has no network hash. The page then leaves the line out,
     * as the response header is left out, rather than showing "null" (issue #245).
     */
    @Test
    void htmlLeavesOutAMissingNetworkHash() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            List<Document> serverInfo = new ArrayList<>(PageMocks.DEFAULT_SERVER_INFO);
            serverInfo.removeIf(d -> "trustNetworkHash".equals(d.getString("_id")));

            PageMocks.Db db = PageMocks.mockDb(dbMock, serverInfo);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "original")).thenReturn(SETTING_ID);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "current")).thenReturn(SETTING_ID);
            when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(db.session)).thenReturn(0L);

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<em>trustStateHash:</em> abcdef1234"), "the trust state hash is shown");
            assertFalse(body.contains("trustNetworkHash"), "the missing network hash is not");
        }
    }

    @Test
    void htmlFlagsTestInstances() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            List<Document> serverInfo = new ArrayList<>(PageMocks.DEFAULT_SERVER_INFO);
            serverInfo.removeIf(d -> "testInstance".equals(d.getString("_id")));
            serverInfo.add(new Document("_id", "testInstance").append("value", true));

            PageMocks.Db db = PageMocks.mockDb(dbMock, serverInfo);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "original")).thenReturn(SETTING_ID);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "current")).thenReturn(SETTING_ID);
            when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(db.session)).thenReturn(0L);

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            assertTrue(ctx.body().contains("This is a test instance."), "test instances carry a visible warning");
        }
    }

    @Test
    void htmlReportsDisabledFeatureFlags() {
        fakeEnv.addVariable("REGISTRY_ENABLE_OPTIONAL_LOAD", "false")
                .addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false").build();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<em>optionalLoadEnabled:</em> false"), "the flag is reported as configured");
            assertTrue(body.contains("<em>trustCalculationEnabled:</em> false"), "the flag is reported as configured");
            // Without trust calculation there are no agents to count, so the section is dropped
            // rather than shown as empty.
            assertFalse(body.contains("<h3>Agents</h3>"));
            assertTrue(body.contains("<h3>Current Trust State</h3>"), "the other sections are unaffected");
        }
    }

    /**
     * The coverage settings are reported as the registry runs with them, so that taking an
     * explicit pubkey out of REGISTRY_COVERAGE_AGENTS shows on the page after the restart.
     */
    @Test
    void htmlReportsTheCoverageTheRegistryRunsWith() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/TypeA")
                .addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting abc123:5000").build();
        CoverageFilter.init();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<em>coverageTypes:</em> http://example.org/TypeA</li>"));
            assertTrue(body.contains("<em>coverageAgents:</em> viaSetting abc123:5000</li>"));
        }
    }

    private static final String AUTHORITY_A = "1162349fdeaf431e71ab55898cb2a425b971d466150c2aa5b3c1beb498045a37";
    private static final String AUTHORITY_B = "2a6683923dcbd9810ef4e5debda8c4152c297b12303e286622bbbb0ed1884384";

    /**
     * Whose quota grants a registry applies decides the quotas it publishes, and registries
     * that list different authorities can differ in their trust state hash. So the
     * authorities are shown, and from the running configuration.
     */
    @Test
    void htmlListsTheQuotaAuthorities() {
        fakeEnv.addVariable("REGISTRY_QUOTA_AUTHORITIES", AUTHORITY_B + " " + AUTHORITY_A).build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<em>quotaAuthorities:</em>"
                                     + " <a href=\"/list/" + AUTHORITY_A + "\"><code>1162349fde</code></a>"
                                     + " <a href=\"/list/" + AUTHORITY_B + "\"><code>2a6683923d</code></a></li>"),
                    "each authority links to its list, in a fixed order");
        }
    }

    @Test
    void jsonListsTheQuotaAuthorities() {
        fakeEnv.addVariable("REGISTRY_QUOTA_AUTHORITIES", AUTHORITY_B + " " + AUTHORITY_A).build();
        AgentFilter.init();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.context("/.json");
            MainPage.show(ctx.context);

            assertTrue(ctx.body().contains("\"quotaAuthorities\":[\"" + AUTHORITY_A + "\",\"" + AUTHORITY_B + "\"]"),
                    "the full hashes, in a fixed order");
        }
    }

    @Test
    void jsonReportsNoQuotaAuthoritiesAsAnEmptyList() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.context("/.json");
            MainPage.show(ctx.context);

            // Present and empty, so that a consumer can tell "none configured" from a
            // registry too old to report them.
            assertTrue(ctx.body().contains("\"quotaAuthorities\":[]"));
        }
    }

    @Test
    void jsonReportsDisabledFeatureFlags() {
        fakeEnv.addVariable("REGISTRY_ENABLE_OPTIONAL_LOAD", "false")
                .addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false").build();

        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.context("/.json");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("\"optionalLoadEnabled\":false"));
            assertTrue(body.contains("\"trustCalculationEnabled\":false"));
            assertFalse(body.contains("agentCount"), "the agent count is not computed when trust calculation is off");
        }
    }

    @Test
    void unsupportedExtensionIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/.trig");
            MainPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

}
