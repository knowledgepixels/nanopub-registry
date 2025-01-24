package com.knowledgepixels.registry;

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
import static com.knowledgepixels.registry.RegistryDB.mongoSession;
import static com.knowledgepixels.registry.RegistryDB.rename;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.knowledgepixels.registry.RegistryDB.setValue;
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

import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.index.NanopubIndex;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.services.ApiResponse;
import org.nanopub.extra.services.ApiResponseEntry;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public enum Task implements Serializable {

	INIT_DB {

		public void run(Document taskDoc) {
			setStatus("launching");

			increaseStateCounter();
			if (RegistryDB.isInitialized()) throw new RuntimeException("DB already initialized");
			setValue("serverInfo", "setupId", Math.abs(new Random().nextLong()));
			schedule(LOAD_CONFIG);
		}

	},

	LOAD_CONFIG {

		public void run(Document taskDoc) {
			if (!getStatus().equals("launching")) {
				throw new RuntimeException("Illegal status for this task: " + getStatus());
			}

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				setValue("serverInfo", "coverageTypes", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				setValue("serverInfo", "coverageAgents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			schedule(LOAD_SETTING);
		}

	},

	LOAD_SETTING {

		public void run(Document taskDoc) throws Exception {
			if (!getStatus().equals("launching")) {
				throw new RuntimeException("Illegal status for this task: " + getStatus());
			}

			NanopubSetting settingNp = getSetting();
			String settingId = TrustyUriUtils.getArtifactCode(settingNp.getNanopub().getUri().stringValue());
			setValue("setting", "original", settingId);
			setValue("setting", "current", settingId);
			loadNanopub(settingNp.getNanopub());
			List<Document> bootstrapServices = new ArrayList<>();
			for (IRI i : settingNp.getBootstrapServices()) {
				bootstrapServices.add(new Document("_id", i.stringValue()));
			}
			setValue("setting", "bootstrap-services", bootstrapServices);
			schedule(INIT_COLLECTIONS);
			if (PERFORM_FULL_LOAD) {
				schedule(LOAD_FULL.withDelay(60 * 1000));
			}

			setStatus("coreLoading");
		}

	},

	INIT_COLLECTIONS {

		// DB read from:
		// DB write to:  trustPaths, endorsements, accounts

		public void run(Document taskDoc) throws Exception {
			if (!getStatus().equals("coreLoading") && !getStatus().equals("updating")) {
				throw new RuntimeException("Illegal status for this task: " + getStatus());
			}

			RegistryDB.initLoadingCollections();

			insert("trustPaths_loading",
					new Document("_id", "$")
						.append("sorthash", "")
						.append("agent", "$")
						.append("pubkey", "$")
						.append("depth", 0)
						.append("ratio", 1.0d)
						.append("type", "extended")
				);

			NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubLoader.retrieveNanopub(getSetting().getAgentIntroCollection().stringValue()));
			loadNanopub(agentIndex);
			for (IRI el : agentIndex.getElements()) {
				String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());

				insert("endorsements_loading",
						new Document("agent", "$")
							.append("pubkey", "$")
							.append("endorsedNanopub", declarationAc)
							.append("source", getValue("setting", "current"))
							.append("status", "to-retrieve")
					);

			}
			insert("accounts_loading",
					new Document("agent", "$")
						.append("pubkey", "$")
						.append("status", "visited")
						.append("depth", 0)
				);

			System.err.println("Starting iteration at depth 0");
			schedule(LOAD_DECLARATIONS.with("depth", 1));
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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");

			if (has("endorsements_loading", new Document("status", "to-retrieve"))) {
				Document d = getOne("endorsements_loading", new Document("status", "to-retrieve"));

				IntroNanopub agentIntro = getAgentIntro(d.getString("endorsedNanopub"));
				if (agentIntro != null) {
					String agentId = agentIntro.getUser().stringValue();

					for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
						String sourcePubkey = d.getString("pubkey");
						String sourceAc = d.getString("source");
						String agentPubkey = Utils.getHash(kd.getPublicKeyString());
						Document trustEdge = new Document("fromAgent", d.getString("agent"))
								.append("fromPubkey", sourcePubkey)
								.append("toAgent", agentId)
								.append("toPubkey", agentPubkey)
								.append("source", sourceAc);
						if (!has("trustEdges", trustEdge)) {
							boolean invalidated = has("invalidations", new Document("invalidatedNp", sourceAc).append("invalidatingPubkey", sourcePubkey));
							insert("trustEdges", trustEdge.append("invalidated", invalidated));
						}

						Document agent = new Document("agent", agentId).append("pubkey", agentPubkey);
						if (!has("accounts_loading", agent)) {
							insert("accounts_loading", agent.append("status", "seen").append("depth", depth));
						}
					}

					set("endorsements_loading", d.append("status", "retrieved"));
				} else {
					set("endorsements_loading", d.append("status", "discarded"));
				}

				schedule(LOAD_DECLARATIONS.with("depth", depth));

			} else {
				schedule(EXPAND_TRUST_PATHS.with("depth", depth));
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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");

			Document d = getOne("accounts_loading",
					new Document("status", "visited")
						.append("depth", depth - 1)
				);

			if (d != null) {
	
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");

				Document trustPath = collection("trustPaths_loading").find(mongoSession,
						new Document("agent", agentId).append("pubkey", pubkeyHash).append("type", "extended").append("depth", depth - 1)
					).sort(orderBy(descending("ratio"), ascending("sorthash"))).first();

				if (trustPath == null) {
					// Check it again in next iteration:
					set("accounts_loading", d.append("depth", depth));
				} else {
					// Only first matching trust path is considered

					Map<String,Document> newPaths = new HashMap<>();
					Map<String,Set<String>> pubkeySets = new HashMap<>();
					String currentSetting = getValue("setting", "current").toString();

					MongoCursor<Document> edgeCursor = get("trustEdges",
							new Document("fromAgent", agentId)
								.append("fromPubkey", pubkeyHash)
								.append("invalidated", false)
						);
					while (edgeCursor.hasNext()) {
						Document e = edgeCursor.next();

						String agent = e.getString("toAgent");
						String pubkey = e.getString("toPubkey");
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
						insert("trustPaths_loading", pd.append("ratio", newRatio));
					}
					set("trustPaths_loading", trustPath.append("type", "primary"));
					set("accounts_loading", d.append("status", "expanded"));
				}
				schedule(EXPAND_TRUST_PATHS.with("depth", depth));
	
			} else {

				schedule(LOAD_CORE.with("depth", depth).append("load-count", 0));

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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");
			int loadCount = taskDoc.getInteger("load-count");

			Document agentAccount = getOne("accounts_loading", new Document("depth", depth).append("status", "seen"));
			Document trustPath = null;
			final String agentId;
			final String pubkeyHash;
			if (agentAccount != null) {
				agentId = agentAccount.getString("agent");
				pubkeyHash = agentAccount.getString("pubkey");
				trustPath = getOne("trustPaths_loading",
						new Document("depth", depth)
							.append("agent", agentId)
							.append("pubkey", pubkeyHash)
					);
			} else {
				agentId = null;
				pubkeyHash = null;
			}

			if (trustPath == null) {
				schedule(FINISH_ITERATION.with("depth", depth).append("load-count", loadCount));
			} else if (trustPath.getDouble("ratio") < MIN_TRUST_PATH_RATIO) {
				set("accounts_loading", agentAccount.append("status", "skipped"));
				Document d = new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH);
				if (!has("lists", d)) {
					insert("lists", d.append("status", "encountered"));
				}
				schedule(LOAD_CORE.with("depth", depth).append("load-count", loadCount + 1));
			} else {
				// TODO check intro limit
				Document introList = new Document()
						.append("pubkey", pubkeyHash)
						.append("type", INTRO_TYPE_HASH)
						.append("status", "loading");
				if (!has("lists", new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH))) {
					insert("lists", introList);
				}

				if (PEER_LOADING_TESTING_MODE) {
					NanopubLoader.retrieveNanopubsFromPeers(INTRO_TYPE_HASH, pubkeyHash).forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(m.getNanopub(), pubkeyHash, INTRO_TYPE);
					});
				} else {
					NanopubLoader.retrieveNanopubs(INTRO_TYPE, pubkeyHash, e -> {
						loadNanopub(NanopubLoader.retrieveNanopub(e.get("np")), pubkeyHash, INTRO_TYPE);
					});
				}

				set("lists", introList.append("status", "loaded"));

				// TODO check endorsement limit
				Document endorseList = new Document()
						.append("pubkey", pubkeyHash)
						.append("type", ENDORSE_TYPE_HASH)
						.append("status", "loading");
				if (!has("lists", new Document("pubkey", pubkeyHash).append("type", ENDORSE_TYPE_HASH))) {
					insert("lists", endorseList);
				}

				if (PEER_LOADING_TESTING_MODE) {
					NanopubLoader.retrieveNanopubsFromPeers(ENDORSE_TYPE_HASH, pubkeyHash).forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						Nanopub nanopub = m.getNanopub();
						loadNanopub(nanopub, pubkeyHash, ENDORSE_TYPE);
						String sourceNpId = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
						for (Statement st : nanopub.getAssertion()) {
							if (!st.getPredicate().equals(Utils.APPROVES_OF)) continue;
							if (!(st.getObject() instanceof IRI)) continue;
							if (!agentId.equals(st.getSubject().stringValue())) continue;
							String objStr = st.getObject().stringValue();
							if (!TrustyUriUtils.isPotentialTrustyUri(objStr)) continue;
							String endorsedNpId = TrustyUriUtils.getArtifactCode(objStr);
							Document endorsement = new Document("agent", agentId)
									.append("pubkey", pubkeyHash)
									.append("endorsedNanopub", endorsedNpId)
									.append("source", sourceNpId);
							if (!has("endorsements_loading", endorsement)) {
								insert("endorsements_loading", endorsement.append("status", "to-retrieve"));
							}
						}
					});
				} else {
					NanopubLoader.retrieveNanopubs(ENDORSE_TYPE, pubkeyHash, e -> {
						Nanopub nanopub = NanopubLoader.retrieveNanopub(e.get("np"));
						loadNanopub(nanopub, pubkeyHash, ENDORSE_TYPE);
						String sourceNpId = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
						for (Statement st : nanopub.getAssertion()) {
							if (!st.getPredicate().equals(Utils.APPROVES_OF)) continue;
							if (!(st.getObject() instanceof IRI)) continue;
							if (!agentId.equals(st.getSubject().stringValue())) continue;
							String objStr = st.getObject().stringValue();
							if (!TrustyUriUtils.isPotentialTrustyUri(objStr)) continue;
							String endorsedNpId = TrustyUriUtils.getArtifactCode(objStr);
							Document endorsement = new Document("agent", agentId)
									.append("pubkey", pubkeyHash)
									.append("endorsedNanopub", endorsedNpId)
									.append("source", sourceNpId);
							if (!has("endorsements_loading", endorsement)) {
								insert("endorsements_loading", endorsement.append("status", "to-retrieve"));
							}
						}
					});
				}

				set("lists", endorseList.append("status", "loaded"));

				Document df = new Document("pubkey", pubkeyHash).append("type", "$");
				if (!has("lists", df)) insert("lists", df.append("status", "encountered"));

				set("accounts_loading", agentAccount.append("status", "visited"));

				schedule(LOAD_CORE.with("depth", depth).append("load-count", loadCount + 1));
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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");
			int loadCount = taskDoc.getInteger("load-count");

			if (loadCount == 0) {
				System.err.println("No new cores loaded; finishing iteration");
				schedule(CALCULATE_TRUST_SCORES);
			} else if (depth == MAX_TRUST_PATH_DEPTH) {
				System.err.println("Maximum depth reached: " + depth);
				schedule(CALCULATE_TRUST_SCORES);
			} else {
				System.err.println("Progressing iteration at depth " + (depth + 1));
				schedule(LOAD_DECLARATIONS.with("depth", depth + 1));
			}
			
		}
		
	},

	CALCULATE_TRUST_SCORES {

		// DB read from: accounts, trustPaths
		// DB write to:  accounts

		public void run(Document taskDoc) {

			Document d = getOne("accounts_loading", new Document("status", "expanded"));

			if (d == null) {
				schedule(AGGREGATE_AGENTS);
			} else {
				double ratio = 0.0;
				Map<String,Boolean> seenPathElements = new HashMap<>();
				int pathCount = 0;
				MongoCursor<Document> trustPaths = collection("trustPaths_loading").find(mongoSession,
						new Document("agent", d.get("agent")).append("pubkey", d.get("pubkey"))
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
				set("accounts_loading",
						d.append("status", "processed")
							.append("ratio", ratio)
							.append("pathCount", pathCount)
							.append("quota", quota)
					);
				schedule(CALCULATE_TRUST_SCORES);
			}
			
		}
		
	},

	AGGREGATE_AGENTS {

		// DB read from: accounts, agents
		// DB write to:  accounts, agents

		public void run(Document taskDoc) {

			Document a = getOne("accounts_loading", new Document("status", "processed"));
			if (a == null) {
				schedule(ASSIGN_PUBKEYS);
			} else {
				Document agentId = new Document("agent", a.getString("agent")).append("status", "processed");
				int count = 0;
				int pathCountSum = 0;
				double totalRatio = 0.0d;
				MongoCursor<Document> agentAccounts = collection("accounts_loading").find(mongoSession, agentId).cursor();
				while (agentAccounts.hasNext()) {
					Document d = agentAccounts.next();
					count++;
					pathCountSum += d.getInteger("pathCount");
					totalRatio += d.getDouble("ratio");
				}
				collection("accounts_loading").updateMany(mongoSession, agentId, new Document("$set", new Document("status", "aggregated")));
				insert("agents_loading",
						agentId.append("accountCount", count)
							.append("avgPathCount", (double) pathCountSum / count)
							.append("totalRatio", totalRatio)
					);
				schedule(AGGREGATE_AGENTS);
			}

		}
		
	},

	ASSIGN_PUBKEYS {

		// DB read from: accounts
		// DB write to:  accounts

		public void run(Document taskDoc) {

			Document a = getOne("accounts_loading", new Document("status", "aggregated"));
			if (a == null) {
				schedule(DETERMINE_UPDATES);
			} else {
				Document pubkeyId = new Document("pubkey", a.getString("pubkey"));
				if (collection("accounts_loading").countDocuments(mongoSession, pubkeyId) == 1) {
					collection("accounts_loading").updateMany(mongoSession, pubkeyId, new Document("$set", new Document("status", "approved")));
				} else {
					// TODO At the moment all get marked as 'contested'; implement more nuanced algorithm
					collection("accounts_loading").updateMany(mongoSession, pubkeyId, new Document("$set", new Document("status", "contested")));
				}
				schedule(ASSIGN_PUBKEYS);
			}
			
		}
		
	},

	DETERMINE_UPDATES {

		// DB read from: accounts
		// DB write to:  accounts

		public void run(Document taskDoc) {

			// TODO Handle contested accounts properly:
			for (Document d : collection("accounts_loading").find(new Document("status", "approved"))) {
				// TODO Consider quota too:
				Document accountId = new Document("agent", d.get("agent")).append("pubkey", d.get("pubkey"));
				if (collection("accounts") == null || !has("accounts", accountId.append("status", "loaded"))) {
					set("accounts_loading", d.append("status", "toLoad"));
				} else {
					set("accounts_loading", d.append("status", "loaded"));
				}
			}
			schedule(FINALIZE_TRUST_STATE);
			
		}
		
	},

	FINALIZE_TRUST_STATE {

		// We do this is a separate task/transaction, because if we do it at the beginning of RELEASE_DATA, that task hangs and cannot
		// properly re-run (as some renaming outside of transactions will have taken place).
		public void run(Document taskDoc) {
			String newTrustStateHash = RegistryDB.calculateTrustStateHash();
			String previousTrustStateHash = (String) getValue("serverInfo", "trustStateHash");
			setValue("serverInfo", "lastTrustStateUpdate", ZonedDateTime.now().toString());

			schedule(RELEASE_DATA.with("newTrustStateHash", newTrustStateHash).append("previousTrustStateHash", previousTrustStateHash));
		}
		
	},

	RELEASE_DATA {

		public void run(Document taskDoc) {
			String status = getStatus();

			String newTrustStateHash = taskDoc.getString("newTrustStateHash");
			String previousTrustStateHash = taskDoc.getString("previousTrustStateHash");

			// Renaming collections is run outside of a transaction, but is idempotent operation, so can safely be retried if task fails:
			rename("accounts_loading", "accounts");
			rename("trustPaths_loading", "trustPaths");
			rename("agents_loading", "agents");
			rename("endorsements_loading", "endorsements");

			if (previousTrustStateHash == null || !previousTrustStateHash.equals(newTrustStateHash)) {
				increaseStateCounter();
				setValue("serverInfo", "trustStateHash", newTrustStateHash);
				insert("debug_trustPaths", new Document()
						.append("trustStateTxt", DebugPage.getTrustPathsTxt())
						.append("trustStateHash", newTrustStateHash)
						.append("trustStateCounter", getValue("serverInfo", "trustStateCounter"))
					);
			}

			if (status.equals("coreLoading")) {
				setStatus("coreReady");
			} else {
				setStatus("ready");
			}

			// Run update after 1h:
			schedule(UPDATE.withDelay(60 * 60 * 1000));
		}
		
	},

	UPDATE {

		public void run(Document taskDoc) {

			String status = getStatus();
			if (status.equals("ready")) {
				setStatus("updating");
				schedule(INIT_COLLECTIONS);
			} else {
				System.err.println("Postponing update; currently in status " + status);
				schedule(UPDATE.withDelay(10 * 60 * 1000));
			}
			
		}
		
	},

	LOAD_FULL {

		public void run(Document taskDoc) {
			if (!PERFORM_FULL_LOAD) return;

			String status = getStatus();
			if (!status.equals("coreReady") && !status.equals("ready")) {
				System.err.println("Server currently not ready; checking again later");
				schedule(LOAD_FULL.withDelay(60 * 1000));
				return;
			}

			Document a = getOne("accounts", new Document("status", "toLoad"));
			if (a == null) {
				System.err.println("Nothing to load");
				if (status.equals("coreReady")) {
					System.err.println("Full load finished");
					setStatus("ready");
				}
				System.err.println("Scheduling optional loading checks");
				schedule(CHECK_MORE_PUBKEYS.withDelay(100));
			} else {
				final String ph = a.getString("pubkey");
				if (!ph.equals("$")) {
					if (PEER_LOADING_TESTING_MODE) {
						NanopubLoader.retrieveNanopubsFromPeers("$", ph).forEach(m -> {
							if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
							Nanopub np = m.getNanopub();
							Set<String> types = new HashSet<>();
							types.add("$");
							for (IRI typeIri : NanopubUtils.getTypes(np)) {
								types.add(typeIri.stringValue());
							}
							loadNanopub(np, ph, types.toArray(new String[types.size()]));
						});
					} else {
						NanopubLoader.retrieveNanopubs(null, ph, e -> {
							Nanopub np = NanopubLoader.retrieveNanopub(e.get("np"));
							Set<String> types = new HashSet<>();
							types.add("$");
							for (IRI typeIri : NanopubUtils.getTypes(np)) {
								types.add(typeIri.stringValue());
							}
							loadNanopub(np, ph, types.toArray(new String[types.size()]));
						});
					}
				}

				Document l = getOne("lists", new Document().append("pubkey", ph).append("type", "$"));
				if (l != null) set("lists", l.append("status", "loaded"));
				set("accounts", a.append("status", "loaded"));

				schedule(LOAD_FULL.withDelay(100));
			}
		}

		@Override
		public boolean runAsTransaction() {
			// TODO Make this a transaction once we connect to other Nanopub Registry instances:
			return false;
		}

	},

	CHECK_MORE_PUBKEYS {

		public void run(Document taskDoc) {
			ApiResponse resp = ApiCache.retrieveResponse("RAWpFps2f4rvpLhFQ-_KiAyphGuXO6YGJLqiW3QBxQQhM/get-all-pubkeys", null);
			for (ApiResponseEntry e : resp.getData()) {
				String pubkeyHash = e.get("pubkeyhash");
				Document d = new Document("pubkey", pubkeyHash).append("type", INTRO_TYPE_HASH);
				if (!has("lists", d)) {
					insert("lists", d.append("status", "encountered"));
				}
			}

			schedule(RUN_OPTIONAL_LOAD.withDelay(100));
		}
		
	},

	RUN_OPTIONAL_LOAD {

		public void run(Document taskDoc) {
			Document di = getOne("lists", new Document("type", INTRO_TYPE_HASH).append("status", "encountered"));
			if (di != null) {
				final String pubkeyHash = di.getString("pubkey");
				System.err.println("Optional core loading: " + pubkeyHash);

				if (PEER_LOADING_TESTING_MODE) {
					NanopubLoader.retrieveNanopubsFromPeers(INTRO_TYPE_HASH, pubkeyHash).forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(m.getNanopub(), pubkeyHash, INTRO_TYPE);
					});
				} else {
					NanopubLoader.retrieveNanopubs(INTRO_TYPE, pubkeyHash, e -> {
						loadNanopub(NanopubLoader.retrieveNanopub(e.get("np")), pubkeyHash, INTRO_TYPE);
					});
				}
				set("lists", di.append("status", "loaded"));

				if (PEER_LOADING_TESTING_MODE) {
					NanopubLoader.retrieveNanopubsFromPeers(ENDORSE_TYPE_HASH, pubkeyHash).forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						loadNanopub(m.getNanopub(), pubkeyHash, ENDORSE_TYPE);
					});
				} else {
					NanopubLoader.retrieveNanopubs(ENDORSE_TYPE, pubkeyHash, e -> {
						loadNanopub(NanopubLoader.retrieveNanopub(e.get("np")), pubkeyHash, ENDORSE_TYPE);
					});
				}

				Document de = new Document("pubkey", pubkeyHash).append("type", ENDORSE_TYPE_HASH);
				if (has("lists", de)) {
					set("lists", de.append("status", "loaded"));
				} else {
					insert("lists", de.append("status", "loaded"));
				}

				Document df = new Document("pubkey", pubkeyHash).append("type", "$");
				if (!has("lists", df)) insert("lists", df.append("status", "encountered"));

				schedule(CHECK_NEW.withDelay(100));
				return;
			}

			Document df = getOne("lists", new Document("type", "$").append("status", "encountered"));
			if (df != null) {
				final String pubkeyHash = df.getString("pubkey");
				System.err.println("Optional full loading: " + pubkeyHash);

				if (PEER_LOADING_TESTING_MODE) {
					NanopubLoader.retrieveNanopubsFromPeers("$", pubkeyHash).forEach(m -> {
						if (!m.isSuccess()) throw new RuntimeException("Failed to download nanopub; aborting task...");
						Nanopub np = m.getNanopub();
						Set<String> types = new HashSet<>();
						types.add("$");
						for (IRI typeIri : NanopubUtils.getTypes(np)) {
							types.add(typeIri.stringValue());
						}
						loadNanopub(np, pubkeyHash, types.toArray(new String[types.size()]));
					});
				} else {
					NanopubLoader.retrieveNanopubs(null, pubkeyHash, e -> {
						Nanopub np = NanopubLoader.retrieveNanopub(e.get("np"));
						Set<String> types = new HashSet<>();
						types.add("$");
						for (IRI typeIri : NanopubUtils.getTypes(np)) {
							types.add(typeIri.stringValue());
						}
						loadNanopub(np, pubkeyHash, types.toArray(new String[types.size()]));
					});
				}

				set("lists", df.append("status", "loaded"));
			}

			schedule(CHECK_NEW.withDelay(100));
		}
		
	},

	CHECK_NEW {

		public void run(Document taskDoc) {
			// TODO Replace this legacy connection with checks at other Nanopub Registries:
			LegacyConnector.checkForNewNanopubs();
			// TODO Somehow throttle the loading of such potentially non-approved nanopubs

			schedule(LOAD_FULL.withDelay(100));
		}

	};

	private static final boolean PEER_LOADING_TESTING_MODE = false;
	private static final boolean PERFORM_FULL_LOAD = true;

	public abstract void run(Document taskDoc) throws Exception;

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

	static void runTasks() {
		if (!RegistryDB.isInitialized()) {
			schedule(INIT_DB);
		}
		while (true) {
			FindIterable<Document> taskResult = tasks.find(mongoSession).sort(ascending("not-before"));
			Document taskDoc = taskResult.first();
			long sleepTime = 10;
			if (taskDoc != null && taskDoc.getLong("not-before") < System.currentTimeMillis()) {
				Task task = valueOf(taskDoc.getString("action"));
				System.err.println("Running task: " + task.name());
				if (task.runAsTransaction()) {
					try {
						RegistryDB.startTransaction();
						System.err.println("Transaction started");
						runTask(task, taskDoc);
						RegistryDB.commitTransaction();
						System.err.println("Transaction committed");
					} catch (Exception ex) {
						System.err.println("Aborting transaction");
						ex.printStackTrace();
						RegistryDB.abortTransaction(ex.getMessage());
						System.err.println("Transaction aborted");
						sleepTime = 1000;
					} finally {
						RegistryDB.cleanTransactionWithRetry();
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

	static void runTask(Task task, Document taskDoc) throws Exception {
		task.run(taskDoc);
		tasks.deleteOne(mongoSession, eq("_id", taskDoc.get("_id")));
	}


	private static NanopubSetting settingNp;

	private static NanopubSetting getSetting() throws RDF4JException, MalformedNanopubException, IOException {
		if (settingNp == null) {
			settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
		}
		return settingNp;
	}

	private static IntroNanopub getAgentIntro(String nanopubId) {
		IntroNanopub agentIntro = new IntroNanopub(NanopubLoader.retrieveNanopub(nanopubId));
		if (agentIntro.getUser() == null) return null;
		loadNanopub(agentIntro.getNanopub());
		return agentIntro;
	}

	private static void setStatus(String status) {
		setValue("serverInfo", "status", status);
	}

	private static String getStatus() {
		return getValue("serverInfo", "status").toString();
	}

	private static void schedule(Task task) {
		schedule(task.doc());
	}

	private static void schedule(Document taskDoc) {
		System.err.println("Scheduling task: " + taskDoc.get("action"));
		tasks.insertOne(mongoSession, taskDoc);
	}

}
