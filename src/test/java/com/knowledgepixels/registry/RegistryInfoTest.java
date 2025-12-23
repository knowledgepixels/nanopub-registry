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
    public static final String COVERATE_AGENTS = "coverage_agent";
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
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "setupId")).thenReturn(SETUP_ID);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "trustStateCounter")).thenReturn(TRUST_STATE_COUNTER);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate")).thenReturn(LAST_TRUST_STATE_UPDATE);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "trustStateHash")).thenReturn(TRUST_STATE_HASH);
            registry.when(() -> RegistryDB.getMaxValue(mockSession, Collection.NANOPUBS.toString(), "counter")).thenReturn(LOAD_COUNTER);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "status")).thenReturn(STATUS);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "coverageTypes")).thenReturn(COVERAGE_TYPES);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "coverageAgents")).thenReturn(COVERATE_AGENTS);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "current")).thenReturn(CURRENT_SETTING);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "original")).thenReturn(ORIGINAL_SETTING);

            MongoCollection mockAgentsCollection = mock(MongoCollection.class);
            MongoCollection mockAccountsCollection = mock(MongoCollection.class);
            MongoCollection mockNanopubsCollection = mock(MongoCollection.class);

            registry.when(() -> RegistryDB.collection(Collection.AGENTS.toString())).thenReturn(mockAgentsCollection);
            registry.when(() -> RegistryDB.collection(Collection.ACCOUNTS.toString())).thenReturn(mockAccountsCollection);
            registry.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(mockNanopubsCollection);

            when(mockAgentsCollection.countDocuments(mockSession)).thenReturn(AGENT_COUNT);
            when(mockAccountsCollection.countDocuments(mockSession)).thenReturn(ACCOUNT_COUNT);
            when(mockNanopubsCollection.countDocuments(mockSession)).thenReturn(NANOPUB_COUNT);

            registry.when(() -> RegistryDB.isSet(mockSession, Collection.SERVER_INFO.toString(), "testInstance")).thenReturn(IS_TEST_INSTANCE);

            RegistryInfo registryInfo = RegistryInfo.getLocal(mockSession);
            String expectedJson = "{"
                                  + "\"setupId\":" + SETUP_ID + ","
                                  + "\"trustStateCounter\":" + TRUST_STATE_COUNTER + ","
                                  + "\"lastTrustStateUpdate\":\"" + LAST_TRUST_STATE_UPDATE + "\","
                                  + "\"trustStateHash\":\"" + TRUST_STATE_HASH + "\","
                                  + "\"status\":\"" + STATUS + "\","
                                  + "\"coverageTypes\":\"" + COVERAGE_TYPES + "\","
                                  + "\"coverateAgents\":\"" + COVERATE_AGENTS + "\","
                                  + "\"currentSetting\":\"" + CURRENT_SETTING + "\","
                                  + "\"originalSetting\":\"" + ORIGINAL_SETTING + "\","
                                  + "\"agentCount\":" + AGENT_COUNT + ","
                                  + "\"accountCount\":" + ACCOUNT_COUNT + ","
                                  + "\"nanopubCount\":" + NANOPUB_COUNT + ","
                                  + "\"loadCounter\":" + LOAD_COUNTER + ","
                                  + "\"isTestInstance\":" + IS_TEST_INSTANCE
                                  + "}";
            assertEquals(expectedJson, registryInfo.asJson());
        }
    }

    @Test
    void asJson() {
        ClientSession mockSession = mock(ClientSession.class);
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "setupId")).thenReturn(SETUP_ID);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "trustStateCounter")).thenReturn(TRUST_STATE_COUNTER);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate")).thenReturn(LAST_TRUST_STATE_UPDATE);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "trustStateHash")).thenReturn(TRUST_STATE_HASH);
            registry.when(() -> RegistryDB.getMaxValue(mockSession, Collection.NANOPUBS.toString(), "counter")).thenReturn(LOAD_COUNTER);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "status")).thenReturn(STATUS);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "coverageTypes")).thenReturn(COVERAGE_TYPES);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SERVER_INFO.toString(), "coverageAgents")).thenReturn(COVERATE_AGENTS);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "current")).thenReturn(CURRENT_SETTING);
            registry.when(() -> RegistryDB.getValue(mockSession, Collection.SETTING.toString(), "original")).thenReturn(ORIGINAL_SETTING);

            MongoCollection mockAgentsCollection = mock(MongoCollection.class);
            MongoCollection mockAccountsCollection = mock(MongoCollection.class);
            MongoCollection mockNanopubsCollection = mock(MongoCollection.class);

            registry.when(() -> RegistryDB.collection(Collection.AGENTS.toString())).thenReturn(mockAgentsCollection);
            registry.when(() -> RegistryDB.collection(Collection.ACCOUNTS.toString())).thenReturn(mockAccountsCollection);
            registry.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(mockNanopubsCollection);

            when(mockAgentsCollection.countDocuments(mockSession)).thenReturn(AGENT_COUNT);
            when(mockAccountsCollection.countDocuments(mockSession)).thenReturn(ACCOUNT_COUNT);
            when(mockNanopubsCollection.countDocuments(mockSession)).thenReturn(NANOPUB_COUNT);

            registry.when(() -> RegistryDB.isSet(mockSession, Collection.SERVER_INFO.toString(), "testInstance")).thenReturn(IS_TEST_INSTANCE);

            RegistryInfo registryInfo = RegistryInfo.getLocal(mockSession);
            String expectedJson = "{"
                                  + "\"setupId\":" + SETUP_ID + ","
                                  + "\"trustStateCounter\":" + TRUST_STATE_COUNTER + ","
                                  + "\"lastTrustStateUpdate\":\"" + LAST_TRUST_STATE_UPDATE + "\","
                                  + "\"trustStateHash\":\"" + TRUST_STATE_HASH + "\","
                                  + "\"status\":\"" + STATUS + "\","
                                  + "\"coverageTypes\":\"" + COVERAGE_TYPES + "\","
                                  + "\"coverateAgents\":\"" + COVERATE_AGENTS + "\","
                                  + "\"currentSetting\":\"" + CURRENT_SETTING + "\","
                                  + "\"originalSetting\":\"" + ORIGINAL_SETTING + "\","
                                  + "\"agentCount\":" + AGENT_COUNT + ","
                                  + "\"accountCount\":" + ACCOUNT_COUNT + ","
                                  + "\"nanopubCount\":" + NANOPUB_COUNT + ","
                                  + "\"loadCounter\":" + LOAD_COUNTER + ","
                                  + "\"isTestInstance\":" + IS_TEST_INSTANCE
                                  + "}";
            assertEquals(expectedJson, registryInfo.asJson());
        }
    }

}