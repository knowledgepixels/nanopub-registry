package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;
import org.bson.Document;

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
    private Boolean optionalLoadEnabled;
    private Boolean trustCalculationEnabled;

    private static final Gson gson = new Gson();

    public static RegistryInfo getLocal(ClientSession mongoSession) {
        RegistryInfo ri = new RegistryInfo();
        Document si = new Document();
        for (Document d : collection(Collection.SERVER_INFO.toString()).find(mongoSession)) {
            si.put(d.getString("_id"), d.get("value"));
        }
        ri.setupId = (Long) si.get("setupId");
        ri.trustStateCounter = (Long) si.get("trustStateCounter");
        ri.lastTrustStateUpdate = (String) si.get("lastTrustStateUpdate");
        ri.trustStateHash = (String) si.get("trustStateHash");
        ri.seqNum = (Long) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "seqNum");
        // TODO(transition): Remove loadCounter after all peers upgraded
        ri.loadCounter = ri.seqNum;
        ri.status = (String) si.get("status");
        ri.coverageTypes = si.get("coverageTypes") != null ? (String) si.get("coverageTypes") : "all";
        ri.coverageAgents = si.get("coverageAgents") != null ? (String) si.get("coverageAgents") : "viaSetting";
        ri.currentSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "current");
        ri.originalSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "original");
        if (!"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"))) {
            ri.agentCount = collection(Collection.AGENTS.toString()).countDocuments(mongoSession);
        }
        ri.accountCount = collection(Collection.ACCOUNTS.toString()).countDocuments(mongoSession);
        ri.nanopubCount = collection(Collection.NANOPUBS.toString()).countDocuments(mongoSession);
        ri.isTestInstance = si.get("testInstance") != null && (Boolean) si.get("testInstance");
        ri.optionalLoadEnabled = !"false".equals(System.getenv("REGISTRY_ENABLE_OPTIONAL_LOAD"));
        ri.trustCalculationEnabled = !"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"));
        return ri;
    }

    public String asJson() {
        return gson.toJson(this);
    }

}
