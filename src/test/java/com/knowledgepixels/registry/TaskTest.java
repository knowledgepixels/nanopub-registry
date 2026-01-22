package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Sorts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.HashMap;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TaskTest {

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Set up fake environment - note that this must be done before RegistryDB.init() is called
        TestUtils.setupFakeEnv(mongoDBContainer);
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");

        // Initialize RegistryDB
        RegistryDB.init();

        // Clear static fields in Task class - this must always be run after the RegistryDB is initialized
        TestUtils.clearStaticFields(Task.class, new HashMap<>() {{
            put("tasksCollection", collection(Collection.TASKS.toString()));
        }});
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

        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).sort(Sorts.descending("not-before")).first().get("action"), Task.LOAD_SETTING.asDocument().get("action"));
    }

}