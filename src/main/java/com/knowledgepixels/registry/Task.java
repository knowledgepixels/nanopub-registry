package com.knowledgepixels.registry;

import com.knowledgepixels.registry.db.IndexInitializer;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import net.trustyuri.TrustyUriUtils;
import org.apache.commons.lang.Validate;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.nanopub.Nanopub;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.index.NanopubIndex;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.knowledgepixels.registry.EntryStatus.*;
import static com.knowledgepixels.registry.NanopubLoader.*;
import static com.knowledgepixels.registry.RegistryDB.*;
import static com.knowledgepixels.registry.ServerStatus.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.*;

public enum Task implements Serializable {

    INIT_DB {
        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running INIT_DB task: initializing a fresh database");
            setServerStatus(s, launching);

            increaseStateCounter(s);
            if (RegistryDB.isInitialized(s)) {
                logger.error("INIT_DB aborted: database is already initialized");
                throw new RuntimeException("DB already initialized");
            }

            long setupId = Math.abs(Utils.getRandom().nextLong());
            boolean testInstance = "true".equals(System.getenv("REGISTRY_TEST_INSTANCE"));
            logger.info("Initializing new database with setupId={} (testInstance={})", setupId, testInstance);

            setValue(s, Collection.SERVER_INFO.toString(), "setupId", setupId);
            setValue(s, Collection.SERVER_INFO.toString(), "testInstance", testInstance);

            logger.debug("INIT_DB complete; scheduling LOAD_CONFIG task");
            schedule(s, LOAD_CONFIG);
        }

    },

    LOAD_CONFIG {
        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running LOAD_CONFIG task");
            ServerStatus status = getServerStatus(s);
            if (status != launching) {
                logger.error("LOAD_CONFIG aborted: expected server status '{}' but found '{}'", launching, status);
                throw new IllegalTaskStatusException("Illegal status for this task: " + status);
            }

            String coverageTypes = System.getenv("REGISTRY_COVERAGE_TYPES");
            if (coverageTypes != null) {
                logger.info("Setting coverageTypes from REGISTRY_COVERAGE_TYPES: {}", coverageTypes);
                setValue(s, Collection.SERVER_INFO.toString(), "coverageTypes", coverageTypes);
            } else {
                logger.debug("REGISTRY_COVERAGE_TYPES not set; leaving coverageTypes unset");
            }

            String coverageAgents = System.getenv("REGISTRY_COVERAGE_AGENTS");
            if (coverageAgents != null) {
                logger.info("Setting coverageAgents from REGISTRY_COVERAGE_AGENTS: {}", coverageAgents);
                setValue(s, Collection.SERVER_INFO.toString(), "coverageAgents", coverageAgents);
            } else {
                logger.debug("REGISTRY_COVERAGE_AGENTS not set; leaving coverageAgents unset");
            }

            logger.debug("LOAD_CONFIG complete; scheduling LOAD_SETTING task");
            schedule(s, LOAD_SETTING);
        }

    },

    LOAD_SETTING {
        public void run(ClientSession s, Document taskDoc) throws Exception {
            logger.info("Running LOAD_SETTING task");
            ServerStatus status = getServerStatus(s);
            if (status != launching) {
                logger.error("LOAD_SETTING aborted: expected server status '{}' but found '{}'", launching, status);
                throw new IllegalTaskStatusException("Illegal status for this task: " + status);
            }

            NanopubSetting settingNp = Utils.getSetting();
            String settingId = TrustyUriUtils.getArtifactCode(settingNp.getNanopub().getUri().stringValue());
            logger.info("Loading setting nanopub {}", settingId);
            setValue(s, Collection.SETTING.toString(), "original", settingId);
            setValue(s, Collection.SETTING.toString(), "current", settingId);
            loadNanopub(s, settingNp.getNanopub());

            List<Document> bootstrapServices = new ArrayList<>();
            for (IRI i : settingNp.getBootstrapServices()) {
                bootstrapServices.add(new Document("_id", i.stringValue()));
            }
            logger.info("Setting {} bootstrap service(s) from the setting nanopub: {}", bootstrapServices.size(), bootstrapServices);
            // potentially currently hardcoded in the nanopub lib
            setValue(s, Collection.SETTING.toString(), "bootstrap-services", bootstrapServices);

            boolean performFullLoad = !"false".equals(System.getenv("REGISTRY_PERFORM_FULL_LOAD"));
            if (performFullLoad) {
                logger.debug("REGISTRY_PERFORM_FULL_LOAD not disabled; scheduling LOAD_FULL task");
                schedule(s, LOAD_FULL);
            } else {
                logger.info("REGISTRY_PERFORM_FULL_LOAD=false; skipping LOAD_FULL task");
            }

            logger.debug("LOAD_SETTING complete; transitioning server status to '{}' and scheduling INIT_COLLECTIONS", coreLoading);
            setServerStatus(s, coreLoading);
            schedule(s, INIT_COLLECTIONS);
        }
    },

    INIT_COLLECTIONS {

        // DB read from:
        // DB write to:  trustPaths, endorsements, accounts
        // This state is periodically executed

        public void run(ClientSession s, Document taskDoc) throws Exception {
            logger.info("Running INIT_COLLECTIONS task");
            ServerStatus status = getServerStatus(s);
            if (status != coreLoading && status != updating) {
                logger.error("INIT_COLLECTIONS aborted: expected server status 'coreLoading' or 'updating' but found '{}'", status);
                throw new IllegalTaskStatusException("Illegal status for this task: " + status);
            }

            IndexInitializer.initLoadingCollections(s);

            if ("false".equals(System.getenv("REGISTRY_ENABLE_TRUST_CALCULATION"))) {
                logger.info("Trust calculation disabled (REGISTRY_ENABLE_TRUST_CALCULATION=false); skipping to FINALIZE_TRUST_STATE");
                for (Map.Entry<String, Integer> entry : AgentFilter.getExplicitPubkeys().entrySet()) {
                    String pubkeyHash = entry.getKey();
                    int quota = entry.getValue();
                    Document account = new Document("agent", "")
                            .append("pubkey", pubkeyHash)
                            .append("status", toLoad.getValue())
                            .append("depth", 0)
                            .append("quota", quota);
                    if (!has(s, "accounts_loading", new Document("pubkey", pubkeyHash))) {
                        insert(s, "accounts_loading", account);
                        logger.debug("Seeded explicit pubkey as account: {} (quota={})", pubkeyHash, quota);
                    }
                }
                schedule(s, FINALIZE_TRUST_STATE);
                return;
            }

            logger.debug("Inserting root trust path entry for base agent");
            // since this may take long, we start with postfix "_loading"
            // and only at completion it's changed to trustPath, endorsements, accounts
            insert(s, "trustPaths_loading",
                    new Document("_id", "$")
                            .append("sorthash", "")
                            .append("agent", "$")
                            .append("pubkey", "$")
                            .append("depth", 0)
                            .append("ratio", 1.0d)
                            .append("type", "extended")
            );

            String agentIntroCollectionUri = Utils.getSetting().getAgentIntroCollection().stringValue();
            logger.info("Retrieving base agent intro collection: {}", agentIntroCollectionUri);
            NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubLoader.retrieveNanopub(s, agentIntroCollectionUri));
            loadNanopub(s, agentIndex);
            for (IRI el : agentIndex.getElements()) {
                String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());
                Validate.notNull(declarationAc);

                insert(s, "endorsements_loading",
                        new Document("agent", "$")
                                .append("pubkey", "$")
                                .append("endorsedNanopub", declarationAc)
                                .append("source", getValue(s, Collection.SETTING.toString(), "current").toString())
                                .append("status", toRetrieve.getValue())

                );

            }
            insert(s, "accounts_loading",
                    new Document("agent", "$")
                            .append("pubkey", "$")
                            .append("status", visited.getValue())
                            .append("depth", 0)
            );

            logger.info("Starting iteration at depth 0");
            schedule(s, LOAD_DECLARATIONS.with("depth", 1));
        }

        // At the end of this task, the base agent is initialized:
        // ------------------------------------------------------------
        //
        //	      $$$$ ----endorses----> [intro]
        //	      base                (to-retrieve)
        //	      $$$$
        //	    (visited)
        //
        //	      [0] trust path
        //
        // ------------------------------------------------------------
        // Only one endorses-link to an introduction is shown here,
        // but there are typically several.

    },

    LOAD_DECLARATIONS {

        // In general, we have at this point accounts with
        // endorsement links to unvisited agent introductions:
        // ------------------------------------------------------------
        //
        //         o      ----endorses----> [intro]
        //    --> /#\  /o\___            (to-retrieve)
        //        / \  \_/^^^
        //         (visited)
        //
        //    ========[X] trust path
        //
        // ------------------------------------------------------------

        // DB read from: endorsements, trustEdges, accounts
        // DB write to:  endorsements, trustEdges, accounts

        public void run(ClientSession s, Document taskDoc) {

            int depth = taskDoc.getInteger("depth");
            logger.info("Running LOAD_DECLARATIONS task at depth {}", depth);

            while (true) {
                Document d = getOne(s, "endorsements_loading",
                        new DbEntryWrapper(toRetrieve).getDocument());
                if (d == null) {
                    break;
                }

                IntroNanopub agentIntro = getAgentIntro(s, d.getString("endorsedNanopub"));
                if (agentIntro != null) {
                    String agentId = agentIntro.getUser().stringValue();
                    // foaf:name + dct:created of the intro nanopub. Same name applies to every
                    // KeyDeclaration in the intro, so resolve once outside the inner loop.
                    String introName = Utils.extractIntroName(agentIntro);
                    Calendar introCreatedCal = SimpleTimestampPattern.getCreationTime(agentIntro.getNanopub());
                    Date introCreatedAt = (introCreatedCal == null) ? null : introCreatedCal.getTime();
                    // The authorizing introduction for this (agent, pubkey): it carries the
                    // KeyDeclaration for the pubkey. Recorded alongside the name and kept in sync
                    // with it (latest-dct:created wins), so it reflects one authorizing intro —
                    // the name-winning one — not the complete set of intros for this account.
                    String introNp = agentIntro.getNanopub().getUri().stringValue();

                    for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
                        String sourceAgent = d.getString("agent");
                        Validate.notNull(sourceAgent);
                        String sourcePubkey = d.getString("pubkey");
                        Validate.notNull(sourcePubkey);
                        String sourceAc = d.getString("source");
                        Validate.notNull(sourceAc);
                        String agentPubkey = Utils.getHash(kd.getPublicKeyString());
                        Validate.notNull(agentPubkey);
                        Document trustEdge = new Document("fromAgent", sourceAgent)
                                .append("fromPubkey", sourcePubkey)
                                .append("toAgent", agentId)
                                .append("toPubkey", agentPubkey)
                                .append("source", sourceAc);
                        if (!has(s, "trustEdges", trustEdge)) {
                            boolean invalidated = has(s, "invalidations", new Document("invalidatedNp", sourceAc).append("invalidatingPubkey", sourcePubkey));
                            if (invalidated) {
                                logger.info("Trust edge from {}/{} to {}/{} is invalidated by source {}", sourceAgent, sourcePubkey, agentId, agentPubkey, sourceAc);
                            }
                            insert(s, "trustEdges", trustEdge.append("invalidated", invalidated));
                        }

                        Document agent = new Document("agent", agentId).append("pubkey", agentPubkey);
                        Document existing = collection("accounts_loading").find(s, agent).first();
                        if (existing == null) {
                            insert(s, "accounts_loading", agent
                                    .append("status", seen.getValue())
                                    .append("depth", depth)
                                    .append("name", introName)
                                    .append("nameCreatedAt", introCreatedAt)
                                    .append("introNanopub", introNp));
                        } else if (introName != null) {
                            // Per-(agent, pubkey) name policy: keep the name from the intro
                            // with the latest dct:created. First write wins when no current
                            // timestamp exists; otherwise compare and replace iff strictly newer.
                            Date currentCreatedAt = existing.getDate("nameCreatedAt");
                            if (currentCreatedAt == null
                                || (introCreatedAt != null && introCreatedAt.after(currentCreatedAt))) {
                                set(s, "accounts_loading", existing
                                        .append("name", introName)
                                        .append("nameCreatedAt", introCreatedAt)
                                        .append("introNanopub", introNp));
                            }
                        }
                    }

                    set(s, "endorsements_loading", d.append("status", retrieved.getValue()));
                } else {
                    logger.debug("Discarding endorsement {}: referenced nanopub {} is not a valid agent intro", d.get("_id"), d.getString("endorsedNanopub"));
                    set(s, "endorsements_loading", d.append("status", discarded.getValue()));
                }
            }
            logger.info("LOAD_DECLARATIONS at depth {} complete.", depth);
            schedule(s, EXPAND_TRUST_PATHS.with("depth", depth));
        }

        // At the end of this step, the key declarations in the agent
        // introductions are loaded and the corresponding trust edges
        // established:
        // ------------------------------------------------------------
        //
        //        o      ----endorses----> [intro]
        //   --> /#\  /o\___                o
        //       / \  \_/^^^ ---trusts---> /#\  /o\___
        //        (visited)                / \  \_/^^^
        //                                   (seen)
        //
        //   ========[X] trust path
        //
        // ------------------------------------------------------------
        // Only one trust edge per introduction is shown here, but
        // there can be several.

    },

    EXPAND_TRUST_PATHS {

        // DB read from: accounts, trustPaths, trustEdges
        // DB write to:  accounts, trustPaths

        public void run(ClientSession s, Document taskDoc) {

            int depth = taskDoc.getInteger("depth");
            logger.info("Running EXPAND_TRUST_PATHS task at depth {}", depth);

            while (true) {
                Document d = getOne(s, "accounts_loading",
                        new Document("status", visited.getValue())
                                .append("depth", depth - 1)
                );
                if (d == null) {
                    break;
                }

                String agentId = d.getString("agent");
                Validate.notNull(agentId);
                String pubkeyHash = d.getString("pubkey");
                Validate.notNull(pubkeyHash);

                Document trustPath = collection("trustPaths_loading").find(s,
                        new Document("agent", agentId).append("pubkey", pubkeyHash).append("type", "extended").append("depth", depth - 1)
                ).sort(orderBy(descending("ratio"), ascending("sorthash"))).first();

                if (trustPath == null) {
                    // Check it again in next iteration:
                    set(s, "accounts_loading", d.append("depth", depth));
                } else {
                    // Only first matching trust path is considered

                    Map<String, Document> newPaths = new HashMap<>();
                    Map<String, Set<String>> pubkeySets = new HashMap<>();
                    String currentSetting = getValue(s, Collection.SETTING.toString(), "current").toString();

                    try (MongoCursor<Document> edgeCursor = get(s, "trustEdges",
                            new Document("fromAgent", agentId)
                                    .append("fromPubkey", pubkeyHash)
                                    .append("invalidated", false)
                    )) {
                        while (edgeCursor.hasNext()) {
                            Document e = edgeCursor.next();

                            String agent = e.getString("toAgent");
                            Validate.notNull(agent);
                            String pubkey = e.getString("toPubkey");
                            Validate.notNull(pubkey);
                            String pathId = trustPath.getString("_id") + " " + agent + "|" + pubkey;
                            newPaths.put(pathId,
                                    new Document("_id", pathId)
                                            .append("sorthash", Utils.getHash(currentSetting + " " + pathId))
                                            .append("agent", agent)
                                            .append("pubkey", pubkey)
                                            .append("depth", depth)
                                            .append("type", "extended")
                            );
                            if (!pubkeySets.containsKey(agent)) {
                                pubkeySets.put(agent, new HashSet<>());
                            }
                            pubkeySets.get(agent).add(pubkey);
                        }
                    }
                    for (String pathId : newPaths.keySet()) {
                        Document pd = newPaths.get(pathId);
                        // first divide by agents; then for each agent, divide by number of pubkeys:
                        double newRatio = (trustPath.getDouble("ratio") * 0.9) / pubkeySets.size() / pubkeySets.get(pd.getString("agent")).size();
                        insert(s, "trustPaths_loading", pd.append("ratio", newRatio));
                    }
                    // Retain only 10% of the ratio — the other 90% was distributed to children
                    double retainedRatio = trustPath.getDouble("ratio") * 0.1;
                    set(s, "trustPaths_loading", trustPath.append("type", "primary").append("ratio", retainedRatio));
                    set(s, "accounts_loading", d.append("status", expanded.getValue()));
                }
            }
            logger.info("EXPAND_TRUST_PATHS at depth {} complete.", depth);
            schedule(s, LOAD_CORE.with("depth", depth).append("load-count", 0));
        }

        // At the end of this step, trust paths are updated to include
        // the new accounts:
        // ------------------------------------------------------------
        //
        //         o      ----endorses----> [intro]
        //    --> /#\  /o\___                o
        //        / \  \_/^^^ ---trusts---> /#\  /o\___
        //        (expanded)                / \  \_/^^^
        //                                    (seen)
        //
        //    ========[X]=====================[X+1] trust path
        //
        // ------------------------------------------------------------
        // Only one trust path is shown here, but they branch out if
        // several trust edges are present.

    },

    LOAD_CORE {

        // From here on, we refocus on the head of the trust paths:
        // ------------------------------------------------------------
        //
        //         o
        //    --> /#\  /o\___
        //        / \  \_/^^^
        //          (seen)
        //
        //    ========[X] trust path
        //
        // ------------------------------------------------------------

        // DB read from: accounts, trustPaths, endorsements, lists
        // DB write to:  accounts, endorsements, lists

        public void run(ClientSession s, Document taskDoc) {

            int depth = taskDoc.getInteger("depth");
            int loadCount = taskDoc.getInteger("load-count");

            Document agentAccount = getOne(s, "accounts_loading",
                    new Document("depth", depth).append("status", seen.getValue()));
            final String agentId;
            final String pubkeyHash;
            final Document trustPath;
            if (agentAccount != null) {
                agentId = agentAccount.getString("agent");
                Validate.notNull(agentId);
                pubkeyHash = agentAccount.getString("pubkey");
                Validate.notNull(pubkeyHash);
                trustPath = getOne(s, "trustPaths_loading",
                        new Document("depth", depth)
                                .append("agent", agentId)
                                .append("pubkey", pubkeyHash)
                );
            } else {
                agentId = null;
                pubkeyHash = null;
                trustPath = null;
            }

            if (agentAccount == null) {
                logger.info("LOAD_CORE at depth {} complete: {} account(s) processed", depth, loadCount);
                schedule(s, FINISH_ITERATION.with("depth", depth).append("load-count", loadCount));
            } else if (trustPath == null) {
                logger.debug("Account {}/{} has no trust path at depth {}; marking skipped", agentId, pubkeyHash, depth);
                // Account was seen but has no trust path at this depth; skip it
                set(s, "accounts_loading", agentAccount.append("status", skipped.getValue()));
                schedule(s, LOAD_CORE.with("depth", depth).append("load-count", loadCount));
            } else if (trustPath.getDouble("ratio") < MIN_TRUST_PATH_RATIO) {
                logger.debug("Pubkey {}: trust path ratio {} below minimum {}; skipping core load, marking encountered", pubkeyHash, trustPath.getDouble("ratio"), MIN_TRUST_PATH_RATIO);
                set(s, "accounts_loading", agentAccount.append("status", skipped.getValue()));
                Document d = new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH);
                if (!has(s, "lists", d)) {
                    insert(s, "lists", d.append("status", encountered.getValue()));
                }
                schedule(s, LOAD_CORE.with("depth", depth).append("load-count", loadCount + 1));
            } else {
                logger.info("Pubkey {}: loading core (intro + endorsements) at depth {}", pubkeyHash, depth);
                // TODO check intro limit
                Document introList = new Document()
                        .append("pubkey", pubkeyHash)
                        .append("type", INTRO_TYPE_HASH)
                        .append("status", loading.getValue());
                if (!has(s, "lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH))) {
                    insert(s, "lists", introList);
                }

                // No checksum skip in LOAD_CORE: the endorsement extraction logic (below) needs to
                // see every nanopub to populate endorsements_loading, which is rebuilt from scratch each UPDATE.
                try (var stream = NanopubLoader.retrieveNanopubsFromPeers(INTRO_TYPE_HASH, pubkeyHash)) {
                    NanopubLoader.loadStreamInParallel(stream, np -> {
                        try (ClientSession ws = RegistryDB.getClient().startSession()) {
                            loadNanopub(ws, np, pubkeyHash, INTRO_TYPE);
                        }
                    });
                }

                set(s, "lists", introList.append("status", loaded.getValue()));
                logger.debug("Pubkey {}: intro list loaded", pubkeyHash);

                // TODO check endorsement limit
                Document endorseList = new Document()
                        .append("pubkey", pubkeyHash)
                        .append("type", ENDORSE_TYPE_HASH)
                        .append("status", loading.getValue());
                if (!has(s, "lists", new Document("pubkey", pubkeyHash).append("type", ENDORSE_TYPE_HASH))) {
                    insert(s, "lists", endorseList);
                }

                try (var stream = NanopubLoader.retrieveNanopubsFromPeers(ENDORSE_TYPE_HASH, pubkeyHash)) {
                    stream.forEach(m -> {
                        if (!m.isSuccess()) {
                            logger.error("Pubkey {}: failed to download an endorsement nanopub; aborting LOAD_CORE task", pubkeyHash);
                            throw new AbortingTaskException("Failed to download nanopub; aborting task...");
                        }
                        Nanopub nanopub = m.getNanopub();
                        loadNanopub(s, nanopub, pubkeyHash, ENDORSE_TYPE);
                        String sourceNpId = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
                        Validate.notNull(sourceNpId);
                        for (Statement st : nanopub.getAssertion()) {
                            if (!st.getPredicate().equals(Utils.APPROVES_OF)) {
                                continue;
                            }
                            if (!(st.getObject() instanceof IRI)) {
                                continue;
                            }
                            if (!agentId.equals(st.getSubject().stringValue())) {
                                continue;
                            }
                            String objStr = st.getObject().stringValue();
                            if (!TrustyUriUtils.isPotentialTrustyUri(objStr)) {
                                continue;
                            }
                            String endorsedNpId = TrustyUriUtils.getArtifactCode(objStr);
                            Validate.notNull(endorsedNpId);
                            Document endorsement = new Document("agent", agentId)
                                    .append("pubkey", pubkeyHash)
                                    .append("endorsedNanopub", endorsedNpId)
                                    .append("source", sourceNpId);
                            if (!has(s, "endorsements_loading", endorsement)) {
                                insert(s, "endorsements_loading",
                                        endorsement.append("status", toRetrieve.getValue()));
                            }
                        }
                    });
                }
                logger.debug("Pubkey {}: endorsement list loaded", pubkeyHash);

                set(s, "lists", endorseList.append("status", loaded.getValue()));

                Document df = new Document("pubkey", pubkeyHash).append("type", "$");
                if (!has(s, "lists", df)) {
                    insert(s, "lists",
                            df.append("status", encountered.getValue()));
                }

                set(s, "accounts_loading", agentAccount.append("status", visited.getValue()));

                schedule(s, LOAD_CORE.with("depth", depth).append("load-count", loadCount + 1));
            }

        }

        // At the end of this step, we have added new endorsement
        // links to yet-to-retrieve agent introductions:
        // ------------------------------------------------------------
        //
        //         o      ----endorses----> [intro]
        //    --> /#\  /o\___            (to-retrieve)
        //        / \  \_/^^^
        //         (visited)
        //
        //    ========[X] trust path
        //
        // ------------------------------------------------------------
        // Only one endorsement is shown here, but there are typically
        // several.

    },

    FINISH_ITERATION {
        public void run(ClientSession s, Document taskDoc) {

            int depth = taskDoc.getInteger("depth");
            int loadCount = taskDoc.getInteger("load-count");

            if (loadCount == 0) {
                logger.info("No new cores loaded; finishing iteration");
                schedule(s, CALCULATE_TRUST_SCORES);
            } else if (depth == MAX_TRUST_PATH_DEPTH) {
                logger.info("Maximum depth reached: {}", depth);
                schedule(s, CALCULATE_TRUST_SCORES);
            } else {
                logger.info("Progressing iteration at depth {}", depth + 1);
                schedule(s, LOAD_DECLARATIONS.with("depth", depth + 1));
            }

        }

    },

    CALCULATE_TRUST_SCORES {

        // DB read from: accounts, trustPaths
        // DB write to:  accounts

        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running CALCULATE_TRUST_SCORES task");

            while (true) {
                Document d = getOne(s, "accounts_loading", new Document("status", expanded.getValue()));
                if (d == null) {
                    break;
                }

                double ratio = 0.0;
                Map<String, Boolean> seenPathElements = new HashMap<>();
                int pathCount = 0;
                try (MongoCursor<Document> trustPaths = collection("trustPaths_loading").find(s,
                        new Document("agent", d.get("agent").toString()).append("pubkey", d.get("pubkey").toString())
                ).sort(orderBy(ascending("depth"), descending("ratio"), ascending("sorthash"))).cursor()) {
                    while (trustPaths.hasNext()) {
                        Document trustPath = trustPaths.next();
                        ratio += trustPath.getDouble("ratio");
                        boolean independentPath = true;
                        String[] pathElements = trustPath.getString("_id").split(" ");
                        // Iterate over path elements, ignoring first (root) and last (this agent/pubkey):
                        for (int i = 1; i < pathElements.length - 1; i++) {
                            String p = pathElements[i];
                            if (seenPathElements.containsKey(p)) {
                                independentPath = false;
                                break;
                            }
                            seenPathElements.put(p, true);
                        }
                        if (independentPath) {
                            pathCount += 1;
                        }
                    }
                }
                double rawQuota = GLOBAL_QUOTA * ratio;
                int quota = (int) rawQuota;
                if (rawQuota < MIN_USER_QUOTA) {
                    quota = MIN_USER_QUOTA;
                } else if (rawQuota > MAX_USER_QUOTA) {
                    quota = MAX_USER_QUOTA;
                }
                set(s, "accounts_loading",
                        d.append("status", processed.getValue())
                                .append("ratio", ratio)
                                .append("pathCount", pathCount)
                                .append("quota", quota)
                );
            }
            logger.info("CALCULATE_TRUST_SCORES complete");
            schedule(s, AGGREGATE_AGENTS);
        }

    },

    AGGREGATE_AGENTS {

        // DB read from: accounts, agents
        // DB write to:  accounts, agents

        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running AGGREGATE_AGENTS task");

            while (true) {
                Document a = getOne(s, "accounts_loading", new Document("status", processed.getValue()));
                if (a == null) {
                    break;
                }

                Document agentId = new Document("agent", a.get("agent").toString()).append("status", processed.getValue());
                int count = 0;
                int pathCountSum = 0;
                double totalRatio = 0.0d;
                // Canonical-name resolution across the agent's approved keys: pick the
                // row with MAX(ratio); ties broken on lex-min name for determinism.
                // Per-(agent, pubkey) name was already chosen by LOAD_ENDORSEMENTS as
                // "latest declaring intro wins"; this layer just folds across keys.
                String chosenName = null;
                double chosenRatio = Double.NEGATIVE_INFINITY;
                try (MongoCursor<Document> agentAccounts = collection("accounts_loading").find(s, agentId).cursor()) {
                    while (agentAccounts.hasNext()) {
                        Document d = agentAccounts.next();
                        count++;
                        pathCountSum += d.getInteger("pathCount");
                        double r = d.getDouble("ratio");
                        totalRatio += r;
                        String n = d.getString("name");
                        if (n != null && (r > chosenRatio
                                          || (r == chosenRatio && (chosenName == null || n.compareTo(chosenName) < 0)))) {
                            chosenName = n;
                            chosenRatio = r;
                        }
                    }
                }
                collection("accounts_loading").updateMany(s, agentId, new Document("$set",
                        new DbEntryWrapper(aggregated).getDocument()));
                insert(s, "agents_loading",
                        agentId.append("accountCount", count)
                                .append("avgPathCount", (double) pathCountSum / count)
                                .append("totalRatio", totalRatio)
                                .append("name", chosenName)
                );
            }
            logger.info("AGGREGATE_AGENTS complete");
            schedule(s, ASSIGN_PUBKEYS);

        }

    },

    ASSIGN_PUBKEYS {

        // DB read from: accounts
        // DB write to:  accounts

        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running ASSIGN_PUBKEYS task");
            int approvedCount = 0;
            int contestedCount = 0;

            while (true) {
                Document a = getOne(s, "accounts_loading", new DbEntryWrapper(aggregated).getDocument());
                if (a == null) {
                    break;
                }

                Document pubkeyId = new Document("pubkey", a.get("pubkey").toString());
                if (collection("accounts_loading").countDocuments(s, pubkeyId) == 1) {
                    collection("accounts_loading").updateMany(s, pubkeyId,
                            new Document("$set", new DbEntryWrapper(approved).getDocument()));
                    approvedCount++;
                } else {
                    // TODO At the moment all get marked as 'contested'; implement more nuanced algorithm
                    logger.debug("Pubkey {} is claimed by multiple accounts; marking contested", pubkeyId.getString("pubkey"));
                    collection("accounts_loading").updateMany(s, pubkeyId, new Document("$set",
                            new DbEntryWrapper(contested).getDocument()));
                    contestedCount++;
                }
            }
            logger.info("ASSIGN_PUBKEYS complete: {} approved, {} contested", approvedCount, contestedCount);
            schedule(s, DETERMINE_UPDATES);

        }

    },

    DETERMINE_UPDATES {

        // DB read from: accounts
        // DB write to:  accounts

        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running DETERMINE_UPDATES task");

            // TODO Handle contested accounts properly:
            for (Document d : collection("accounts_loading").find(
                    new DbEntryWrapper(approved).getDocument())) {
                // TODO Consider quota too:
                Document accountId = new Document("agent", d.get("agent").toString()).append("pubkey", d.get("pubkey").toString());
                if (collection(Collection.ACCOUNTS.toString()) == null || !has(s, Collection.ACCOUNTS.toString(),
                        accountId.append("status", loaded.getValue()))) {
                    set(s, "accounts_loading", d.append("status", toLoad.getValue()));
                } else {
                    set(s, "accounts_loading", d.append("status", loaded.getValue()));
                }
            }
            logger.info("DETERMINE_UPDATES complete");
            schedule(s, FINALIZE_TRUST_STATE);

        }

    },

    FINALIZE_TRUST_STATE {
        // We do this is a separate task/transaction, because if we do it at the beginning of RELEASE_DATA, that task hangs and cannot
        // properly re-run (as some renaming outside of transactions will have taken place).
        public void run(ClientSession s, Document taskDoc) {
            logger.info("Running FINALIZE_TRUST_STATE task");
            String newTrustStateHash = RegistryDB.calculateTrustStateHash(s);
            String previousTrustStateHash = (String) getValue(s, Collection.SERVER_INFO.toString(), "trustStateHash");  // may be null
            logger.info("Computed new trust state hash {} (previous: {})", newTrustStateHash, previousTrustStateHash);
            setValue(s, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate", ZonedDateTime.now().toString());

            schedule(s, RELEASE_DATA.with("newTrustStateHash", newTrustStateHash).append("previousTrustStateHash", previousTrustStateHash));
        }

    },

    RELEASE_DATA {

        private static final int TRUST_STATE_SNAPSHOT_RETENTION = 100;

        public void run(ClientSession s, Document taskDoc) {
            ServerStatus status = getServerStatus(s);
            logger.info("Running RELEASE_DATA task (current status: {})", status);

            String newTrustStateHash = taskDoc.get("newTrustStateHash").toString();
            String previousTrustStateHash = taskDoc.getString("previousTrustStateHash");  // may be null

            // Renaming collections is run outside of a transaction, but is idempotent operation, so can safely be retried if task fails:
            logger.debug("Renaming loading collections into live collections");
            rename("accounts_loading", Collection.ACCOUNTS.toString());
            rename("trustPaths_loading", "trustPaths");
            rename("agents_loading", Collection.AGENTS.toString());
            rename("endorsements_loading", "endorsements");

            if (previousTrustStateHash == null || !previousTrustStateHash.equals(newTrustStateHash)) {
                logger.info("Trust state changed ({} -> {}); recording new state and snapshot", previousTrustStateHash, newTrustStateHash);
                increaseStateCounter(s);
                setValue(s, Collection.SERVER_INFO.toString(), "trustStateHash", newTrustStateHash);
                Object trustStateCounter = getValue(s, Collection.SERVER_INFO.toString(), "trustStateCounter");
                insert(s, "debug_trustPaths", new Document()
                        .append("trustStateTxt", DebugPage.getTrustPathsTxt(s))
                        .append("trustStateHash", newTrustStateHash)
                        .append("trustStateCounter", trustStateCounter)
                );

                // Structured hash-keyed snapshot for consumer mirroring (#107).
                // Reads the accounts collection just renamed from accounts_loading above (:697).
                List<Document> snapshotAccounts = new ArrayList<>();
                for (Document a : collection(Collection.ACCOUNTS.toString()).find(s)) {
                    String pubkey = a.getString("pubkey");
                    if ("$".equals(pubkey)) {
                        continue;
                    }
                    snapshotAccounts.add(new Document()
                            .append("pubkey", pubkey)
                            .append("agent", a.getString("agent"))
                            .append("name", a.getString("name"))
                            .append("nameCreatedAt", a.get("nameCreatedAt"))
                            .append("introNanopub", a.getString("introNanopub"))
                            .append("status", a.getString("status"))
                            .append("depth", a.get("depth"))
                            .append("pathCount", a.get("pathCount"))
                            .append("ratio", a.get("ratio"))
                            .append("quota", a.get("quota")));
                }
                Document snapshot = new Document()
                        .append("_id", newTrustStateHash)
                        .append("trustStateCounter", trustStateCounter)
                        .append("createdAt", ZonedDateTime.now().toString())
                        .append("accounts", snapshotAccounts);
                collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).replaceOne(
                        s,
                        new Document("_id", newTrustStateHash),
                        snapshot,
                        new ReplaceOptions().upsert(true));
                logger.debug("Saved trust state snapshot {} with {} account(s)", newTrustStateHash, snapshotAccounts.size());

                // Prune beyond retention: collect _ids of snapshots past the Nth most recent, delete them.
                // trustStateCounter is monotonically increasing (see increaseStateCounter above), so ordering is well-defined.
                List<Object> toPrune = new ArrayList<>();
                try (MongoCursor<Document> stale = collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                        .find(s)
                        .sort(descending("trustStateCounter"))
                        .skip(TRUST_STATE_SNAPSHOT_RETENTION)
                        .projection(new Document("_id", 1))
                        .cursor()) {
                    while (stale.hasNext()) {
                        toPrune.add(stale.next().get("_id"));
                    }
                }
                if (!toPrune.isEmpty()) {
                    logger.info("Pruning {} trust state snapshot(s) beyond retention limit of {}", toPrune.size(), TRUST_STATE_SNAPSHOT_RETENTION);
                    collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).deleteMany(
                            s, new Document("_id", new Document("$in", toPrune)));
                }
            } else {
                logger.info("Trust state unchanged ({}); skipping snapshot and pruning", newTrustStateHash);
            }

            if (status == coreLoading) {
                logger.info("Server status transitioning coreLoading -> coreReady");
                setServerStatus(s, coreReady);
            } else {
                logger.info("Server status transitioning {} -> ready", status);
                setServerStatus(s, ready);
            }

            // Run update after 1h:
            logger.debug("RELEASE_DATA complete; scheduling next UPDATE in 1h");
            schedule(s, UPDATE.withDelay(60 * 60 * 1000));
        }

    },

    UPDATE {
        public void run(ClientSession s, Document taskDoc) {
            ServerStatus status = getServerStatus(s);
            if (status == ready || status == coreReady) {
                logger.info("Server status {} eligible for update; transitioning to updating and scheduling INIT_COLLECTIONS", status);
                setServerStatus(s, updating);
                schedule(s, INIT_COLLECTIONS);
            } else {
                logger.info("Postponing update; currently in status {}", status);
                schedule(s, UPDATE.withDelay(10 * 60 * 1000));
            }

        }

    },

    LOAD_FULL {
        public void run(ClientSession s, Document taskDoc) {
            logger.debug("LOAD_FULL invoked; taskDoc={}", taskDoc);

            if ("false".equals(System.getenv("REGISTRY_PERFORM_FULL_LOAD"))) {
                logger.info("REGISTRY_PERFORM_FULL_LOAD=false; skipping full load");
                return;
            }

            ServerStatus status = getServerStatus(s);
            logger.debug("Server status check for LOAD_FULL: status={}", status);
            if (status != coreReady && status != ready && status != updating) {
                long delay = 1000;
                logger.info("Server status={} is not eligible for full load (expected coreReady/ready/updating); deferring and retrying in {}ms", status, delay);
                schedule(s, LOAD_FULL.withDelay(delay));
                return;
            }

            Document a = getOne(s, Collection.ACCOUNTS.toString(), new DbEntryWrapper(toLoad).getDocument());
            if (a == null) {
                logger.info("No accounts left with status={}; full load pass complete", toLoad);
                if (status == coreReady) {
                    logger.info("Server status transitioning coreReady -> ready; full load finished");
                    setServerStatus(s, ready);
                }
                long delay = 100;
                logger.info("Scheduling optional loading checks (RUN_OPTIONAL_LOAD) in {}ms", delay);
                schedule(s, RUN_OPTIONAL_LOAD.withDelay(delay));
            } else {
                final String ph = a.getString("pubkey");
                boolean quotaReached = false;
                if (!ph.equals("$")) {
                    if (!AgentFilter.isAllowed(s, ph)) {
                        logger.info("Pubkey {} is not covered by the agent filter; marking account as skipped", ph);
                        set(s, Collection.ACCOUNTS.toString(), a.append("status", skipped.getValue()));
                        schedule(s, LOAD_FULL.withDelay(100));
                        return;
                    }
                    if (AgentFilter.isOverQuota(s, ph)) {
                        logger.info("Pubkey {} is already over quota; skipping load, marking account as capped", ph);
                        quotaReached = true;
                    } else {
                        long startTime = System.nanoTime();
                        AtomicLong totalLoaded = new AtomicLong(0);

                        // Load per covered type (or "$" if no restriction) with checksum skip-ahead
                        for (String typeHash : getLoadTypeHashes(s, ph)) {
                            logger.debug("Pubkey {}: starting load for typeHash={}", ph, typeHash);
                            String checksums = buildChecksumFallbacks(s, ph, typeHash);
                            logger.debug("Pubkey {}, typeHash={}: checksum fallbacks={}", ph, typeHash, checksums);
                            try (var stream = NanopubLoader.retrieveNanopubsFromPeers(typeHash, ph, checksums)) {
                                NanopubLoader.loadStreamInParallel(stream, np -> {
                                    if (!CoverageFilter.isCovered(np)) {
                                        logger.debug("Pubkey {}: nanopub {} not covered; skipping", ph, np);
                                        return;
                                    }
                                    try (ClientSession ws = RegistryDB.getClient().startSession()) {
                                        if (!AgentFilter.isOverQuota(ws, ph)) {
                                            loadNanopub(ws, np, ph, "$");
                                            totalLoaded.incrementAndGet();
                                        } else {
                                            logger.debug("Pubkey {} hit quota mid-stream; skipping nanopub {}", ph, np);
                                        }
                                    }
                                });
                            }
                            logger.debug("Pubkey {}: finished load for typeHash={}", ph, typeHash);
                        }

                        double timeSeconds = (System.nanoTime() - startTime) * 1e-9;
                        logger.info("Pubkey {}: loaded {} nanopubs in {}s ({} np/s)",
                                ph, totalLoaded.get(), String.format("%.2f", timeSeconds),
                                String.format("%.2f", totalLoaded.get() / timeSeconds));

                        if (AgentFilter.isOverQuota(s, ph)) {
                            logger.info("Pubkey {} reached quota during this load; marking account as capped", ph);
                            quotaReached = true;
                        }
                    }
                } else {
                    logger.debug("Account pubkey is '$' (unrestricted); no per-pubkey quota/filter checks applied");
                }

                Document l = getOne(s, "lists", new Document().append("pubkey", ph).append("type", "$"));
                if (l != null) {
                    logger.debug("Pubkey {}: marking matching list entry as loaded", ph);
                    set(s, "lists", l.append("status", loaded.getValue()));
                }
                EntryStatus accountStatus = quotaReached ? capped : loaded;
                int effectiveQuota = AgentFilter.getQuota(s, ph);
                if (effectiveQuota >= 0) {
                    logger.debug("Pubkey {}: recording effective quota={}", ph, effectiveQuota);
                    a.append("quota", effectiveQuota);
                }
                logger.info("Pubkey {}: account load complete, status={}", ph, accountStatus);
                set(s, Collection.ACCOUNTS.toString(), a.append("status", accountStatus.getValue()));

                schedule(s, LOAD_FULL.withDelay(100));
            }
        }

        @Override
        public boolean runAsTransaction() {
            // TODO Make this a transaction once we connect to other Nanopub Registry instances:
            return false;
        }

    },

    RUN_OPTIONAL_LOAD {

        private static final int BATCH_SIZE = Integer.parseInt(
                Utils.getEnv("REGISTRY_OPTIONAL_LOAD_BATCH_SIZE", "100"));

        public void run(ClientSession s, Document taskDoc) {
            if ("false".equals(System.getenv("REGISTRY_ENABLE_OPTIONAL_LOAD"))) {
                schedule(s, CHECK_NEW.withDelay(500));
                return;
            }

            AtomicLong totalLoaded = new AtomicLong(0);

            // Phase 1: Process encountered intro lists (core loading)
            while (totalLoaded.get() < BATCH_SIZE) {
                Document di = getOne(s, "lists", new Document("type", INTRO_TYPE_HASH).append("status", encountered.getValue()));
                if (di == null) {
                    break;
                }

                final String pubkeyHash = di.getString("pubkey");
                Validate.notNull(pubkeyHash);
                logger.info("Optional core loading: {}", pubkeyHash);

                String introChecksums = buildChecksumFallbacks(s, pubkeyHash, INTRO_TYPE_HASH);
                try (var stream = NanopubLoader.retrieveNanopubsFromPeers(INTRO_TYPE_HASH, pubkeyHash, introChecksums)) {
                    NanopubLoader.loadStreamInParallel(stream, np -> {
                        try (ClientSession ws = RegistryDB.getClient().startSession()) {
                            loadNanopub(ws, np, pubkeyHash, INTRO_TYPE);
                            totalLoaded.incrementAndGet();
                        }
                    });
                }
                set(s, "lists", di.append("status", loaded.getValue()));

                String endorseChecksums = buildChecksumFallbacks(s, pubkeyHash, ENDORSE_TYPE_HASH);
                try (var stream = NanopubLoader.retrieveNanopubsFromPeers(ENDORSE_TYPE_HASH, pubkeyHash, endorseChecksums)) {
                    NanopubLoader.loadStreamInParallel(stream, np -> {
                        try (ClientSession ws = RegistryDB.getClient().startSession()) {
                            loadNanopub(ws, np, pubkeyHash, ENDORSE_TYPE);
                            totalLoaded.incrementAndGet();
                        }
                    });
                }

                Document de = new Document("pubkey", pubkeyHash).append("type", ENDORSE_TYPE_HASH);
                if (has(s, "lists", de)) {
                    set(s, "lists", de.append("status", loaded.getValue()));
                } else {
                    insert(s, "lists", de.append("status", loaded.getValue()));
                }

                Document df = new Document("pubkey", pubkeyHash).append("type", "$");
                if (!has(s, "lists", df)) {
                    insert(s, "lists", df.append("status", encountered.getValue()));
                }
            }

            // Phase 2: Process encountered full lists (if budget remains)
            while (totalLoaded.get() < BATCH_SIZE) {
                Document df = getOne(s, "lists", new Document("type", "$").append("status", encountered.getValue()));
                if (df == null) {
                    break;
                }

                final String pubkeyHash = df.getString("pubkey");
                logger.info("Optional full loading: {}", pubkeyHash);

                // Load per covered type (or "$" if no restriction) with checksum skip-ahead
                for (String typeHash : getLoadTypeHashes(s, pubkeyHash)) {
                    String checksums = buildChecksumFallbacks(s, pubkeyHash, typeHash);
                    try (var stream = NanopubLoader.retrieveNanopubsFromPeers(typeHash, pubkeyHash, checksums)) {
                        NanopubLoader.loadStreamInParallel(stream, np -> {
                            if (!CoverageFilter.isCovered(np)) {
                                return;
                            }
                            try (ClientSession ws = RegistryDB.getClient().startSession()) {
                                loadNanopub(ws, np, pubkeyHash, "$");
                                totalLoaded.incrementAndGet();
                            }
                        });
                    }
                }

                set(s, "lists", df.append("status", loaded.getValue()));

                // Backfill nanopubs stored locally during the transitional period (i.e. before
                // the $ list was loaded). Such nanopubs were stored in the nanopubs collection by
                // simpleLoad() but never added to listEntries; add them to the $ list now.
                logger.info("Backfilling locally stored nanopubs for pubkey: {}", pubkeyHash);
                try (MongoCursor<Document> npCursor = collection(Collection.NANOPUBS.toString())
                        .find(s, new Document("pubkey", pubkeyHash)).cursor()) {
                    while (npCursor.hasNext()) {
                        String fullId = npCursor.next().getString("fullId");
                        if (fullId == null) {
                            continue;
                        }
                        try (ClientSession ws = RegistryDB.getClient().startSession()) {
                            Nanopub np = NanopubLoader.retrieveLocalNanopub(ws, fullId);
                            if (np != null && CoverageFilter.isCovered(np)) {
                                loadNanopub(ws, np, pubkeyHash, "$");
                                totalLoaded.incrementAndGet();
                            }
                        } catch (Exception ex) {
                            logger.info("Error backfilling nanopub {}: {}", fullId, ex.getMessage());
                        }
                    }
                }
            }

            if (totalLoaded.get() > 0) {
                logger.info("Optional load batch completed: {} nanopubs across multiple pubkeys", totalLoaded.get());
            }

            if (prioritizeAllPubkeys()) {
                // Check if there are more pubkeys waiting to be processed
                boolean moreWork = has(s, "lists", new Document("type", INTRO_TYPE_HASH).append("status", encountered.getValue()))
                                   || has(s, "lists", new Document("type", "$").append("status", encountered.getValue()));
                if (moreWork) {
                    // Continue processing without a full CHECK_NEW cycle in between.
                    // CHECK_NEW will run naturally once all encountered lists are processed.
                    schedule(s, RUN_OPTIONAL_LOAD.withDelay(10));
                } else {
                    schedule(s, CHECK_NEW.withDelay(500));
                }
            } else {
                // Throttled: yield to CHECK_NEW after each batch to prioritize approved pubkeys
                schedule(s, CHECK_NEW.withDelay(500));
            }
        }

    },

    CHECK_NEW {
        public void run(ClientSession s, Document taskDoc) {
            logger.debug("Running CHECK_NEW task: checking peers and legacy source for new nanopubs");

            logger.debug("Checking peers for new nanopubs");
            RegistryPeerConnector.checkPeers(s);
            // Keep legacy connection during transition period:
            logger.debug("Checking legacy connector for new nanopubs");
            LegacyConnector.checkForNewNanopubs(s);
            // TODO Somehow throttle the loading of such potentially non-approved nanopubs

            long delay = 100;
            logger.debug("CHECK_NEW complete; scheduling LOAD_FULL with {}ms delay", delay);
            schedule(s, LOAD_FULL.withDelay(delay));
        }

        @Override
        public boolean runAsTransaction() {
            // Peer sync includes long-running streaming fetches that would exceed
            // MongoDB's transaction timeout; each operation is individually safe.
            return false;
        }

    };

    private static final Logger logger = LoggerFactory.getLogger(Task.class);

    public abstract void run(ClientSession s, Document taskDoc) throws Exception;

    public boolean runAsTransaction() {
        return true;
    }

    Document asDocument() {
        return withDelay(0L);
    }

    private Document withDelay(long delay) {
        // TODO Rename "not-before" to "notBefore" for consistency with other field names
        return new Document()
                .append("not-before", System.currentTimeMillis() + delay)
                .append("action", name());
    }

    private Document with(String key, Object value) {
        return asDocument().append(key, value);
    }

    private static boolean prioritizeAllPubkeys() {
        return "true".equals(System.getenv("REGISTRY_PRIORITIZE_ALL_PUBKEYS"));
    }

    /**
     * Returns the type hashes to load for a given pubkey. When coverage is unrestricted,
     * returns just "$" (all types in one request). When restricted, returns each covered
     * type hash for per-type fetching with checksum skip-ahead.
     * <p>
     * TODO: Fetching "$" from peers with type restrictions will only return their covered
     * types, not all types. To get full coverage, we'd need to fetch per-type from such peers.
     * Additionally, checksum-based skip-ahead won't work correctly against such peers, because
     * their "$" list has different checksums due to the differing type subset. This means full
     * re-downloads on every cycle. Per-type fetching would solve both issues.
     */
    private static java.util.List<String> getLoadTypeHashes(ClientSession s, String pubkeyHash) {
        if (CoverageFilter.coversAllTypes()) {
            return java.util.List.of("$");
        }
        return java.util.List.copyOf(CoverageFilter.getCoveredTypeHashes());
    }

    // TODO Move these to setting:
    private static final int MAX_TRUST_PATH_DEPTH = 10;
    private static final double MIN_TRUST_PATH_RATIO = 0.0000000001;
    //private static final double MIN_TRUST_PATH_RATIO = 0.01; // For testing
    private static final int GLOBAL_QUOTA = Integer.parseInt(
            Utils.getEnv("REGISTRY_GLOBAL_QUOTA", "1000000000"));
    private static final int MIN_USER_QUOTA = Integer.parseInt(
            Utils.getEnv("REGISTRY_MIN_USER_QUOTA", "1000"));
    private static final int MAX_USER_QUOTA = Integer.parseInt(
            Utils.getEnv("REGISTRY_MAX_USER_QUOTA", "100000"));

    private static MongoCollection<Document> tasksCollection = collection(Collection.TASKS.toString());

    private static volatile String currentTaskName;
    private static volatile long currentTaskStartTime;

    public static String getCurrentTaskName() {
        return currentTaskName;
    }

    public static long getCurrentTaskStartTime() {
        return currentTaskStartTime;
    }

    /**
     * The super important base entry point!
     */
    static void runTasks() {
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            if (!RegistryDB.isInitialized(s)) {
                schedule(s, INIT_DB); // does not yet execute, only schedules
            }

            logger.info("Task runner started");
            while (true) {
                FindIterable<Document> taskResult = tasksCollection.find(s).sort(ascending("not-before"));
                Document taskDoc = taskResult.first();
                long sleepTime = 10;
                if (taskDoc != null && taskDoc.getLong("not-before") < System.currentTimeMillis()) {
                    Task task = valueOf(taskDoc.getString("action"));
                    Object taskId = taskDoc.getOrDefault("_id", null);
                    logger.info("Picked task to run: {} (docId={})", task.name(), taskId);

                    if (task.runAsTransaction()) {
                        try {
                            s.startTransaction();
                            logger.debug("Transaction started for task {}", task.name());
                            runTask(task, taskDoc);
                            s.commitTransaction();
                            logger.info("Transaction committed for task {}", task.name());
                        } catch (Exception ex) {
                            logger.warn("Transactional task {} failed, aborting: {}", task.name(), ex.getMessage(), ex);
                            abortTransaction(s, ex.getMessage());
                            logger.info("Transaction aborted for task {}", task.name());
                            sleepTime = 1000;
                        } finally {
                            cleanTransactionWithRetry(s);
                        }
                    } else {
                        try {
                            runTask(task, taskDoc);
                        } catch (Exception ex) {
                            logger.warn("Non-transactional task {} failed: {}", task.name(), ex.getMessage(), ex);
                        }
                    }
                }
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ex) {
                    // ignore
                    logger.debug("Task runner sleep interrupted");
                }
            }
        }
    }

    static void runTask(Task task, Document taskDoc) throws Exception {
        currentTaskName = task.name();
        currentTaskStartTime = System.currentTimeMillis();
        logger.info("Starting task {} with request: {}", currentTaskName, taskDoc);

        try (ClientSession s = RegistryDB.getClient().startSession()) {
            task.run(s, taskDoc);
            long duration = System.currentTimeMillis() - currentTaskStartTime;
            tasksCollection.deleteOne(s, eq("_id", taskDoc.get("_id")));
            logger.info("Completed and removed from queue task {} in {} ms", currentTaskName, duration);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - currentTaskStartTime;
            logger.error("Task {} failed after {} ms: {}", currentTaskName, duration, ex.getMessage(), ex);
            throw ex;
        } finally {
            currentTaskName = null;
        }
    }

    public static void abortTransaction(ClientSession mongoSession, String message) {
        boolean successful = false;
        while (!successful) {
            try {
                if (mongoSession.hasActiveTransaction()) {
                    logger.info("Attempting to abort transaction: {}", message);
                    mongoSession.abortTransaction();
                }
                successful = true;
                logger.debug("abortTransaction succeeded");
            } catch (Exception ex) {
                logger.warn("abortTransaction attempt failed: {}", ex.getMessage(), ex);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    logger.debug("abortTransaction sleep interrupted");
                }
            }
        }
    }

    public synchronized static void cleanTransactionWithRetry(ClientSession mongoSession) {
        boolean successful = false;
        while (!successful) {
            try {
                if (mongoSession.hasActiveTransaction()) {
                    mongoSession.abortTransaction();
                    logger.debug("abortTransaction during cleanup succeeded");
                }
                successful = true;
                logger.debug("Transaction cleanup completed");
            } catch (Exception ex) {
                logger.warn("Transaction cleanup failed: {}", ex.getMessage(), ex);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    logger.debug("cleanTransactionWithRetry sleep interrupted");
                }
            }
        }
    }

    private static IntroNanopub getAgentIntro(ClientSession mongoSession, String nanopubId) {
        IntroNanopub agentIntro = new IntroNanopub(NanopubLoader.retrieveNanopub(mongoSession, nanopubId));
        if (agentIntro.getUser() == null) {
            logger.debug("Nanopub {} is not a valid agent intro (no declared user); discarding", nanopubId);
            return null;
        }
        loadNanopub(mongoSession, agentIntro.getNanopub());
        return agentIntro;
    }


    private static void setServerStatus(ClientSession mongoSession, ServerStatus status) {
        setValue(mongoSession, Collection.SERVER_INFO.toString(), "status", status.toString());
    }

    private static ServerStatus getServerStatus(ClientSession mongoSession) {
        Object status = getValue(mongoSession, Collection.SERVER_INFO.toString(), "status");
        if (status == null) {
            throw new RuntimeException("Illegal DB state: serverInfo status unavailable");
        }
        return ServerStatus.valueOf(status.toString());
    }

    private static void schedule(ClientSession mongoSession, Task task) {
        schedule(mongoSession, task.asDocument());
    }

    private static void schedule(ClientSession mongoSession, Document taskDoc) {
        logger.info("Scheduling task: {}", taskDoc.getString("action"));
        tasksCollection.insertOne(mongoSession, taskDoc);
    }

}
