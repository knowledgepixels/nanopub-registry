package com.knowledgepixels.registry;

import com.google.gson.Gson;
import com.knowledgepixels.registry.store.MongoRegistryReader;
import com.knowledgepixels.registry.store.RegistryReader;
import com.mongodb.client.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Map;

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
    private Boolean isLocalInstance;
    private Boolean optionalLoadEnabled;
    private Boolean trustCalculationEnabled;
    private Boolean isReadOnly;

    private static final Gson gson = new Gson();

    /**
     * Assembles the info from a MongoDB session (the write path and older callers).
     */
    public static RegistryInfo getLocal(ClientSession mongoSession) {
        return getLocal(MongoRegistryReader.of(mongoSession));
    }

    public static RegistryInfo getLocal(RegistryReader reader) {
        logger.debug("Assembling RegistryInfo snapshot");

        RegistryInfo ri = new RegistryInfo();
        Map<String, Object> si = reader.serverInfo();
        logger.debug("Loaded {} entries from {} collection: {}", si.size(), Collection.SERVER_INFO, si.keySet());

        ri.registryVersion = Utils.getVersion();
        ri.setupId = (Long) si.get("setupId");
        ri.trustStateCounter = (Long) si.get("trustStateCounter");
        ri.lastTrustStateUpdate = (String) si.get("lastTrustStateUpdate");
        ri.trustStateHash = (String) si.get("trustStateHash");
        ri.loadCounter = reader.maxCounter();
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

        ri.currentSetting = (String) reader.settingValue("current");
        ri.originalSetting = (String) reader.settingValue("original");
        if (ri.currentSetting == null || ri.originalSetting == null) {
            logger.debug("Setting lookup incomplete: currentSetting={}, originalSetting={}", ri.currentSetting, ri.originalSetting);
        }

        ri.trustCalculationEnabled = !"false".equals(Utils.getEnv("REGISTRY_ENABLE_TRUST_CALCULATION", null));
        if (!"false".equals(Utils.getEnv("REGISTRY_ENABLE_TRUST_CALCULATION", null))) {
            ri.agentCount = reader.agentCount();
            logger.debug("Trust calculation enabled; agentCount={}", ri.agentCount);
        } else {
            logger.debug("Trust calculation disabled (REGISTRY_ENABLE_TRUST_CALCULATION=false); skipping agentCount");
        }

        ri.accountCount = reader.accountCount();
        ri.nanopubCount = reader.nanopubCountExact();
        logger.debug("Counts: accountCount={}, nanopubCount={}", ri.accountCount, ri.nanopubCount);

        ri.isTestInstance = si.get("testInstance") != null && (Boolean) si.get("testInstance");
        ri.isLocalInstance = Utils.isLocalInstance();
        ri.optionalLoadEnabled = !"false".equals(Utils.getEnv("REGISTRY_ENABLE_OPTIONAL_LOAD", null));
        ri.isReadOnly = ReadOnlyMode.isEnabled();

        logger.info("RegistryInfo snapshot ready: version={}, status={}, setupId={}, isTestInstance={}, isLocalInstance={}, optionalLoadEnabled={}, trustCalculationEnabled={}", ri.registryVersion, ri.status, ri.setupId, ri.isTestInstance, ri.isLocalInstance, ri.optionalLoadEnabled, ri.trustCalculationEnabled);

        return ri;
    }

    public String asJson() {
        String json = gson.toJson(this);
        logger.debug("Serialized RegistryInfo to JSON ({} chars)", json.length());
        return json;
    }

}
