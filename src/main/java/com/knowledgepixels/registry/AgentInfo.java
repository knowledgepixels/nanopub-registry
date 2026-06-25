package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

@SuppressWarnings("unused")
public class AgentInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(AgentInfo.class);

    private String agentId;
    private String name;
    private Integer accountCount;
    private Double avgPathCount;
    private Double totalRatio;

    private static final Gson gson = new Gson();

    public static AgentInfo get(ClientSession mongoSession, String agentId) {
        logger.debug("Looking up AgentInfo for agentId='{}'", agentId);

        AgentInfo agentInfo = new AgentInfo();
        agentInfo.agentId = agentId;
        Document d = RegistryDB.getOne(mongoSession, Collection.AGENTS.toString(), new Document("agent", agentId));
        if (d == null) {
            logger.warn("No agent found in {} for agentId='{}'; AgentInfo will be incomplete", Collection.AGENTS, agentId);
        }

        agentInfo.name = d.getString("name");
        agentInfo.accountCount = (Integer) d.get("accountCount");
        agentInfo.avgPathCount = (Double) d.get("avgPathCount");
        agentInfo.totalRatio = (Double) d.get("totalRatio");

        logger.debug("AgentInfo resolved for agentId='{}': name='{}', accountCount={}, avgPathCount={}, totalRatio={}", agentId, agentInfo.name, agentInfo.accountCount, agentInfo.avgPathCount, agentInfo.totalRatio);

        return agentInfo;
    }

    public String asJson() {
        String json = gson.toJson(this);
        logger.debug("Serialized AgentInfo for agentId='{}' to JSON ({} chars)", agentId, json.length());
        return json;
    }

}
