package com.knowledgepixels.registry;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.io.File;
import java.io.IOException;
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

    @Test
    void getPubkey() throws MalformedNanopubException, IOException {
        File file = new File(this.getClass().getClassLoader().getResource("testsuite/valid/signed/simple1.trig").getFile());
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);
        assertNotNull(pubkey);
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyh/JXpQoR8t9THhrBraTIvPVnlky+1p/Cr1J3VpUtaslV/6j9qgHhGc92g1BZ93DnUmiB+peSAwmva/OWZXsKxuYOTeIGFqwtBv9V91WSoXGRK4SJGVbj6kVK15CPH2qjls29ZWzTwskyIm9u7Gpscm28TR81v+qCzDMTIWB2zQzn6DDcyFJ3zaCrwAc3DhbLtbteZaC56gHfKTPu/ko+gXbzXVvgOkgvUwa3HB7EBdDaxDiM9LpYidV72AUhIgIpFCkrZMWklSTDCKK9Gp6VnDe1Lzr7JZyFR1liA0C6DntX4ZtZOzL7XMTZIM+yseJ6MrdIwiaunBV1Nr3C08SFwIDAQAB", pubkey);
    }

    @Test
    void getPubkeyWithInvalidNanopub() throws MalformedNanopubException, IOException {
        File file = new File(this.getClass().getClassLoader().getResource("testsuite/invalid/signed/simple1-invalid-rsa.trig").getFile());
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);
        assertNull(pubkey);
    }

}