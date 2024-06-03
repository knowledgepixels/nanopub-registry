package com.knowledgepixels.registry;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;

public class RegistryDB {

	private RegistryDB() {} 

	private static MongoClient mongoClient;
	private static MongoDatabase mongoDB;

	public static MongoDatabase getDB() {
		if (mongoDB == null) init();
		return mongoDB;
	}

	public static MongoCollection<Document> collection(String name) {
		if (mongoDB == null) init();
		return mongoDB.getCollection(name);
	}

	public static void init() {
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase("nanopub-registry");
		new Thread(() -> {
			TaskManager.runTasks();
		}).start();
	}

	public static Long getSetupId() {
		if (mongoDB == null) init();
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "setup-id")).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().getLong("value");
	}

	public static Long getStateCounter() {
		if (mongoDB == null) init();
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "state-counter")).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().getLong("value");
	}

	public static void increateStateCounter() {
		if (mongoDB == null) init();
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "state-counter")).cursor();
		if (cursor.hasNext()) {
			long counter = cursor.next().getLong("value");
			collection("server-info").updateOne(new BasicDBObject("_id", "state-counter"), new BasicDBObject("$set", new BasicDBObject("value", counter + 1)));
		} else {
			collection("server-info").insertOne(new Document("_id", "state-counter").append("value", 0l));
		}
	}

	public static String getStatus() {
		if (mongoDB == null) init();
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "status")).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().getString("value");
	}

	public static void setStatus(String status) {
		if (mongoDB == null) init();
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "status")).cursor();
		if (cursor.hasNext()) {
			collection("server-info").updateOne(new BasicDBObject("_id", "status"), new BasicDBObject("$set", new BasicDBObject("value", status)));
		} else {
			collection("server-info").insertOne(new Document("_id", "status").append("value", status));
		}
	}

}
