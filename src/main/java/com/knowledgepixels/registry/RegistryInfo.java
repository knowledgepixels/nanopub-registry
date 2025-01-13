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

	public static RegistryInfo getLocal() {
		RegistryInfo ri = new RegistryInfo();
		ri.setupId = (Long) getValue("server-info", "setup-id");
		ri.stateCounter = (Long) getValue("server-info", "state-counter");
		ri.status = (String) getValue("server-info", "status");
		return ri;
	}

	public String asJson() {
		return new Gson().toJson(this);
	}

}
