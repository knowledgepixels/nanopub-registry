package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;

import java.io.Serializable;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;

@SuppressWarnings("unused")
public class RegistryInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	private Long setupId;
	private Long trustStateCounter;
	private String lastTrustStateUpdate;
	private String trustStateHash;
	private String status;
	private String coverageTypes;
	private String coverateAgents;
	private String currentSetting;
	private String originalSetting;
	private Long agentCount;
	private Long accountCount;
	private Long nanopubCount;
	private Long loadCounter;

	private static Gson gson = new Gson();

	public static RegistryInfo getLocal(ClientSession mongoSession) {
		RegistryInfo ri = new RegistryInfo();
		ri.setupId = (Long) getValue(mongoSession, "serverInfo", "setupId");
		ri.trustStateCounter = (Long) getValue(mongoSession, "serverInfo", "trustStateCounter");
		ri.lastTrustStateUpdate = (String) getValue(mongoSession, "serverInfo", "lastTrustStateUpdate");
		ri.trustStateHash = (String) getValue(mongoSession, "serverInfo", "trustStateHash");
		ri.loadCounter = (Long) getMaxValue(mongoSession, "nanopubs", "counter");
		ri.status = (String) getValue(mongoSession, "serverInfo", "status");
		ri.coverageTypes = (String) getValue(mongoSession, "serverInfo", "coverageTypes");
		ri.coverateAgents = (String) getValue(mongoSession, "serverInfo", "coverageAgents");
		ri.currentSetting = (String) getValue(mongoSession, "setting", "current");
		ri.originalSetting = (String) getValue(mongoSession, "setting", "original");
		ri.agentCount = collection("agents").countDocuments(mongoSession);
		ri.accountCount = collection("accounts").countDocuments(mongoSession);
		ri.nanopubCount = collection("nanopubs").countDocuments(mongoSession);
		return ri;
	}

	public String asJson() {
		return gson.toJson(this);
	}

}
