package com.knowledgepixels.registry;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RegistryDBTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeAll
    static void beforeAll() {
        Map<String, String> fakeEnv = new HashMap<>();
        fakeEnv.put("REGISTRY_DB_NAME", "nanopubRegistry");
        fakeEnv.put("REGISTRY_DB_HOST", mongoDBContainer.getHost());
        fakeEnv.put("REGISTRY_DB_PORT", String.valueOf(mongoDBContainer.getFirstMappedPort()));
        ReadsEnvironment reader = new ReadsEnvironment(fakeEnv::get);
        Utils.setEnvReader(reader);
    }

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        Field field = RegistryDB.class.getDeclaredField("mongoClient");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void getDBWhenInitialized() {
        RegistryDB.init();
        MongoDatabase db = RegistryDB.getDB();
        RegistryDB.init();
        assertSame(db, RegistryDB.getDB());
    }

    @Test
    void getDBWhenNotInitialized() {
        assertNull(RegistryDB.getDB());
    }

    @Test
    void getClientWhenNotInitialized() {
        assertNull(RegistryDB.getClient());
    }

    @Test
    void getClientWhenInitialized() {
        RegistryDB.init();
        MongoClient client = RegistryDB.getClient();
        assertNotNull(client);
    }

    @Test
    void init() {
        RegistryDB.init();
        assertNotNull(RegistryDB.getClient());
    }

    @Test
    void initWhenAlreadyInitialized() {
        RegistryDB.init();
        MongoClient firstClient = RegistryDB.getClient();
        RegistryDB.init();
        assertSame(firstClient, RegistryDB.getClient());
    }

}