package com.knowledgepixels.registry;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;

import net.trustyuri.TrustyUriUtils;

public class RegistryDB {

	private RegistryDB() {}

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

		if (isInitialized()) return;

		final IndexOptions unique = new IndexOptions().unique(true);

		collection("tasks").createIndex(Indexes.descending("not-before"));

		collection("nanopubs").createIndex(ascending("full-id"), unique);
		collection("nanopubs").createIndex(descending("counter"), unique);
		collection("nanopubs").createIndex(ascending("pubkey"));

		collection("lists").createIndex(ascending("pubkey", "type"), unique);
		collection("lists").createIndex(ascending("status"));

		collection("list-entries").createIndex(ascending("np"));
		collection("list-entries").createIndex(ascending("pubkey", "type", "np"), unique);
		collection("list-entries").createIndex(compoundIndex(Indexes.ascending("pubkey"), ascending("type"), descending("position")), unique);
		collection("list-entries").createIndex(ascending("pubkey", "type", "checksum"), unique);
		collection("list-entries").createIndex(ascending("invalidated"));

		collection("invalidations").createIndex(ascending("invalidating-np"));
		collection("invalidations").createIndex(ascending("invalidating-pubkey"));
		collection("invalidations").createIndex(ascending("invalidated-np"));
		collection("invalidations").createIndex(ascending("invalidating-pubkey", "invalidated-np"));

		collection("endorsements").createIndex(ascending("agent"));
		collection("endorsements").createIndex(ascending("pubkey"));
		collection("endorsements").createIndex(ascending("endorsed-nanopub"));
		collection("endorsements").createIndex(ascending("source"));
		collection("endorsements").createIndex(ascending("status"));

		collection("agents").createIndex(ascending("agent"));
		collection("agents").createIndex(ascending("pubkey"));
		collection("agents").createIndex(ascending("agent", "pubkey"), unique);
		collection("agents").createIndex(ascending("type"));
		collection("agents").createIndex(ascending("status"));

		collection("trust-edges").createIndex(ascending("from-agent"));
		collection("trust-edges").createIndex(ascending("from-pubkey"));
		collection("trust-edges").createIndex(ascending("to-agent"));
		collection("trust-edges").createIndex(ascending("to-pubkey"));
		collection("trust-edges").createIndex(ascending("source"));
		collection("trust-edges").createIndex(ascending("from-agent", "from-pubkey", "to-agent", "to-pubkey", "source"), unique);
		collection("trust-edges").createIndex(ascending("invalidated"));

