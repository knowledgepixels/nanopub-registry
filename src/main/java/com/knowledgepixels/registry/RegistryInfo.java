package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.getValue;

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
		return ri;
	}

	public String asJson() {
		return gson.toJson(this);
	}

}
