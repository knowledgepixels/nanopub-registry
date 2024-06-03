package com.knowledgepixels.registry;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

import java.util.Random;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;

public class TaskManager {

	private static MongoCollection<Document> tasks = RegistryDB.collection("tasks");

	private TaskManager() {}

	static void runTasks() {
		if (!RegistryDB.collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor().hasNext()) {
			tasks.insertOne(new Document("not-before", System.currentTimeMillis() + 5000).append("action", "init-db"));
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
			RegistryDB.setStatus("launching");
			RegistryDB.increateStateCounter();
			MongoCollection<Document> serverInfo = RegistryDB.collection("server-info");
			FindIterable<Document> result = serverInfo.find(new BasicDBObject("_id", "setup-id"));
			if (result.cursor().hasNext()) throw new RuntimeException("DB already initialized");
			System.err.println("Setting up new database...");
			long setupId = Math.abs(new Random().nextLong());
			System.err.println("New Setup ID: " + setupId);
			serverInfo.insertOne(new Document("_id", "setup-id").append("value", setupId));

			MongoCollection<Document> tasks = RegistryDB.collection("tasks");
			String resultCreateIndex = tasks.createIndex(Indexes.descending("not-before"));
			System.out.println(String.format("Index created: %s", resultCreateIndex));
			long timeNow = System.currentTimeMillis();
			tasks.insertOne(new Document("not-before", timeNow + 2000).append("action", "test1"));
			tasks.insertOne(new Document("not-before", timeNow + 4000).append("action", "test2"));
			tasks.insertOne(new Document("not-before", timeNow + 1000).append("action", "test3"));
			tasks.insertOne(new Document("not-before", timeNow + 10000).append("action", "test5"));
		} else if (action.equals("test5")) {
			RegistryDB.setStatus("ready");
			RegistryDB.increateStateCounter();
		}
		tasks.deleteOne(eq("_id", task.get("_id")));
	}

}
