package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
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
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;

public class TaskManager {

	private static MongoCollection<Document> tasks = collection("tasks");

	private TaskManager() {}

	static void runTasks() {
		if (!RegistryDB.isInitialized()) {
			scheduleTask("init-db");
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
			scheduleTask("load-config");

		} else if (action.equals("load-config")) {

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				set("server-info", "coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				set("server-info", "coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			set("server-info", "status", "initializing");
			tasks.insertOne(new Document("not-before", System.currentTimeMillis()).append("action", "load-setting"));

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
				scheduleTask("load-agents", settingNp.getAgentIntroCollection().stringValue());
			} catch (RDF4JException | MalformedNanopubException | IOException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

		} else if (action.equals("load-agents")) {

			try {
				NanopubIndex agentIndex = IndexUtils.castToIndex(GetNanopub.get(param));
				loadNanopub(agentIndex);
				for (IRI el : agentIndex.getElements()) {
					scheduleTask("load-agent-intro", el.stringValue());
				}

			} catch (MalformedNanopubException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
			}

		} else if (action.equals("load-agent-intro")) {

			IntroNanopub agentIntro = new IntroNanopub(GetNanopub.get(param));
			System.err.println(agentIntro.getUser());
			loadNanopub(agentIntro.getNanopub());
			for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
				String hash = getHash(kd.getPublicKeyString());
				RegistryDB.add("pubkeys", new Document("_id", hash).append("full-key", kd.getPublicKeyString()));
				RegistryDB.add("base-agents", new Document("agent", agentIntro.getUser().stringValue()).append("pubkey", hash));
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

	private static void scheduleTask(String name) {
		scheduleTask(name, null, 0l);
	}

	private static void scheduleTask(String name, String param) {
		scheduleTask(name, param, 0l);
	}

	private static void scheduleTask(String name, String param, long delay) {
		Document d = new Document("not-before", System.currentTimeMillis() + delay).append("action", name);
		if (param != null) d.append("param", param);
		tasks.insertOne(d);
	}

	public static String getHash(String pubkey) {
		return Hashing.sha256().hashString(pubkey, Charsets.UTF_8).toString();
	}

}
