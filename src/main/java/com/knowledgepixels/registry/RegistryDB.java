package com.knowledgepixels.registry;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;

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

	private static final String REGISTRY_DB_NAME = Utils.getEnv("NANOPUB_REGISTRY_DB_NAME", "nanopub-registry");
	private static final String REGISTRY_DB_HOST = Utils.getEnv("NANOPUB_REGISTRY_DB_HOST", "mongodb");

	private static MongoClient mongoClient;
	private static MongoDatabase mongoDB;
	static ClientSession mongoSession;

	public static MongoDatabase getDB() {
		return mongoDB;
	}

	public static MongoCollection<Document> collection(String name) {
		return mongoDB.getCollection(name);
	}

	private final static IndexOptions unique = new IndexOptions().unique(true);

	public static void init() {
		if (mongoClient != null) return;
		mongoClient = new MongoClient(REGISTRY_DB_HOST);
		mongoDB = mongoClient.getDatabase(REGISTRY_DB_NAME);
		mongoSession = mongoClient.startSession();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> mongoSession.close()));

		if (isInitialized()) return;

		final IndexOptions unique = new IndexOptions().unique(true);

		collection("tasks").createIndex(mongoSession, Indexes.descending("not-before"));

		collection("nanopubs").createIndex(mongoSession, ascending("full-id"), unique);
		collection("nanopubs").createIndex(mongoSession, descending("counter"), unique);
		collection("nanopubs").createIndex(mongoSession, ascending("pubkey"));

		collection("lists").createIndex(mongoSession, ascending("pubkey", "type"), unique);
		collection("lists").createIndex(mongoSession, ascending("status"));

		collection("list-entries").createIndex(mongoSession, ascending("np"));
		collection("list-entries").createIndex(mongoSession, ascending("pubkey", "type", "np"), unique);
		collection("list-entries").createIndex(mongoSession, compoundIndex(ascending("pubkey"), ascending("type"), descending("position")), unique);
		collection("list-entries").createIndex(mongoSession, ascending("pubkey", "type", "checksum"), unique);
		collection("list-entries").createIndex(mongoSession, ascending("invalidated"));

		collection("invalidations").createIndex(mongoSession, ascending("invalidating-np"));
		collection("invalidations").createIndex(mongoSession, ascending("invalidating-pubkey"));
		collection("invalidations").createIndex(mongoSession, ascending("invalidated-np"));
		collection("invalidations").createIndex(mongoSession, ascending("invalidating-pubkey", "invalidated-np"));
	}

	public static void initLoadingCollections() {
		collection("endorsements_loading").createIndex(mongoSession, ascending("agent"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("pubkey"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("endorsed-nanopub"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("source"));
		collection("endorsements_loading").createIndex(mongoSession, ascending("status"));

		collection("agents_loading").createIndex(mongoSession, ascending("agent"), unique);
		collection("agents_loading").createIndex(mongoSession, descending("account-count"));
		collection("agents_loading").createIndex(mongoSession, descending("avg-path-count"));
		collection("agents_loading").createIndex(mongoSession, descending("total-ratio"));

		collection("agent-accounts_loading").createIndex(mongoSession, ascending("agent"));
		collection("agent-accounts_loading").createIndex(mongoSession, ascending("pubkey"));
		collection("agent-accounts_loading").createIndex(mongoSession, ascending("agent", "pubkey"), unique);
		collection("agent-accounts_loading").createIndex(mongoSession, ascending("type"));
		collection("agent-accounts_loading").createIndex(mongoSession, ascending("status"));
		collection("agent-accounts_loading").createIndex(mongoSession, descending("ratio"));
		collection("agent-accounts_loading").createIndex(mongoSession, descending("path-count"));

		collection("trust-paths_loading").createIndex(mongoSession, ascending("agent", "pubkey", "depth", "sorthash"), unique);
		collection("trust-paths_loading").createIndex(mongoSession, ascending("depth"));
		collection("trust-paths_loading").createIndex(mongoSession, descending("ratio"));

		// TODO This was supposed to be a collection that doesn't need regeneration at each update,
		//      but it is currently necessary. Updating on an existing 'trust-edges' collection leads
		//      to not all agents being loaded/approved.
		collection("trust-edges_loading").createIndex(mongoSession, ascending("from-agent"));
		collection("trust-edges_loading").createIndex(mongoSession, ascending("from-pubkey"));
		collection("trust-edges_loading").createIndex(mongoSession, ascending("to-agent"));
		collection("trust-edges_loading").createIndex(mongoSession, ascending("to-pubkey"));
		collection("trust-edges_loading").createIndex(mongoSession, ascending("source"));
		collection("trust-edges_loading").createIndex(mongoSession, ascending("from-agent", "from-pubkey", "to-agent", "to-pubkey", "source"), unique);
		collection("trust-edges_loading").createIndex(mongoSession, ascending("invalidated"));
	}

	public static boolean isInitialized() {
		return getValue("server-info", "setup-id") != null;
	}

	public synchronized static void startTransaction() {
		if (mongoSession.hasActiveTransaction()) throw new RuntimeException("Cannot start transaction: one already running");
		mongoSession.startTransaction();
	}

	public synchronized static void commitTransaction() {
		mongoSession.commitTransaction();
	}

	public synchronized static void abortTransaction(String message) {
		boolean successful = false;
		while (!successful) {
			try {
				setValue("server-info", "status", "error");
				setValue("server-info", "status-details", message);
				cleanTransaction();
				successful = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException iex) {
					iex.printStackTrace();
				}
			}
		}
	}

	public synchronized static void cleanTransactionWithRetry() {
		boolean successful = false;
		while (!successful) {
			try {
				cleanTransaction();
				successful = true;
			} catch (Exception ex) {
				ex.printStackTrace();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException iex) {
					iex.printStackTrace();
				}
			}
		}
	}

	public synchronized static void cleanTransaction() {
		if (mongoSession.hasActiveTransaction()) {
			mongoSession.abortTransaction();
		}
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

	public static void increaseStateCounter() {
		MongoCursor<Document> cursor = collection("server-info").find(mongoSession, new Document("_id", "state-counter")).cursor();
		if (cursor.hasNext()) {
			long counter = cursor.next().getLong("value");
			collection("server-info").updateOne(mongoSession, new Document("_id", "state-counter"), new Document("$set", new Document("value", counter + 1)));
		} else {
			collection("server-info").insertOne(mongoSession, new Document("_id", "state-counter").append("value", 0l));
		}
	}

	public static boolean has(String collection, String elementName) {
		return has(collection, new Document("_id", elementName));
	}

	public static boolean has(String collection, Bson find) {
		return collection(collection).find(mongoSession, find).cursor().hasNext();
	}

	public static MongoCursor<Document> get(String collection, Bson find) {
		return collection(collection).find(mongoSession, find).cursor();
	}

	public static Object getValue(String collection, String elementName) {
		Document d = collection(collection).find(mongoSession, new Document("_id", elementName)).first();
		if (d == null) return null;
		return d.get("value");
	}

	public static Document getOne(String collection, Bson find) {
		return collection(collection).find(mongoSession, find).first();
	}

	public static Document getOne(String collection, Bson find, Bson sort) {
		return collection(collection).find(mongoSession, find).sort(sort).first();
	}

	public static Object getMaxValue(String collection, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(mongoSession).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Object getMaxValue(String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Document getMaxValueDocument(String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).sort(new Document(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next();
	}

	public static void set(String collection, Document update) {
		Bson find = new Document("_id", update.get("_id"));
		MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(mongoSession, find, new Document("$set", update));
		}
	}

	public static void insert(String collection, Document doc) {
		collection(collection).insertOne(mongoSession, doc);
	}

	public static void setValue(String collection, String elementId, Object value) {
		collection(collection).updateOne(mongoSession,
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
			collection("nanopubs").insertOne(mongoSession,
					new Document("_id", ac)
						.append("full-id", nanopub.getUri().stringValue())
						.append("counter", counter + 1)
						.append("pubkey", ph)
						.append("content", nanopubString)
				);

			for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(nanopub)) {
				String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
				collection("invalidations").insertOne(mongoSession,
						new Document("invalidating-np", ac)
							.append("invalidating-pubkey", ph)
							.append("invalidated-np", invalidatedAc)
					);
				collection("list-entries").updateMany(mongoSession,
						new Document("np", invalidatedAc).append("pubkey", ph),
						new Document("$set", new Document("invalidated", true))
					);
				collection("trust-edges_loading").updateMany(mongoSession,
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
				collection("list-entries").insertOne(mongoSession,
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
			collection("list-entries").updateMany(mongoSession,
					new Document("np", ac).append("pubkey", ph),
					new Document("$set", new Document("invalidated", true))
				);
			collection("trust-edges_loading").updateMany(mongoSession,
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
