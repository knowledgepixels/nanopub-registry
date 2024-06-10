package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.add;
import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.increateStateCounter;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.set;
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

			set("server-info", "status", "launching");
			increateStateCounter();
			if (RegistryDB.isInitialized()) error("DB already initialized");
			set("server-info", "setup-id", Math.abs(new Random().nextLong()));
			schedule(task("load-config"));

		} else if (action.equals("load-config")) {

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				set("server-info", "coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				set("server-info", "coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			set("server-info", "status", "initializing");
			schedule(task("load-setting"));

		} else if (action.equals("load-setting")) {

			try {
				NanopubSetting settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
				set("setting", "original", settingNp.getNanopub().getUri().stringValue());
				set("setting", "current", settingNp.getNanopub().getUri().stringValue());
				loadNanopub(settingNp.getNanopub());
				List<BasicDBObject> bootstrapServices = new ArrayList<>();
				for (IRI i : settingNp.getBootstrapServices()) {
					bootstrapServices.add(new BasicDBObject("_id", i.stringValue()));
				}
				set("setting", "bootstrap-services", bootstrapServices);
				set("server-info", "status", "loaded");
				schedule(task("load-agents").append("param", settingNp.getAgentIntroCollection().stringValue()));
			} catch (RDF4JException | MalformedNanopubException | IOException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

		} else if (action.equals("load-agents")) {

			try {
				NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubRetriever.retrieveNanopub(param));
				loadNanopub(agentIndex);
				for (IRI el : agentIndex.getElements()) {
					schedule(task("load-agent-intro").append("param", el.stringValue()));
				}

			} catch (MalformedNanopubException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

			schedule(task("load-all-core-info"));

		} else if (action.equals("load-agent-intro")) {

			IntroNanopub agentIntro = new IntroNanopub(NanopubRetriever.retrieveNanopub(param));
			System.err.println(agentIntro.getUser());
			loadNanopub(agentIntro.getNanopub());
			for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
				String agentId = agentIntro.getUser().stringValue();
				String pubkeyHash = Utils.getHash(kd.getPublicKeyString());
				add("base-agents", new Document("agent", agentId).append("pubkey", pubkeyHash).append("type", "base"));

				//schedule(task("load-agent-core-intros").append("pubkey", pubkeyHash));
			}

		} else if (action.equals("load-all-core-info")) {

			NanopubRetriever.retrieveNanopubs(Utils.INTRO_TYPE.stringValue(), null, npId -> {
				loadNanopub(NanopubRetriever.retrieveNanopub(npId));
			});
			NanopubRetriever.retrieveNanopubs(Utils.APPROVAL_TYPE.stringValue(), null, npId -> {
				loadNanopub(NanopubRetriever.retrieveNanopub(npId));
			});

		} else if (action.equals("load-agent-core-approvals")) {

			// currently deactivated

			String pubkeyHash = task.getString("pubkey");
			String approvalType = Utils.APPROVAL_TYPE.stringValue();

			add("lists", new Document("pubkey", pubkeyHash).append("type", Utils.getHash(approvalType)).append("status", "loading"));
			NanopubRetriever.retrieveNanopubs(approvalType, pubkeyHash, npId -> {
				loadNanopub(NanopubRetriever.retrieveNanopub(npId), approvalType, pubkeyHash);
			});

			schedule(task("run-test"));

		} else if (action.equals("load-agent-core-intros")) {

			// currently deactivated

			String pubkeyHash = task.getString("pubkey");
			String introType = Utils.INTRO_TYPE.stringValue();

			add("lists", new Document("pubkey", pubkeyHash).append("type", Utils.getHash(introType)).append("status", "loading"));
			NanopubRetriever.retrieveNanopubs(introType, pubkeyHash, npId -> {
				loadNanopub(NanopubRetriever.retrieveNanopub(npId), introType, pubkeyHash);
			});

			schedule(task("load-agent-core-approvals").append("pubkey", task.getString("pubkey")));

		} else if (action.equals("load-agent-core-approvals")) {

			String pubkeyHash = task.getString("pubkey");
			String approvalType = Utils.APPROVAL_TYPE.stringValue();

			add("lists", new Document("pubkey", pubkeyHash).append("type", Utils.getHash(approvalType)).append("status", "loading"));
			NanopubRetriever.retrieveNanopubs(approvalType, pubkeyHash, npId -> {
				loadNanopub(NanopubRetriever.retrieveNanopub(npId), approvalType, pubkeyHash);
			});

			schedule(task("run-test"));

		} else if (action.equals("run-test")) {

			MongoCursor<Document> cursor = get("nanopubs");
			while (cursor.hasNext()) {
				Document d = cursor.next();
				if (RegistryDB.hasStrongInvalidation(d.getString("_id"), d.getString("pubkey"))) {
					System.err.println("INVALID: " + d.getString("_id"));
				} else {
					System.err.println("VALID: " + d.getString("_id"));
				}
			}

		} else {

			error("Unknown task: " + action);

		}

		tasks.deleteOne(eq("_id", task.get("_id")));
	}

	private static void error(String message) {
		set("server-info", "status", "hanging");
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
