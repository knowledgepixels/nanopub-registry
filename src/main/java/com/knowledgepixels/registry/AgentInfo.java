package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;
import org.bson.Document;

import java.io.Serializable;

@SuppressWarnings("unused")
public class AgentInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String agentId;
    private Integer accountCount;
    private Double avgPathCount;
    private Double totalRatio;

    private static final Gson gson = new Gson();

    public static AgentInfo get(ClientSession mongoSession, String agentId) {
        AgentInfo ri = new AgentInfo();
        ri.agentId = agentId;
        Document d = RegistryDB.getOne(mongoSession, Collection.AGENTS.toString(), new Document("agent", agentId));
        ri.accountCount = (Integer) d.get("accountCount");
        ri.avgPathCount = (Double) d.get("avgPathCount");
        ri.totalRatio = (Double) d.get("totalRatio");
        return ri;
    }

    public String asJson() {
        return gson.toJson(this);
    }

}
