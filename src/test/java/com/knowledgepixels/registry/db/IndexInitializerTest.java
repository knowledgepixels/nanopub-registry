package com.knowledgepixels.registry.db;

import com.knowledgepixels.registry.Collection;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class IndexInitializerTest {

    @Container
    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setupFakeEnv(mongoDBContainer);
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
    }

    @Test
    void initCollections() {
        assertEquals(2, getNumberOfIndexes(Collection.TASKS.toString()));
        assertEquals(4, getNumberOfIndexes(Collection.NANOPUBS.toString()));
        assertEquals(3, getNumberOfIndexes("lists"));
        assertEquals(6, getNumberOfIndexes("listEntries"));
        assertEquals(5, getNumberOfIndexes("invalidations"));
        assertEquals(8, getNumberOfIndexes("trustEdges"));
        assertEquals(3, getNumberOfIndexes("hashes"));
    }

    @Test
    void initLoadingCollections() {
        MongoClient client = RegistryDB.getClient();
        ClientSession session = client.startSession();
        IndexInitializer.initLoadingCollections(session);
        assertEquals(6, getNumberOfIndexes("endorsements_loading"));
        assertEquals(5, getNumberOfIndexes("agents_loading"));
        assertEquals(8, getNumberOfIndexes("accounts_loading"));
        assertEquals(4, getNumberOfIndexes("trustPaths_loading"));
    }

    /**
     * Helper method to get the number of indexes in a collection. Keep in mind that MongoDB creates a default index on the _id field.
     * So if you call createIndex once, the total number of indexes will be 2. And so on.
     *
     * @param collectionName the name of the collection
     * @return the number of indexes
     */
    private int getNumberOfIndexes(String collectionName) {
        MongoCollection<Document> collection = RegistryDB.collection(collectionName);
        return collection.listIndexes().into(new ArrayList<>()).size();
    }

}