package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.HashMap;
import java.util.Map;

import static com.knowledgepixels.registry.RegistryDB.getValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
class TaskTest {

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() {
        Map<String, String> fakeEnv = new HashMap<>();
        fakeEnv.put("REGISTRY_DB_NAME", "nanopubRegistry");
        fakeEnv.put("REGISTRY_DB_HOST", mongoDBContainer.getHost());
        fakeEnv.put("REGISTRY_DB_PORT", String.valueOf(mongoDBContainer.getFirstMappedPort()));
        ReadsEnvironment reader = new ReadsEnvironment(fakeEnv::get);
        Utils.setEnvReader(reader);
    }

    @Test
    void initDB() throws Exception {
        RegistryDB.init();
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        ClientSession mongoSession = RegistryDB.getClient().startSession();

        assertEquals(ServerStatus.launching.toString(), getValue(mongoSession, Collection.SERVER_INFO.toString(), "status"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId"));
        assertNotNull(RegistryDB.getValue(mongoSession, Collection.SERVER_INFO.toString(), "testInstance"));
        assertEquals(1, RegistryDB.collection(Collection.TASKS.toString()).countDocuments(mongoSession));
        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(mongoSession).first().get("action"), Task.LOAD_CONFIG.asDocument().get("action"));
    }

}