		collection("trust-paths").createIndex(ascending("agent", "pubkey", "depth", "sorthash"), unique);
		collection("trust-paths").createIndex(ascending("depth"));
		collection("trust-paths").createIndex(descending("ratio"));
	}

	public static boolean isInitialized() {
		return get("server-info", "setup-id") != null;
	}

	public static void increateStateCounter() {
		MongoCursor<Document> cursor = collection("server-info").find(new Document("_id", "state-counter")).cursor();
		if (cursor.hasNext()) {
			long counter = cursor.next().getLong("value");
			collection("server-info").updateOne(new Document("_id", "state-counter"), new Document("$set", new Document("value", counter + 1)));
		} else {
			collection("server-info").insertOne(new Document("_id", "state-counter").append("value", 0l));
		}
	}

	public static boolean has(String collection, String elementName) {
		return has(collection, new Document("_id", elementName));
	}

	public static boolean has(String collection, Bson find) {
		return collection(collection).find(find).cursor().hasNext();
	}

	public static boolean hasStrongInvalidation(String npId, String pubkey) {
		return has("invalidations", new Document("invalidated-np", npId).append("invalidating-pubkey", pubkey));
	}

	public static Object get(String collection, String elementName) {
		return get(collection, new Document("_id", elementName), "value");
	}

	public static Object get(String collection, Bson find, String field) {
		MongoCursor<Document> cursor = collection(collection).find(find).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(field);
	}

	public static Object getMaxValue(String collection, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find().sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Object getMaxValue(String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(find).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Document getMaxValueDocument(String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(find).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next();
	}

	public static void set(String collection, Bson find, Bson set) {
		MongoCursor<Document> cursor = collection(collection).find(find).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(find, new Document("$set", set));
		}
	}

	public static void set(String collection, Document doc, Bson set) {
		Bson find = new Document("_id", doc.get("_id"));
		set(collection, find, set);
	}

	public static void add(String collection, Document doc) {
		collection(collection).insertOne(doc);
	}

	public static MongoCursor<Document> get(String collection) {
		return collection(collection).find().cursor();
	}

	public static MongoCursor<Document> get(String collection, Bson find) {
		return collection(collection).find(find).cursor();
	}

	public static Document getOne(String collection, Bson find) {
		MongoCursor<Document> cursor = collection(collection).find(find).cursor();
		if (cursor.hasNext()) return cursor.next();
		return null;
	}

	public static void upsert(String collection, String elementId, Object value) {
		upsert(collection, new Document("_id", elementId), new Document("value", value));
	}

	public static void upsert(String collection, Bson find, Bson update) {
		collection(collection).updateOne(find, new Document("$set", update), new UpdateOptions().upsert(true));
	}

	public static void loadNanopub(Nanopub nanopub) {
		loadNanopub(nanopub, null, null);
	}

	public static void loadNanopub(Nanopub nanopub, String type, String pubkeyHash) {
		String pubkey = getPubkey(nanopub);
		if (pubkey == null) {
			System.err.println("Ignoring invalid nanopub: " + nanopub.getUri());
			return;
		}
		String ph = Utils.getHash(pubkey);
		if (!has("pubkeys", ph)) {
			add("pubkeys", new Document("_id", ph).append("full-pubkey", pubkey));
		}

		String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
		if (has("nanopubs", ac)) {
			System.err.println("Already loaded: " + nanopub.getUri());
		} else {
			Long counter = (Long) getMaxValue("nanopubs", "counter");
			if (counter == null) counter = 0l;
			String nanopubString;
			try {
				nanopubString = NanopubUtils.writeToString(nanopub, RDFFormat.TRIG);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
			collection("nanopubs").insertOne(
					new Document("_id", ac)
						.append("full-id", nanopub.getUri().stringValue())
						.append("counter", counter + 1)
						.append("pubkey", ph)
						.append("content", nanopubString)
				);

			for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(nanopub)) {
				String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
				collection("invalidations").insertOne(
						new Document("invalidating-np", ac)
							.append("invalidating-pubkey", ph)
							.append("invalidated-np", invalidatedAc)
					);
				collection("list-entries").updateMany(
						new Document("np", invalidatedAc).append("pubkey", ph),
						new Document("$set", new Document("invalidated", true))
					);
				collection("trust-edges").updateMany(
						new Document("source", invalidatedAc),
						new Document("$set", new Document("invalidated", true))
					);
			}
		}

		if (type != null && ph.equals(pubkeyHash) && hasType(nanopub, type)) {
			String typeHash = Utils.getHash(type);
	
			if (has("list-entries", new Document("pubkey", ph).append("type", typeHash).append("np", ac))) {
				System.err.println("Already listed: " + nanopub.getUri());
			} else {
				
				Document doc = getMaxValueDocument("list-entries", new Document("pubkey", ph).append("type", typeHash), "position");
				long position;
				String checksum;
				if (doc == null) {
					position = 0l;
					checksum = NanopubUtils.updateXorChecksum(nanopub.getUri(), NanopubUtils.INIT_CHECKSUM);
				} else {
					position = doc.getLong("position") + 1;
					checksum = NanopubUtils.updateXorChecksum(nanopub.getUri(), doc.getString("checksum"));
				}
				collection("list-entries").insertOne(
						new Document("pubkey", ph)
							.append("type", typeHash)
							.append("position", position)
							.append("np", ac)
							.append("checksum", checksum)
							.append("invalidated", false)
					);
			}

		}

		if (has("invalidations", new Document("invalidated-np", ac).append("invalidating-pubkey", ph))) {
			collection("list-entries").updateMany(
					new Document("np", ac).append("pubkey", ph),
					new Document("$set", new Document("invalidated", true))
				);
			collection("trust-edges").updateMany(
					new Document("source", ac),
					new Document("$set", new Document("invalidated", true))
				);
		}

	}

	private static String getPubkey(Nanopub nanopub) {
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
		for (IRI typeIri : NanopubUtils.getTypes(nanopub)) {
			if (typeIri.stringValue().equals(type)) return true;
		}
		return false;
	}

}
