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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Issue #60 — invalidating the setting nanopub this instance was bootstrapped from must not
 * sever its trust edges.
 *
 * <p>Scope, and it is deliberately narrow: the setting still gets invalidated normally. The
 * {@code invalidations} record is written and its {@code listEntries} are marked, so it reads
 * as superseded wherever it is listed. Only the consequence for the trust graph is suppressed.
 *
 * <p>Why it matters: {@code Task.INIT_COLLECTIONS} seeds every root endorsement with
 * {@code source = <setting artifact code>}, and {@code RegistryDB.loadNanopub} marks
 * <em>all</em> trust edges carrying an invalidated nanopub as their source:
 * {@code updateMany(trustEdges, {source: invalidatedAc}, {$set: {invalidated: true}})} —
 * with no pubkey restriction. {@code EXPAND_TRUST_PATHS} then follows only edges with
 * {@code invalidated == false}, so a single {@code npx:supersedes} of the setting severs
 * every root edge and the trust network collapses to the base agent.
 *
 * <p>It cannot recover on its own: {@code LOAD_SETTING} runs only at
 * {@code status == launching}, so the instance keeps using the invalidated setting.
 *
 * <p>Observed in production 2026-08-05: a setting superseding the deployed one was
 * published at 05:39:06 (its only assertion change was the trust-range algorithm), and the
 * next iteration produced a trust state with 14 accounts instead of 777 — identically on
 * three independent registries. Every iteration since logged
 * "LOAD_CORE at depth 1 complete: 0 account(s) processed".
 */
@Testcontainers
class RegistryDBImmuneSettingTest {

    private static final String SETTING_AC = "RAb81iFm09N9D3-L5WoJCLNUjg7NBRs29MLgz-J2mXIWg";
    private static final String OTHER_AC = "RAwUp0SmZZwQNOY1zbSPhR21aQoImiUyQrDlyXj5QYXmQ";

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

    @Test
    void bootstrapSettingIsImmune() {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            RegistryDB.setValue(s, Collection.SETTING.toString(), "original", SETTING_AC);
            RegistryDB.setValue(s, Collection.SETTING.toString(), "current", SETTING_AC);

            assertTrue(RegistryDB.isImmuneSetting(s, SETTING_AC),
                    "the setting this instance was bootstrapped from must be immune");
        }
    }

    @Test
    void otherNanopubsAreNotImmune() {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            RegistryDB.setValue(s, Collection.SETTING.toString(), "original", SETTING_AC);
            RegistryDB.setValue(s, Collection.SETTING.toString(), "current", SETTING_AC);

            assertFalse(RegistryDB.isImmuneSetting(s, OTHER_AC),
                    "immunity must not leak to ordinary nanopubs — normal retraction and "
                            + "supersession must keep working");
        }
    }

    @Test
    void currentSettingIsAlsoImmuneWhenItDiffersFromOriginal() {
        // current == original today, but they are stored and read separately. If current
        // ever starts tracking supersession, the in-use setting must stay protected too.
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            RegistryDB.setValue(s, Collection.SETTING.toString(), "original", OTHER_AC);
            RegistryDB.setValue(s, Collection.SETTING.toString(), "current", SETTING_AC);

            assertTrue(RegistryDB.isImmuneSetting(s, SETTING_AC), "current setting must be immune");
            assertTrue(RegistryDB.isImmuneSetting(s, OTHER_AC), "original setting must be immune");
        }
    }

    /**
     * The invalidation must still be recorded in full — the setting genuinely is superseded
     * and has to read as invalidated wherever it is listed. Only its effect on the trust
     * graph is suppressed.
     */
    @Test
    void settingInvalidationIsRecordedButItsTrustEdgesSurvive() throws Exception {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            String settingAc = SETTING_AC;
            RegistryDB.setValue(s, Collection.SETTING.toString(), "original", settingAc);
            RegistryDB.setValue(s, Collection.SETTING.toString(), "current", settingAc);

            // A root trust edge, as Task.INIT_COLLECTIONS seeds them.
            RegistryDB.insert(s, "trustEdges", new Document("fromAgent", "$").append("fromPubkey", "$")
                    .append("toAgent", "http://example.org/a").append("toPubkey", "pk")
                    .append("source", settingAc).append("invalidated", false));

            loadNanopubInvalidating(s, settingAc);

            assertTrue(RegistryDB.has(s, "invalidations", new Document("invalidatedNp", settingAc)),
                    "the invalidation itself must still be recorded");
            assertFalse(trustEdgeInvalidated(s, settingAc),
                    "trust edges sourced from the bootstrap setting must survive its invalidation");
        }
    }

    @Test
    void ordinaryInvalidationStillSeversItsTrustEdges() throws Exception {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            String settingAc = SETTING_AC;
            String ordinaryAc = "RAlPfnKm8LTiHfLm2Uu7oHrJ5NCUOhqUvOTUuFVAr5hEg";
            RegistryDB.setValue(s, Collection.SETTING.toString(), "original", settingAc);
            RegistryDB.setValue(s, Collection.SETTING.toString(), "current", settingAc);

            RegistryDB.insert(s, "trustEdges", new Document("fromAgent", "x").append("fromPubkey", "pk1")
                    .append("toAgent", "http://example.org/b").append("toPubkey", "pk2")
                    .append("source", ordinaryAc).append("invalidated", false));

            loadNanopubInvalidating(s, ordinaryAc);

            assertTrue(RegistryDB.has(s, "invalidations", new Document("invalidatedNp", ordinaryAc)));
            assertTrue(trustEdgeInvalidated(s, ordinaryAc),
                    "immunity must not leak: ordinary invalidation still severs its trust edges");
        }
    }

    private static boolean trustEdgeInvalidated(ClientSession s, String sourceAc) {
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
        IRI invalidated = SimpleValueFactory.getInstance()
                .createIRI("http://purl.org/np/" + invalidatedAc);
        try (MockedStatic<Utils> utils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> Utils.getInvalidatedNanopubIds(np)).thenReturn(Set.of(invalidated));
            RegistryDB.loadNanopub(s, np);
        }
    }

    @Test
    void noSettingRecordedYetMeansNoImmunity() {
        // LOAD_SETTING loads the setting nanopub itself right after writing these values;
        // before that there is nothing built, so there are no trust edges to protect.
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            assertFalse(RegistryDB.isImmuneSetting(s, SETTING_AC));
            assertFalse(RegistryDB.isImmuneSetting(s, null),
                    "a null artifact code must not be treated as the setting");
        }
    }
}
