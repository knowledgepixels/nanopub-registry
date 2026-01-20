package com.knowledgepixels.registry;

import com.knowledgepixels.registry.db.IndexInitializer;
import com.mongodb.MongoClient;
import com.mongodb.MongoNamespace;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.UpdateOptions;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.jelly.JellyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;

import static com.mongodb.client.model.Indexes.ascending;

public class RegistryDB {

    private RegistryDB() {
    }

    private static final String REGISTRY_DB_NAME = Utils.getEnv("REGISTRY_DB_NAME", "nanopubRegistry");

    private static final Logger logger = LoggerFactory.getLogger(RegistryDB.class);

    private static MongoClient mongoClient;
    private static MongoDatabase mongoDB;

    /**
     * Returns the MongoDB database instance.
     *
     * @return the MongoDatabase instance
     */
    public static MongoDatabase getDB() {
        return mongoDB;
    }

    /**
     * Returns the MongoDB client instance.
     *
     * @return the MongoClient instance
     */
    public static MongoClient getClient() {
        return mongoClient;
    }

    /**
     * Returns the specified collection from the MongoDB database.
     *
     * @param name the name of the collection
     * @return the MongoCollection instance
     */
    public static MongoCollection<Document> collection(String name) {
        return mongoDB.getCollection(name);
    }

    /**
     * Initializes the MongoDB connection and sets up collections and indexes if not already initialized.
     */
    public static void init() {
        if (mongoClient != null) {
            logger.info("RegistryDB already initialized");
            return;
        }
        final String REGISTRY_DB_HOST = Utils.getEnv("REGISTRY_DB_HOST", "mongodb");
        final int REGISTRY_DB_PORT = Integer.parseInt(Utils.getEnv("REGISTRY_DB_PORT", String.valueOf(ServerAddress.defaultPort())));
        logger.info("Initializing RegistryDB connection to database '{}' at {}:{}", REGISTRY_DB_NAME, REGISTRY_DB_HOST, REGISTRY_DB_PORT);
        mongoClient = new MongoClient(REGISTRY_DB_HOST, REGISTRY_DB_PORT);
        mongoDB = mongoClient.getDatabase(REGISTRY_DB_NAME);

        try (ClientSession mongoSession = mongoClient.startSession()) {
            if (isInitialized(mongoSession)) {
                return;
            }
            IndexInitializer.initCollections(mongoSession);
        }
    }

    /**
     * Checks if the database has been initialized.
     *
     * @param mongoSession the MongoDB client session
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized(ClientSession mongoSession) {
        return getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId") != null;
    }

    /**
     * Renames a collection in the database. If the new collection name already exists, it will be dropped first.
     *
     * @param oldCollectionName the current name of the collection
     * @param newCollectionName the new name for the collection
     */
    public static void rename(String oldCollectionName, String newCollectionName) {
        // Designed as idempotent operation: calling multiple times has same effect as calling once
        if (hasCollection(oldCollectionName)) {
            if (hasCollection(newCollectionName)) {
                collection(newCollectionName).drop();
            }
            collection(oldCollectionName).renameCollection(new MongoNamespace(REGISTRY_DB_NAME, newCollectionName));
        }
    }

    /**
     * Checks if a collection with the given name exists in the database.
     *
     * @param collectionName the name of the collection to check
     * @return true if the collection exists, false otherwise
     */
    public static boolean hasCollection(String collectionName) {
        return mongoDB.listCollectionNames().into(new ArrayList<>()).contains(collectionName);
    }

    /**
     * Increases the trust state counter in the server info collection.
     *
     * @param mongoSession the MongoDB client session
     */
    public static void increaseStateCounter(ClientSession mongoSession) {
        MongoCursor<Document> cursor = collection(Collection.SERVER_INFO.toString()).find(mongoSession, new Document("_id", "trustStateCounter")).cursor();
        if (cursor.hasNext()) {
            long counter = cursor.next().getLong("value");
            collection(Collection.SERVER_INFO.toString()).updateOne(mongoSession, new Document("_id", "trustStateCounter"), new Document("$set", new Document("value", counter + 1)));
        } else {
            collection(Collection.SERVER_INFO.toString()).insertOne(mongoSession, new Document("_id", "trustStateCounter").append("value", 0L));
        }
    }

