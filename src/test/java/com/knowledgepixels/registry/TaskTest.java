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

    private static long trustStateCounter(ClientSession mongoSession) {
        return ((Number) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateCounter")).longValue();
    }

}