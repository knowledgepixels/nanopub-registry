package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TaskTest {

    private FakeEnv fakeEnv;

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Set up fake environment - note that this must be done before RegistryDB.init() is called
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");

        // Initialize RegistryDB
        RegistryDB.init();

        // Clear static fields in Task class - this must always be run after the RegistryDB is initialized
        TestUtils.clearStaticFields(Task.class, new HashMap<>() {{
            put("tasksCollection", collection(Collection.TASKS.toString()));
        }});
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.cleanupDataDir();
        fakeEnv.reset();
    }

    @Test
    void initDB() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());

        assertEquals(ServerStatus.launching.toString(), getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "testInstance"));
        assertEquals(1, RegistryDB.collection(Collection.TASKS.toString()).countDocuments(mongoSession));
        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).first().getString("action"), Task.LOAD_CONFIG.asDocument().getString("action"));
    }

    @Test
    void loadConfig() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        assertNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageTypes"));
        assertNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageAgents"));

        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).sort(Sorts.descending("not-before")).first().getString("action"), Task.LOAD_SETTING.asDocument().getString("action"));
    }

    @Test
    void loadSetting() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();

        Task.runTask(mongoSession, Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SETTING.toString(), "original"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SETTING.toString(), "current"));

        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SETTING.toString(), "bootstrap-services"));

        assertEquals(ServerStatus.coreLoading.toString(), getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"));
        List<Document> retrievedTasks = RegistryDB.collection(Collection.TASKS.toString())
                .find(mongoSession)
                .into(new ArrayList<>());
        // LOAD_SETTING schedules both LOAD_FULL and INIT_COLLECTIONS with no delay;
        // relative order between them is not significant — LOAD_FULL's status guard
        // handles either execution order.
        List<String> actions = retrievedTasks.stream().map(d -> d.getString("action")).toList();
        assertTrue(actions.contains(Task.LOAD_FULL.name()));
        assertTrue(actions.contains(Task.INIT_COLLECTIONS.name()));
    }

    @Test
    void loadFull() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();

        Task.runTask(mongoSession, Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());
        Task.runTask(mongoSession, Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        List<Document> retrievedTasks = RegistryDB.collection(Collection.TASKS.toString())
                .find(mongoSession)
                .sort(Sorts.descending("not-before"))
                .into(new ArrayList<>());

        // LOAD_FULL ran while status was still launching/coreLoading, so it self-rescheduled
        // with a 1s retry delay; that's the only task with a non-zero not-before, so it
        // sorts first. The queue also still contains the LOAD_FULL scheduled earlier by
        // LOAD_SETTING and INIT_COLLECTIONS (both at near-zero delay).
        assertEquals(Task.LOAD_FULL.name(), retrievedTasks.getFirst().getString("action"));
        List<String> actions = retrievedTasks.stream().map(d -> d.getString("action")).toList();
        assertTrue(actions.contains(Task.INIT_COLLECTIONS.name()));
        long loadFullCount = actions.stream().filter(a -> a.equals(Task.LOAD_FULL.name())).count();
        assertTrue(loadFullCount >= 2);
    }

    /**
     * RELEASE_DATA renames collections, which is DDL, so it cannot be transactional and an
     * interrupted run is re-executed from the same queue document on restart. That re-run enqueues
     * PUBLISH_TRUST_STATE a second time with the same hashes, so the successor has to absorb the
     * duplicate: no second counter bump, and no second debug row for the same state.
     */
    @Test
    void publishTrustStateAbsorbsADuplicateFromReleaseData() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();
        Task.runTask(mongoSession, Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        long counterBefore = trustStateCounter(mongoSession);

        // previousTrustStateHash is absent, i.e. null, as on the first cycle of a fresh instance
        Document releaseDoc = Task.RELEASE_DATA.asDocument().append("newTrustStateHash", "testTrustStateHash");
        Task.runTask(mongoSession, Task.RELEASE_DATA, releaseDoc);
        // ... interrupted before being dequeued, so the runner picks it up again
        Task.runTask(mongoSession, Task.RELEASE_DATA, releaseDoc);

        List<Document> queued = collection(Collection.TASKS.toString())
                .find(mongoSession, new Document("action", Task.PUBLISH_TRUST_STATE.name()))
                .into(new ArrayList<>());
        assertEquals(2, queued.size(), "the interrupted re-run should have enqueued the successor twice");

        Task.runTask(mongoSession, Task.PUBLISH_TRUST_STATE, queued.get(0));
        long counterAfterFirst = trustStateCounter(mongoSession);
        Task.runTask(mongoSession, Task.PUBLISH_TRUST_STATE, queued.get(1));
        long counterAfterSecond = trustStateCounter(mongoSession);

        assertEquals(counterBefore + 1, counterAfterFirst, "first run should record the new trust state");
        assertEquals(counterAfterFirst, counterAfterSecond, "the duplicate must not bump the trust state counter again");
        assertEquals("testTrustStateHash", getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateHash"));
        assertEquals(1, collection("debug_trustPaths").countDocuments(mongoSession), "the duplicate must replace the debug row, not append one");
        assertEquals(1, collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(mongoSession));
    }

    /**
     * INIT_COLLECTIONS is not transactional (it drops and indexes collections and fetches the
     * agent intro collection over the network), so an interrupted run leaves a partially prepared
     * loading state behind and is re-executed from the same queue document on restart. The seeding
     * that follows it used to fail on a duplicate key on every retry, forever (issue #50): both
     * the root trust path and the base account are inserted under fixed keys. The task now resets
     * the loading collections first, and the seeding itself moved to SEED_TRUST_STATE.
     *
     * <p>This exercises the reset with trust calculation disabled, which is the only branch that
     * reaches no peers. The documents seeded below are the ones SEED_TRUST_STATE inserts under
     * fixed keys, so their removal is what makes a retry collide-free; the enabled branch needs a
     * peer stub to test and is not covered here.
     */
    @Test
    void initCollectionsResetsLoadingCollections() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString())
                .addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false")
                .build();
        Task.runTask(mongoSession, Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        // Leftovers of a run interrupted partway through, under the keys it collides on
        RegistryDB.insert(mongoSession, "trustPaths_loading", new Document("_id", "$").append("agent", "$").append("pubkey", "$"));
        RegistryDB.insert(mongoSession, "accounts_loading", new Document("agent", "$").append("pubkey", "$"));
        RegistryDB.insert(mongoSession, "endorsements_loading", new Document("agent", "$").append("pubkey", "$"));

        Task.runTask(mongoSession, Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument());

        assertEquals(0, collection("trustPaths_loading").countDocuments(mongoSession), "stale trust paths must not survive the reset");
        assertEquals(0, collection("endorsements_loading").countDocuments(mongoSession));
        assertEquals(0, collection("accounts_loading").countDocuments(mongoSession, new Document("agent", "$")),
                "the stale base account must be gone, so SEED_TRUST_STATE can re-insert it");

        // The flag is observed once, here, and carried to the successor rather than re-read there
        Document seedDoc = collection(Collection.TASKS.toString())
                .find(mongoSession, new Document("action", Task.SEED_TRUST_STATE.name())).first();
        assertNotNull(seedDoc, "INIT_COLLECTIONS should hand over to SEED_TRUST_STATE");
        assertEquals(Boolean.FALSE, seedDoc.getBoolean("trustCalculation"));

        // And the task itself is repeatable, which is the property the retry depends on
        assertDoesNotThrow(() -> Task.runTask(mongoSession, Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument()));
    }

    /**
     * A fresh registry's first computed trust state is the near-empty bootstrap one: every account
     * is still 'toLoad' and therefore excluded from the hash and the snapshot (issue #119).
     * PUBLISH_TRUST_STATE holds that state back — nothing is recorded until a cycle finds the
     * initial full load complete — so consumers never see a trust state containing essentially
     * nobody, replaced right afterwards without any change in the network.
     */
    @Test
    void publishTrustStateHoldsBackBootstrapState() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();
        Task.runTask(mongoSession, Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        long counterBefore = trustStateCounter(mongoSession);

        // As after RELEASE_DATA on a fresh instance: the released accounts still await the full load
        RegistryDB.insert(mongoSession, Collection.ACCOUNTS.toString(),
                new Document("agent", "testAgent").append("pubkey", "testPubkey").append("status", EntryStatus.toLoad.getValue()));

        Task.runTask(mongoSession, Task.PUBLISH_TRUST_STATE,
                Task.PUBLISH_TRUST_STATE.asDocument().append("newTrustStateHash", "bootstrapHash"));

        assertNull(getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateHash"), "the bootstrap state must not be recorded");
        assertEquals(0, collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(mongoSession));
        assertEquals(counterBefore, trustStateCounter(mongoSession), "the bootstrap state must not bump the trust state counter");
        assertEquals(ServerStatus.coreReady.toString(), getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"),
                "holding back the state must not hold back the status transition");

        // The initial full load completes, and the next cycle publishes its state as the first one
        collection(Collection.ACCOUNTS.toString()).updateMany(mongoSession, new Document(),
                new Document("$set", new Document("status", EntryStatus.loaded.getValue())));

        Task.runTask(mongoSession, Task.PUBLISH_TRUST_STATE,
                Task.PUBLISH_TRUST_STATE.asDocument().append("newTrustStateHash", "fullHash"));

        assertEquals("fullHash", getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateHash"));
        assertEquals(counterBefore + 1, trustStateCounter(mongoSession));
        assertNotNull(collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).find(mongoSession, new Document("_id", "fullHash")).first(),
                "the first published snapshot should be the post-load state");
    }

    private static long trustStateCounter(ClientSession mongoSession) {
        return ((Number) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateCounter")).longValue();
    }

    /**
     * The snapshot carries the agent-level endorsement edges of the published state
     * (nanopub-query#184). trustEdges is cumulative and never rebuilt, so the export must
     * filter: invalidated edges, bootstrap seed edges (from the "$" account) and edges with
     * an endpoint outside the published account set — such as an account that dropped out
     * of the trust state in an earlier cycle — must all stay out. The 'source' artifact
     * code is resolved to the endorsement nanopub's full URI via the local nanopub store,
     * with the w3id.org resolver form as the fallback for a missing local copy.
     */
    @Test
    void publishTrustStateExportsEndorsementEdges() throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(mongoSession, Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();
        Task.runTask(mongoSession, Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        // Published accounts: alice and bob; the "$" base account is excluded from the
        // snapshot by the pubkey filter, and charlie left the trust state in an earlier
        // cycle, so only his stale trustEdges rows remain.
        RegistryDB.insert(mongoSession, Collection.ACCOUNTS.toString(),
                new Document("agent", "$").append("pubkey", "$").append("status", EntryStatus.loaded.getValue()));
        RegistryDB.insert(mongoSession, Collection.ACCOUNTS.toString(),
                new Document("agent", "alice").append("pubkey", "pkA").append("status", EntryStatus.loaded.getValue()));
        RegistryDB.insert(mongoSession, Collection.ACCOUNTS.toString(),
                new Document("agent", "bob").append("pubkey", "pkB").append("status", EntryStatus.loaded.getValue()));

        RegistryDB.insert(mongoSession, "trustEdges", new Document("fromAgent", "alice").append("fromPubkey", "pkA")
                .append("toAgent", "bob").append("toPubkey", "pkB").append("source", "RAresolvable").append("invalidated", false));
        RegistryDB.insert(mongoSession, "trustEdges", new Document("fromAgent", "bob").append("fromPubkey", "pkB")
                .append("toAgent", "alice").append("toPubkey", "pkA").append("source", "RAunresolvable").append("invalidated", false));
        RegistryDB.insert(mongoSession, "trustEdges", new Document("fromAgent", "alice").append("fromPubkey", "pkA")
                .append("toAgent", "bob").append("toPubkey", "pkB").append("source", "RAretracted").append("invalidated", true));
        RegistryDB.insert(mongoSession, "trustEdges", new Document("fromAgent", "alice").append("fromPubkey", "pkA")
                .append("toAgent", "charlie").append("toPubkey", "pkC").append("source", "RAtostale").append("invalidated", false));
        RegistryDB.insert(mongoSession, "trustEdges", new Document("fromAgent", "$").append("fromPubkey", "$")
                .append("toAgent", "alice").append("toPubkey", "pkA").append("source", "RAseed").append("invalidated", false));

        // Local copy of the resolvable endorsement nanopub, carrying its full URI.
        RegistryDB.insert(mongoSession, Collection.NANOPUBS.toString(),
                new Document("_id", "RAresolvable").append("fullId", "http://purl.org/np/RAresolvable"));

        Task.runTask(mongoSession, Task.PUBLISH_TRUST_STATE,
                Task.PUBLISH_TRUST_STATE.asDocument().append("newTrustStateHash", "edgeHash"));

        Document snapshot = collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                .find(mongoSession, new Document("_id", "edgeHash")).first();
        assertNotNull(snapshot);
        List<Document> edges = snapshot.getList("edges", Document.class);
        assertEquals(2, edges.size(), "only non-invalidated edges between published accounts are exported");

        Document aliceToBob = edges.stream().filter(e -> "alice".equals(e.getString("fromAgent"))).findFirst().orElseThrow();
        assertEquals("bob", aliceToBob.getString("toAgent"));
        assertEquals("pkA", aliceToBob.getString("fromPubkey"));
        assertEquals("pkB", aliceToBob.getString("toPubkey"));
        assertEquals("http://purl.org/np/RAresolvable", aliceToBob.getString("viaNanopub"),
                "source artifact code is resolved to the locally stored full URI");

        Document bobToAlice = edges.stream().filter(e -> "bob".equals(e.getString("fromAgent"))).findFirst().orElseThrow();
        assertEquals("https://w3id.org/np/RAunresolvable", bobToAlice.getString("viaNanopub"),
                "a source without a local copy falls back to the resolver-form URI");
    }

    @Test
    void recoverInterruptedCycleRestartsTheCycle() throws Exception {
        ClientSession mongoSession = startCycleInterruptedAs(ServerStatus.updating);
        // A cycle interrupted midway, plus a duplicate successor left behind by the crash:
        queueTasks(mongoSession, Task.LOAD_DECLARATIONS, Task.EXPAND_TRUST_PATHS, Task.LOAD_FULL);

        Task.recoverInterruptedCycle(mongoSession);

        List<String> actions = queuedActions(mongoSession);
        assertEquals(2, actions.size());
        assertTrue(actions.contains(Task.INIT_COLLECTIONS.name()));
        // Tasks outside the trust-state cycle keep their place in the queue:
        assertTrue(actions.contains(Task.LOAD_FULL.name()));
    }

    @Test
    void recoverInterruptedCycleDuringCoreLoading() throws Exception {
        ClientSession mongoSession = startCycleInterruptedAs(ServerStatus.coreLoading);
        queueTasks(mongoSession, Task.INIT_COLLECTIONS);

        Task.recoverInterruptedCycle(mongoSession);

        // The re-queued INIT_COLLECTIONS is the recovery's own, not the interrupted one:
        assertEquals(List.of(Task.INIT_COLLECTIONS.name()), queuedActions(mongoSession));
    }

    @Test
    void recoverInterruptedCycleKeepsPendingReleaseData() throws Exception {
        ClientSession mongoSession = startCycleInterruptedAs(ServerStatus.updating);
        // The cycle is complete; RELEASE_DATA is idempotent and finishes it:
        queueTasks(mongoSession, Task.FINALIZE_TRUST_STATE, Task.RELEASE_DATA);

        Task.recoverInterruptedCycle(mongoSession);

        assertEquals(List.of(Task.RELEASE_DATA.name()), queuedActions(mongoSession));
    }

    /**
     * The seeding writes of a cycle live in SEED_TRUST_STATE, so a queued one is a half-built cycle
     * like any other cycle task and must be dropped. Leaving it behind would let it seed into the
     * collections the restarted cycle has just reset, colliding on the fixed keys it inserts under
     * — the very failure the restart exists to prevent (issue #50).
     */
    @Test
    void recoverInterruptedCycleDropsQueuedSeeding() throws Exception {
        ClientSession mongoSession = startCycleInterruptedAs(ServerStatus.updating);
        queueTasks(mongoSession, Task.SEED_TRUST_STATE);

        Task.recoverInterruptedCycle(mongoSession);

        assertEquals(List.of(Task.INIT_COLLECTIONS.name()), queuedActions(mongoSession));
    }

    /**
     * PUBLISH_TRUST_STATE is the other half of the cycle's tail: by the time it is queued
     * RELEASE_DATA has already promoted the staging collections, so the computation is done and
     * recomputing it would discard a completed cycle. Like RELEASE_DATA it is idempotent, so it is
     * kept and left to finish.
     */
    @Test
    void recoverInterruptedCycleKeepsPendingPublishTrustState() throws Exception {
        ClientSession mongoSession = startCycleInterruptedAs(ServerStatus.updating);
        // RELEASE_DATA ran and scheduled its successor, but was interrupted before being dequeued:
        queueTasks(mongoSession, Task.RELEASE_DATA, Task.PUBLISH_TRUST_STATE);

        Task.recoverInterruptedCycle(mongoSession);

        List<String> actions = queuedActions(mongoSession);
        assertEquals(2, actions.size());
        assertTrue(actions.contains(Task.RELEASE_DATA.name()));
        assertTrue(actions.contains(Task.PUBLISH_TRUST_STATE.name()));
        // The duplicate is harmless: PUBLISH_TRUST_STATE absorbs a second run of the same state.
        assertFalse(actions.contains(Task.INIT_COLLECTIONS.name()), "a completed cycle must not be recomputed");
    }

    @Test
    void recoverInterruptedCycleWhenNoCycleWasRunning() throws Exception {
        ClientSession mongoSession = startCycleInterruptedAs(ServerStatus.ready);
        queueTasks(mongoSession, Task.UPDATE);

        Task.recoverInterruptedCycle(mongoSession);

        assertEquals(List.of(Task.UPDATE.name()), queuedActions(mongoSession));
    }

    @Test
    void recoverInterruptedCycleOnUninitializedDatabase() {
        ClientSession mongoSession = RegistryDB.getClient().startSession();

        Task.recoverInterruptedCycle(mongoSession);

        assertEquals(List.of(), queuedActions(mongoSession));
    }

    /**
     * Brings the DB into the state a shutdown during a trust-state cycle leaves behind: the given
     * server status, and an empty task queue for the test to fill with the tasks that were pending.
     */
    private ClientSession startCycleInterruptedAs(ServerStatus status) throws Exception {
        ClientSession mongoSession = RegistryDB.getClient().startSession();
        Task.runTask(mongoSession, Task.INIT_DB, Task.INIT_DB.asDocument());
        collection(Collection.TASKS.toString()).deleteMany(mongoSession, new Document());
        RegistryDB.setValue(mongoSession, Collection.SERVER_INFO.toString(), "status", status.toString());
        return mongoSession;
    }

    private void queueTasks(ClientSession mongoSession, Task... tasks) {
        for (Task task : tasks) {
            collection(Collection.TASKS.toString()).insertOne(mongoSession, task.asDocument());
        }
    }

    private List<String> queuedActions(ClientSession mongoSession) {
        return collection(Collection.TASKS.toString())
                .find(mongoSession)
                .into(new ArrayList<Document>())
                .stream()
                .map(d -> d.getString("action"))
                .toList();
    }

}