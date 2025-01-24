package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

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

	public static RegistryInfo getLocal() {
		RegistryInfo ri = new RegistryInfo();
		ri.setupId = (Long) getValue("serverInfo", "setupId");
		ri.trustStateCounter = (Long) getValue("serverInfo", "trustStateCounter");
		ri.lastTrustStateUpdate = (String) getValue("serverInfo", "lastTrustStateUpdate");
		ri.trustStateHash = (String) getValue("serverInfo", "trustStateHash");
		ri.loadCounter = (Long) getMaxValue("nanopubs", "counter");
		ri.status = (String) getValue("serverInfo", "status");
		ri.coverageTypes = (String) getValue("serverInfo", "coverageTypes");
		ri.coverateAgents = (String) getValue("serverInfo", "coverageAgents");
		ri.currentSetting = (String) getValue("setting", "current");
		ri.originalSetting = (String) getValue("setting", "original");
		ri.agentCount = collection("agents").countDocuments(mongoSession);
		ri.accountCount = collection("accounts").countDocuments(mongoSession);
		ri.nanopubCount = collection("nanopubs").countDocuments(mongoSession);
		return ri;
	}

	public String asJson() {
		return gson.toJson(this);
	}

}
