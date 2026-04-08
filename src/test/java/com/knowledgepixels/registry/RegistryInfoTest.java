package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RegistryInfoTest {

    private static final Long SETUP_ID = 1L;
    public static final Long TRUST_STATE_COUNTER = 0L;
    public static final String LAST_TRUST_STATE_UPDATE = "test_state";
    public static final String TRUST_STATE_HASH = "hash_value";
    public static final String STATUS = "test_running";
    public static final String COVERAGE_TYPES = "coverage_type";
    public static final String COVERAGE_AGENTS = "coverage_agent";
    public static final String CURRENT_SETTING = "current_setting";
    public static final String ORIGINAL_SETTING = "original_setting";
    public static final Long AGENT_COUNT = 0L;
    public static final Long ACCOUNT_COUNT = 0L;
    public static final Long NANOPUB_COUNT = 0L;
    public static final Long LOAD_COUNTER = 0L;
    public static final Boolean IS_TEST_INSTANCE = Boolean.FALSE;

    @Test
    void getLocal() {
        ClientSession mockSession = mock(ClientSession.class);
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            registry.when(() -> RegistryDB.getMaxValue(mockSession, Collection.NANOPUBS.toString(), "counter")).thenReturn(LOAD_COUNTER);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "current")).thenReturn(CURRENT_SETTING);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "original")).thenReturn(ORIGINAL_SETTING);

            MongoCollection mockServerInfoCollection = mock(MongoCollection.class);
            MongoCollection mockAgentsCollection = mock(MongoCollection.class);
            MongoCollection mockAccountsCollection = mock(MongoCollection.class);
            MongoCollection mockNanopubsCollection = mock(MongoCollection.class);

            registry.when(() -> RegistryDB.collection(Collection.SERVER_INFO.toString())).thenReturn(mockServerInfoCollection);
            registry.when(() -> RegistryDB.collection(Collection.AGENTS.toString())).thenReturn(mockAgentsCollection);
            registry.when(() -> RegistryDB.collection(Collection.ACCOUNTS.toString())).thenReturn(mockAccountsCollection);
            registry.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(mockNanopubsCollection);

            java.util.List<org.bson.Document> serverInfoDocs = java.util.List.of(
                    new org.bson.Document("_id", "setupId").append("value", SETUP_ID),
                    new org.bson.Document("_id", "trustStateCounter").append("value", TRUST_STATE_COUNTER),
                    new org.bson.Document("_id", "lastTrustStateUpdate").append("value", LAST_TRUST_STATE_UPDATE),
                    new org.bson.Document("_id", "trustStateHash").append("value", TRUST_STATE_HASH),
                    new org.bson.Document("_id", "status").append("value", STATUS),
                    new org.bson.Document("_id", "coverageTypes").append("value", COVERAGE_TYPES),
                    new org.bson.Document("_id", "coverageAgents").append("value", COVERAGE_AGENTS),
                    new org.bson.Document("_id", "testInstance").append("value", IS_TEST_INSTANCE)
            );
            var mockFindIterable = mock(com.mongodb.client.FindIterable.class);
            when(mockServerInfoCollection.find(mockSession)).thenReturn(mockFindIterable);
            when(mockFindIterable.iterator()).thenAnswer(invocation -> {
                java.util.Iterator<org.bson.Document> it = serverInfoDocs.iterator();
                com.mongodb.client.MongoCursor<org.bson.Document> cursor = mock(com.mongodb.client.MongoCursor.class);
                when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
                when(cursor.next()).thenAnswer(inv -> it.next());
                return cursor;
            });
            when(mockFindIterable.spliterator()).thenAnswer(invocation -> serverInfoDocs.spliterator());

            when(mockAgentsCollection.countDocuments(mockSession)).thenReturn(AGENT_COUNT);
            when(mockAccountsCollection.countDocuments(mockSession)).thenReturn(ACCOUNT_COUNT);
            when(mockNanopubsCollection.countDocuments(mockSession)).thenReturn(NANOPUB_COUNT);

            RegistryInfo registryInfo = RegistryInfo.getLocal(mockSession);
            String expectedJson = "{"
                                  + "\"setupId\":" + SETUP_ID + ","
                                  + "\"trustStateCounter\":" + TRUST_STATE_COUNTER + ","
                                  + "\"lastTrustStateUpdate\":\"" + LAST_TRUST_STATE_UPDATE + "\","
                                  + "\"trustStateHash\":\"" + TRUST_STATE_HASH + "\","
                                  + "\"status\":\"" + STATUS + "\","
                                  + "\"coverageTypes\":\"" + COVERAGE_TYPES + "\","
                                  + "\"coverageAgents\":\"" + COVERAGE_AGENTS + "\","
                                  + "\"currentSetting\":\"" + CURRENT_SETTING + "\","
                                  + "\"originalSetting\":\"" + ORIGINAL_SETTING + "\","
                                  + "\"agentCount\":" + AGENT_COUNT + ","
                                  + "\"accountCount\":" + ACCOUNT_COUNT + ","
                                  + "\"nanopubCount\":" + NANOPUB_COUNT + ","
                                  + "\"loadCounter\":" + LOAD_COUNTER + ","
                                  + "\"isTestInstance\":" + IS_TEST_INSTANCE + ","
                                  + "\"optionalLoadEnabled\":true,"
                                  + "\"trustCalculationEnabled\":true"
                                  + "}";
            assertEquals(expectedJson, registryInfo.asJson());
        }
    }

    @Test
    void asJson() {
        ClientSession mockSession = mock(ClientSession.class);
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            registry.when(() -> RegistryDB.getMaxValue(mockSession, Collection.NANOPUBS.toString(), "counter")).thenReturn(LOAD_COUNTER);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "current")).thenReturn(CURRENT_SETTING);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "original")).thenReturn(ORIGINAL_SETTING);

            MongoCollection mockServerInfoCollection = mock(MongoCollection.class);
            MongoCollection mockAgentsCollection = mock(MongoCollection.class);
            MongoCollection mockAccountsCollection = mock(MongoCollection.class);
            MongoCollection mockNanopubsCollection = mock(MongoCollection.class);

            registry.when(() -> RegistryDB.collection(Collection.SERVER_INFO.toString())).thenReturn(mockServerInfoCollection);
            registry.when(() -> RegistryDB.collection(Collection.AGENTS.toString())).thenReturn(mockAgentsCollection);
            registry.when(() -> RegistryDB.collection(Collection.ACCOUNTS.toString())).thenReturn(mockAccountsCollection);
            registry.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(mockNanopubsCollection);

            java.util.List<org.bson.Document> serverInfoDocs = java.util.List.of(
                    new org.bson.Document("_id", "setupId").append("value", SETUP_ID),
                    new org.bson.Document("_id", "trustStateCounter").append("value", TRUST_STATE_COUNTER),
                    new org.bson.Document("_id", "lastTrustStateUpdate").append("value", LAST_TRUST_STATE_UPDATE),
                    new org.bson.Document("_id", "trustStateHash").append("value", TRUST_STATE_HASH),
                    new org.bson.Document("_id", "status").append("value", STATUS),
                    new org.bson.Document("_id", "coverageTypes").append("value", COVERAGE_TYPES),
                    new org.bson.Document("_id", "coverageAgents").append("value", COVERAGE_AGENTS),
                    new org.bson.Document("_id", "testInstance").append("value", IS_TEST_INSTANCE)
            );
            var mockFindIterable = mock(com.mongodb.client.FindIterable.class);
            when(mockServerInfoCollection.find(mockSession)).thenReturn(mockFindIterable);
            when(mockFindIterable.iterator()).thenAnswer(invocation -> {
                java.util.Iterator<org.bson.Document> it = serverInfoDocs.iterator();
                com.mongodb.client.MongoCursor<org.bson.Document> cursor = mock(com.mongodb.client.MongoCursor.class);
                when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
                when(cursor.next()).thenAnswer(inv -> it.next());
                return cursor;
            });
            when(mockFindIterable.spliterator()).thenAnswer(invocation -> serverInfoDocs.spliterator());

            when(mockAgentsCollection.countDocuments(mockSession)).thenReturn(AGENT_COUNT);
            when(mockAccountsCollection.countDocuments(mockSession)).thenReturn(ACCOUNT_COUNT);
            when(mockNanopubsCollection.countDocuments(mockSession)).thenReturn(NANOPUB_COUNT);

            RegistryInfo registryInfo = RegistryInfo.getLocal(mockSession);
            String expectedJson = "{"
                                  + "\"setupId\":" + SETUP_ID + ","
                                  + "\"trustStateCounter\":" + TRUST_STATE_COUNTER + ","
                                  + "\"lastTrustStateUpdate\":\"" + LAST_TRUST_STATE_UPDATE + "\","
                                  + "\"trustStateHash\":\"" + TRUST_STATE_HASH + "\","
                                  + "\"status\":\"" + STATUS + "\","
                                  + "\"coverageTypes\":\"" + COVERAGE_TYPES + "\","
                                  + "\"coverageAgents\":\"" + COVERAGE_AGENTS + "\","
                                  + "\"currentSetting\":\"" + CURRENT_SETTING + "\","
                                  + "\"originalSetting\":\"" + ORIGINAL_SETTING + "\","
                                  + "\"agentCount\":" + AGENT_COUNT + ","
                                  + "\"accountCount\":" + ACCOUNT_COUNT + ","
                                  + "\"nanopubCount\":" + NANOPUB_COUNT + ","
                                  + "\"loadCounter\":" + LOAD_COUNTER + ","
                                  + "\"isTestInstance\":" + IS_TEST_INSTANCE + ","
                                  + "\"optionalLoadEnabled\":true,"
                                  + "\"trustCalculationEnabled\":true"
                                  + "}";
            assertEquals(expectedJson, registryInfo.asJson());
        }
    }

}