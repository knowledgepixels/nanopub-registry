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
    private String coverageAgents;
    private String currentSetting;
    private String originalSetting;
    private Long agentCount;
    private Long accountCount;
    private Long nanopubCount;
    private Long seqNum;
    // TODO(transition): Remove loadCounter after all peers upgraded
    private Long loadCounter;
    private Boolean isTestInstance;

    private static final Gson gson = new Gson();

    public static RegistryInfo getLocal(ClientSession mongoSession) {
        RegistryInfo ri = new RegistryInfo();
        ri.setupId = (Long) getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId");
        ri.trustStateCounter = (Long) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateCounter");
        ri.lastTrustStateUpdate = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate");
        ri.trustStateHash = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "trustStateHash");
        ri.seqNum = (Long) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "seqNum");
        // TODO(transition): Remove loadCounter after all peers upgraded
        ri.loadCounter = ri.seqNum;
        ri.status = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "status");
        ri.coverageTypes = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageTypes");
        ri.coverageAgents = (String) getValue(mongoSession, Collection.SERVER_INFO.toString(), "coverageAgents");
        ri.currentSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "current");
        ri.originalSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "original");
        if (!"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"))) {
            ri.agentCount = collection(Collection.AGENTS.toString()).countDocuments(mongoSession);
        }
        ri.accountCount = collection(Collection.ACCOUNTS.toString()).countDocuments(mongoSession);
        ri.nanopubCount = collection(Collection.NANOPUBS.toString()).countDocuments(mongoSession);
        ri.isTestInstance = isSet(mongoSession, Collection.SERVER_INFO.toString(), "testInstance");
        return ri;
    }

    public String asJson() {
        return gson.toJson(this);
    }

}
