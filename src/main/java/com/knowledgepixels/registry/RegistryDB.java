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
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;

import net.trustyuri.TrustyUriUtils;

public class RegistryDB {

	private RegistryDB() {}

	private static final String REGISTRY_DB_NAME = "nanopub-registry";

	private static MongoClient mongoClient;
	private static MongoDatabase mongoDB;

	public static MongoDatabase getDB() {
		return mongoDB;
	}

	public static MongoCollection<Document> collection(String name) {
		return mongoDB.getCollection(name);
	}

	private final static IndexOptions unique = new IndexOptions().unique(true);

	public static void init() {
		if (mongoClient != null) return;
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase(REGISTRY_DB_NAME);

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
		collection("list-entries").createIndex(compoundIndex(ascending("pubkey"), ascending("type"), descending("position")), unique);
		collection("list-entries").createIndex(ascending("pubkey", "type", "checksum"), unique);
		collection("list-entries").createIndex(ascending("invalidated"));

		collection("invalidations").createIndex(ascending("invalidating-np"));
		collection("invalidations").createIndex(ascending("invalidating-pubkey"));
		collection("invalidations").createIndex(ascending("invalidated-np"));
		collection("invalidations").createIndex(ascending("invalidating-pubkey", "invalidated-np"));

		collection("trust-edges").createIndex(ascending("from-agent"));
		collection("trust-edges").createIndex(ascending("from-pubkey"));
		collection("trust-edges").createIndex(ascending("to-agent"));
		collection("trust-edges").createIndex(ascending("to-pubkey"));
		collection("trust-edges").createIndex(ascending("source"));
		collection("trust-edges").createIndex(ascending("from-agent", "from-pubkey", "to-agent", "to-pubkey", "source"), unique);
		collection("trust-edges").createIndex(ascending("invalidated"));

		initLoadingCollections();
	}

	public static void initLoadingCollections() {
		collection("endorsements_loading").createIndex(ascending("agent"));
		collection("endorsements_loading").createIndex(ascending("pubkey"));
		collection("endorsements_loading").createIndex(ascending("endorsed-nanopub"));
		collection("endorsements_loading").createIndex(ascending("source"));
		collection("endorsements_loading").createIndex(ascending("status"));

		collection("agents_loading").createIndex(ascending("agent"), unique);
		collection("agents_loading").createIndex(descending("account-count"));
		collection("agents_loading").createIndex(descending("avg-path-count"));
		collection("agents_loading").createIndex(descending("total-ratio"));

		collection("agent-accounts_loading").createIndex(ascending("agent"));
		collection("agent-accounts_loading").createIndex(ascending("pubkey"));
		collection("agent-accounts_loading").createIndex(ascending("agent", "pubkey"), unique);
		collection("agent-accounts_loading").createIndex(ascending("type"));
		collection("agent-accounts_loading").createIndex(ascending("status"));
		collection("agent-accounts_loading").createIndex(descending("ratio"));
		collection("agent-accounts_loading").createIndex(descending("path-count"));

		collection("trust-paths_loading").createIndex(ascending("agent", "pubkey", "depth", "sorthash"), unique);
		collection("trust-paths_loading").createIndex(ascending("depth"));
		collection("trust-paths_loading").createIndex(descending("ratio"));
	}

	public static boolean isInitialized() {
		return getValue("server-info", "setup-id") != null;
	}

	public static void drop(String collection) {
		if (mongoDB.getCollection(collection) == null) return;
		mongoDB.getCollection(collection).drop();
	}

	public static void rename(String oldCollectionName, String newCollectionName) {
		drop(newCollectionName);
		collection(oldCollectionName).renameCollection(new MongoNamespace(REGISTRY_DB_NAME, newCollectionName));
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

	public static MongoCursor<Document> get(String collection, Bson find) {
		return collection(collection).find(find).cursor();
	}

	public static Object getValue(String collection, String elementName) {
		Document d = collection(collection).find(new Document("_id", elementName)).first();
		if (d == null) return null;
		return d.get("value");
	}

	public static Document getOne(String collection, Bson find) {
		return collection(collection).find(find).first();
	}

	public static Document getOne(String collection, Bson find, Bson sort) {
		return collection(collection).find(find).sort(sort).first();
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

	public static void set(String collection, Document update) {
		Bson find = new Document("_id", update.get("_id"));
		MongoCursor<Document> cursor = collection(collection).find(find).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(find, new Document("$set", update));
		}
	}

	public static void insert(String collection, Document doc) {
		collection(collection).insertOne(doc);
	}

	public static void upsert(String collection, Document doc) {
		collection(collection).insertOne(doc);
	}

	public static void setValue(String collection, String elementId, Object value) {
		collection(collection).updateOne(
				new Document("_id", elementId),
				new Document("$set", new Document("value", value)),
				new UpdateOptions().upsert(true)
			);
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
			insert("pubkeys", new Document("_id", ph).append("full-pubkey", pubkey));
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
