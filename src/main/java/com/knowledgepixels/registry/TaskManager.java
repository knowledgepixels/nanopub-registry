package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.add;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.getOne;
import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.increateStateCounter;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.knowledgepixels.registry.RegistryDB.upsert;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.nanopub.MalformedNanopubException;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.index.NanopubIndex;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;

import net.trustyuri.TrustyUriUtils;

public class TaskManager {

	private static MongoCollection<Document> tasks = collection("tasks");

	private TaskManager() {}

	static void runTasks() {
		if (!RegistryDB.isInitialized()) {
			schedule(task("init-db"));
		}
		while (true) {
			FindIterable<Document> taskResult = tasks.find().sort(ascending("not-before"));
			if (taskResult.cursor().hasNext()) {
				Document task = taskResult.cursor().next();
				if (task.getLong("not-before") < System.currentTimeMillis()) {
					try {
						runTask(task);
					} catch (Exception ex) {
						ex.printStackTrace();
						error(ex.getMessage());
						break;
					}
				}
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				ex.printStackTrace();
			}
		}
	}

	static void runTask(Document task) {
		String action = task.getString("action");
		if (action == null) throw new RuntimeException("Action is null");
		System.err.println("Running task: " + action);

		if (action.equals("init-db")) {

			upsert("server-info", "status", "launching");
			increateStateCounter();
			if (RegistryDB.isInitialized()) error("DB already initialized");
			upsert("server-info", "setup-id", Math.abs(new Random().nextLong()));
			schedule(task("load-config"));

		} else if (action.equals("load-config")) {

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				upsert("server-info", "coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				upsert("server-info", "coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			upsert("server-info", "status", "initializing");
			schedule(task("load-setting"));

		} else if (action.equals("load-setting")) {

			try {
				NanopubSetting settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
				upsert("setting", "original", settingNp.getNanopub().getUri().stringValue());
				upsert("setting", "current", settingNp.getNanopub().getUri().stringValue());
				loadNanopub(settingNp.getNanopub());
				List<BasicDBObject> bootstrapServices = new ArrayList<>();
				for (IRI i : settingNp.getBootstrapServices()) {
					bootstrapServices.add(new BasicDBObject("_id", i.stringValue()));
				}
				upsert("setting", "bootstrap-services", bootstrapServices);
				upsert("server-info", "status", "loaded");
				schedule(task("load-agent-intros").append("depth", 0).append("agent-index", settingNp.getAgentIntroCollection().stringValue()));
			} catch (RDF4JException | MalformedNanopubException | IOException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

		} else if (action.equals("load-agent-intros")) {

			int depth = task.getInteger("depth");

			if (depth == 0) {
	
				try {
					NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubRetriever.retrieveNanopub(task.getString("agent-index")));
					loadNanopub(agentIndex);
					for (IRI el : agentIndex.getElements()) {
						String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());
						add("agent-intros", new Document("intro-np", declarationAc).append("type", "base").append("status", "to-try"));
					}
	
				} catch (MalformedNanopubException ex) {
					ex.printStackTrace();
					error(ex.getMessage());
				}

			} else {

				MongoCursor<Document> trustPathCursor = get("trust-paths", new BasicDBObject("depth", depth - 1));
				while (trustPathCursor.hasNext()) {
					Document trustPathDoc = trustPathCursor.next();
					String agentId = trustPathDoc.getString("agent");
					String pubkeyHash = trustPathDoc.getString("pubkey");
					MongoCursor<Document> endorsementCursor = get("endorsements", new BasicDBObject("agent", agentId).append("pubkey", pubkeyHash));
					while (endorsementCursor.hasNext()) {
						Document endorsementDoc = endorsementCursor.next();
						String endorsedNanopubAc = endorsementDoc.getString("endorsed-nanopub");
						// TODO Make this dependent on ratio
						if (!has("agent-intros", new BasicDBObject("intro-np", endorsedNanopubAc))) {
							add("agent-intros", new Document("intro-np", endorsedNanopubAc).append("type", "regular").append("status", "to-try"));
						}
					}
				}

			}

			schedule(task("load-pubkey-declarations").append("depth", depth));

		} else if (action.equals("load-pubkey-declarations")) {

			int depth = task.getInteger("depth");

			if (has("agent-intros", new BasicDBObject("status", "to-try"))) {
				System.err.println("load-core stage 1: getting agent intro");

				Document d = getOne("agent-intros", new BasicDBObject("status", "to-try"));
				String introId = d.getString("intro-np");
				String type = d.getString("type");

				IntroNanopub agentIntro = new IntroNanopub(NanopubRetriever.retrieveNanopub(introId));
				System.err.println("Load: " + introId);
				loadNanopub(agentIntro.getNanopub());
				if (agentIntro.getUser() != null) {
					// TODO Why/when is user null?
					String agentId = agentIntro.getUser().stringValue();
					for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
						String pubkeyHash = Utils.getHash(kd.getPublicKeyString());
						if (!has("agents", new Document("agent", agentId).append("pubkey", pubkeyHash))) {
							add("agents", new Document("agent", agentId).append("pubkey", pubkeyHash).append("type", type).append("status", "loading"));
						} else {
							// Agent already loaded/loading, possibly with different type; nothing to do here
						}
						upsert("pubkey-declarations", new BasicDBObject("intro-np", introId).append("agent", agentId).append("pubkey", pubkeyHash),
								new Document("intro-np", introId).append("status", "to-retrieve").append("agent", agentId).append("pubkey", pubkeyHash));
					}

					set("agent-intros", d, new BasicDBObject("status", "loaded").append("agent", agentId));
				} else {
					set("agent-intros", d, new BasicDBObject("status", "discarded"));
				}

				schedule(task("load-pubkey-declarations").append("depth", depth));

			} else {
				schedule(task("populate-trust-edges").append("depth", depth));
			}

		} else if (action.equals("populate-trust-edges")) {

			int depth = task.getInteger("depth");

			if (has("pubkey-declarations", new BasicDBObject("status", "to-retrieve"))) {
				System.err.println("load-core stage 2: getting incoming endorsements");

				Document d = getOne("pubkey-declarations", new BasicDBObject("status", "to-retrieve"));
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");

				MongoCursor<Document> incomingEndorsements = get("endorsements", new BasicDBObject("endorsed-nanopub", d.getString("intro-np")));
				while (incomingEndorsements.hasNext()) {
					Document i = incomingEndorsements.next();
					String endorsingAgentId = i.getString("agent");
					String endorsingPubkeyHash = i.getString("pubkey");
					BasicDBObject o = new BasicDBObject("from-agent", endorsingAgentId)
							.append("from-pubkey", endorsingPubkeyHash)
							.append("to-agent", agentId)
							.append("to-pubkey", pubkeyHash)
							.append("source", i.getString("source"))
							.append("invalidated", false);
					collection("trust-edges").updateOne(o, new BasicDBObject("$set", o), new UpdateOptions().upsert(true));
				}

				set("pubkey-declarations", d, new BasicDBObject("status", "retrieved"));
				set("agents", new BasicDBObject("agent", agentId).append("pubkey", pubkeyHash), new BasicDBObject("status", "core-to-load"));

				schedule(task("populate-trust-edges").append("depth", depth));

			} else {
				schedule(task("load-core").append("depth", depth));
			}

		} else if (action.equals("load-core")) {

			int depth = task.getInteger("depth");

			if (has("agents", new BasicDBObject("status", "core-to-load"))) {
				System.err.println("load-core stage 3: loading core nanopubs");

				Document d = getOne("agents", new BasicDBObject("status", "core-to-load"));
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");

				// TODO check intro limit
				String introType = Utils.INTRO_TYPE.stringValue();
				String introTypeHash = Utils.getHash(introType);
				if (!has("lists", new BasicDBObject("pubkey", pubkeyHash).append("type", introTypeHash))) {
					// TODO Why/when is list already loaded?
					Document introList = new Document("pubkey", pubkeyHash).append("type", introTypeHash).append("status", "loading");
					add("lists", introList);
					NanopubRetriever.retrieveNanopubs(introType, pubkeyHash, e -> {
						loadNanopub(NanopubRetriever.retrieveNanopub(e.get("np")), introType, pubkeyHash);
					});
					set("lists", introList, new BasicDBObject("status", "loaded"));
				}

				// TODO check endorsement limit
				String endorseType = Utils.APPROVAL_TYPE.stringValue();
				String endorseTypeHash = Utils.getHash(endorseType);
				if (!has("lists", new BasicDBObject("pubkey", pubkeyHash).append("type", endorseTypeHash))) {
					Document endorseList = new Document("pubkey", pubkeyHash).append("type", endorseTypeHash).append("status", "loading");
					add("lists", endorseList);
					NanopubRetriever.retrieveNanopubs(endorseType, pubkeyHash, e -> {
						loadNanopub(NanopubRetriever.retrieveNanopub(e.get("np")), endorseType, pubkeyHash);
					});
					set("lists", endorseList, new BasicDBObject("status", "loaded"));
				}

				set("agents", new BasicDBObject("agent", agentId).append("pubkey", pubkeyHash), new BasicDBObject("status", "core-loaded"));

				schedule(task("load-core").append("depth", depth));

			} else {
				schedule(task("calculate-trust-paths").append("depth", depth));
			}

		} else if (action.equals("calculate-trust-paths")) {

			int depth = task.getInteger("depth");
			System.err.println("DEPTH " + depth);
			String currentSetting = get("setting", "current").toString();

			if (depth > 10) {

				schedule(task("run-test"));

			} else {

				if (depth == 0) {

					MongoCursor<Document> baseAgents = get("agents", new BasicDBObject("type", "base").append("status", "core-loaded"));
	
					long count = collection("agents").countDocuments(new BasicDBObject("type", "base"));
					while (baseAgents.hasNext()) {
						Document d = baseAgents.next();
						String agentId = d.getString("agent");
						String pubkeyHash = d.getString("pubkey");
						String pathId = agentId + ">" + pubkeyHash;
						String sortHash = Utils.getHash(currentSetting + " " + pathId);
						upsert("trust-paths", new BasicDBObject("_id", pathId), new Document("sorthash", sortHash)
								.append("agent", agentId).append("pubkey", pubkeyHash).append("depth", 0).append("ratio", 1.0d / count));
					}
	
				}
	
				MongoCursor<Document> agentCursor = collection("agents").find(new BasicDBObject("status", "core-loaded")).cursor();
				
				System.err.println("Trust path calculation at depth " + depth);
	
				while (agentCursor.hasNext()) {
	
					Document d = agentCursor.next();
	
					String agentId = d.getString("agent");
					String pubkeyHash = d.getString("pubkey");
	
					System.err.println(agentId + " / " + pubkeyHash);
	
					// TODO Consider also maximum ratio?
					Document trustPath = collection("trust-paths").find(
							new BasicDBObject("agent", agentId).append("pubkey", pubkeyHash).append("depth", depth)
						).sort(new BasicDBObject("sorthash", 1)).cursor().tryNext();
	
					if (trustPath != null) {
						// Only first matching trust path is considered
						System.err.println("Trust path: " + trustPath.getString("_id"));
	
						BasicDBObject findTerm = new BasicDBObject("from-agent", agentId).append("from-pubkey", pubkeyHash).append("invalidated", false);
						MongoCursor<Document> edgeCursor = collection("trust-edges").find(findTerm).cursor();
						// TODO Count only unique:
						long count = collection("trust-edges").countDocuments(findTerm);
						while (edgeCursor.hasNext()) {
							Document edgeDoc = edgeCursor.next();
							String targetAgentId = edgeDoc.getString("to-agent");
							String targetPubkeyHash = edgeDoc.getString("to-pubkey");
							System.err.println("Trust path target: " + targetAgentId + " / " + targetPubkeyHash);
							String pathId = trustPath.getString("_id") + " " + targetAgentId + ">" + targetPubkeyHash;
							String sortHash = Utils.getHash(currentSetting + " " + pathId);
							double parentRatio = trustPath.getDouble("ratio");
							if (!has("trust-paths", new BasicDBObject("_id", pathId))) {
								// TODO has-check shouldn't be necessary if duplicates are removed above?
								add("trust-paths", new Document("_id", pathId).append("sorthash", sortHash)
										.append("agent", targetAgentId).append("pubkey", targetPubkeyHash).append("depth", depth + 1).append("ratio", (parentRatio*0.9) / count));
							}
						}
						set("agents", d, new BasicDBObject("status", "core-processed").append("depth", depth + 1));
					}

					schedule(task("load-agent-intros").append("depth", depth + 1));

				}

			}

		} else if (action.equals("run-test")) {

			System.err.println("EVERYTHING DONE");

//			MongoCursor<Document> cursor = get("nanopubs");
//			while (cursor.hasNext()) {
//				Document d = cursor.next();
//				if (RegistryDB.hasStrongInvalidation(d.getString("_id"), d.getString("pubkey"))) {
//					System.err.println("INVALID: " + d.getString("_id"));
//				} else {
//					System.err.println("VALID: " + d.getString("_id"));
//				}
//			}

		} else {

			error("Unknown task: " + action);

		}

		tasks.deleteOne(eq("_id", task.get("_id")));
	}

	private static void error(String message) {
		upsert("server-info", "status", "hanging");
		throw new RuntimeException(message);
	}

	private static void schedule(Document task) {
		tasks.insertOne(task);
	}

	private static Document task(String name) {
		return task(name, 0l);
	}

	private static Document task(String name, long delay) {
		return new Document("not-before", System.currentTimeMillis() + delay).append("action", name);
	}

}
