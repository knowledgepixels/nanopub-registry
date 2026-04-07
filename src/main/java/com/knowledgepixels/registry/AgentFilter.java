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
 *
 * Format: whitespace-separated entries, each being either:
 * - "viaSetting" — include all agents approved by the trust network (with computed quotas)
 * - "pubkeyHash:quota" — include a specific pubkey with an explicit quota
 *
 * Example: viaSetting abc123...def456:5000 789xyz...abc012:10000
 *
 * When unset, defaults to viaSetting (all trusted agents, no restrictions).
 */
public final class AgentFilter {

    private static final Logger logger = LoggerFactory.getLogger(AgentFilter.class);

    private static volatile boolean viaSetting = true;
    private static volatile Map<String, Integer> explicitPubkeys = Collections.emptyMap();

    private AgentFilter() {}

    /**
     * Initializes the agent filter from the REGISTRY_COVERAGE_AGENTS env var.
     */
    public static void init() {
        String config = Utils.getEnv("REGISTRY_COVERAGE_AGENTS", "viaSetting");
        boolean via = false;
        Map<String, Integer> pubkeys = new HashMap<>();

        for (String entry : config.trim().split("\\s+")) {
            if (entry.isEmpty()) continue;
            if ("viaSetting".equals(entry)) {
                via = true;
            } else if (entry.contains(":")) {
                String[] parts = entry.split(":", 2);
                String pubkeyHash = parts[0];
                int quota;
                try {
                    quota = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid quota in REGISTRY_COVERAGE_AGENTS: " + entry);
                }
                pubkeys.put(pubkeyHash, quota);
            } else {
                throw new IllegalArgumentException(
                        "Invalid entry in REGISTRY_COVERAGE_AGENTS: " + entry
                                + " (expected 'viaSetting' or 'pubkeyHash:quota')");
            }
        }

        viaSetting = via;
        explicitPubkeys = Collections.unmodifiableMap(pubkeys);

        if (via && pubkeys.isEmpty()) {
            logger.info("Agent filter: viaSetting (all trusted agents)");
        } else if (via) {
            logger.info("Agent filter: viaSetting + {} explicit pubkeys", pubkeys.size());
        } else {
            logger.info("Agent filter: {} explicit pubkeys only", pubkeys.size());
        }

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
            return explicitPubkeys.get(pubkeyHash);
        }

        // Check trust network if enabled
        if (viaSetting) {
            Document account = collection(Collection.ACCOUNTS.toString()).find(session,
                    new Document("pubkey", pubkeyHash).append("status", "loaded")).first();
            if (account != null && account.get("quota") != null) {
                return account.getInteger("quota");
            }
            // Also accept toLoad accounts (approved but not yet fully loaded)
            account = collection(Collection.ACCOUNTS.toString()).find(session,
                    new Document("pubkey", pubkeyHash).append("status", "toLoad")).first();
            if (account != null && account.get("quota") != null) {
                return account.getInteger("quota");
            }
        }

        return -1; // not allowed
    }

    /**
     * Returns true if the given pubkey is allowed to publish (has a quota >= 0).
     */
    public static boolean isAllowed(ClientSession session, String pubkeyHash) {
        return getQuota(session, pubkeyHash) >= 0;
    }

    /**
     * Returns true if the given pubkey has exceeded its quota.
     * Checks the current nanopub count for this pubkey against its quota.
     */
    public static boolean isOverQuota(ClientSession session, String pubkeyHash) {
        int quota = getQuota(session, pubkeyHash);
        if (quota < 0) return true; // not allowed at all

        // Count nanopubs for this pubkey via the "$" list position
        Document listDoc = collection("lists").find(session,
                new Document("pubkey", pubkeyHash).append("type", "$")).first();
        long currentCount = (listDoc != null && listDoc.get("maxPosition") != null)
                ? listDoc.getLong("maxPosition") + 1 : 0;

        return currentCount >= quota;
    }
}
