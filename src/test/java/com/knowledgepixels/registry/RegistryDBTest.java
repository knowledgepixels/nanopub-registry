package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RegistryDBTest {

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setupFakeEnv(mongoDBContainer);
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
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
    void renameWhenOriginalNotExists() {
        RegistryDB.init();

        String originalCollectionName = "testCollection";
        String newCollectionName = "renamedCollection";

        assertFalse(RegistryDB.hasCollection(originalCollectionName));
        assertFalse(RegistryDB.hasCollection(newCollectionName));

        RegistryDB.rename(originalCollectionName, newCollectionName);
        assertFalse(RegistryDB.hasCollection(originalCollectionName));
        assertFalse(RegistryDB.hasCollection(newCollectionName));
    }

    @Test
    void renameWhenNewNotExists() {
        RegistryDB.init();

        ClientSession session = RegistryDB.getClient().startSession();
        String originalCollectionName = "testCollection";
        String newCollectionName = "renamedCollection";

        RegistryDB.insert(session, originalCollectionName, new Document("_id", "testKey"));
        assertTrue(RegistryDB.hasCollection(originalCollectionName));

        RegistryDB.rename(originalCollectionName, newCollectionName);
        assertFalse(RegistryDB.hasCollection(originalCollectionName));
        assertTrue(RegistryDB.hasCollection(newCollectionName));
    }

    @Test
    void renameWhenNewAlreadyExists() {
        RegistryDB.init();

        ClientSession session = RegistryDB.getClient().startSession();
        String originalCollectionName = "testCollection";
        String newCollectionName = "renamedCollection";

        RegistryDB.insert(session, originalCollectionName, new Document("_id", "testKey"));
        RegistryDB.insert(session, newCollectionName, new Document("_id", "testKey"));
        assertTrue(RegistryDB.hasCollection(originalCollectionName));
        assertTrue(RegistryDB.hasCollection(newCollectionName));

        RegistryDB.rename(originalCollectionName, newCollectionName);
        assertFalse(RegistryDB.hasCollection(originalCollectionName));
        assertTrue(RegistryDB.hasCollection(newCollectionName));
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

    @Test
    void increaseStateCounter() {
        RegistryDB.init();
        MongoClient client = RegistryDB.getClient();
        ClientSession session = client.startSession();
        long counterValue = 0L;

        assertNull(RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter"));

        RegistryDB.increaseStateCounter(session);
        Object retrievedCounterValue = RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter");
        assertEquals(counterValue, retrievedCounterValue);

        counterValue++;
        RegistryDB.increaseStateCounter(session);

        retrievedCounterValue = RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter");
        assertEquals(counterValue, retrievedCounterValue);
    }

    @Test
    void hasWithElementName() {
        RegistryDB.init();
        MongoClient client = RegistryDB.getClient();
        ClientSession session = client.startSession();
        String collectionName = "testCollection";

        assertFalse(RegistryDB.has(session, collectionName, "testKey"));

        RegistryDB.insert(session, collectionName, new Document("_id", "testKey"));
        assertTrue(RegistryDB.has(session, collectionName, "testKey"));
    }

    @Test
    void hasWithDocument() {
        RegistryDB.init();
        MongoClient client = RegistryDB.getClient();
        ClientSession session = client.startSession();
        String collectionName = "testCollection";

        Document doc = new Document("_id", "testKey");
        assertFalse(RegistryDB.has(session, collectionName, doc));

        RegistryDB.insert(session, collectionName, doc);
        assertTrue(RegistryDB.has(session, collectionName, doc));
    }

    @Test
    void hasCollection() {
        RegistryDB.init();
        assertTrue(RegistryDB.hasCollection(Collection.NANOPUBS.toString()));
        assertFalse(RegistryDB.hasCollection("nonExistingCollection"));
    }

    @Test
    void get() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        Document doc = new Document("_id", "testKey");
        MongoCursor<Document> cursor = RegistryDB.get(session, collectionName, doc);
        assertFalse(cursor.hasNext());

        RegistryDB.insert(session, collectionName, doc);
        cursor = RegistryDB.get(session, collectionName, doc);
        assertTrue(cursor.hasNext());
        assertEquals(doc, cursor.next());
    }

    @Test
    void getValue() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        MongoCollection<Document> collection = RegistryDB.collection(collectionName);
        Object value = RegistryDB.getValue(session, collectionName, "testKey");
        assertNull(value);

        collection.insertOne(new Document("_id", "testKey").append("value", "testValue"));
        value = RegistryDB.getValue(session, collectionName, "testKey");
        assertEquals("testValue", value);
    }

    @Test
    void setValue() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        MongoCollection<Document> collection = RegistryDB.collection(collectionName);
        collection.insertOne(new Document("_id", "testKey").append("value", "testValue"));
        Object value = RegistryDB.getValue(session, collectionName, "testKey");
        assertEquals("testValue", value);

        RegistryDB.setValue(session, collectionName, "testKey", "newValue");
        value = RegistryDB.getValue(session, collectionName, "testKey");
        assertEquals("newValue", value);
    }

    @Test
    void insert() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        Object value = RegistryDB.getValue(session, collectionName, "testKey");
        assertNull(value);

        Document doc = new Document("_id", "testKey").append("value", "testValue");
        RegistryDB.insert(session, collectionName, doc);
        assertTrue(RegistryDB.hasCollection(collectionName));
        value = RegistryDB.getValue(session, collectionName, "testKey");
        assertEquals("testValue", value);
    }

    @Test
    void set() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        Document doc = new Document("_id", "testKey").append("value", "testValue");
        RegistryDB.insert(session, collectionName, doc);
        assertEquals("testValue", RegistryDB.getValue(session, collectionName, "testKey"));

        RegistryDB.set(session, collectionName, new Document("_id", "testKey").append("value", "newValue"));
        assertEquals("newValue", RegistryDB.getValue(session, collectionName, "testKey"));

        RegistryDB.set(session, collectionName, new Document("_id", "anotherKey").append("value", "newValue"));
        // this shouldn't update anything as 'anotherKey' didn't exist before
        assertNull(RegistryDB.getValue(session, collectionName, "anotherKey"));
    }

    @Test
    void isSet() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        assertFalse(RegistryDB.isSet(session, collectionName, "testKey"));

        Document doc = new Document("_id", "testKey").append("value", true);
        RegistryDB.insert(session, collectionName, doc);
        assertTrue(RegistryDB.isSet(session, collectionName, "testKey"));
    }

    @Test
    void getOne() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        Document doc1 = new Document("value", "testValue").append("otherField", 10);
        Document doc2 = new Document("value", "testValue").append("otherField", 20);
        Document doc3 = new Document("val", "moreDifferentValue");

        RegistryDB.insert(session, collectionName, doc1);
        RegistryDB.insert(session, collectionName, doc2);
        RegistryDB.insert(session, collectionName, doc3);

        Document retrieved = RegistryDB.getOne(session, collectionName, new Document().append("value", "anotherValue"));
        assertNull(retrieved);

        retrieved = RegistryDB.getOne(session, collectionName, new Document().append("value", "testValue"));
        assertTrue(retrieved.equals(doc1) || retrieved.equals(doc2));

        retrieved = RegistryDB.getOne(session, collectionName, new Document().append("val", "moreDifferentValue"));
        assertEquals(doc3, retrieved);
    }

    @Test
    void isInitialized() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        assertFalse(RegistryDB.isInitialized(session));

        // TODO implement the rest of the test when initialization flag is set in the DB - after tasks are executed
    }

    @Test
    void getMaxValue() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        Document doc1 = new Document("value", 10);
        Document doc2 = new Document("value", 20);

        RegistryDB.insert(session, collectionName, doc1);
        RegistryDB.insert(session, collectionName, doc2);

        Object retrieved = RegistryDB.getMaxValue(session, collectionName, "value");
        assertEquals(20, retrieved);

        retrieved = RegistryDB.getMaxValue(session, collectionName, "nonExistingField");
        assertNull(retrieved);
    }

    @Test
    void getMaxValueDocument() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();
        String collectionName = "testCollection";

        Document doc1 = new Document("value", 10).append("position", 1);
        Document doc2 = new Document("value", 10).append("position", 2);
        Document doc3 = new Document("value", 10).append("position", 5);

        RegistryDB.insert(session, collectionName, doc1);
        RegistryDB.insert(session, collectionName, doc2);
        RegistryDB.insert(session, collectionName, doc3);

        Document retrieved = RegistryDB.getMaxValueDocument(session, collectionName, new Document().append("value", 10), "position");
        assertEquals(doc3, retrieved);
    }

    @Test
    void recordHash() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        String valueToBeHashed = "testValue";
        String expectedHash = Utils.getHash(valueToBeHashed);

        RegistryDB.recordHash(session, valueToBeHashed);
        assertEquals(expectedHash, RegistryDB.collection("hashes").find(new Document().append("value", valueToBeHashed)).first().getString("hash"));
    }

    @Test
    void unhash() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        String valueToBeHashed = "testValue";
        String valueHashed = Utils.getHash(valueToBeHashed);

        RegistryDB.insert(session, "hashes", new Document("value", valueToBeHashed).append("hash", valueHashed));
        String retrievedValue = RegistryDB.unhash(valueHashed);
        assertEquals(valueToBeHashed, retrievedValue);

        String nonExistingHash = Utils.getHash("anotherValue");
        retrievedValue = RegistryDB.unhash(nonExistingHash);
        assertNull(retrievedValue);
    }

}