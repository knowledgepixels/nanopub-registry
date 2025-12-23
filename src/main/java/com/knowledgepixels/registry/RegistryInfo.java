package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;

import java.io.Serializable;

import static com.knowledgepixels.registry.RegistryDB.*;

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
    private Boolean isTestInstance;

    private static final Gson gson = new Gson();

    public static RegistryInfo getLocal(ClientSession mongoSession) {
        RegistryInfo ri = new RegistryInfo();
        ri.setupId = (Long) getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId");
        ri.trustStateCounter = (Long) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateCounter");
        ri.lastTrustStateUpdate = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate");
        ri.trustStateHash = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateHash");
        ri.loadCounter = (Long) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "counter");
        ri.status = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "status");
        ri.coverageTypes = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageTypes");
        ri.coverateAgents = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageAgents");
        ri.currentSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "current");
        ri.originalSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "original");
        ri.agentCount = collection(Collection.AGENTS.toString()).countDocuments(mongoSession);
        ri.accountCount = collection(Collection.ACCOUNTS.toString()).countDocuments(mongoSession);
        ri.nanopubCount = collection(Collection.NANOPUBS.toString()).countDocuments(mongoSession);
        ri.isTestInstance = isSet(mongoSession, Collection.SERVER_INFO.toString(), "testInstance");
        return ri;
    }

    public String asJson() {
        return gson.toJson(this);
    }

}
