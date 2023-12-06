package com.knowledgepixels.registry;

import java.util.Random;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
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

	private MongoClient mongoClient;
	private MongoDatabase mongoDB;

	private RegistryDB() {
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase("nanopub-registry");
		init();
	}

	private void init() {
		BasicDBObject query = new BasicDBObject("_id", "setup-id");
		MongoCollection<Document> collection = mongoDB.getCollection("server-info");
		FindIterable<Document> result = collection.find(query);
		if (result.cursor().hasNext()) {
			System.err.println("Existing Setup ID: " + result.cursor().next().get("value").toString());
		} else {
			long setupId = Math.abs(new Random().nextLong());
			System.err.println("New Setup ID: " + setupId);
			Document doc = new Document("_id", "setup-id").append("value", setupId);
			collection.insertOne(doc);
		}
	}

}
