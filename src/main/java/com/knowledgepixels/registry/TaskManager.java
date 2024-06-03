package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.increateStateCounter;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import org.bson.Document;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.nanopub.MalformedNanopubException;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.setting.NanopubSetting;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;

public class TaskManager {

	private static MongoCollection<Document> tasks = collection("tasks");

	private TaskManager() {}

	static void runTasks() {
		if (get("server-info", "setup-id") == null) {
			tasks.insertOne(new Document("not-before", System.currentTimeMillis()).append("action", "init-db"));
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

			set("server-info", "status", "launching");
			increateStateCounter();
			if (collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor().hasNext()) {
				error("DB already initialized");
			}
			long setupId = Math.abs(new Random().nextLong());
			collection("server-info").insertOne(new Document("_id", "setup-id").append("value", setupId));

			tasks.insertOne(new Document("not-before", System.currentTimeMillis()).append("action", "init-config"));

		} else if (action.equals("init-config")) {

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
				set("server-info", "status", "loaded");
			} catch (RDF4JException | MalformedNanopubException | IOException ex) {
				ex.printStackTrace();
				error(ex.getMessage());
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

}
