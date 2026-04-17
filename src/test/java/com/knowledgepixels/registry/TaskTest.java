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

}