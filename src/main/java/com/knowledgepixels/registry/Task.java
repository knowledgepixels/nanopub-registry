package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.rename;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.getOne;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.increateStateCounter;
import static com.knowledgepixels.registry.RegistryDB.insert;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.knowledgepixels.registry.RegistryDB.setValue;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Sorts.orderBy;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public enum Task implements Serializable {

	INIT_DB {

		public void run(Document taskDoc) {
			setValue("server-info", "status", "launching");
			increateStateCounter();
			if (RegistryDB.isInitialized()) error("DB already initialized");
			setValue("server-info", "setup-id", Math.abs(new Random().nextLong()));
			schedule(LOAD_CONFIG);
		}

	},

	LOAD_CONFIG {

		public void run(Document taskDoc) {
			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				setValue("server-info", "coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				setValue("server-info", "coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			setValue("server-info", "status", "initializing");
			schedule(LOAD_SETTING);
		}

	},

	LOAD_SETTING {

		public void run(Document taskDoc) throws Exception {
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
			setValue("server-info", "status", "loading");
			schedule(INIT_COLLECTIONS);
		}

	},

	INIT_COLLECTIONS {

		public void run(Document taskDoc) throws Exception {
			insert("trust-paths_loading",
					new Document("_id", "@")
						.append("sorthash", "")
						.append("agent", "@")
						.append("pubkey", "@")
						.append("depth", 0)
						.append("ratio", 1.0d)
						.append("type", "extended")
				);

			NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubRetriever.retrieveNanopub(getSetting().getAgentIntroCollection().stringValue()));
			loadNanopub(agentIndex);
			for (IRI el : agentIndex.getElements()) {
				String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());

				insert("endorsements_loading",
						new Document("agent", "@")
							.append("pubkey", "@")
							.append("endorsed-nanopub", declarationAc)
							.append("source", getValue("setting", "current"))
							.append("status", "to-retrieve")
					);

			}
			insert("agent-accounts_loading",
					new Document("agent", "@")
						.append("pubkey", "@")
						.append("status", "visited")
						.append("depth", 0)
				);

			System.err.println("Starting iteration at depth 0");
			schedule(LOAD_DECLARATIONS.with("depth", 1));
		}

		// At the end of this task, the base agent is initialized:
		// ------------------------------------------------------------
		//
		//	      @@@@ ----endorses----> [intro]
		//	      base                (to-retrieve)
		//	      @@@@
		//	    (visited)
		//	    
		//	      [0] trust path
		//
		// ------------------------------------------------------------
		// Only one endorses-link to an introduction is shown here,
		// but there are typically several.

	},

	LOAD_DECLARATIONS {

		// In general, we have at this point agent accounts with
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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");

			if (has("endorsements_loading", new Document("status", "to-retrieve"))) {
				Document d = getOne("endorsements_loading", new Document("status", "to-retrieve"));

				IntroNanopub agentIntro = getAgentIntro(d.getString("endorsed-nanopub"));
				if (agentIntro != null) {
					String agentId = agentIntro.getUser().stringValue();

					for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
						String pubkeyHash = Utils.getHash(kd.getPublicKeyString());

						Document trustEdge = new Document("from-agent", d.getString("agent"))
								.append("from-pubkey", d.getString("pubkey"))
								.append("to-agent", agentId)
								.append("to-pubkey", pubkeyHash)
								.append("source", d.getString("source"));
						if (!has("trust-edges", trustEdge)) {
							insert("trust-edges", trustEdge.append("invalidated", false));
						}

						Document agent = new Document("agent", agentId).append("pubkey", pubkeyHash);
						if (!has("agent-accounts_loading", agent)) {
							insert("agent-accounts_loading", agent.append("status", "seen").append("depth", depth));
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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");

			Document d = getOne("agent-accounts_loading",
					new Document("status", "visited")
						.append("depth", depth - 1)
				);

			if (d != null) {
	
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");

				Document trustPath = collection("trust-paths_loading").find(
						new Document("agent", agentId).append("pubkey", pubkeyHash).append("type", "extended").append("depth", depth - 1)
					).sort(orderBy(descending("ratio"), ascending("sorthash"))).first();

				if (trustPath == null) {
					// Check it again in next iteration:
					set("agent-accounts_loading", d.append("depth", depth));
				} else {
					// Only first matching trust path is considered

					Map<String,Document> newPaths = new HashMap<>();
					String currentSetting = getValue("setting", "current").toString();

					MongoCursor<Document> edgeCursor = get("trust-edges",
							new Document("from-agent", agentId)
								.append("from-pubkey", pubkeyHash)
								.append("invalidated", false)
						);
					while (edgeCursor.hasNext()) {
						Document e = edgeCursor.next();

						String pathId = trustPath.getString("_id") + " " + e.get("to-agent") + ">" + e.get("to-pubkey");
						newPaths.put(pathId,
								new Document("_id", pathId)
									.append("sorthash", Utils.getHash(currentSetting + " " + pathId))
									.append("agent", e.get("to-agent"))
									.append("pubkey", e.get("to-pubkey"))
									.append("depth", depth)
									.append("type", "extended")
							);
					}
					double newRatio = (trustPath.getDouble("ratio") * 0.9) / newPaths.size();
					for (String pathId : newPaths.keySet()) {
						insert("trust-paths_loading", newPaths.get(pathId).append("ratio", newRatio));
					}
					set("trust-paths_loading", trustPath.append("type", "primary"));
					set("agent-accounts_loading", d.append("status", "expanded"));
				}
				schedule(EXPAND_TRUST_PATHS.with("depth", depth));
	
			} else {

				schedule(LOAD_CORE.with("depth", depth).append("load-count", 0));

			}
			
		}

		// At the end of this step, trust paths are updated to include
		// the new agent accounts:
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

		public void run(Document taskDoc) {

			int depth = taskDoc.getInteger("depth");
			int loadCount = taskDoc.getInteger("load-count");

			Document agentAccount = getOne("agent-accounts_loading", new Document("depth", depth).append("status", "seen"));
			Document trustPath = null;
			final String agentId;
			final String pubkeyHash;
			if (agentAccount != null) {
				agentId = agentAccount.getString("agent");
				pubkeyHash = agentAccount.getString("pubkey");
				trustPath = getOne("trust-paths_loading",
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
				set("agent-accounts_loading", agentAccount.append("status", "skipped"));
				schedule(LOAD_CORE.with("depth", depth).append("load-count", loadCount + 1));
			} else {
				// TODO check intro limit
				String introType = Utils.INTRO_TYPE.stringValue();
				String introTypeHash = Utils.getHash(introType);
				if (!has("lists", new Document("pubkey", pubkeyHash).append("type", introTypeHash))) {
					// TODO Why/when is list already loaded?
					Document introList = new Document()
							.append("pubkey", pubkeyHash)
							.append("type", introTypeHash)
							.append("status", "loading");
					insert("lists", introList);
					NanopubRetriever.retrieveNanopubs(introType, pubkeyHash, e -> {
						loadNanopub(NanopubRetriever.retrieveNanopub(e.get("np")), introType, pubkeyHash);
					});
					set("lists", introList.append("status", "loaded"));
				}

				// TODO check endorsement limit
				String endorseType = Utils.APPROVAL_TYPE.stringValue();
				String endorseTypeHash = Utils.getHash(endorseType);
				if (!has("lists", new Document("pubkey", pubkeyHash).append("type", endorseTypeHash))) {
					Document endorseList = new Document()
							.append("pubkey", pubkeyHash)
							.append("type", endorseTypeHash)
							.append("status", "loading");
					insert("lists", endorseList);
					set("lists", endorseList.append("status", "loaded"));
				}
				NanopubRetriever.retrieveNanopubs(endorseType, pubkeyHash, e -> {
					Nanopub nanopub = NanopubRetriever.retrieveNanopub(e.get("np"));
					loadNanopub(nanopub, endorseType, pubkeyHash);
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
								.append("endorsed-nanopub", endorsedNpId)
								.append("source", sourceNpId)
								.append("status", "to-retrieve");
						if (!has("endorsements_loading", endorsement)) {
							insert("endorsements_loading", endorsement);
						}
					}
				});

				set("agent-accounts_loading", agentAccount.append("status", "visited"));

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

		public void run(Document taskDoc) {

			Document d = getOne("agent-accounts_loading", new Document("status", "expanded"));

			if (d == null) {
				schedule(AGGREGATE_AGENTS);
			} else {
				double ratio = 0.0;
				Map<String,Boolean> seenPathElements = new HashMap<>();
				int pathCount = 0;
				MongoCursor<Document> trustPaths = collection("trust-paths_loading").find(
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
				set("agent-accounts_loading", d.append("status", "processed").append("ratio", ratio).append("path-count", pathCount));
				schedule(CALCULATE_TRUST_SCORES);
			}
			
		}
		
	},

	AGGREGATE_AGENTS {

		public void run(Document taskDoc) {

			Document a = getOne("agent-accounts_loading", new Document("status", "processed"));
			if (a == null) {
				schedule(LOADING_DONE);
			} else {
				Document agentId = new Document("agent", a.getString("agent")).append("status", "processed");
				int count = 0;
				int pathCountSum = 0;
				double totalRatio = 0.0d;
				MongoCursor<Document> agentAccounts = collection("agent-accounts_loading").find(agentId).cursor();
				while (agentAccounts.hasNext()) {
					Document d = agentAccounts.next();
					count++;
					pathCountSum += d.getInteger("path-count");
					totalRatio += d.getDouble("ratio");
				}
				collection("agent-accounts_loading").updateMany(agentId, new Document("$set", new Document("status", "aggregated")));
				insert("agents_loading",
						agentId.append("account-count", count)
							.append("avg-path-count", (double) pathCountSum / count)
							.append("total-ratio", totalRatio)
					);
				schedule(AGGREGATE_AGENTS);
			}
			
		}
		
	},

	LOADING_DONE {

		public void run(Document taskDoc) {

			rename("agent-accounts_loading", "agent-accounts");
			rename("trust-paths_loading", "trust-paths");
			rename("agents_loading", "agents");
			rename("endorsements_loading", "endorsements");
			RegistryDB.initLoadingCollections();

			// TODO Only increase counter when state has actually changed:
			increateStateCounter();
			setValue("server-info", "status", "ready");

			System.err.println("Loading done");

			// Run update after 1h:
			schedule(UPDATE.withDelay(60 * 60 * 1000));
			
		}
		
	},

	UPDATE {

		public void run(Document taskDoc) {

			setValue("server-info", "status", "updating");

			schedule(INIT_COLLECTIONS);
			
		}
		
	};

	public abstract void run(Document taskDoc) throws Exception;


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
	private static final double MIN_TRUST_PATH_RATIO = 0.000001;
	//private static final double MIN_TRUST_PATH_RATIO = 0.01; // For testing

	private static MongoCollection<Document> tasks = collection("tasks");

	static void runTasks() {
		if (!RegistryDB.isInitialized()) {
			schedule(INIT_DB);
		}
		while (true) {
			FindIterable<Document> taskResult = tasks.find().sort(ascending("not-before"));
			Document task = taskResult.first();
			if (task != null && task.getLong("not-before") < System.currentTimeMillis()) {
				try {
					runTask(task);
				} catch (Exception ex) {
					ex.printStackTrace();
					error(ex.getMessage());
					break;
				}
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException ex) {
				ex.printStackTrace();
			}
		}
	}

	static void runTask(Document taskDoc) throws Exception {
		String action = taskDoc.getString("action");
		if (action == null) throw new RuntimeException("Action is null");
		System.err.println("Running task: " + action);
		try {
			Task task = valueOf(action);
			task.run(taskDoc);
		} catch (IllegalArgumentException ex) {
			error(ex.getMessage());
		}
		tasks.deleteOne(eq("_id", taskDoc.get("_id")));
	}


	private static NanopubSetting settingNp;

	private static NanopubSetting getSetting() throws RDF4JException, MalformedNanopubException, IOException {
		if (settingNp == null) {
			settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
		}
		return settingNp;
	}

	private static IntroNanopub getAgentIntro(String nanopubId) {
		IntroNanopub agentIntro = new IntroNanopub(NanopubRetriever.retrieveNanopub(nanopubId));
		if (agentIntro.getUser() == null) return null;
		loadNanopub(agentIntro.getNanopub());
		return agentIntro;
	}

	private static void error(String message) {
		setValue("server-info", "status", "hanging");
		throw new RuntimeException(message);
	}

	private static void schedule(Task task) {
		schedule(task.doc());
	}

	private static void schedule(Document taskDoc) {
		tasks.insertOne(taskDoc);
	}

}
