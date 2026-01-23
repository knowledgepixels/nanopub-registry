package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Sorts;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
    void tearDown() throws IOException {
        FileUtils.deleteDirectory(new File("data"));
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

        Path dataDir = Path.of("data");
        Files.createDirectory(dataDir);
        Files.copy(Path.of("setting.trig"), dataDir.resolve("setting.trig"));
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", "./data/setting.trig");
        fakeEnv.build();

        Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();

        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SETTING.toString(), "original"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SETTING.toString(), "current"));

        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SETTING.toString(), "bootstrap-services"));

        assertEquals(ServerStatus.coreLoading.toString(), getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"));
        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).sort(Sorts.descending("not-before")).first().getString("action"), Task.LOAD_FULL.asDocument().getString("action"));
    }

}