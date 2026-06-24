package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

import static com.knowledgepixels.registry.RegistryDB.*;

@SuppressWarnings("unused")
public class RegistryInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(RegistryInfo.class);

    private String registryVersion;
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
    private Long loadCounter;
    private Boolean isTestInstance;
    private Boolean optionalLoadEnabled;
    private Boolean trustCalculationEnabled;

    private static final Gson gson = new Gson();

    public static RegistryInfo getLocal(ClientSession mongoSession) {
        logger.debug("Assembling RegistryInfo snapshot");

        RegistryInfo ri = new RegistryInfo();
        Document si = new Document();
        for (Document d : collection(Collection.SERVER_INFO.toString()).find(mongoSession)) {
            si.put(d.getString("_id"), d.get("value"));
        }
        logger.debug("Loaded {} entries from {} collection: {}", si.size(), Collection.SERVER_INFO, si.keySet());

        ri.registryVersion = Utils.getVersion();
        ri.setupId = (Long) si.get("setupId");
        ri.trustStateCounter = (Long) si.get("trustStateCounter");
        ri.lastTrustStateUpdate = (String) si.get("lastTrustStateUpdate");
        ri.trustStateHash = (String) si.get("trustStateHash");
        ri.loadCounter = (Long) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "counter");
        if (ri.loadCounter == null) {
            logger.debug("No max 'counter' value found in {} (collection may be empty)", Collection.NANOPUBS);
        }

        ri.status = (String) si.get("status");
        if (ri.status == null) {
            logger.warn("Server status is missing from {} ('status' key not set)", Collection.SERVER_INFO);
        }

        ri.coverageTypes = si.get("coverageTypes") != null ? (String) si.get("coverageTypes") : "all";
        ri.coverageAgents = si.get("coverageAgents") != null ? (String) si.get("coverageAgents") : "viaSetting";
        logger.debug("Coverage settings resolved: coverageTypes={}, coverageAgents={}", ri.coverageTypes, ri.coverageAgents);

        ri.currentSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "current");
        ri.originalSetting = (String) getValue(mongoSession, Collection.SETTING.toString(), "original");
        if (ri.currentSetting == null || ri.originalSetting == null) {
            logger.debug("Setting lookup incomplete: currentSetting={}, originalSetting={}", ri.currentSetting, ri.originalSetting);
        }

        ri.trustCalculationEnabled = !"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"));
        if (!"false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"))) {
            ri.agentCount = collection(Collection.AGENTS.toString()).countDocuments(mongoSession);
            logger.debug("Trust calculation enabled; agentCount={}", ri.agentCount);
        } else {
            logger.debug("Trust calculation disabled (REGISTRY_ENABLE_TRUST_CALCULATION=false); skipping agentCount");
        }

        ri.accountCount = collection(Collection.ACCOUNTS.toString()).countDocuments(mongoSession);
        ri.nanopubCount = collection(Collection.NANOPUBS.toString()).countDocuments(mongoSession);
        logger.debug("Counts: accountCount={}, nanopubCount={}", ri.accountCount, ri.nanopubCount);

        ri.isTestInstance = si.get("testInstance") != null && (Boolean) si.get("testInstance");
        ri.optionalLoadEnabled = !"false".equals(System.getenv("REGISTRY_ENABLE_OPTIONAL_LOAD"));

        logger.info("RegistryInfo snapshot ready: version={}, status={}, setupId={}, isTestInstance={}, optionalLoadEnabled={}, trustCalculationEnabled={}", ri.registryVersion, ri.status, ri.setupId, ri.isTestInstance, ri.optionalLoadEnabled, ri.trustCalculationEnabled);

        return ri;
    }

    public String asJson() {
        String json = gson.toJson(this);
        logger.debug("Serialized RegistryInfo to JSON ({} chars)", json.length());
        return json;
    }

}
