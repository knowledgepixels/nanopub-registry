package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.EntryStatus.*;
import static com.knowledgepixels.registry.NanopubLoader.ENDORSE_TYPE;
import static com.knowledgepixels.registry.NanopubLoader.ENDORSE_TYPE_HASH;
import static com.knowledgepixels.registry.NanopubLoader.INTRO_TYPE;
import static com.knowledgepixels.registry.NanopubLoader.INTRO_TYPE_HASH;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.getOne;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.increaseStateCounter;
import static com.knowledgepixels.registry.RegistryDB.insert;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.rename;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.knowledgepixels.registry.RegistryDB.setValue;
import static com.knowledgepixels.registry.ServerStatus.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Sorts.orderBy;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang.Validate;
import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.index.NanopubIndex;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.services.ApiResponse;
import org.nanopub.extra.services.ApiResponseEntry;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;

import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public enum Task implements Serializable {

	INIT_DB {

		public void run(ClientSession s, Document taskDoc) {
			setServerStatus(s, launching);

			increaseStateCounter(s);
			if (RegistryDB.isInitialized(s)) throw new RuntimeException("DB already initialized");
			setValue(s, "serverInfo", "setupId", Math.abs(new Random().nextLong()));
			setValue(s, "serverInfo", "testInstance", "true".equals(System.getenv("REGISTRY_TEST_INSTANCE")));
			schedule(s, LOAD_CONFIG);
		}

	},

	LOAD_CONFIG {

		public void run(ClientSession s, Document taskDoc) {
			if (getServerStatus(s) != launching) {
				throw new RuntimeException("Illegal status for this task: " + getServerStatus(s));
			}

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				setValue(s, "serverInfo", "coverageTypes", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				setValue(s, "serverInfo", "coverageAgents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			schedule(s, LOAD_SETTING);
		}

	},

	LOAD_SETTING {

		public void run(ClientSession s, Document taskDoc) throws Exception {
			if (getServerStatus(s) != launching) {
				throw new RuntimeException("Illegal status for this task: " + getServerStatus(s));
			}

			NanopubSetting settingNp = getSetting();
			String settingId = TrustyUriUtils.getArtifactCode(settingNp.getNanopub().getUri().stringValue());
			setValue(s, "setting", "original", settingId);
			setValue(s, "setting", "current", settingId);
			loadNanopub(s, settingNp.getNanopub());
			List<Document> bootstrapServices = new ArrayList<>();
			for (IRI i : settingNp.getBootstrapServices()) {
				bootstrapServices.add(new Document("_id", i.stringValue()));
			}
			// potentially currently hardcoded in the nanopub lib
			setValue(s, "setting", "bootstrap-services", bootstrapServices);

			if (!"false".equals(System.getenv("REGISTRY_PERFORM_FULL_LOAD"))) {
				schedule(s, LOAD_FULL.withDelay(60 * 1000));
			}

			setServerStatus(s, coreLoading);
			schedule(s, INIT_COLLECTIONS);
		}

	},

	INIT_COLLECTIONS {

		// DB read from:
		// DB write to:  trustPaths, endorsements, accounts
		// This state is periodically executed

		public void run(ClientSession s, Document taskDoc) throws Exception {
			if (getServerStatus(s) != coreLoading && getServerStatus(s) != updating) {
				throw new RuntimeException("Illegal status for this task: " + getServerStatus(s));
			}

			RegistryDB.initLoadingCollections(s);

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

			NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubLoader.retrieveNanopub(s, getSetting().getAgentIntroCollection().stringValue()));
			loadNanopub(s, agentIndex);
			for (IRI el : agentIndex.getElements()) {
				String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());
				Validate.notNull(declarationAc);

				insert(s, "endorsements_loading",
						new Document("agent", "$")
							.append("pubkey", "$")
							.append("endorsedNanopub", declarationAc)
							.append("source", getValue(s, "setting", "current").toString())
							.append("status", toRetrieve.getValue())

					);

			}
			insert(s, "accounts_loading",
					new Document("agent", "$")
						.append("pubkey", "$")
						.append("status", visited.getValue())
						.append("depth", 0)
				);

			System.err.println("Starting iteration at depth 0");
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

			if (has(s, "endorsements_loading", new Document("status", toRetrieve.getValue()))) {
				Document d = getOne(s, "endorsements_loading",
						new DbEntryWrapper(toRetrieve).getDocument());

				IntroNanopub agentIntro = getAgentIntro(s, d.getString("endorsedNanopub"));
				if (agentIntro != null) {
					String agentId = agentIntro.getUser().stringValue();

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
							insert(s, "trustEdges", trustEdge.append("invalidated", invalidated));
						}

						Document agent = new Document("agent", agentId).append("pubkey", agentPubkey);
						if (!has(s, "accounts_loading", agent)) {
							insert(s, "accounts_loading", agent.append("status", seen.getValue()).append("depth", depth));
						}
					}

					set(s, "endorsements_loading", d.append("status", retrieved.getValue()));
				} else {
					set(s, "endorsements_loading", d.append("status", discarded.getValue()));
				}

				schedule(s, LOAD_DECLARATIONS.with("depth", depth));

			} else {
				schedule(s, EXPAND_TRUST_PATHS.with("depth", depth));
			}
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

			Document d = getOne(s, "accounts_loading",
					new Document("status", visited.getValue())
						.append("depth", depth - 1)
				);

			if (d != null) {
	
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

					Map<String,Document> newPaths = new HashMap<>();
					Map<String,Set<String>> pubkeySets = new HashMap<>();
					String currentSetting = getValue(s, "setting", "current").toString();

					MongoCursor<Document> edgeCursor = get(s, "trustEdges",
							new Document("fromAgent", agentId)
								.append("fromPubkey", pubkeyHash)
								.append("invalidated", false)
						);
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
						if (!pubkeySets.containsKey(agent)) pubkeySets.put(agent, new HashSet<>());
						pubkeySets.get(agent).add(pubkey);
					}
					for (String pathId : newPaths.keySet()) {
						Document pd = newPaths.get(pathId);
						// first divide by agents; then for each agent, divide by number of pubkeys:
						double newRatio = (trustPath.getDouble("ratio") * 0.9) / pubkeySets.size() / pubkeySets.get(pd.getString("agent")).size();
						insert(s, "trustPaths_loading", pd.append("ratio", newRatio));
					}
					set(s, "trustPaths_loading", trustPath.append("type", "primary"));
					set(s, "accounts_loading", d.append("status", expanded.getValue()));
				}
				schedule(s, EXPAND_TRUST_PATHS.with("depth", depth));
	
			} else {

				schedule(s, LOAD_CORE.with("depth", depth).append("load-count", 0));

			}
			
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

			if (trustPath == null) {
				schedule(s, FINISH_ITERATION.with("depth", depth).append("load-count", loadCount));
			} else if (trustPath.getDouble("ratio") < MIN_TRUST_PATH_RATIO) {
				set(s, "accounts_loading", agentAccount.append("status", skipped.getValue()));
				Document d = new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH);
				if (!has(s, "lists", d)) {
					insert(s, "lists", d.append("status", encountered.getValue()));
				}
				schedule(s, LOAD_CORE.with("depth", depth).append("load-count", loadCount + 1));
			} else {
				// TODO check intro limit
				Document introList = new Document()
						.append("pubkey", pubkeyHash)
						.append("type", INTRO_TYPE_HASH)
						.append("status", loading.getValue());
				if (!has(s, "lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH))) {
					insert(s, "lists", introList);
				}

				try (var stream = NanopubLoader.retrieveNanopubsFromPeers(INTRO_TYPE_HASH, pubkeyHash)) {
					stream.forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(s, m.getNanopub(), pubkeyHash, INTRO_TYPE);
					});
				}

				set(s, "lists", introList.append("status", loaded.getValue()));

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
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						Nanopub nanopub = m.getNanopub();
						loadNanopub(s, nanopub, pubkeyHash, ENDORSE_TYPE);
						String sourceNpId = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
						Validate.notNull(sourceNpId);
						for (Statement st : nanopub.getAssertion()) {
							if (!st.getPredicate().equals(Utils.APPROVES_OF)) continue;
							if (!(st.getObject() instanceof IRI)) continue;
							if (!agentId.equals(st.getSubject().stringValue())) continue;
							String objStr = st.getObject().stringValue();
							if (!TrustyUriUtils.isPotentialTrustyUri(objStr)) continue;
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

				set(s, "lists", endorseList.append("status", loaded.getValue()));

				Document df = new Document("pubkey", pubkeyHash).append("type", "$");
				if (!has(s, "lists", df)) insert(s, "lists",
						df.append("status", encountered.getValue()));

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
				System.err.println("No new cores loaded; finishing iteration");
				schedule(s, CALCULATE_TRUST_SCORES);
			} else if (depth == MAX_TRUST_PATH_DEPTH) {
				System.err.println("Maximum depth reached: " + depth);
				schedule(s, CALCULATE_TRUST_SCORES);
			} else {
				System.err.println("Progressing iteration at depth " + (depth + 1));
				schedule(s, LOAD_DECLARATIONS.with("depth", depth + 1));
			}
			
		}
		
	},

	CALCULATE_TRUST_SCORES {

		// DB read from: accounts, trustPaths
		// DB write to:  accounts

		public void run(ClientSession s, Document taskDoc) {

			Document d = getOne(s, "accounts_loading", new Document("status", expanded.getValue()));

			if (d == null) {
				schedule(s, AGGREGATE_AGENTS);
			} else {
				double ratio = 0.0;
				Map<String,Boolean> seenPathElements = new HashMap<>();
				int pathCount = 0;
				MongoCursor<Document> trustPaths = collection("trustPaths_loading").find(s,
						new Document("agent", d.get("agent").toString()).append("pubkey", d.get("pubkey").toString())
					).sort(orderBy(ascending("depth"), descending("ratio"), ascending("sorthash"))).cursor();
				while (trustPaths.hasNext()) {
					Document trustPath = trustPaths.next();
					ratio += trustPath.getDouble("ratio");
					boolean independentPath = true;
					String[] pathElements = trustPath.getString("_id").split(" ");
					// Iterate over path elements, ignoring first (root) and last (this agent/pubkey):
					for (int i = 1 ; i < pathElements.length - 1 ; i++) {
						String p = pathElements[i];
						if (seenPathElements.containsKey(p)) {
							independentPath = false;
							break;
						}
						seenPathElements.put(p, true);
					}
					if (independentPath) pathCount += 1;
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
				schedule(s, CALCULATE_TRUST_SCORES);
			}
			
		}
		
	},

	AGGREGATE_AGENTS {

		// DB read from: accounts, agents
		// DB write to:  accounts, agents

		public void run(ClientSession s, Document taskDoc) {

			Document a = getOne(s, "accounts_loading", new Document("status", processed.getValue()));
			if (a == null) {
				schedule(s, ASSIGN_PUBKEYS);
			} else {
				Document agentId = new Document("agent", a.get("agent").toString()).append("status", processed.getValue());
				int count = 0;
				int pathCountSum = 0;
				double totalRatio = 0.0d;
				MongoCursor<Document> agentAccounts = collection("accounts_loading").find(s, agentId).cursor();
				while (agentAccounts.hasNext()) {
					Document d = agentAccounts.next();
					count++;
					pathCountSum += d.getInteger("pathCount");
					totalRatio += d.getDouble("ratio");
				}
				collection("accounts_loading").updateMany(s, agentId, new Document("$set",
						new DbEntryWrapper(aggregated).getDocument()));
				insert(s, "agents_loading",
						agentId.append("accountCount", count)
							.append("avgPathCount", (double) pathCountSum / count)
							.append("totalRatio", totalRatio)
					);
				schedule(s, AGGREGATE_AGENTS);
			}

		}
		
	},

	ASSIGN_PUBKEYS {

		// DB read from: accounts
		// DB write to:  accounts

		public void run(ClientSession s, Document taskDoc) {

			Document a = getOne(s, "accounts_loading", new DbEntryWrapper(aggregated).getDocument());
			if (a == null) {
				schedule(s, DETERMINE_UPDATES);
			} else {
				Document pubkeyId = new Document("pubkey", a.get("pubkey").toString());
				if (collection("accounts_loading").countDocuments(s, pubkeyId) == 1) {
					collection("accounts_loading").updateMany(s, pubkeyId,
							new Document("$set", new DbEntryWrapper(approved).getDocument()));
				} else {
					// TODO At the moment all get marked as 'contested'; implement more nuanced algorithm
					collection("accounts_loading").updateMany(s, pubkeyId, new Document("$set",
							new DbEntryWrapper( contested).getDocument()));
				}
				schedule(s, ASSIGN_PUBKEYS);
			}

		}

	},

	DETERMINE_UPDATES {

		// DB read from: accounts
		// DB write to:  accounts

		public void run(ClientSession s, Document taskDoc) {

			// TODO Handle contested accounts properly:
			for (Document d : collection("accounts_loading").find(
					new DbEntryWrapper(approved).getDocument())) {
				// TODO Consider quota too:
				Document accountId = new Document("agent", d.get("agent").toString()).append("pubkey", d.get("pubkey").toString());
				if (collection("accounts") == null || !has(s, "accounts",
						accountId.append("status", loaded.getValue()))) {
					set(s, "accounts_loading", d.append("status", toLoad.getValue()));
				} else {
					set(s, "accounts_loading", d.append("status", loaded.getValue()));
				}
			}
			schedule(s, FINALIZE_TRUST_STATE);
			
		}
		
	},

	FINALIZE_TRUST_STATE {

		// We do this is a separate task/transaction, because if we do it at the beginning of RELEASE_DATA, that task hangs and cannot
		// properly re-run (as some renaming outside of transactions will have taken place).
		public void run(ClientSession s, Document taskDoc) {
			String newTrustStateHash = RegistryDB.calculateTrustStateHash(s);
			String previousTrustStateHash = (String) getValue(s, "serverInfo", "trustStateHash");  // may be null
			setValue(s, "serverInfo", "lastTrustStateUpdate", ZonedDateTime.now().toString());

			schedule(s, RELEASE_DATA.with("newTrustStateHash", newTrustStateHash).append("previousTrustStateHash", previousTrustStateHash));
		}
		
	},

	RELEASE_DATA {

		public void run(ClientSession s, Document taskDoc) {
			ServerStatus status = getServerStatus(s);

			String newTrustStateHash = taskDoc.get("newTrustStateHash").toString();
			String previousTrustStateHash = taskDoc.getString("previousTrustStateHash");  // may be null

			// Renaming collections is run outside of a transaction, but is idempotent operation, so can safely be retried if task fails:
			rename("accounts_loading", "accounts");
			rename("trustPaths_loading", "trustPaths");
			rename("agents_loading", "agents");
			rename("endorsements_loading", "endorsements");

			if (previousTrustStateHash == null || !previousTrustStateHash.equals(newTrustStateHash)) {
				increaseStateCounter(s);
				setValue(s, "serverInfo", "trustStateHash", newTrustStateHash);
				insert(s, "debug_trustPaths", new Document()
						.append("trustStateTxt", DebugPage.getTrustPathsTxt(s))
						.append("trustStateHash", newTrustStateHash)
						.append("trustStateCounter", getValue(s, "serverInfo", "trustStateCounter"))
					);
			}

			if (status == coreLoading) {
				setServerStatus(s, coreReady);
			} else {
				setServerStatus(s, ready);
			}

			// Run update after 1h:
			schedule(s, UPDATE.withDelay(60 * 60 * 1000));
		}
		
	},

	UPDATE {

		public void run(ClientSession s, Document taskDoc) {

			ServerStatus status = getServerStatus(s);
			if (status == ready) {
				setServerStatus(s, updating);
				schedule(s, INIT_COLLECTIONS);
			} else {
				System.err.println("Postponing update; currently in status " + status);
				schedule(s, UPDATE.withDelay(10 * 60 * 1000));
			}
			
		}
		
	},

	LOAD_FULL {

		public void run(ClientSession s, Document taskDoc) {
			if ("false".equals(System.getenv("REGISTRY_PERFORM_FULL_LOAD"))) return;

			ServerStatus status = getServerStatus(s);
			if (status != coreReady && status != ready) {
				System.err.println("Server currently not ready; checking again later");
				schedule(s, LOAD_FULL.withDelay(60 * 1000));
				return;
			}

			Document a = getOne(s, "accounts", new DbEntryWrapper(toLoad).getDocument());
			if (a == null) {
				System.err.println("Nothing to load");
				if (status == coreReady) {
					System.err.println("Full load finished");
					setServerStatus(s, ready);
				}
				System.err.println("Scheduling optional loading checks");
				schedule(s, CHECK_MORE_PUBKEYS.withDelay(100));
			} else {
				final String ph = a.getString("pubkey");
				if (!ph.equals("$")) {
					try (var stream = NanopubLoader.retrieveNanopubsFromPeers("$", ph)) {
						stream.forEach(m -> {
							if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
							loadNanopub(s, m.getNanopub(), ph, "$");
						});
					}
				}

				Document l = getOne(s, "lists", new Document().append("pubkey", ph).append("type", "$"));
				if (l != null) set(s, "lists", l.append("status", loaded.getValue()));
				set(s, "accounts", a.append("status", loaded.getValue()));

				schedule(s, LOAD_FULL.withDelay(100));
			}
		}

		@Override
		public boolean runAsTransaction() {
			// TODO Make this a transaction once we connect to other Nanopub Registry instances:
			return false;
		}

	},

	CHECK_MORE_PUBKEYS {

		public void run(ClientSession s, Document taskDoc) {
			ApiResponse resp = ApiCache.retrieveResponse("RAWpFps2f4rvpLhFQ-_KiAyphGuXO6YGJLqiW3QBxQQhM/get-all-pubkeys", null);
			for (ApiResponseEntry e : resp.getData()) {
				String pubkeyHash = e.get("pubkeyhash");
				Validate.notNull(pubkeyHash);
				Document d = new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH);
				if (!has(s, "lists", d)) {
					insert(s, "lists", d.append("status", encountered.getValue()));
				}
			}

			schedule(s, RUN_OPTIONAL_LOAD.withDelay(100));
		}
		
	},

	RUN_OPTIONAL_LOAD {

		public void run(ClientSession s, Document taskDoc) {
			Document di = getOne(s, "lists", new Document("type", INTRO_TYPE_HASH).append("status", encountered.getValue()));
			if (di != null) {
				final String pubkeyHash = di.getString("pubkey");
				Validate.notNull(pubkeyHash);
				System.err.println("Optional core loading: " + pubkeyHash);

				try (var stream = NanopubLoader.retrieveNanopubsFromPeers(INTRO_TYPE_HASH, pubkeyHash)) {
					stream.forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(s, m.getNanopub(), pubkeyHash, INTRO_TYPE);
					});
				}
				set(s, "lists", di.append("status", loaded.getValue()));

				try (var stream = NanopubLoader.retrieveNanopubsFromPeers(ENDORSE_TYPE_HASH, pubkeyHash)) {
					stream.forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(s, m.getNanopub(), pubkeyHash, ENDORSE_TYPE);
					});
				}

				Document de = new Document("pubkey", pubkeyHash).append("type", ENDORSE_TYPE_HASH);
				if (has(s, "lists", de)) {
					set(s, "lists", de.append("status", loaded.getValue()));
				} else {
					insert(s, "lists", de.append("status", loaded.getValue()));
				}

				Document df = new Document("pubkey", pubkeyHash).append("type", "$");
				if (!has(s, "lists", df)) insert(s, "lists", df.append("status", encountered.getValue()));

				schedule(s, CHECK_NEW.withDelay(100));
				return;
			}

			Document df = getOne(s, "lists", new Document("type", "$").append("status", encountered.getValue()));
			if (df != null) {
				final String pubkeyHash = df.getString("pubkey");
				System.err.println("Optional full loading: " + pubkeyHash);

				try (var stream = NanopubLoader.retrieveNanopubsFromPeers("$", pubkeyHash)) {
					stream.forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(s, m.getNanopub(), pubkeyHash, "$");
					});
				}

				set(s, "lists", df.append("status", loaded.getValue()));
			}

			schedule(s, CHECK_NEW.withDelay(100));
		}
		
	},

	CHECK_NEW {

		public void run(ClientSession s, Document taskDoc) {
			// TODO Replace this legacy connection with checks at other Nanopub Registries:
			LegacyConnector.checkForNewNanopubs(s);
			// TODO Somehow throttle the loading of such potentially non-approved nanopubs

			schedule(s, LOAD_FULL.withDelay(100));
		}

	};

	public abstract void run(ClientSession s, Document taskDoc) throws Exception;

	public boolean runAsTransaction() {
		return true;
	}

	private Document doc() {
		return withDelay(0l);
	}

	private Document withDelay(long delay) {
		return new Document()
				.append("not-before", System.currentTimeMillis() + delay)
				.append("action", name());
	}

	private Document with(String key, Object value) {
		return doc().append(key, value);
	}

	// TODO Move these to setting:
	private static final int MAX_TRUST_PATH_DEPTH = 10;
	private static final double MIN_TRUST_PATH_RATIO = 0.00000001;
	//private static final double MIN_TRUST_PATH_RATIO = 0.01; // For testing
	private static final int GLOBAL_QUOTA = 100000000;
	private static final int MIN_USER_QUOTA = 100;
	private static final int MAX_USER_QUOTA = 10000;

	private static MongoCollection<Document> tasks = collection("tasks");

	/**
	 * The super important base entry point!
	 */
	static void runTasks() {
		try (ClientSession s = RegistryDB.getClient().startSession()) {
			if (!RegistryDB.isInitialized(s)) {
				schedule(s, INIT_DB); // does not yet execute, only schedules
			}

			while (true) {
				FindIterable<Document> taskResult = tasks.find(s).sort(ascending("not-before"));
				Document taskDoc = taskResult.first();
				long sleepTime = 10;
				if (taskDoc != null && taskDoc.getLong("not-before") < System.currentTimeMillis()) {
					Task task = valueOf(taskDoc.getString("action"));
					System.err.println("Running task: " + task.name());
					if (task.runAsTransaction()) {
						try {
							s.startTransaction();
							System.err.println("Transaction started");
							runTask(task, taskDoc);
							s.commitTransaction();
							System.err.println("Transaction committed");
						} catch (Exception ex) {
							System.err.println("Aborting transaction");
							ex.printStackTrace();
							abortTransaction(s, ex.getMessage());
							System.err.println("Transaction aborted");
							sleepTime = 1000;
						} finally {
							cleanTransactionWithRetry(s);
						}
					} else {
						try {
							runTask(task, taskDoc);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				}
				try {
					Thread.sleep(sleepTime);
				} catch (InterruptedException ex) {
					ex.printStackTrace();
				}
			}
		}
	}

	static void runTask(Task task, Document taskDoc) throws Exception {
		try (ClientSession s = RegistryDB.getClient().startSession()) {
			task.run(s, taskDoc);
			tasks.deleteOne(s, eq("_id", taskDoc.get("_id")));
		}
	}

	public static void abortTransaction(ClientSession mongoSession, String message) {
		boolean successful = false;
		while (!successful) {
			try {
				if (mongoSession.hasActiveTransaction()) {
					mongoSession.abortTransaction();
				}
				successful = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException iex) {
					iex.printStackTrace();
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
				}
				successful = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException iex) {
					iex.printStackTrace();
				}
			}
		}
	}

	private static NanopubSetting settingNp;

	private static NanopubSetting getSetting() throws RDF4JException, MalformedNanopubException, IOException {
		if (settingNp == null) {
			settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
		}
		return settingNp;
	}

	private static IntroNanopub getAgentIntro(ClientSession mongoSession, String nanopubId) {
		IntroNanopub agentIntro = new IntroNanopub(NanopubLoader.retrieveNanopub(mongoSession, nanopubId));
		if (agentIntro.getUser() == null) return null;
		loadNanopub(mongoSession, agentIntro.getNanopub());
		return agentIntro;
	}

	private static void setServerStatus(ClientSession mongoSession, ServerStatus status) {
		setValue(mongoSession, "serverInfo", "status", status.toString());
	}

	private static ServerStatus getServerStatus(ClientSession mongoSession) {
		Object status = getValue(mongoSession, "serverInfo", "status");
		if (status == null) {
			throw new RuntimeException("Illegal DB state: serverInfo status unavailable");
		}
		return ServerStatus.valueOf(status.toString());
	}

	private static void schedule(ClientSession mongoSession, Task task) {
		schedule(mongoSession, task.doc());
	}

	private static void schedule(ClientSession mongoSession, Document taskDoc) {
		System.err.println("Scheduling task: " + taskDoc.get("action"));
		tasks.insertOne(mongoSession, taskDoc);
	}

}
