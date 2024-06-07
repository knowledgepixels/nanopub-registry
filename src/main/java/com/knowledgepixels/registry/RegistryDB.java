package com.knowledgepixels.registry;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

import java.security.GeneralSecurityException;
import java.util.Set;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.setting.IntroNanopub;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

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

		collection("invalidations").createIndex(ascending("invalidating-np"));
		collection("invalidations").createIndex(ascending("invalidating-pubkey"));
		collection("invalidations").createIndex(ascending("invalidated-np"));
		collection("invalidations").createIndex(ascending("invalidating-pubkey", "invalidated-np"));

		collection("pubkey-declarations").createIndex(ascending("agent"));
		collection("pubkey-declarations").createIndex(ascending("pubkey"));
		collection("pubkey-declarations").createIndex(ascending("declaration-pubkey"));
		collection("pubkey-declarations").createIndex(ascending("declaration"));
		collection("pubkey-declarations").createIndex(ascending("type"));
		collection("pubkey-declarations").createIndex(ascending("status"));

		collection("agents").createIndex(ascending("agent"));
		collection("agents").createIndex(ascending("pubkey"));
		collection("agents").createIndex(ascending("agent", "pubkey"), unique);
		collection("agents").createIndex(ascending("type"));

		collection("trust-edges").createIndex(ascending("from-agent"));
		collection("trust-edges").createIndex(ascending("from-pubkey"));
		collection("trust-edges").createIndex(ascending("to-agent"));
		collection("trust-edges").createIndex(ascending("to-pubkey"));
		collection("trust-edges").createIndex(ascending("source"));

		collection("trust-paths").createIndex(ascending("agent", "pubkey"));
		collection("trust-edges").createIndex(ascending("source"));
	}

	public static boolean isInitialized() {
		return get("server-info", "setup-id") != null;
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

	public static boolean has(String collection, String elementName) {
		return has(collection, new BasicDBObject("_id", elementName));
	}

	public static boolean has(String collection, Bson find) {
		return collection(collection).find(find).cursor().hasNext();
	}

	public static boolean hasStrongInvalidation(String npId, String pubkey) {
		return has("invalidations", new BasicDBObject("invalidated-np", npId).append("invalidating-pubkey", pubkey));
	}

	public static Object get(String collection, String elementName) {
		return get(collection, new BasicDBObject("_id", elementName), "value");
	}

	public static Object get(String collection, Bson find, String field) {
		MongoCursor<Document> cursor = collection(collection).find(find).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(field);
	}

	public static Object getMaxValue(String collection, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find().sort(new BasicDBObject(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Object getMaxValue(String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(find).sort(new BasicDBObject(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static Document getMaxValueDocument(String collection, Bson find, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find(find).sort(new BasicDBObject(fieldName, -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next();
	}

	public static void set(String collection, String elementId, Object value) {
		MongoCursor<Document> cursor = collection(collection).find(new BasicDBObject("_id", elementId)).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(new BasicDBObject("_id", elementId), new BasicDBObject("$set", new BasicDBObject("value", value)));
		} else {
			collection(collection).insertOne(new Document("_id", elementId).append("value", value));
		}
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
			collection("nanopubs").insertOne(
					new Document("_id", ac)
						.append("full-id", nanopub.getUri().stringValue())
						.append("counter", counter + 1)
						.append("pubkey", ph)
						.append("content", NanopubUtils.writeToString(nanopub, RDFFormat.TRIG))
				);

			for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(nanopub)) {
				String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
				collection("invalidations").insertOne(
						new Document("invalidating-np", ac)
							.append("invalidating-pubkey", ph)
							.append("invalidated-np", invalidatedAc)
					);
			}
			final Set<IRI> types = NanopubUtils.getTypes(nanopub);
			if (types.contains(Utils.INTRO_TYPE)) {
				IntroNanopub introNp = new IntroNanopub(nanopub);
				for (KeyDeclaration kd : introNp.getKeyDeclarations()) {
					if (kd.getDeclarers().size() != 1) {
						System.err.println("Ignoring intro with invalid number of declarers: " + nanopub.getUri());
					}
					collection("pubkey-declarations").insertOne(
							new Document("agent", kd.getDeclarers().iterator().next().stringValue())
								.append("pubkey", kd.getPublicKeyString())
								.append("declaration-pubkey", ph)
								.append("declaration", ac)
						);
				}
			}
			if (types.contains(Utils.APPROVAL_TYPE)) {
				for (Statement st : nanopub.getAssertion()) {
					if (!st.getPredicate().equals(Utils.APPROVES_OF)) continue;
					if (!(st.getObject() instanceof IRI)) continue;
					String objStr = st.getObject().stringValue();
					if (!TrustyUriUtils.isPotentialTrustyUri(objStr)) continue;
					String objAc = TrustyUriUtils.getArtifactCode(objStr);
					MongoCursor<Document> c = get("pubkey-declarations", new BasicDBObject("declaration", objAc));
					if (!c.hasNext()) System.err.println("NOT FOUND: " + objAc);
					// TODO: deal with case when declaration isn't loaded yet
					while (c.hasNext()) {
						Document d = c.next();
						collection("trust-edges").insertOne(
								new Document("from-agent", st.getSubject().stringValue())
									.append("from-pubkey", ph)
									.append("to-agent", d.getString("agent"))
									.append("to-pubkey", d.getString("pubkey"))
									.append("source", ac)
							);
					}
				}
			}
		}

		if (type != null && ph.equals(pubkeyHash) && hasType(nanopub, type)) {
			String typeHash = Utils.getHash(type);
	
			if (has("list-entries", new BasicDBObject("pubkey", ph).append("type", typeHash).append("np", ac))) {
				System.err.println("Already listed: " + nanopub.getUri());
			} else {
				
				Document doc = getMaxValueDocument("list-entries", new BasicDBObject("pubkey", ph).append("type", typeHash), "position");
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
					);
			}
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
