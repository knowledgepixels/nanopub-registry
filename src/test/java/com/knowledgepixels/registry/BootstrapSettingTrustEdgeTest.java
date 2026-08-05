package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.io.File;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Issue #60 — a superseded setting is invalidated like any other nanopub, but the trust
 * calculation keeps following the trust edges sourced from it.
 *
 * <p>The distinction matters. Everything about the invalidation is recorded truthfully: the
 * {@code invalidations} record, the {@code listEntries} marking, <em>and</em>
 * {@code trustEdges.invalidated = true} for the edges the setting is the source of. Nothing
 * pretends the setting was not superseded. The only change is at the point of use —
 * {@link Task#trustEdgeFilter} keeps following those edges anyway.
 *
 * <p>Why: the root endorsements of a running instance are all sourced from its setting
 * ({@code Task.INIT_COLLECTIONS}), so honouring that invalidation severs every root edge at
 * once. {@code EXPAND_TRUST_PATHS} then has nothing to follow and the trust network collapses
 * to the base agent, with no way back — {@code LOAD_SETTING} runs only at
 * {@code status == launching}.
 *
 * <p>Observed in production 2026-08-05: a setting superseding the deployed one was published
 * at 05:39:06, changing only its trust-range algorithm (a field no code reads). The next
 * iteration produced a trust state with 14 accounts instead of 777, identically on three
 * independent registries, and every iteration since logged "LOAD_CORE at depth 1 complete:
 * 0 account(s) processed".
 */
@Testcontainers
class BootstrapSettingTrustEdgeTest {

    private static final String SETTING_AC = "RAb81iFm09N9D3-L5WoJCLNUjg7NBRs29MLgz-J2mXIWg";
    private static final String OTHER_AC = "RAwUp0SmZZwQNOY1zbSPhR21aQoImiUyQrDlyXj5QYXmQ";
    private static final String ORDINARY_AC = "RAlPfnKm8LTiHfLm2Uu7oHrJ5NCUOhqUvOTUuFVAr5hEg";

    private FakeEnv fakeEnv;

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
    }

    // ---------- the invalidation is recorded truthfully ----------

    /**
     * The setting is superseded, so its trust edges genuinely are invalidated and must say so.
     * Suppressing that record is what the first attempt at this fix got wrong.
     */
    @Test
    void supersedingTheSettingStillMarksItsTrustEdgesInvalidated() throws Exception {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            recordSetting(s, SETTING_AC);
            insertEdge(s, "$", "$", SETTING_AC);

            loadNanopubInvalidating(s, SETTING_AC);

            assertTrue(RegistryDB.has(s, "invalidations", new Document("invalidatedNp", SETTING_AC)),
                    "the invalidation must be recorded");
            assertTrue(edgeInvalidated(s, SETTING_AC),
                    "the edge is invalidated and must be recorded as invalidated — the exception "
                            + "belongs in the trust calculation, not in the stored data");
        }
    }

    // ---------- the trust calculation declines to act on it ----------

    @Test
    void trustCalculationFollowsInvalidatedEdgesFromTheBootstrapSetting() {
        Document filter = Task.trustEdgeFilter("$", "$", Set.of(SETTING_AC));

        assertEquals("$", filter.getString("fromAgent"));
        assertEquals("$", filter.getString("fromPubkey"));
        assertNotNull(filter.get("$or"),
                "the filter must accept invalidated edges sourced from the bootstrap setting");
        assertTrue(filter.toJson().contains(SETTING_AC));
    }

    @Test
    void trustCalculationSkipsInvalidatedEdgesFromEverythingElse() {
        // No bootstrap setting recorded: the plain "not invalidated" filter, no escape hatch.
        Document filter = Task.trustEdgeFilter("agent", "pk", Set.of());

        assertEquals(Boolean.FALSE, filter.get("invalidated"));
        assertEquals("agent", filter.getString("fromAgent"));
        assertNull(filter.get("$or"), "no escape hatch when there is no bootstrap setting");
    }

    @Test
    void bothOriginalAndCurrentSettingsAreFollowed() {
        String json = Task.trustEdgeFilter("$", "$", Set.of(SETTING_AC, OTHER_AC)).toJson();
        assertTrue(json.contains(SETTING_AC), "original setting must be followed");
        assertTrue(json.contains(OTHER_AC), "current setting must be followed");
    }

    // ---------- the lookup that feeds the filter ----------

    @Test
    void bootstrapSettingAcsReadsOriginalAndCurrent() {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            RegistryDB.setValue(s, Collection.SETTING.toString(), "original", OTHER_AC);
            RegistryDB.setValue(s, Collection.SETTING.toString(), "current", SETTING_AC);

            assertEquals(Set.of(OTHER_AC, SETTING_AC), RegistryDB.getBootstrapSettingAcs(s));
        }
    }

    @Test
    void bootstrapSettingAcsIsEmptyBeforeTheSettingIsRecorded() {
        // LOAD_SETTING loads the setting nanopub right after writing these values; before that
        // nothing is built, so there are no trust edges to consider.
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            assertTrue(RegistryDB.getBootstrapSettingAcs(s).isEmpty());
        }
    }

    // ---------- ordinary invalidation is untouched ----------

    @Test
    void ordinaryInvalidationStillMarksAndSkipsItsEdges() throws Exception {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            recordSetting(s, SETTING_AC);
            insertEdge(s, "x", "pk1", ORDINARY_AC);

            loadNanopubInvalidating(s, ORDINARY_AC);

            assertTrue(edgeInvalidated(s, ORDINARY_AC));
            assertTrue(!Task.trustEdgeFilter("x", "pk1", Set.of(SETTING_AC)).toJson().contains(ORDINARY_AC),
                    "ordinary invalidated edges must stay excluded from the trust calculation");
        }
    }

    // ---------- helpers ----------

    private static void recordSetting(ClientSession s, String ac) {
        RegistryDB.setValue(s, Collection.SETTING.toString(), "original", ac);
        RegistryDB.setValue(s, Collection.SETTING.toString(), "current", ac);
    }

    private static void insertEdge(ClientSession s, String fromAgent, String fromPubkey, String sourceAc) {
        RegistryDB.insert(s, "trustEdges", new Document("fromAgent", fromAgent).append("fromPubkey", fromPubkey)
                .append("toAgent", "http://example.org/" + sourceAc).append("toPubkey", "pk")
                .append("source", sourceAc).append("invalidated", false));
    }

    private static boolean edgeInvalidated(ClientSession s, String sourceAc) {
        Document e = RegistryDB.collection("trustEdges").find(s, new Document("source", sourceAc)).first();
        assertNotNull(e, "test setup: the trust edge should exist");
        return Boolean.TRUE.equals(e.getBoolean("invalidated"));
    }

    /**
     * Loads a real nanopub while forcing it to invalidate {@code invalidatedAc}, so the
     * invalidation branch of {@code loadNanopub} runs against a genuinely insertable nanopub.
     */
    private static void loadNanopubInvalidating(ClientSession s, String invalidatedAc) throws Exception {
        File file = NanopubTestSuite.getLatest()
                .getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub np = new NanopubImpl(file);
        IRI invalidated = SimpleValueFactory.getInstance().createIRI("http://purl.org/np/" + invalidatedAc);
        try (MockedStatic<Utils> utils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> Utils.getInvalidatedNanopubIds(np)).thenReturn(Set.of(invalidated));
            RegistryDB.loadNanopub(s, np);
        }
    }
}
