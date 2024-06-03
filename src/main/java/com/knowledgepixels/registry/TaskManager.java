package com.knowledgepixels.registry;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;
import static com.knowledgepixels.registry.RegistryDB.*;

import java.util.Random;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;

public class TaskManager {

	private static MongoCollection<Document> tasks = collection("tasks");

	private TaskManager() {}

	static void runTasks() {
		if (!collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor().hasNext()) {
			tasks.insertOne(new Document("not-before", System.currentTimeMillis()).append("action", "init-db"));
		}
		while (true) {
			FindIterable<Document> taskResult = tasks.find().sort(ascending("not-before"));
			if (taskResult.cursor().hasNext()) {
				Document task = taskResult.cursor().next();
				if (task.getLong("not-before") < System.currentTimeMillis()) {
					runTask(task);
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
			setServerInfoString("status", "launching");
			increateStateCounter();
			if (collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor().hasNext()) {
				error("DB already initialized");
			}
			long setupId = Math.abs(new Random().nextLong());
			collection("server-info").insertOne(new Document("_id", "setup-id").append("value", setupId));

			MongoCollection<Document> tasks = collection("tasks");
			String resultCreateIndex = tasks.createIndex(Indexes.descending("not-before"));
			System.out.println(String.format("Index created: %s", resultCreateIndex));
			tasks.insertOne(new Document("not-before", System.currentTimeMillis()).append("action", "init-config"));
		} else if (action.equals("init-config")) {
			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				setServerInfoString("coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				setServerInfoString("coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			setServerInfoString("status", "ready");
		} else {
			error("Unknown task: " + action);
		}
		tasks.deleteOne(eq("_id", task.get("_id")));
	}

	private static void error(String message) {
		setServerInfoString("status", "hanging");
		throw new RuntimeException(message);
	}

}
