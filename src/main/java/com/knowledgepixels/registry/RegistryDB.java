package com.knowledgepixels.registry;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;

public class RegistryDB {

	private RegistryDB() {}

	static {
		init();
	}

	private static MongoClient mongoClient;
	private static MongoDatabase mongoDB;

	public static MongoDatabase getDB() {
		return mongoDB;
	}

	public static MongoCollection<Document> collection(String name) {
		return mongoDB.getCollection(name);
	}

	public static void init() {
		if (mongoClient != null) return;
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase("nanopub-registry");
		new Thread(() -> {
			TaskManager.runTasks();
		}).start();
	}

	public static Long getSetupIdX() {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().getLong("value");
	}

	public static Long getStateCounterX() {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "state-counter")).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().getLong("value");
	}

	public static void increateStateCounter() {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "state-counter")).cursor();
		if (cursor.hasNext()) {
			long counter = cursor.next().getLong("value");
			collection("server-info").updateOne(new BasicDBObject("_id", "state-counter"), new BasicDBObject("$set", new BasicDBObject("value", counter + 1)));
		} else {
			collection("server-info").insertOne(new Document("_id", "state-counter").append("value", 0l));
		}
	}

	public static Object get(String collection, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(new BasicDBObject("_id", fieldName)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get("value");
	}

	public static void set(String collection, String fieldName, Object value) {
		MongoCursor<Document> cursor = collection(collection).find(new BasicDBObject("_id", fieldName)).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(new BasicDBObject("_id", fieldName), new BasicDBObject("$set", new BasicDBObject("value", value)));
		} else {
			collection(collection).insertOne(new Document("_id", fieldName).append("value", value));
		}
	}

}
