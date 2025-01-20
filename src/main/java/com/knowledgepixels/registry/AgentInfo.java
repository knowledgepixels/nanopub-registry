package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;

import com.google.gson.Gson;

@SuppressWarnings("unused")
public class AgentInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	private String agentId;
	private Integer accountCount;
	private Double avgPathCount;
	private Double totalRatio;

	private static Gson gson = new Gson();

	public static AgentInfo get(String agentId) {
		AgentInfo ri = new AgentInfo();
		ri.agentId = agentId;
		Document d = RegistryDB.getOne("agents", new Document("agent", agentId));
		ri.accountCount = (Integer) d.get("accountCount");
		ri.avgPathCount = (Double) d.get("avgPathCount");
		ri.totalRatio = (Double) d.get("totalRatio");
		return ri;
	}

	public String asJson() {
		return gson.toJson(this);
	}

}
