package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * Controls which pubkeys are allowed to publish nanopubs and what their quotas are.
 * Configured via REGISTRY_COVERAGE_AGENTS env var.
 * <p>
 * Format: whitespace-separated entries, each being either:
 * - "viaSetting" — include all agents approved by the trust network (with computed quotas)
 * - "pubkeyHash:quota" — include a specific pubkey with an explicit quota
 * <p>
 * Example: viaSetting abc123...def456:5000 789xyz...abc012:10000
 * <p>
 * When unset, defaults to viaSetting (all trusted agents, no restrictions).
 */
public final class AgentFilter {

    private static final Logger logger = LoggerFactory.getLogger(AgentFilter.class);

    private static volatile boolean viaSetting = true;
    private static volatile boolean enforceQuota = false;
    private static volatile Map<String, Integer> explicitPubkeys = Collections.emptyMap();

    private AgentFilter() {
    }

    /**
     * Initializes the agent filter from the REGISTRY_COVERAGE_AGENTS env var.
     */
    public static void init() {
        String config = Utils.getEnv("REGISTRY_COVERAGE_AGENTS", "viaSetting");
        logger.debug("Initializing AgentFilter from REGISTRY_COVERAGE_AGENTS='{}'", config);

        boolean via = false;
        Map<String, Integer> pubkeys = new HashMap<>();

        for (String entry : config.trim().split("\\s+")) {
            if (entry.isEmpty()) {
                continue;
            }
            if ("viaSetting".equals(entry)) {
                via = true;
            } else if (entry.contains(":")) {
                String[] parts = entry.split(":", 2);
                String pubkeyHash = parts[0];
                int quota;
                try {
                    quota = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    logger.error("Failed to parse quota in REGISTRY_COVERAGE_AGENTS entry '{}': '{}' is not a valid integer", entry, parts[1]);
                    throw new IllegalArgumentException("Invalid quota in REGISTRY_COVERAGE_AGENTS: " + entry);
                }
                pubkeys.put(pubkeyHash, quota);
            } else {
                logger.error("Unrecognized entry in REGISTRY_COVERAGE_AGENTS: '{}'", entry);
                throw new IllegalArgumentException("Invalid entry in REGISTRY_COVERAGE_AGENTS: " + entry + " (expected 'viaSetting' or 'pubkeyHash:quota')");
            }
        }

        viaSetting = via;
        enforceQuota = "true".equals(System.getenv("REGISTRY_ENFORCE_QUOTA"));
        explicitPubkeys = Collections.unmodifiableMap(pubkeys);

        if (via && pubkeys.isEmpty()) {
            logger.info("Agent filter: viaSetting (all trusted agents)");
        } else if (via) {
            logger.info("Agent filter: viaSetting + {} explicit pubkeys", pubkeys.size());
        } else {
            logger.info("Agent filter: {} explicit pubkeys only", pubkeys.size());
        }
        logger.info("Quota enforcement (REGISTRY_ENFORCE_QUOTA): {}", enforceQuota);

        if (via && "false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"))) {
            logger.warn("viaSetting is enabled but trust calculation is disabled — " +
                        "no agents will be discovered via the trust network; only explicit pubkeys will work");
        }
    }

    /**
     * Returns true if the trust network (viaSetting) is used for agent approval.
     */
    public static boolean usesViaSetting() {
        return viaSetting;
    }

    /**
     * Returns the explicitly configured pubkeys with their quotas.
     */
    public static Map<String, Integer> getExplicitPubkeys() {
        return explicitPubkeys;
    }

    /**
     * Returns the quota for a given pubkey, checking explicit config first,
     * then the accounts collection for trust-computed quotas.
     * Returns -1 if the pubkey is not allowed.
     */
    public static int getQuota(ClientSession session, String pubkeyHash) {
        // Explicit pubkeys take precedence
        if (explicitPubkeys.containsKey(pubkeyHash)) {
            int quota = explicitPubkeys.get(pubkeyHash);
            logger.trace("Pubkey {}: quota={} (explicit config)", pubkeyHash, quota);
            return quota;
        }

        // Check trust network if enabled
        if (viaSetting) {
            Document account = collection(Collection.ACCOUNTS.toString()).find(session,
                    new Document("pubkey", pubkeyHash).append("status", "loaded")).first();
            if (account != null && account.get("quota") != null) {
                int quota = account.getInteger("quota");
                logger.trace("Pubkey {}: quota={} (account status=loaded)", pubkeyHash, quota);
                return quota;
            }
            // Also accept toLoad accounts (approved but not yet fully loaded)
            account = collection(Collection.ACCOUNTS.toString()).find(session,
                    new Document("pubkey", pubkeyHash).append("status", "toLoad")).first();
            if (account != null && account.get("quota") != null) {
                int quota = account.getInteger("quota");
                logger.trace("Pubkey {}: quota={} (account status=toLoad)", pubkeyHash, quota);
                return quota;
            }
        }

        logger.trace("Pubkey {}: no quota found (not allowed)", pubkeyHash);
        return -1; // not allowed
    }

    /**
     * Returns true if the given pubkey is allowed to publish (has a quota >= 0).
     * Always returns true if quota enforcement is disabled.
     */
    public static boolean isAllowed(ClientSession session, String pubkeyHash) {
        if (!enforceQuota) {
            return true;
        }
        boolean allowed = getQuota(session, pubkeyHash) >= 0;
        if (!allowed) {
            logger.debug("Pubkey {}: not allowed (no quota entry found)", pubkeyHash);
        }
        return allowed;
    }

    /**
     * Returns true if the given pubkey has exceeded its quota.
     * Always returns false if quota enforcement is disabled.
     */
    public static boolean isOverQuota(ClientSession session, String pubkeyHash) {
        if (!enforceQuota) {
            return false;
        }
        int quota = getQuota(session, pubkeyHash);
        if (quota < 0) {
            logger.debug("Pubkey {}: treated as over quota (not allowed at all)", pubkeyHash);
            return true; // not allowed at all
        }

        // Count nanopubs for this pubkey via the "$" list position
        Document listDoc = collection("lists").find(session,
                new Document("pubkey", pubkeyHash).append("type", "$")).first();
        long currentCount = (listDoc != null && listDoc.get("maxPosition") != null)
                ? listDoc.getLong("maxPosition") + 1 : 0;

        boolean over = currentCount >= quota;
        if (over) {
            logger.debug("Pubkey {}: over quota ({} / {})", pubkeyHash, currentCount, quota);
        } else {
            logger.trace("Pubkey {}: under quota ({} / {})", pubkeyHash, currentCount, quota);
        }
        return over;
    }

}