    /**
     * Checks if an element with the given name exists in the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param elementName  the name of the element used as the _id field
     * @return true if the element exists, false otherwise
     */
    public static boolean has(ClientSession mongoSession, String collection, String elementName) {
        return has(mongoSession, collection, new Document("_id", elementName));
    }

    private static final CountOptions hasCountOptions = new CountOptions().limit(1);

    /**
     * Checks if any document matching the given filter exists in the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param find         the filter to match documents
     * @return true if at least one matching document exists, false otherwise
     */
    public static boolean has(ClientSession mongoSession, String collection, Bson find) {
        return collection(collection).countDocuments(mongoSession, find, hasCountOptions) > 0;
    }

    /**
     * Retrieves a cursor for documents matching the given filter in the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param find         the filter to match documents
     * @return a MongoCursor for the matching documents
     */
    public static MongoCursor<Document> get(ClientSession mongoSession, String collection, Bson find) {
        return collection(collection).find(mongoSession, find).cursor();
    }

    /**
     * Retrieves the value of an element with the given name from the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param elementName  the name of the element used as the _id field
     * @return the value of the element, or null if not found
     */
    public static Object getValue(ClientSession mongoSession, String collection, String elementName) {
        Document d = collection(collection).find(mongoSession, new Document("_id", elementName)).first();
        if (d == null) {
            return null;
        }
        return d.get("value");
    }

    /**
     * Retrieves the boolean value of an element with the given name from the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param elementName  the name of the element used as the _id field
     * @return the value of the element, or null if not found
     */
    public static boolean isSet(ClientSession mongoSession, String collection, String elementName) {
        Document d = collection(collection).find(mongoSession, new Document("_id", elementName)).first();
        if (d == null) {
            return false;
        }
        return d.getBoolean("value");
    }

    /**
     * Retrieves a single document matching the given filter from the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param find         the filter to match the document
     * @return the matching document, or null if not found
     */
    public static Document getOne(ClientSession mongoSession, String collection, Bson find) {
        return collection(collection).find(mongoSession, find).first();
    }

    /**
     * Retrieves the maximum value of a specified field from the documents in the given collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param fieldName    the field for which to find the maximum value
     * @return the maximum value of the specified field, or null if no documents exist
     */
    public static Object getMaxValue(ClientSession mongoSession, String collection, String fieldName) {
        Document doc = collection(collection).find(mongoSession).projection(new Document(fieldName, 1)).sort(new Document(fieldName, -1)).first();
        if (doc == null) {
            return null;
        }
        return doc.get(fieldName);
    }

    /**
     * Retrieves the document with the maximum value of a specified field from the documents matching the given filter in the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param find         the filter to match documents
     * @param fieldName    the field for which to find the maximum value
     * @return the document with the maximum value of the specified field, or null if no matching documents exist
     */
    public static Document getMaxValueDocument(ClientSession mongoSession, String collection, Bson find, String fieldName) {
        return collection(collection).find(mongoSession, find).sort(new Document(fieldName, -1)).first();
    }

