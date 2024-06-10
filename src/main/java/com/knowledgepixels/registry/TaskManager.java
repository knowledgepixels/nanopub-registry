package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.add;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.getOne;
import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.increateStateCounter;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.knowledgepixels.registry.RegistryDB.setOrInsert;
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
		String param = task.getString("param");
		if (action == null) throw new RuntimeException("Action is null");
		System.err.println("Running task: " + action);

		if (action.equals("init-db")) {

			setOrInsert("server-info", "status", "launching");
			increateStateCounter();
			if (RegistryDB.isInitialized()) error("DB already initialized");
			setOrInsert("server-info", "setup-id", Math.abs(new Random().nextLong()));
			schedule(task("load-config"));

		} else if (action.equals("load-config")) {

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				setOrInsert("server-info", "coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				setOrInsert("server-info", "coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			setOrInsert("server-info", "status", "initializing");
			schedule(task("load-setting"));

		} else if (action.equals("load-setting")) {

			try {
				NanopubSetting settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
				setOrInsert("setting", "original", settingNp.getNanopub().getUri().stringValue());
				setOrInsert("setting", "current", settingNp.getNanopub().getUri().stringValue());
				loadNanopub(settingNp.getNanopub());
				List<BasicDBObject> bootstrapServices = new ArrayList<>();
				for (IRI i : settingNp.getBootstrapServices()) {
					bootstrapServices.add(new BasicDBObject("_id", i.stringValue()));
				}
				setOrInsert("setting", "bootstrap-services", bootstrapServices);
				setOrInsert("server-info", "status", "loaded");
				schedule(task("load-base-declarations").append("param", settingNp.getAgentIntroCollection().stringValue()));
			} catch (RDF4JException | MalformedNanopubException | IOException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

		} else if (action.equals("load-base-declarations")) {

			try {
				NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubRetriever.retrieveNanopub(param));
				loadNanopub(agentIndex);
				for (IRI el : agentIndex.getElements()) {
					String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());
					add("pubkey-declarations", new Document("declaration", declarationAc).append("type","base").append("status", "to-try"));
				}

			} catch (MalformedNanopubException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

			schedule(task("load-core"));

		} else if (action.equals("load-core")) {

			if (has("pubkey-declarations", new BasicDBObject("status", "to-try"))) {

				Document d = getOne("pubkey-declarations", new BasicDBObject("status", "to-try"));
				String declarationId = d.getString("declaration");

				IntroNanopub agentIntro = new IntroNanopub(NanopubRetriever.retrieveNanopub(declarationId));
				loadNanopub(agentIntro.getNanopub());
				String agentId = agentIntro.getUser().stringValue();
				for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
					String pubkeyHash = Utils.getHash(kd.getPublicKeyString());
					add("agents", new Document("agent", agentId).append("pubkey", pubkeyHash)
							.append("type", "base").append("status", "loading"));
					add("pubkey-declarations", new Document("declaration", declarationId)
							.append("type", d.get("type")).append("status", "to-load")
							.append("agent", agentId).append("pubkey", pubkeyHash));
				}

				set("pubkey-declarations", new BasicDBObject("_id", d.get("_id")), new BasicDBObject("status", "loaded")
						.append("agent", agentId).append("pubkey", "*"));
				schedule(task("load-core"));

			} else if (has("pubkey-declarations", new BasicDBObject("status", "to-load"))) {

				Document d = getOne("pubkey-declarations", new BasicDBObject("status", "to-load"));
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");
				set("pubkey-declarations", new BasicDBObject("_id", d.get("_id")), new BasicDBObject("status", "loaded"));

				// TODO check intro limit
				String introType = Utils.INTRO_TYPE.stringValue();
				Document introList = new Document("pubkey", pubkeyHash).append("type", Utils.getHash(introType)).append("status", "loading");
				add("lists", introList);
				NanopubRetriever.retrieveNanopubs(introType, pubkeyHash, npId -> {
					loadNanopub(NanopubRetriever.retrieveNanopub(npId), introType, pubkeyHash);
				});
				set("lists", introList, new BasicDBObject("status", "loaded"));

				// TODO check endorsement limit
				String endorseType = Utils.APPROVAL_TYPE.stringValue();
				Document endorseList = new Document("pubkey", pubkeyHash).append("type", Utils.getHash(endorseType)).append("status", "loading");
				add("lists", endorseList);
				NanopubRetriever.retrieveNanopubs(endorseType, pubkeyHash, npId -> {
					loadNanopub(NanopubRetriever.retrieveNanopub(npId), endorseType, pubkeyHash);
				});
				set("lists", endorseList, new BasicDBObject("status", "loaded"));

				MongoCursor<Document> incomingEndorsements = get("endorsements", new BasicDBObject("endorsed-nanopub", d.getString("declaration")));
				while (incomingEndorsements.hasNext()) {
					Document i = incomingEndorsements.next();
					String endorsingAgentId = i.getString("agent");
					String endorsingPubkeyHash = i.getString("pubkey");
					RegistryDB.loadIncomingEndorsements(endorsingAgentId, endorsingPubkeyHash, d.getString("declaration"), i.getString("source"));
				}

				set("agents", new BasicDBObject("agent", agentId).append("pubkey", pubkeyHash), new BasicDBObject("status", "loaded"));

				schedule(task("load-core"));

			} else {

				schedule(task("run-test"));

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
		setOrInsert("server-info", "status", "hanging");
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
