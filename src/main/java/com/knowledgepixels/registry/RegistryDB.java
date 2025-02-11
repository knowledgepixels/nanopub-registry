package com.knowledgepixels.registry;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;

import com.knowledgepixels.registry.jelly.JellyUtils;
import com.mongodb.MongoClient;
import com.mongodb.MongoNamespace;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;

import net.trustyuri.TrustyUriUtils;

public class RegistryDB {

	private RegistryDB() {}

	private static final String REGISTRY_DB_NAME = Utils.getEnv("NANOPUB_REGISTRY_DB_NAME", "nanopubRegistry");
	private static final String REGISTRY_DB_HOST = Utils.getEnv("NANOPUB_REGISTRY_DB_HOST", "mongodb");

	private static MongoClient mongoClient;
	private static MongoDatabase mongoDB;

	public static MongoDatabase getDB() {
		return mongoDB;
	}

	public static MongoClient getClient() {
		return mongoClient;
	}

	public static MongoCollection<Document> collection(String name) {
		return mongoDB.getCollection(name);
	}

	private final static IndexOptions unique = new IndexOptions().unique(true);

	public static void init() {
		if (mongoClient != null) return;
		mongoClient = new MongoClient(REGISTRY_DB_HOST);
		mongoDB = mongoClient.getDatabase(REGISTRY_DB_NAME);

		try (ClientSession mongoSession = mongoClient.startSession()) {
			if (isInitialized(mongoSession)) return;
	
			final IndexOptions unique = new IndexOptions().unique(true);
	
			collection("tasks").createIndex(mongoSession, Indexes.descending("not-before"));
	
			collection("nanopubs").createIndex(mongoSession, ascending("fullId"), unique);
			collection("nanopubs").createIndex(mongoSession, descending("counter"), unique);
			collection("nanopubs").createIndex(mongoSession, ascending("pubkey"));
	
			collection("lists").createIndex(mongoSession, ascending("pubkey", "type"), unique);
			collection("lists").createIndex(mongoSession, ascending("status"));
	
			collection("listEntries").createIndex(mongoSession, ascending("np"));
			collection("listEntries").createIndex(mongoSession, ascending("pubkey", "type", "np"), unique);
			collection("listEntries").createIndex(mongoSession, compoundIndex(ascending("pubkey"), ascending("type"), descending("position")), unique);
			collection("listEntries").createIndex(mongoSession, ascending("pubkey", "type", "checksum"), unique);
			collection("listEntries").createIndex(mongoSession, ascending("invalidated"));
	
			collection("invalidations").createIndex(mongoSession, ascending("invalidatingNp"));
			collection("invalidations").createIndex(mongoSession, ascending("invalidatingPubkey"));
			collection("invalidations").createIndex(mongoSession, ascending("invalidatedNp"));
			collection("invalidations").createIndex(mongoSession, ascending("invalidatingPubkey", "invalidatedNp"));
	
			collection("trustEdges").createIndex(mongoSession, ascending("fromAgent"));
			collection("trustEdges").createIndex(mongoSession, ascending("fromPubkey"));
			collection("trustEdges").createIndex(mongoSession, ascending("toAgent"));
			collection("trustEdges").createIndex(mongoSession, ascending("toPubkey"));
			collection("trustEdges").createIndex(mongoSession, ascending("source"));
			collection("trustEdges").createIndex(mongoSession, ascending("fromAgent", "fromPubkey", "toAgent", "toPubkey", "source"), unique);
			collection("trustEdges").createIndex(mongoSession, ascending("invalidated"));
	
			collection("hashes").createIndex(mongoSession, ascending("hash"), unique);
			collection("hashes").createIndex(mongoSession, ascending("value"), unique);
		}
	}

