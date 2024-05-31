package com.knowledgepixels.registry;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

public class RegistryDB {

	private static RegistryDB obj;

	public synchronized static RegistryDB get() {
		if (obj == null) {
			obj = new RegistryDB();
		}
		return obj;
	}

	public static MongoDatabase getDB() {
		return RegistryDB.get().mongoDB;
	}

	public static MongoCollection<Document> collection(String name) {
		return RegistryDB.get().mongoDB.getCollection(name);
	}

	private MongoClient mongoClient;
	private MongoDatabase mongoDB;

	private RegistryDB() {
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase("nanopub-registry");
		init();
	}

	private void init() {
		if (!collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor().hasNext()) {
			collection("tasks").insertOne(new Document("not-before", System.currentTimeMillis() + 5000).append("action", "init-db"));
		}

		new Thread(() -> {
			TaskManager.runTasks();
		}).start();
	}

}
