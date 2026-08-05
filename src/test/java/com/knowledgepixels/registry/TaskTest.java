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
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();

        assertEquals(ServerStatus.launching.toString(), getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "testInstance"));
        assertEquals(1, RegistryDB.collection(Collection.TASKS.toString()).countDocuments(mongoSession));
        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).first().getString("action"), Task.LOAD_CONFIG.asDocument().getString("action"));
    }

    @Test
    void loadConfig() throws Exception {
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();

        assertNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageTypes"));
        assertNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageAgents"));

        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).sort(Sorts.descending("not-before")).first().getString("action"), Task.LOAD_SETTING.asDocument().getString("action"));
    }

    @Test
    void loadSetting() throws Exception {
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();

        Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();

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
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();

        Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());
        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();

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
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();
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