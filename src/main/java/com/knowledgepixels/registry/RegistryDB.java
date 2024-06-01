package com.knowledgepixels.registry;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
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
		if (!mongoDB.getCollection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor().hasNext()) {
			mongoDB.getCollection("tasks").insertOne(new Document("not-before", System.currentTimeMillis() + 5000).append("action", "init-db"));
		}

		new Thread(() -> {
			TaskManager.runTasks();
		}).start();
	}

	public long getSetupId() {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor();
		if (!cursor.hasNext()) return 0;
		return cursor.next().getLong("value");
	}

	public String getStatus() {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "status")).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().getString("value");
	}

	public void setStatus(String status) {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "status")).cursor();
		if (cursor.hasNext()) {
			collection("server-info").updateOne(new BasicDBObject("_id", "status"), new BasicDBObject("$set", new BasicDBObject("value", status)));
		} else {
			collection("server-info").insertOne(new Document("_id", "status").append("value", status));
		}
	}

}
