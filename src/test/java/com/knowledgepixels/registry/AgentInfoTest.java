package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class AgentInfoTest {

    private static final String AGENT_ID = "agent123";
    private static final int ACCOUNT_COUNT = 5;
    private static final double AVG_PATH_COUNT = 3.2;
    private static final double TOTAL_RATIO = 1.5;

    @Test
    void get() {
        ClientSession mockSession = mock(ClientSession.class);
        Document mockDocument = new Document("accountCount", ACCOUNT_COUNT)
                .append("avgPathCount", AVG_PATH_COUNT)
                .append("totalRatio", TOTAL_RATIO);
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            registry.when(() -> RegistryDB.getOne(mockSession, "agents", new Document("agent", AGENT_ID)))
                    .thenReturn(mockDocument);

            AgentInfo agentInfo = AgentInfo.get(mockSession, AGENT_ID);
            String expectedJson = "{\"agentId\":\"" + AGENT_ID + "\",\"accountCount\":" + ACCOUNT_COUNT + ",\"avgPathCount\":" + AVG_PATH_COUNT + ",\"totalRatio\":" + TOTAL_RATIO + "}";
            assertEquals(expectedJson, agentInfo.asJson());
        }
    }

    @Test
    void asJson() {
        ClientSession mockSession = mock(ClientSession.class);
        Document mockDocument = new Document("accountCount", ACCOUNT_COUNT)
                .append("avgPathCount", AVG_PATH_COUNT)
                .append("totalRatio", TOTAL_RATIO);
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            registry.when(() -> RegistryDB.getOne(mockSession, "agents", new Document("agent", AGENT_ID)))
                    .thenReturn(mockDocument);

            AgentInfo agentInfo = AgentInfo.get(mockSession, AGENT_ID);
            String expectedJson = "{\"agentId\":\"" + AGENT_ID + "\",\"accountCount\":" + ACCOUNT_COUNT + ",\"avgPathCount\":" + AVG_PATH_COUNT + ",\"totalRatio\":" + TOTAL_RATIO + "}";
            assertEquals(expectedJson, agentInfo.asJson());
        }
    }

}
