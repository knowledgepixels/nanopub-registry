package com.knowledgepixels.registry;

import java.util.Random;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;

public class RegistryDB {

	private static RegistryDB obj;

	public synchronized static RegistryDB get() {
		if (obj == null) {
			obj = new RegistryDB();
		}
		return obj;
	}

	private MongoClient mongoClient;
	private MongoDatabase mongoDB;

	private RegistryDB() {
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase("nanopub-registry");
		init();
	}

	public MongoDatabase getDB() {
		return mongoDB;
	}

	private void init() {
		MongoCollection<Document> serverInfo = mongoDB.getCollection("server-info");
		FindIterable<Document> result = serverInfo.find(new BasicDBObject("_id", "setup-id"));
		if (result.cursor().hasNext()) {
			System.err.println("Existing Setup ID: " + result.cursor().next().get("value").toString());
		} else {
			System.err.println("Setting up new database...");
			long setupId = Math.abs(new Random().nextLong());
			System.err.println("New Setup ID: " + setupId);
			serverInfo.insertOne(new Document("_id", "setup-id").append("value", setupId));

			MongoCollection<Document> tasks = mongoDB.getCollection("tasks");
			String resultCreateIndex = tasks.createIndex(Indexes.descending("not-before"));
			System.out.println(String.format("Index created: %s", resultCreateIndex));
			long timeNow = System.currentTimeMillis();
			tasks.insertOne(new Document("not-before", timeNow + 2000).append("action", "test1"));
			tasks.insertOne(new Document("not-before", timeNow + 4000).append("action", "test2"));
			tasks.insertOne(new Document("not-before", timeNow + 1000).append("action", "test3"));
		}
		Thread t1 = new Thread(new Runnable() {
			@Override
		    public void run() {
		    	TaskManager.runTasks();
		    }
		});
		t1.start();
	}

}
