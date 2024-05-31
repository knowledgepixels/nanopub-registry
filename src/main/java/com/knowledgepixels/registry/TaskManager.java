package com.knowledgepixels.registry;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

import org.bson.Document;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;

public class TaskManager {

	private static MongoCollection<Document> tasks = RegistryDB.get().getDB().getCollection("tasks");

	private TaskManager() {}

	static void runTasks() {
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
		System.err.println("Running task: " + task.getString("action"));
		tasks.insertOne(new Document("not-before", System.currentTimeMillis() + 5000).append("action", task.getString("action")));
		// TODO Add code here to run the actions
		tasks.deleteOne(eq("_id", task.get("_id")));
	}

}