	public static void initLoadingCollections(ClientSession mongoSession) {
		collection("endorsements_loading").createIndex(mongoSession, ascending("agent"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("pubkey"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("endorsedNanopub"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("source"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("status"));

		collection("agents_loading").createIndex(mongoSession, ascending("agent"), unique);
		collection("agents_loading").createIndex(mongoSession, descending("accountCount"));
		collection("agents_loading").createIndex(mongoSession, descending("avgPathCount"));
		collection("agents_loading").createIndex(mongoSession, descending("totalRatio"));

		collection("accounts_loading").createIndex(mongoSession, ascending("agent"));
		collection("accounts_loading").createIndex(mongoSession, ascending("pubkey"));
		collection("accounts_loading").createIndex(mongoSession, ascending("agent", "pubkey"), unique);
		collection("accounts_loading").createIndex(mongoSession, ascending("type"));
		collection("accounts_loading").createIndex(mongoSession, ascending("status"));
		collection("accounts_loading").createIndex(mongoSession, descending("ratio"));
		collection("accounts_loading").createIndex(mongoSession, descending("pathCount"));

		collection("trustPaths_loading").createIndex(mongoSession, ascending("agent", "pubkey", "depth", "sorthash"), unique);
		collection("trustPaths_loading").createIndex(mongoSession, ascending("depth"));
		collection("trustPaths_loading").createIndex(mongoSession, descending("ratio"));
	}

	public static boolean isInitialized(ClientSession mongoSession) {
		return getValue(mongoSession, "serverInfo", "setupId") != null;
	}

	public static void rename(String oldCollectionName, String newCollectionName) {
		 // Designed as idempotent operation: calling multiple times has same effect as calling once
		if (hasCollection(oldCollectionName)) {
			if (hasCollection(newCollectionName)) {
				collection(newCollectionName).drop();
			}
			collection(oldCollectionName).renameCollection(new MongoNamespace(REGISTRY_DB_NAME, newCollectionName));
		}
	}

	public static boolean hasCollection(String collectionName) {
		return mongoDB.listCollectionNames().into(new ArrayList<String>()).contains(collectionName);
	}

	public static void increaseStateCounter(ClientSession mongoSession) {
		MongoCursor<Document> cursor = collection("serverInfo").find(mongoSession, new Document("_id", "trustStateCounter")).cursor();
		if (cursor.hasNext()) {
			long counter = cursor.next().getLong("value");
			collection("serverInfo").updateOne(mongoSession, new Document("_id", "trustStateCounter"), new Document("$set", new Document("value", counter + 1)));
		} else {
			collection("serverInfo").insertOne(mongoSession, new Document("_id", "trustStateCounter").append("value", 0l));
		}
	}

	public static boolean has(ClientSession mongoSession, String collection, String elementName) {
		return has(mongoSession, collection, new Document("_id", elementName));
	}

	public static boolean has(ClientSession mongoSession, String collection, Bson find) {
		return collection(collection).find(mongoSession, find).cursor().hasNext();
	}

	public static MongoCursor<Document> get(ClientSession mongoSession, String collection, Bson find) {
		return collection(collection).find(mongoSession, find).cursor();
	}

	public static Object getValue(ClientSession mongoSession, String collection, String elementName) {
		Document d = collection(collection).find(mongoSession, new Document("_id", elementName)).first();
		if (d == null) return null;
		return d.get("value");
	}

	public static Document getOne(ClientSession mongoSession, String collection, Bson find) {
		return collection(collection).find(mongoSession, find).first();
	}

	public static Document getOne(ClientSession mongoSession, String collection, Bson find, Bson sort) {
		return collection(collection).find(mongoSession, find).sort(sort).first();
	}

	public static Object getMaxValue(ClientSession mongoSession, String collection, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(mongoSession).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Object getMaxValue(ClientSession mongoSession, String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Document getMaxValueDocument(ClientSession mongoSession, String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next();
	}

	public static void set(ClientSession mongoSession, String collection, Document update) {
		Bson find = new Document("_id", update.get("_id"));
		MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(mongoSession, find, new Document("$set", update));
		}
	}

	public static void insert(ClientSession mongoSession, String collection, Document doc) {
		collection(collection).insertOne(mongoSession, doc);
	}

	public static void setValue(ClientSession mongoSession, String collection, String elementId, Object value) {
		collection(collection).updateOne(mongoSession,
				new Document("_id", elementId),
				new Document("$set", new Document("value", value)),
				new UpdateOptions().upsert(true)
			);
	}

	public static void recordHash(ClientSession mongoSession, String value) {
		if (!has(mongoSession, "hashes", new Document("value", value))) {
			insert(mongoSession, "hashes", new Document("value", value).append("hash", Utils.getHash(value)));
		}
	}

	public static String unhash(String hash) {
		var c = collection("hashes").find(new Document("hash", hash)).cursor();
		if (c.hasNext()) return c.next().get("value").toString();
		return null;
	}

	public static void loadNanopub(ClientSession mongoSession, Nanopub nanopub) {
		loadNanopub(mongoSession, nanopub, null);
	}

	public static void loadNanopub(ClientSession mongoSession, Nanopub nanopub, String pubkeyHash, String... types) {
		String pubkey = getPubkey(nanopub);
		if (pubkey == null) {
			System.err.println("Ignoring invalid nanopub: " + nanopub.getUri());
			return;
		}
		String ph = Utils.getHash(pubkey);
		if (pubkeyHash != null && !pubkeyHash.equals(ph)) {
			System.err.println("Ignoring nanopub with non-matching pubkey: " + nanopub.getUri());
			return;
		}
		recordHash(mongoSession, pubkey);

		String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
		if (has(mongoSession, "nanopubs", ac)) {
			System.err.println("Already loaded: " + nanopub.getUri());
		} else {
			Long counter = (Long) getMaxValue(mongoSession, "nanopubs", "counter");
			if (counter == null) counter = 0l;
			String nanopubString;
			byte[] jellyContent;
			try {
				nanopubString = NanopubUtils.writeToString(nanopub, RDFFormat.TRIG);
				// Save the same thing in the Jelly format for faster loading
				jellyContent = JellyUtils.writeNanopubForDB(nanopub);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
			collection("nanopubs").insertOne(mongoSession,
					new Document("_id", ac)
						.append("fullId", nanopub.getUri().stringValue())
						.append("counter", counter + 1)
						.append("pubkey", ph)
						.append("content", nanopubString)
						.append("jelly", new Binary(jellyContent))
				);

			for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(nanopub)) {
				String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());

				// Add this nanopub also to all lists of invalidated nanopubs:
				collection("invalidations").insertOne(mongoSession,
						new Document("invalidatingNp", ac)
							.append("invalidatingPubkey", ph)
							.append("invalidatedNp", invalidatedAc)
					);
				MongoCursor<Document> invalidatedEntries = collection("listEntries").find(mongoSession,
						new Document("np", invalidatedAc).append("pubkey", ph)
					).cursor();
				while (invalidatedEntries.hasNext()) {
					Document invalidatedEntry = invalidatedEntries.next();
					addToList(mongoSession, nanopub, ph, invalidatedEntry.getString("type"));
				}

				collection("listEntries").updateMany(mongoSession,
						new Document("np", invalidatedAc).append("pubkey", ph),
						new Document("$set", new Document("invalidated", true))
					);
				collection("trustEdges").updateMany(mongoSession,
						new Document("source", invalidatedAc),
						new Document("$set", new Document("invalidated", true))
					);
			}
		}

		if (pubkeyHash != null) {
			for (String type : types) {
				if (!hasType(nanopub, type)) continue;
				addToList(mongoSession, nanopub, pubkeyHash, Utils.getTypeHash(mongoSession, type));
			}
		}

		if (has(mongoSession, "invalidations", new Document("invalidatedNp", ac).append("invalidatingPubkey", ph))) {

			// Add the invalidating nanopubs also to the lists of this nanopub:
			MongoCursor<Document> invalidations = collection("invalidations").find(mongoSession,
					new Document("invalidatedNp", ac).append("invalidatingPubkey", ph)
				).cursor();
			while (invalidations.hasNext()) {
				String iac = invalidations.next().getString("invalidatingNp");
				try {
					Nanopub inp = new NanopubImpl(collection("nanopubs").find(mongoSession, new Document("_id", iac)).first().getString("content"), RDFFormat.TRIG);
					for (IRI type : NanopubUtils.getTypes(inp)) {
						addToList(mongoSession, inp, ph, Utils.getTypeHash(mongoSession, type));
					}
				} catch (RDF4JException | MalformedNanopubException ex) {
					ex.printStackTrace();
				}
			}

			collection("listEntries").updateMany(mongoSession,
					new Document("np", ac).append("pubkey", ph),
					new Document("$set", new Document("invalidated", true))
				);
			collection("trustEdges").updateMany(mongoSession,
					new Document("source", ac),
					new Document("$set", new Document("invalidated", true))
				);
		}

	}

	private static void addToList(ClientSession mongoSession, Nanopub nanopub, String pubkeyHash, String typeHash) {
		String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
		if (!has(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", typeHash))) {
			insert(mongoSession, "lists", new Document().append("pubkey", pubkeyHash).append("type", typeHash));
		}

		if (has(mongoSession, "listEntries", new Document("pubkey", pubkeyHash).append("type", typeHash).append("np", ac))) {
			System.err.println("Already listed: " + nanopub.getUri());
		} else {
			
			Document doc = getMaxValueDocument(mongoSession, "listEntries", new Document("pubkey", pubkeyHash).append("type", typeHash), "position");
			long position;
			String checksum;
			if (doc == null) {
				position = 0l;
				checksum = NanopubUtils.updateXorChecksum(nanopub.getUri(), NanopubUtils.INIT_CHECKSUM);
			} else {
				position = doc.getLong("position") + 1;
				checksum = NanopubUtils.updateXorChecksum(nanopub.getUri(), doc.getString("checksum"));
			}
			collection("listEntries").insertOne(mongoSession,
					new Document("pubkey", pubkeyHash)
						.append("type", typeHash)
						.append("position", position)
						.append("np", ac)
						.append("checksum", checksum)
						.append("invalidated", false)
				);
		}
	}

	public static String getPubkey(Nanopub nanopub) {
		NanopubSignatureElement el = null;
		try {
			el = SignatureUtils.getSignatureElement(nanopub);
		} catch (MalformedCryptoElementException ex) {
			ex.printStackTrace();
		}
		try {
			if (el != null && SignatureUtils.hasValidSignature(el) && el.getPublicKeyString() != null) {
				return el.getPublicKeyString();
			}
		} catch (GeneralSecurityException ex) {
			System.err.println("Error for signature element " + el.getUri());
			ex.printStackTrace();
		}
		return null;
	}

	private static boolean hasType(Nanopub nanopub, String type) {
		return true;
		// TODO We need to do a proper check here. Type applies also if the nanopub itself
		//      doesn't directly have the type but is an invalidation of a nanopub that has it.
//		if (type.equals("$")) return true;
//		for (IRI typeIri : NanopubUtils.getTypes(nanopub)) {
//			if (typeIri.stringValue().equals(type)) return true;
//		}
//		return false;
	}

	public static String calculateTrustStateHash(ClientSession mongoSession) {
		MongoCursor<Document> tp = collection("trustPaths_loading").find(mongoSession).sort(ascending("_id")).cursor();
		// TODO Improve this so we don't create the full string just for calculating its hash:
		String s = "";
		while (tp.hasNext()) {
			Document d = tp.next();
			s += d.getString("_id") + " (" + d.getString("type") + ")\n";
		}
		return Utils.getHash(s);
	}

}
