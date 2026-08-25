package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link TrustUpdateTrigger}: which arrivals ask for an early trust
 * state update, and how far forward the queued {@code UPDATE} is actually moved.
 */
@Testcontainers
class TrustUpdateTriggerTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    /**
     * An agent introduction: its assertion carries a {@code npx:declaredBy} key
     * declaration, which is what the cycle turns into trust edges.
     */
    private static final String INTRO_AC = "RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE";

    /**
     * A nanopub with an ordinary assertion and nothing the trust calculation reads.
     */
    private static final String PLAIN_AC = "RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY";

    /**
     * A nanopub that retracts {@link #RETRACTED_AC}.
     */
    private static final String RETRACTION_AC = "RAjPRftIBK8ZbR2LausQpdsMbI39_eRe07AZwfHTsm2dY";

    private static final String RETRACTED_AC = "RARv1-bZWsdvQs88TDH2trcwNoGF1g5AawE2sPKeh5K_0";

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");

        RegistryDB.init();

        TestUtils.clearStaticFields(Task.class, new HashMap<>() {{
            put("tasksCollection", collection(Collection.TASKS.toString()));
        }});
        TestUtils.clearStaticFields(Utils.class, Map.of("peerUrls", List.of()));
        TestUtils.clearStaticFields(Utils.class, "settingNp");
        CoverageFilter.init();
        AgentFilter.init();

        session = RegistryDB.getClient().startSession();
        TrustUpdateTrigger.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        TrustUpdateTrigger.reset();
        session.close();
        RegistryDB.getDB().drop();
        RegistryDB.getClient().close();
        TestUtils.clearStaticFields(Utils.class, "settingNp", "peerUrls");
        TestUtils.cleanupDataDir();
        fakeEnv.reset();
    }

    // ---------------------------------------------------------------- helpers

    private Nanopub testSuiteNanopub(String artifactCode) throws Exception {
        return new NanopubImpl(
                NanopubTestSuite.getLatest().getByArtifactCode(artifactCode).getFirst().toFile());
    }

    /**
     * An endorsement, which the test suite has no example of: an assertion whose
     * predicate is {@code npx:approvesOf}, exactly what LOAD_CORE looks for.
     */
    private Nanopub endorsement() throws Exception {
        return new NanopubImpl("""
                @prefix : <http://example.org/endorsement/> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix dct: <http://purl.org/dc/terms/> .
                @prefix prov: <http://www.w3.org/ns/prov#> .
                @prefix np: <http://www.nanopub.org/nschema#> .
                @prefix npx: <http://purl.org/nanopub/x/> .
                @prefix orcid: <https://orcid.org/> .

                :Head {
                    : np:hasAssertion :assertion ;
                        np:hasProvenance :provenance ;
                        np:hasPublicationInfo :pubinfo ;
                        a np:Nanopublication .
                }

                :assertion {
                    orcid:0000-0001-8492-0354 npx:approvesOf
                        <https://w3id.org/np/RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE> .
                }

                :provenance {
                    :assertion prov:wasAttributedTo orcid:0000-0001-8492-0354 .
                }

                :pubinfo {
                    : dct:created "2026-01-01T00:00:00Z"^^xsd:dateTime .
                }
                """, RDFFormat.TRIG);
    }

    /**
     * Queues an {@code UPDATE} the way {@code scheduleUpdate} does, with the given
     * offsets from now for its due time and its floor.
     */
    private void queueUpdate(long dueInMs, long floorInMs) {
        long now = System.currentTimeMillis();
        collection(Collection.TASKS.toString()).insertOne(session, new Document()
                .append("not-before", now + dueInMs)
                .append("not-before-floor", now + floorInMs)
                .append("action", Task.UPDATE.name()));
    }

    private Document queuedUpdate() {
        return collection(Collection.TASKS.toString())
                .find(session, new Document("action", Task.UPDATE.name())).first();
    }

    // --------------------------------------------------------- classification

    @Test
    void anAgentIntroductionIsTrustRelevant() throws Exception {
        assertTrue(TrustUpdateTrigger.isTrustRelevant(session, testSuiteNanopub(INTRO_AC)),
                "its key declarations become trust edges");
    }

    @Test
    void anEndorsementIsTrustRelevant() throws Exception {
        assertTrue(TrustUpdateTrigger.isTrustRelevant(session, endorsement()));
    }

    @Test
    void anOrdinaryNanopubIsNotTrustRelevant() throws Exception {
        assertFalse(TrustUpdateTrigger.isTrustRelevant(session, testSuiteNanopub(PLAIN_AC)),
                "the overwhelming majority of arrivals must not trigger a recompute");
    }

    @Test
    void retractingSomethingThatSourcesATrustEdgeIsTrustRelevant() throws Exception {
        // A retracted approval revokes trust, and the graph only reflects that on the next cycle.
        RegistryDB.insert(session, "trustEdges", new Document("fromAgent", "$")
                .append("fromPubkey", "$")
                .append("toAgent", "A")
                .append("toPubkey", "P")
                .append("source", RETRACTED_AC)
                .append("invalidated", false));

        assertTrue(TrustUpdateTrigger.isTrustRelevant(session, testSuiteNanopub(RETRACTION_AC)));
    }

    @Test
    void retractingSomethingUnrelatedToTheTrustGraphIsNotTrustRelevant() throws Exception {
        assertFalse(TrustUpdateTrigger.isTrustRelevant(session, testSuiteNanopub(RETRACTION_AC)),
                "nothing in the trust graph is sourced from the retracted nanopub");
    }

    // -------------------------------------------------------------- requesting

    @Test
    void newTrustRelevantDataRequestsAnEarlyUpdate() throws Exception {
        TrustUpdateTrigger.noteIncoming(session, testSuiteNanopub(INTRO_AC));

        assertTrue(TrustUpdateTrigger.isPending());
    }

    @Test
    void alreadyStoredNanopubsRequestNothing() throws Exception {
        Nanopub intro = testSuiteNanopub(INTRO_AC);
        assertTrue(RegistryDB.loadNanopub(session, intro));

        TrustUpdateTrigger.noteIncoming(session, intro);

        assertFalse(TrustUpdateTrigger.isPending(),
                "peers re-offer nanopubs we already have; those carry no new information");
    }

    @Test
    void ordinaryNanopubsRequestNothing() throws Exception {
        TrustUpdateTrigger.noteIncoming(session, testSuiteNanopub(PLAIN_AC));

        assertFalse(TrustUpdateTrigger.isPending());
    }

    // ----------------------------------------------------------------- applying

    @Test
    void aPendingRequestPullsTheQueuedUpdateForwardToItsFloor() throws Exception {
        queueUpdate(10 * 60 * 1000, 2 * 60 * 1000);
        long originalDue = queuedUpdate().getLong("not-before");
        TrustUpdateTrigger.noteIncoming(session, testSuiteNanopub(INTRO_AC));

        TrustUpdateTrigger.applyIfPending(session);

        long due = queuedUpdate().getLong("not-before");
        assertTrue(due < originalDue, "the update was moved forward");
        assertTrue(due >= System.currentTimeMillis() + 2 * 60 * 1000 - 1000,
                "but no further forward than the floor allows");
        assertFalse(TrustUpdateTrigger.isPending(), "the request was consumed");
    }

    @Test
    void aFloorAlreadyInThePastAllowsAnImmediateUpdate() throws Exception {
        queueUpdate(10 * 60 * 1000, -60 * 1000);
        TrustUpdateTrigger.noteIncoming(session, testSuiteNanopub(INTRO_AC));

        long before = System.currentTimeMillis();
        TrustUpdateTrigger.applyIfPending(session);

        long due = queuedUpdate().getLong("not-before");
        assertTrue(due >= before, "never scheduled into the past");
        assertTrue(due <= before + Task.UPDATE_TRIGGER_JITTER_MS,
                "due now, give or take the jitter that keeps registries from phase-locking");
    }

    @Test
    void repeatedRequestsDoNotKeepMovingTheUpdate() throws Exception {
        queueUpdate(10 * 60 * 1000, 2 * 60 * 1000);
        TrustUpdateTrigger.noteIncoming(session, testSuiteNanopub(INTRO_AC));
        TrustUpdateTrigger.applyIfPending(session);
        long due = queuedUpdate().getLong("not-before");

        // A burst of arrivals: however many there are, they collapse into the one early update.
        TrustUpdateTrigger.noteIncoming(session, endorsement());
        TrustUpdateTrigger.applyIfPending(session);
        TrustUpdateTrigger.noteIncoming(session, endorsement());
        TrustUpdateTrigger.applyIfPending(session);

        assertEquals(due, queuedUpdate().getLong("not-before"));
    }

    @Test
    void anUpdateThatIsAlreadyDueSoonerIsLeftAlone() {
        queueUpdate(1000, 2 * 60 * 1000);
        long due = queuedUpdate().getLong("not-before");

        assertTrue(Task.pullUpdateForward(session));

        assertEquals(due, queuedUpdate().getLong("not-before"), "the trigger never delays an update");
    }

    @Test
    void aRequestIsKeptWhileACycleIsRunning() throws Exception {
        // Mid-cycle there is no queued UPDATE: the one that started the cycle left the queue.
        TrustUpdateTrigger.noteIncoming(session, testSuiteNanopub(INTRO_AC));

        TrustUpdateTrigger.applyIfPending(session);

        assertTrue(TrustUpdateTrigger.isPending(),
                "the request survives to be applied once the cycle has queued the next update");
    }

    @Test
    void anUpdateQueuedWithoutAFloorGetsOne() {
        // An UPDATE left in the queue by a version that did not stamp floors yet.
        long now = System.currentTimeMillis();
        collection(Collection.TASKS.toString()).insertOne(session, new Document()
                .append("not-before", now + 10 * 60 * 1000)
                .append("action", Task.UPDATE.name()));

        assertTrue(Task.pullUpdateForward(session));

        assertTrue(queuedUpdate().getLong("not-before") >= now + Task.UPDATE_MIN_INTERVAL_MS,
                "an unknown floor counts as one starting now, not as no floor at all");
    }

    @Test
    void pullingForwardReportsWhenThereIsNothingQueued() {
        assertFalse(Task.pullUpdateForward(session));
    }

    // ----------------------------------------------------------------- floors

    @Test
    void aPostponedUpdateArmsAFloorOfItsOwn() throws Exception {
        RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "status",
                ServerStatus.coreLoading.toString());
        long before = System.currentTimeMillis();

        Task.runTask(Task.UPDATE, Task.UPDATE.asDocument());

        Document queued = queuedUpdate();
        assertNotNull(queued);
        assertTrue(queued.getLong("not-before") >= before + Task.UPDATE_INTERVAL_MS);
        assertTrue(queued.getLong("not-before-floor") >= before + Task.UPDATE_MIN_INTERVAL_MS,
                "a registry that is busy cannot have an update triggered into it either");
    }

}