    /**
     * Sets or updates a document in the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param update       the document to set or update (must contain an _id field)
     */
    public static void set(ClientSession mongoSession, String collection, Document update) {
        Bson find = new Document("_id", update.get("_id"));
        MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).cursor();
        if (cursor.hasNext()) {
            collection(collection).updateOne(mongoSession, find, new Document("$set", update));
        }
    }

    /**
     * Inserts a document into the specified collection.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param doc          the document to insert
     */
    public static void insert(ClientSession mongoSession, String collection, Document doc) {
        collection(collection).insertOne(mongoSession, doc);
    }

    /**
     * Sets the value of an element with the given name in the specified collection.
     * If the element does not exist, it will be created.
     *
     * @param mongoSession the MongoDB client session
     * @param collection   the name of the collection
     * @param elementId    the name of the element used as the _id field
     * @param value        the value to set
     */
    public static void setValue(ClientSession mongoSession, String collection, String elementId, Object value) {
        collection(collection).updateOne(mongoSession, new Document("_id", elementId), new Document("$set", new Document("value", value)), new UpdateOptions().upsert(true));
    }

    /**
     * Records the hash of a given value in the "hashes" collection.
     * If the hash already exists, it will be ignored.
     *
     * @param mongoSession the MongoDB client session
     * @param value        the value to hash and record
     */
    public static void recordHash(ClientSession mongoSession, String value) {
        try {
            insert(mongoSession, "hashes", new Document("value", value).append("hash", Utils.getHash(value)));
        } catch (MongoWriteException e) {
            // Duplicate key error -- ignore it
            if (e.getError().getCode() != 11000) throw e;
        }
    }

    /**
     * Retrieves the original value corresponding to a given hash from the "hashes" collection.
     *
     * @param hash the hash to look up
     * @return the original value, or null if not found
     */
    public static String unhash(String hash) {
        try (var c = collection("hashes").find(new Document("hash", hash)).cursor()) {
            if (c.hasNext()) {
                return c.next().getString("value");
            }
            return null;
        }
    }

    /**
     * Loads a nanopublication into the database.
     *
     * @param mongoSession the MongoDB client session
     * @param nanopub      the nanopublication to load
     */
    public static boolean loadNanopub(ClientSession mongoSession, Nanopub nanopub) {
        return loadNanopub(mongoSession, nanopub, null);
    }

    /**
     * Loads a nanopublication into the database, optionally filtering by public key hash and types.
     *
     * @param mongoSession the MongoDB client session
     * @param nanopub      the nanopublication to load
     * @param pubkeyHash   the public key hash to filter by (can be null)
     * @param types        the types to filter by (can be empty)
     * @return true if the nanopublication was loaded, false otherwise
     */
    public static boolean loadNanopub(ClientSession mongoSession, Nanopub nanopub, String pubkeyHash, String... types) {
        if (nanopub.getTripleCount() > 1200) {
            logger.info("Nanopub has too many triples ({}): {}", nanopub.getTripleCount(), nanopub.getUri());
            return false;
        }
        if (nanopub.getByteCount() > 1000000) {
            logger.info("Nanopub is too large ({}): {}", nanopub.getByteCount(), nanopub.getUri());
            return false;
        }
        String pubkey = getPubkey(nanopub);
        if (pubkey == null) {
            logger.info("Ignoring invalid nanopub: {}", nanopub.getUri());
            return false;
        }
        String ph = Utils.getHash(pubkey);
        if (pubkeyHash != null && !pubkeyHash.equals(ph)) {
            logger.info("Ignoring nanopub with non-matching pubkey: {}", nanopub.getUri());
            return false;
        }
        recordHash(mongoSession, pubkey);

        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        if (ac == null) {
            // I don't think this ever happens, but checking here to be sure
            logger.info("ERROR. Unexpected Trusty URI: {}", nanopub.getUri());
            return false;
        }
        if (has(mongoSession, Collection.NANOPUBS.toString(), ac)) {
            logger.info("Already loaded: {}", nanopub.getUri());
        } else {
            Long counter = (Long) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "counter");
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
            collection(Collection.NANOPUBS.toString()).insertOne(mongoSession, new Document("_id", ac).append("fullId", nanopub.getUri().stringValue()).append("counter", counter + 1).append("pubkey", ph).append("content", nanopubString).append("jelly", new Binary(jellyContent)));

            for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(nanopub)) {
                String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
                if (invalidatedAc == null) continue;  // This should never happen; checking here just to be sure

                // Add this nanopub also to all lists of invalidated nanopubs:
                collection("invalidations").insertOne(mongoSession, new Document("invalidatingNp", ac).append("invalidatingPubkey", ph).append("invalidatedNp", invalidatedAc));
                MongoCursor<Document> invalidatedEntries = collection("listEntries").find(mongoSession, new Document("np", invalidatedAc).append("pubkey", ph)).cursor();
                while (invalidatedEntries.hasNext()) {
                    Document invalidatedEntry = invalidatedEntries.next();
                    addToList(mongoSession, nanopub, ph, invalidatedEntry.getString("type"));
                }

                collection("listEntries").updateMany(mongoSession, new Document("np", invalidatedAc).append("pubkey", ph), new Document("$set", new Document("invalidated", true)));
                collection("trustEdges").updateMany(mongoSession, new Document("source", invalidatedAc), new Document("$set", new Document("invalidated", true)));
            }
        }

        if (pubkeyHash != null) {
            for (String type : types) {
                // TODO Check if nanopub really has the type?
                addToList(mongoSession, nanopub, pubkeyHash, Utils.getTypeHash(mongoSession, type));
                if (type.equals("$")) {
                    for (IRI t : NanopubUtils.getTypes(nanopub)) {
                        addToList(mongoSession, nanopub, pubkeyHash, Utils.getTypeHash(mongoSession, t));
                    }
                }
            }
        }

        // Add the invalidating nanopubs also to the lists of this nanopub:
        try (MongoCursor<Document> invalidations = collection("invalidations").find(mongoSession, new Document("invalidatedNp", ac).append("invalidatingPubkey", ph)).cursor()) {
            if (invalidations.hasNext()) {
                collection("listEntries").updateMany(mongoSession, new Document("np", ac).append("pubkey", ph), new Document("$set", new Document("invalidated", true)));
                collection("trustEdges").updateMany(mongoSession, new Document("source", ac), new Document("$set", new Document("invalidated", true)));
            }
            while (invalidations.hasNext()) {
                String iac = invalidations.next().getString("invalidatingNp");
                try {
                    Document npDoc = collection(Collection.NANOPUBS.toString()).find(mongoSession, new Document("_id", iac)).projection(new Document("jelly", 1)).first();
                    Nanopub inp = JellyUtils.readFromDB(npDoc.get("jelly", Binary.class).getData());
                    for (IRI type : NanopubUtils.getTypes(inp)) {
                        addToList(mongoSession, inp, ph, Utils.getTypeHash(mongoSession, type));
                    }
                } catch (RDF4JException | MalformedNanopubException ex) {
                    ex.printStackTrace();
                }
            }

        }

        return true;
    }

    private static void addToList(ClientSession mongoSession, Nanopub nanopub, String pubkeyHash, String typeHash) {
        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        try {
            insert(mongoSession, "lists", new Document("pubkey", pubkeyHash).append("type", typeHash));
        } catch (MongoWriteException e) {
            // Duplicate key error -- ignore it
            if (e.getError().getCode() != 11000) throw e;
        }

        if (has(mongoSession, "listEntries", new Document("pubkey", pubkeyHash).append("type", typeHash).append("np", ac))) {
            logger.info("Already listed: {}", nanopub.getUri());
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
            collection("listEntries").insertOne(mongoSession, new Document("pubkey", pubkeyHash).append("type", typeHash).append("position", position).append("np", ac).append("checksum", checksum).append("invalidated", false));
        }
    }

    /**
     * Returns the public key string of the Nanopub's signature, or null if not available or invalid.
     *
     * @param nanopub the nanopub to extract the public key from
     * @return The public key string, or null if not available or invalid.
     */
    public static String getPubkey(Nanopub nanopub) {
        // TODO shouldn't this be moved to a utility class in nanopub-java? there is a similar method in NanopubElement class of nanodash
        NanopubSignatureElement el;
        try {
            el = SignatureUtils.getSignatureElement(nanopub);
            if (el != null && SignatureUtils.hasValidSignature(el) && el.getPublicKeyString() != null) {
                return el.getPublicKeyString();
            }
        } catch (MalformedCryptoElementException | GeneralSecurityException ex) {
            logger.error("Error in checking the signature of the nanopub {}", nanopub.getUri());
        }
        return null;
    }

    /**
     * Calculates a hash representing the current state of the trust paths in the loading collection.
     *
     * @param mongoSession the MongoDB client session
     * @return the calculated trust state hash
     */
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
