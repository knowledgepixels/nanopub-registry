package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import net.trustyuri.TrustyUriUtils;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class RegistryDBTest {

    private FakeEnv fakeEnv;

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
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
        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);
        assertNotNull(pubkey);
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyh/JXpQoR8t9THhrBraTIvPVnlky+1p/Cr1J3VpUtaslV/6j9qgHhGc92g1BZ93DnUmiB+peSAwmva/OWZXsKxuYOTeIGFqwtBv9V91WSoXGRK4SJGVbj6kVK15CPH2qjls29ZWzTwskyIm9u7Gpscm28TR81v+qCzDMTIWB2zQzn6DDcyFJ3zaCrwAc3DhbLtbteZaC56gHfKTPu/ko+gXbzXVvgOkgvUwa3HB7EBdDaxDiM9LpYidV72AUhIgIpFCkrZMWklSTDCKK9Gp6VnDe1Lzr7JZyFR1liA0C6DntX4ZtZOzL7XMTZIM+yseJ6MrdIwiaunBV1Nr3C08SFwIDAQAB", pubkey);
    }

    @Test
    void getPubkeyWithInvalidNanopub() throws MalformedNanopubException, IOException {
        File file = NanopubTestSuite.getLatest().getByArtifactCode("RAeUPiCKlke8Pw9wYbqIESyBqFJM5UDSkx4uF9kkRfCh0").getFirst().toFile();
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
    void loadNanopubRejectsFutureTimestamp() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        Nanopub nanopub = mock(Nanopub.class);
        when(nanopub.getTripleCount()).thenReturn(10);
        when(nanopub.getByteCount()).thenReturn(100L);
        IRI uri = SimpleValueFactory.getInstance().createIRI("http://example.org/test");
        when(nanopub.getUri()).thenReturn(uri);

        Calendar futureTime = Calendar.getInstance();
        futureTime.add(Calendar.HOUR, 1);
        when(nanopub.getCreationTime()).thenReturn(futureTime);

        assertFalse(RegistryDB.loadNanopub(session, nanopub));
    }

    @Test
    void loadNanopubAcceptsNullCreationTime() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        Nanopub nanopub = mock(Nanopub.class);
        when(nanopub.getTripleCount()).thenReturn(10);
        when(nanopub.getByteCount()).thenReturn(100L);
        IRI uri = SimpleValueFactory.getInstance().createIRI("http://example.org/test");
        when(nanopub.getUri()).thenReturn(uri);
        when(nanopub.getCreationTime()).thenReturn(null);
        when(nanopub.getGraphUris()).thenReturn(Set.of());

        // Will pass the timestamp check but fail on signature validation (no pubkey)
        assertFalse(RegistryDB.loadNanopub(session, nanopub));
    }

    @Test
    void loadNanopubRejectsMismatchedGraphUris() {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        Nanopub nanopub = mock(Nanopub.class);
        when(nanopub.getTripleCount()).thenReturn(10);
        when(nanopub.getByteCount()).thenReturn(100L);
        when(nanopub.getCreationTime()).thenReturn(null);
        IRI nanopubUri = SimpleValueFactory.getInstance().createIRI("https://w3id.org/np/RA3QeEArKrJhMi5hGQJwjizvDEPKnaM2wME9iuKItk_nE");
        IRI mismatchedGraphUri = SimpleValueFactory.getInstance().createIRI("https://w3id.org/np/RA54f2f2ef2408bf88c12fbb8fd62844263ab83ef5c22/Head");
        when(nanopub.getUri()).thenReturn(nanopubUri);
        when(nanopub.getGraphUris()).thenReturn(Set.of(mismatchedGraphUri));

        assertFalse(RegistryDB.loadNanopub(session, nanopub));
    }

    @Test
    void loadNanopubAcceptsPastTimestamp() throws MalformedNanopubException, IOException {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        // simple1.trig has dc:created "2014-07-24T18:05:11+01:00" — well in the past
        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);

        assertNotNull(nanopub.getCreationTime());
        assertTrue(RegistryDB.loadNanopub(session, nanopub));
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

    @Test
    void loadNanopubVerifiedStoresNanopub() throws MalformedNanopubException, IOException {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);
        assertNotNull(pubkey);

        // Load via the verified path (skips signature re-verification)
        assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));

        // Verify nanopub is stored in the database
        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        assertTrue(RegistryDB.has(session, Collection.NANOPUBS.toString(), ac));

        // Verify counter was assigned
        Document doc = RegistryDB.collection(Collection.NANOPUBS.toString()).find(session, new Document("_id", ac)).first();
        assertNotNull(doc);
        assertTrue(doc.getLong("counter") > 0);
        assertEquals(Utils.getHash(pubkey), doc.getString("pubkey"));
    }

    @Test
    void loadNanopubVerifiedMatchesLoadNanopub() throws MalformedNanopubException, IOException {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);

        // Load via verified path
        assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));

        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        Document doc = RegistryDB.collection(Collection.NANOPUBS.toString()).find(session, new Document("_id", ac)).first();
        assertNotNull(doc);

        // Verify the stored fields match what loadNanopub would produce
        assertEquals(nanopub.getUri().stringValue(), doc.getString("fullId"));
        assertEquals(Utils.getHash(pubkey), doc.getString("pubkey"));
        assertNotNull(doc.getString("content"));
        assertNotNull(doc.get("jelly"));
    }

    @Test
    void loadNanopubVerifiedSkipsDuplicates() throws MalformedNanopubException, IOException {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);

        // Load twice — second call should succeed without error
        assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));
        assertTrue(RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null));

        // Only one document in the collection
        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        assertEquals(1, RegistryDB.collection(Collection.NANOPUBS.toString())
                .countDocuments(session, new Document("_id", ac)));
    }

    @Test
    void simpleLoadWithVerifiedPubkeyCreatesListEntries() throws MalformedNanopubException, IOException {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);
        String pubkeyHash = Utils.getHash(pubkey);

        // Store the nanopub first
        RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null);

        // simpleLoad should create an encountered list entry for unknown pubkeys
        NanopubLoader.simpleLoad(session, nanopub, pubkey);

        // Verify that a list was created for this pubkey (encountered status for unknown pubkey)
        assertTrue(RegistryDB.has(session, "lists",
                new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)));
    }

    @Test
    void simpleLoadWithVerifiedPubkeyMatchesSimpleLoad() throws MalformedNanopubException, IOException {
        RegistryDB.init();
        ClientSession session = RegistryDB.getClient().startSession();

        File file = NanopubTestSuite.getLatest().getByArtifactCode("RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(file);
        String pubkey = RegistryDB.getPubkey(nanopub);
        String pubkeyHash = Utils.getHash(pubkey);

        // Store via verified path then simpleLoad with pubkey
        RegistryDB.loadNanopubVerified(session, nanopub, pubkey, null);
        NanopubLoader.simpleLoad(session, nanopub, pubkey);

        // Capture the state created by the verified path
        Document listDoc = RegistryDB.collection("lists").find(session,
                new Document("pubkey", pubkeyHash).append("type", NanopubLoader.INTRO_TYPE_HASH)).first();
        assertNotNull(listDoc);
        assertEquals(EntryStatus.encountered.getValue(), listDoc.getString("status"));
    }

}