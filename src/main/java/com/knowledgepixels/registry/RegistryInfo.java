package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;

import java.io.Serializable;

import com.google.gson.Gson;

@SuppressWarnings("unused")
public class RegistryInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	private Long setupId;
	private Long stateCounter;
	private String status;
	private String coverageTypes;
	private String coverateAgents;
	private String currentSetting;
	private String originalSetting;
	private Long agentCount;
	private Long accountCount;
	private Long nanopubCount;
	private Long nanopubCounter;

	private static Gson gson = new Gson();

	public static RegistryInfo getLocal() {
		RegistryInfo ri = new RegistryInfo();
		ri.setupId = (Long) getValue("server-info", "setup-id");
		ri.stateCounter = (Long) getValue("server-info", "state-counter");
		ri.status = (String) getValue("server-info", "status");
		ri.coverageTypes = (String) getValue("server-info", "coverage-types");
		ri.coverateAgents = (String) getValue("server-info", "coverage-agents");
		ri.currentSetting = (String) getValue("setting", "current");
		ri.originalSetting = (String) getValue("setting", "original");
		ri.agentCount = collection("agents").countDocuments(mongoSession);
		ri.accountCount = collection("accounts").countDocuments(mongoSession);
		ri.nanopubCount = collection("nanopubs").countDocuments(mongoSession);
		ri.nanopubCounter = (Long) getMaxValue("nanopubs", "counter");
		return ri;
	}

	public String asJson() {
		return gson.toJson(this);
	}

}
