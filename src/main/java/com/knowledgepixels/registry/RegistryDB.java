package com.knowledgepixels.registry;

import com.knowledgepixels.registry.db.IndexInitializer;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoNamespace;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.server.NanopubServerUtils;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.jelly.JellyUtils;
import org.nanopub.trusty.TrustyNanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            logger.info("RegistryDB already initialized (database: {})", REGISTRY_DB_NAME);
            return;
        }
        final String REGISTRY_DB_HOST = Utils.getEnv("REGISTRY_DB_HOST", "mongodb");
        final int REGISTRY_DB_PORT = Integer.parseInt(Utils.getEnv("REGISTRY_DB_PORT", String.valueOf(ServerAddress.defaultPort())));
        logger.info("Initializing RegistryDB connection to database '{}' at {}:{}", REGISTRY_DB_NAME, REGISTRY_DB_HOST, REGISTRY_DB_PORT);
        mongoClient = MongoClients.create("mongodb://" + REGISTRY_DB_HOST + ":" + REGISTRY_DB_PORT);
        mongoDB = mongoClient.getDatabase(REGISTRY_DB_NAME);

        try (ClientSession mongoSession = mongoClient.startSession()) {
            logger.debug("MongoDB client session started for initialization");
            if (!isInitialized(mongoSession)) {
                logger.info("Database '{}' not initialized; creating collections and indexes", REGISTRY_DB_NAME);
                IndexInitializer.initCollections(mongoSession);
            } else {
                logger.debug("Database '{}' already has setupId", REGISTRY_DB_NAME);
            }
            initCounter(mongoSession);
        }
    }

    /**
     * Checks if the database has been initialized.
     *
     * @param mongoSession the MongoDB client session
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized(ClientSession mongoSession) {
        boolean initialized = getValue(mongoSession, Collection.SERVER_INFO.toString(), "setupId") != null;
        logger.debug("isInitialized check for database '{}': {}", REGISTRY_DB_NAME, initialized);
        return initialized;
    }

    /**
     * The staging collections that a trust-state cycle rebuilds from scratch, each mapped to the
     * live collection it is promoted to when the cycle completes.
     *
     * @see #promoteLoadingCollections()
     * @see #dropLoadingCollections()
     */
    private static final Map<String, String> LOADING_COLLECTIONS = createLoadingCollectionsMap();

    private static Map<String, String> createLoadingCollectionsMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("accounts_loading", Collection.ACCOUNTS.toString());
        map.put("trustPaths_loading", "trustPaths");
        map.put("agents_loading", Collection.AGENTS.toString());
        map.put("endorsements_loading", "endorsements");
        return Collections.unmodifiableMap(map);
    }

    /**
     * Discards all staging collections of a trust-state cycle.
     *
     * <p>Designed as an idempotent operation: in a healthy run the collections do not exist at this
     * point, as {@link #promoteLoadingCollections()} renamed them away at the end of the previous
     * cycle. Anything left behind is a half-built cycle from an interrupted run, which must be
     * discarded rather than added to: the cycle tasks insert into these collections unconditionally
     * and would otherwise fail on duplicate keys forever (issue #50).
     */
    public static void dropLoadingCollections() {
        for (String loadingCollectionName : LOADING_COLLECTIONS.keySet()) {
            if (hasCollection(loadingCollectionName)) {
                logger.info("Discarding leftover staging collection '{}'", loadingCollectionName);
                collection(loadingCollectionName).drop();
            }
        }
    }

    /**
     * Promotes the staging collections of a completed trust-state cycle to their live names,
     * replacing the previous cycle's data.
     *
     * <p>Designed as an idempotent operation, since {@link #rename} is.
     */
    public static void promoteLoadingCollections() {
        for (Map.Entry<String, String> entry : LOADING_COLLECTIONS.entrySet()) {
            rename(entry.getKey(), entry.getValue());
        }
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
        boolean exists = mongoDB.listCollectionNames().into(new ArrayList<>()).contains(collectionName);
        logger.debug("Collection existence check for '{}': {}", collectionName, exists);
        return exists;
    }

    /**
     * Increases the trust state counter in the server info collection.
     *
     * @param mongoSession the MongoDB client session
     */
    public static void increaseStateCounter(ClientSession mongoSession) {
        try (MongoCursor<Document> cursor = collection(Collection.SERVER_INFO.toString()).find(mongoSession, new Document("_id", "trustStateCounter")).cursor()) {
            if (cursor.hasNext()) {
                long counter = cursor.next().getLong("value");
                collection(Collection.SERVER_INFO.toString()).updateOne(mongoSession, new Document("_id", "trustStateCounter"), new Document("$set", new Document("value", counter + 1)));
                logger.debug("Incremented trustStateCounter from {} to {}", counter, counter + 1);
            } else {
                collection(Collection.SERVER_INFO.toString()).insertOne(mongoSession, new Document("_id", "trustStateCounter").append("value", 0L));
                logger.info("Initialized trustStateCounter to 0 in collection '{}'", Collection.SERVER_INFO);
            }
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
        boolean found = collection(collection).countDocuments(mongoSession, find, hasCountOptions) > 0;
        logger.debug("Existence check in collection '{}' for filter {}: {}", collection, find, found);
        return found;
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
        logger.trace("Querying collection '{}' with filter {}", collection, find);
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
        logger.debug("Reading value of element '{}' from collection '{}'", elementName, collection);
        Document d = collection(collection).find(mongoSession, new Document("_id", elementName)).first();
        if (d == null) {
            logger.trace("Element '{}' not found in collection '{}'", elementName, collection);
            return null;
        }
        Object value = d.get("value");
        logger.debug("Found element '{}' in collection '{}' with value type {}", elementName, collection, value == null ? "null" : value.getClass().getSimpleName());
        return value;
    }

    /**
     * Artifact codes of the setting nanopub(s) this instance was bootstrapped from.
     *
     * <p>Used by {@code Task.EXPAND_TRUST_PATHS} to decide which invalidated trust edges the
     * trust calculation should still follow (issue #60).
     *
     * <p>This is deliberately <em>not</em> an exemption from invalidation. A superseded setting
     * is invalidated like any other nanopub: the {@code invalidations} record is written, its
     * {@code listEntries} are marked, and the trust edges it is the {@code source} of are
     * marked {@code invalidated: true}. All of that is true and stays recorded. The one thing
     * that changes is how the trust calculation <em>reads</em> those edges — it keeps following
     * them, because the root endorsements of a running instance are sourced from its setting
     * (Task.INIT_COLLECTIONS), and superseding a setting must not sever the trust root of an
     * instance already running on it.
     *
     * <p>Returns both {@code original} and {@code current}. They hold the same value today —
     * {@code LOAD_SETTING} writes both and only runs at {@code status == launching} — but they
     * are read separately elsewhere, so returning both keeps the rule correct if {@code current}
     * ever starts tracking supersession.
     *
     * <p>Empty while {@code LOAD_SETTING} has not recorded the setting yet. Nothing has been
     * built at that point, so there are no trust edges to consider.
     *
     * @param mongoSession the MongoDB client session
     * @return the bootstrap setting artifact codes, possibly empty, never null
     */
    static Set<String> getBootstrapSettingAcs(ClientSession mongoSession) {
        Set<String> acs = new HashSet<>();
        for (String key : new String[] { "original", "current" }) {
            Object v = getValue(mongoSession, Collection.SETTING.toString(), key);
            if (v != null) {
                acs.add(v.toString());
            }
        }
        return acs;
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
            logger.trace("isSet: element '{}' not found in collection '{}'", elementName, collection);
            return false;
        }
        Boolean val = d.getBoolean("value");
        logger.debug("isSet: element '{}' in collection '{}' has boolean value {}", elementName, collection, val);
        return val;
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
        logger.trace("getOne from '{}' with filter {}", collection, find);
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
            logger.trace("getMaxValue: no documents in collection '{}' for field '{}'", collection, fieldName);
            return null;
        }
        Object val = doc.get(fieldName);
        logger.debug("getMaxValue: collection '{}' field '{}' max = {}", collection, fieldName, val);
        return val;
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
        logger.trace("getMaxValueDocument in '{}' with filter {} for field '{}'", collection, find, fieldName);
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
        try (MongoCursor<Document> cursor = collection(collection).find(mongoSession, find).cursor()) {
            if (cursor.hasNext()) {
                collection(collection).updateOne(mongoSession, find, new Document("$set", update));
                logger.debug("Updated document with _id={} in collection '{}'", update.get("_id"), collection);
            } else {
                logger.debug("set: no existing document with _id={} in collection '{}'; update skipped", update.get("_id"), collection);
            }
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
        logger.debug("Inserted document into '{}' with _id={}", collection, doc.get("_id"));
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
        logger.debug("Setting value for element '{}' in collection '{}' (upsert)", elementId, collection);
        collection(collection).updateOne(mongoSession, new Document("_id", elementId), new Document("$set", new Document("value", value)), new UpdateOptions().upsert(true));
    }

    /**
     * Records the hash of a given value in the "hashes" collection.
     * Uses upsert to avoid expensive exception-based duplicate handling.
     *
     * @param mongoSession the MongoDB client session
     * @param value        the value to hash and record
     */
    public static void recordHash(ClientSession mongoSession, String value) {
        String hash = Utils.getHash(value);
        try {
            collection("hashes").updateOne(mongoSession, new Document("value", value), new Document("$setOnInsert", new Document("value", value).append("hash", hash)), new UpdateOptions().upsert(true));
            logger.debug("Recorded hash for value (hash={})", hash);
        } catch (MongoWriteException e) {
            // Concurrent upsert race: another thread inserted the same hash — safe to ignore
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                logger.error("Failed to record hash for value (hash={}): {}", hash, e.getMessage(), e);
                throw e;
            }
            logger.debug("Concurrent insertion for hash {} detected; duplicate ignored", hash);
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
                String value = c.next().getString("value");
                logger.debug("Unhash found value for hash {}", hash);
                return value;
            }
            logger.debug("Unhash: no value found for hash {}", hash);
            return null;
        }
    }

    /**
     * Initializes the counter document to the current maximum counter value
     * in the nanopubs collection.
     * Uses $max to ensure the counter is never decreased. Safe to call on every startup.
     */
    private static void initCounter(ClientSession mongoSession) {
        Long maxCounter = (Long) getMaxValue(mongoSession, Collection.NANOPUBS.toString(), "counter");
        long effective = maxCounter != null ? maxCounter : 0L;
        collection("counters").updateOne(mongoSession, new Document("_id", "nanopubs"), new Document("$max", new Document("value", effective)), new UpdateOptions().upsert(true));
        if (maxCounter != null) {
            logger.info("Nanopub counter resumed at {} (max found in DB)", effective);
        } else {
            logger.info("Nanopub counter initialized to 0 (no existing nanopubs found)");
        }
    }

    /**
     * Returns the next counter value for a nanopub via atomic increment.
     */
    private static long getNextCounter(ClientSession mongoSession) {
        Document result = collection("counters").findOneAndUpdate(mongoSession, new Document("_id", "nanopubs"), new Document("$inc", new Document("value", 1L)), new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return result.getLong("value");
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
        String pubkey = getPubkey(nanopub);
        if (pubkey == null) {
            logger.warn("Ignoring nanopub {}: no valid public key / signature found", nanopub.getUri());
            return false;
        }
        return loadNanopubVerified(mongoSession, nanopub, pubkey, pubkeyHash, types);
    }

    /**
     * Loads a nanopublication with a pre-verified public key, skipping signature verification.
     * Use this when the caller has already verified the signature via getPubkey().
     */
    static boolean loadNanopubVerified(ClientSession mongoSession, Nanopub nanopub, String verifiedPubkey, String pubkeyHash, String... types) {
        try {
            loadNanopubOrReject(mongoSession, nanopub, verifiedPubkey, pubkeyHash, types);
            return true;
        } catch (NanopubRejectedException ex) {
            logger.error("Rejecting nanopub {}: {}", nanopub.getUri(), ex.getMessage());
            return false;
        }
    }

    /**
     * Same as {@link #loadNanopubVerified}, but says why a nanopublication was refused instead
     * of collapsing every reason into {@code false}.
     * <p>
     * Callers with someone to answer to — the POST handler — use this one and pass the reason
     * on to the publisher, who cannot read our log. The background loaders have nowhere to put
     * a reason and keep using {@link #loadNanopubVerified}, which logs it.
     *
     * @throws NanopubRejectedException if the nanopublication fails one of the checks
     */
    static void loadNanopubOrReject(ClientSession mongoSession, Nanopub nanopub, String verifiedPubkey, String pubkeyHash, String... types) {
        if (nanopub.getTripleCount() > 1200) {
            throw new NanopubRejectedException(NanopubRejectedException.CONTENT_TOO_LARGE,
                    "triple count " + nanopub.getTripleCount() + " exceeds limit of 1200");
        }
        if (nanopub.getByteCount() > 1000000) {
            throw new NanopubRejectedException(NanopubRejectedException.CONTENT_TOO_LARGE,
                    "size " + nanopub.getByteCount() + " bytes exceeds limit of 1000000");
        }
        if (!Utils.isLocalInstance() && NanopubServerUtils.isProtectedNanopub(nanopub)) {
            throw new NanopubRejectedException(NanopubRejectedException.FORBIDDEN,
                    "has type npx:ProtectedNanopub, which is only accepted on local/private instances");
        }
        Calendar creationTime;
        try {
            creationTime = nanopub.getCreationTime();
        } catch (Exception ex) {
            logger.warn("Nanopub {} has a malformed timestamp; proceeding without one", nanopub.getUri());
            creationTime = null;
        }
        if (creationTime != null && creationTime.getTimeInMillis() > System.currentTimeMillis() + 60000) {
            throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                    "timestamp " + creationTime.toInstant() + " is more than 60s in the future");
        }
        String nanopubUriStr = nanopub.getUri().stringValue();
        for (IRI graphUri : nanopub.getGraphUris()) {
            if (!graphUri.stringValue().startsWith(nanopubUriStr)) {
                throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                        "graph URI " + graphUri + " does not start with the nanopub base URI");
            }
        }
        String ph = Utils.getHash(verifiedPubkey);
        if (pubkeyHash != null && !pubkeyHash.equals(ph)) {
            throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                    "provided pubkey hash " + pubkeyHash + " does not match computed hash " + ph);
        }

        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        if (ac == null) {
            // I don't think this ever happens, but checking here to be sure
            throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                    "could not extract artifact code from Trusty URI");
        }
        if (!hasValidTrustyArtifactCode(nanopub)) {
            throw new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                    "artifact code " + ac + " does not verify against its content");
        }
        recordHash(mongoSession, verifiedPubkey);

        boolean inserted = false;
        if (has(mongoSession, Collection.NANOPUBS.toString(), ac)) {
            logger.debug("Skipping nanopub {}: already present in the database", nanopub.getUri());
        } else {
            String nanopubString;
            byte[] jellyContent;
            try {
                nanopubString = NanopubUtils.writeToString(nanopub, RDFFormat.TRIG);
                // Save the same thing in the Jelly format for faster loading
                jellyContent = JellyUtils.writeNanopubForDB(nanopub);
            } catch (IOException ex) {
                logger.error("Failed to serialize nanopub {}: {}", nanopub.getUri(), ex.getMessage(), ex);
                throw new RuntimeException(ex);
            }
            long counter = getNextCounter(mongoSession);
            try {
                collection(Collection.NANOPUBS.toString()).insertOne(mongoSession, new Document("_id", ac).append("fullId", nanopub.getUri().stringValue()).append("counter", counter).append("pubkey", ph).append("content", nanopubString).append("jelly", new Binary(jellyContent)));
                inserted = true;
                logger.info("Loaded nanopub {} (counter: {}, pubkey hash: {})", nanopub.getUri(), counter, ph);
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    logger.error("Failed to insert nanopub {} (artifact {}): {}", nanopub.getUri(), ac, e.getMessage(), e);
                    throw e;
                }
                // Another thread inserted this nanopub concurrently — safe to skip
                logger.debug("Skipping nanopub {}: inserted concurrently by another thread", nanopub.getUri());
            }
        }

        // The lists this nanopub goes on, resolved before waiting for the turn below, as
        // recording a type hash is a round trip of its own.
        List<String> ownTypeHashes = new ArrayList<>();
        if (pubkeyHash != null) {
            for (String type : types) {
                // TODO Check if nanopub really has the type?
                ownTypeHashes.add(Utils.getTypeHash(mongoSession, type));
                if (type.equals("$")) {
                    for (IRI t : NanopubUtils.getTypes(nanopub)) {
                        String th = Utils.getTypeHash(mongoSession, t);
                        if (CoverageFilter.isCoveredType(th)) {
                            ownTypeHashes.add(th);
                        }
                    }
                }
            }
        }

        List<String> invalidatedAcs = new ArrayList<>();
        for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(nanopub)) {
            String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
            if (invalidatedAc == null) {
                logger.warn("Nanopub {} references invalidated nanopub {} with an unresolvable artifact code; skipping", nanopub.getUri(), invalidatedId);
                continue;  // This should never happen; checking here just to be sure
            }
            invalidatedAcs.add(invalidatedAc);
        }

        // A nanopub that was stored before has usually been through all of what follows. What
        // it is on record for is looked up here, before waiting for the turn below, where the
        // workers of a parallel load run side by side. Only answers that cannot turn stale are
        // taken from here: an entry is never taken off a list again, and an invalidation
        // record is never removed.
        Set<String> listedOn = new LinkedHashSet<>();
        Set<String> recordedBefore = new HashSet<>();
        Set<String> settledInvalidators = new HashSet<>();
        boolean markedBefore = false;
        if (!inserted) {
            listedOn.addAll(listedTypes(mongoSession, ph, ac));
            if (!invalidatedAcs.isEmpty()) {
                for (Document record : collection("invalidations").find(mongoSession, new Document("invalidatingNp", ac).append("invalidatingPubkey", ph))) {
                    recordedBefore.add(record.getString("invalidatedNp"));
                }
                for (String invalidatedAc : invalidatedAcs) {
                    if (recordedBefore.contains(invalidatedAc)) {
                        // The record is in place, so marking again needs no particular moment.
                        markInvalidated(mongoSession, ph, invalidatedAc);
                    }
                }
            }
            // The same for the invalidations of this nanopub: an invalidating nanopub that is
            // on the lists this nanopub is on stays on them, and in a parallel load the
            // entries of this nanopub can be marked right away, as the ones still to come wait
            // in the batch and are marked there.
            boolean inParallelLoad = AppendOrder.currentBatch() != null;
            for (String invalidatingAc : invalidatorsOf(mongoSession, ph, ac)) {
                if (inParallelLoad && !markedBefore) {
                    markInvalidated(mongoSession, ph, ac);
                    markedBefore = true;
                }
                if (listedTypes(mongoSession, ph, invalidatingAc).containsAll(listedOn)) {
                    settledInvalidators.add(invalidatingAc);
                }
            }
        }

        // Everything from here on appends to lists. In a parallel load this waits for the
        // nanopub's turn, so that the lists keep the order of the stream (see AppendOrder), and
        // the appends go through the load's batch (see ListAppendBatch).
        AppendOrder.awaitTurn();
        ListAppendBatch batch = AppendOrder.currentBatch();
        Set<String> appendedNow = new HashSet<>();

        // The invalidations this nanopub makes are applied whether it was stored just now or
        // before: a registry that stopped between storing a nanopub and applying them, or with
        // entries still waiting in a batch, would otherwise never make up for it, as the
        // nanopub counts as known from then on. Every step is safe to repeat.
        for (String invalidatedAc : invalidatedAcs) {
            boolean recorded = recordedBefore.contains(invalidatedAc);
            if (!recorded) {
                logger.debug("Nanopub {} invalidates {}; updating list entries and trust edges", nanopub.getUri(), invalidatedAc);
                // Recorded before the entries are marked: whoever lists the invalidated nanopub
                // from now on finds the record, and what is listed already is marked below.
                Document record = new Document("invalidatingNp", ac).append("invalidatingPubkey", ph).append("invalidatedNp", invalidatedAc);
                try {
                    collection("invalidations").updateOne(mongoSession, record, new Document("$setOnInsert", record), new UpdateOptions().upsert(true));
                } catch (MongoWriteException e) {
                    // Another writer loading this nanopub at the same moment recorded it first.
                    if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                        throw e;
                    }
                }
            }

            // Add this nanopub also to all lists of the invalidated nanopub:
            List<String> typesOfInvalidated = new ArrayList<>(listedTypes(mongoSession, ph, invalidatedAc));
            if (batch != null) {
                typesOfInvalidated.addAll(batch.pendingTypesOf(ph, invalidatedAc));
            }
            for (String typeHash : typesOfInvalidated) {
                if (listedOn.add(typeHash) && addToList(mongoSession, nanopub.getUri(), ac, ph, typeHash, !inserted)) {
                    appendedNow.add(typeHash);
                }
            }

            if (!recorded) {
                markInvalidated(mongoSession, ph, invalidatedAc);
                logger.debug("Marked invalidated entries and trust edges for invalidated artifact {}", invalidatedAc);
            }
            if (batch != null) {
                batch.markInvalidated(ph, invalidatedAc);
            }
        }

        for (String typeHash : ownTypeHashes) {
            // Skipping the lists this nanopub is known to be on, including the ones it was
            // put on just above. On the others, only a nanopub that was stored before can be
            // listed by now, so only that case asks the database again.
            if (listedOn.add(typeHash) && addToList(mongoSession, nanopub.getUri(), ac, pubkeyHash, typeHash, !inserted)) {
                appendedNow.add(typeHash);
            }
        }

        // The other direction: the nanopubs that invalidate this one. Whichever of the two
        // arrives first, the outcome has to be the same, so what an invalidating nanopub does
        // above when it arrives second is done here when it arrived first: the entries of this
        // nanopub are marked, and the invalidating nanopub joins the lists this one is on. The
        // records are looked up now that the entries of this nanopub are in place (or waiting
        // in the batch), so that none that was written meanwhile is missed.
        List<String> invalidators = invalidatorsOf(mongoSession, ph, ac);
        if (!invalidators.isEmpty()) {
            if (!markedBefore) {
                markInvalidated(mongoSession, ph, ac);
                logger.debug("Marked existing list entries and trust edges for nanopub {} as invalidated due to invalidations", ac);
            }
            if (batch != null) {
                batch.markInvalidated(ph, ac);
            }
            for (String typeHash : listedOn) {
                boolean listedBefore = !appendedNow.contains(typeHash);
                for (String invalidatingAc : invalidators) {
                    if (listedBefore && settledInvalidators.contains(invalidatingAc)) {
                        continue;
                    }
                    addInvalidatorToList(mongoSession, ph, typeHash, invalidatingAc);
                }
            }
        }
    }

    /**
     * Puts a nanopub that invalidates another on a list the invalidated one is on, and the
     * nanopubs that invalidate it in turn, down the chain of versions.
     * <p>
     * This is the rule that makes the lists independent of the order of arrival: the types of
     * an invalidated nanopub also apply to the nanopub that invalidates it, whether the
     * invalidation was recorded before or after the invalidated nanopub got onto the list. A
     * nanopub that is on the list already is not followed further, as the ones invalidating it
     * joined the list along with it.
     *
     * @return the number of entries added
     */
    static int addInvalidatorToList(ClientSession mongoSession, String pubkeyHash, String typeHash, String invalidatingAc) {
        ListAppendBatch batch = AppendOrder.currentBatch();
        int added = 0;
        Deque<String> toAdd = new ArrayDeque<>();
        toAdd.add(invalidatingAc);
        while (!toAdd.isEmpty()) {
            String ac = toAdd.remove();
            // Only the artifact code of the URI enters the checksum.
            IRI uri = SimpleValueFactory.getInstance().createIRI("https://w3id.org/np/" + ac);
            if (!addToList(mongoSession, uri, ac, pubkeyHash, typeHash, true)) {
                continue;
            }
            added++;
            logger.debug("Added invalidating nanopub {} to list pubkey={} type={}", ac, pubkeyHash, typeHash);
            List<String> itsInvalidators = invalidatorsOf(mongoSession, pubkeyHash, ac);
            if (!itsInvalidators.isEmpty()) {
                // It is invalidated itself, and so is the entry it just got.
                if (batch != null) {
                    batch.markInvalidated(pubkeyHash, ac);
                } else {
                    collection("listEntries").updateMany(mongoSession, new Document("np", ac).append("pubkey", pubkeyHash), new Document("$set", new Document("invalidated", true)));
                }
                toAdd.addAll(itsInvalidators);
            }
        }
        return added;
    }

    /**
     * Makes the lists reflect one invalidation record: the entries of the invalidated nanopub
     * under the pubkey are marked, and the invalidating nanopub is on every list of that
     * pubkey the invalidated one is on. This is what loading the two nanopubs comes to in
     * either order; {@link com.knowledgepixels.registry.db.DataRepair} uses it for records
     * that earlier versions left incomplete. Safe to repeat.
     *
     * @return the number of entries that were marked plus the number that were added
     */
    public static long completeInvalidation(ClientSession mongoSession, String invalidatingAc, String pubkeyHash, String invalidatedAc) {
        Set<String> typesOfInvalidated = listedTypes(mongoSession, pubkeyHash, invalidatedAc);
        if (typesOfInvalidated.isEmpty()) {
            return 0;
        }
        long changed = collection("listEntries").updateMany(mongoSession,
                new Document("np", invalidatedAc).append("pubkey", pubkeyHash).append("invalidated", new Document("$ne", true)),
                new Document("$set", new Document("invalidated", true))).getModifiedCount();
        Set<String> typesOfInvalidating = listedTypes(mongoSession, pubkeyHash, invalidatingAc);
        if (typesOfInvalidating.containsAll(typesOfInvalidated)) {
            return changed;
        }
        if (!has(mongoSession, Collection.NANOPUBS.toString(), invalidatingAc)) {
            // A record is written when its invalidating nanopub is stored, so this should not happen.
            logger.error("Invalidation record names {} as invalidating {}, but that nanopub is not stored; not adding it to any list", invalidatingAc, invalidatedAc);
            return changed;
        }
        for (String typeHash : typesOfInvalidated) {
            if (!typesOfInvalidating.contains(typeHash)) {
                changed += addInvalidatorToList(mongoSession, pubkeyHash, typeHash, invalidatingAc);
            }
        }
        return changed;
    }

    /** The types of the lists of the given pubkey that a nanopub has an entry on. */
    private static Set<String> listedTypes(ClientSession mongoSession, String pubkeyHash, String ac) {
        Set<String> types = new LinkedHashSet<>();
        for (Document entry : collection("listEntries").find(mongoSession, new Document("np", ac).append("pubkey", pubkeyHash)).projection(new Document("type", 1))) {
            types.add(entry.getString("type"));
        }
        return types;
    }

    /** The nanopubs on record as invalidating the given nanopub for the given pubkey. */
    private static List<String> invalidatorsOf(ClientSession mongoSession, String pubkeyHash, String ac) {
        List<String> invalidators = new ArrayList<>();
        for (Document record : collection("invalidations").find(mongoSession, new Document("invalidatedNp", ac).append("invalidatingPubkey", pubkeyHash))) {
            invalidators.add(record.getString("invalidatingNp"));
        }
        return invalidators;
    }

    /**
     * Marks the list entries of an invalidated nanopub under the given pubkey as invalidated,
     * and the trust edges it is the source of if the pubkey is the one that signed it (see
     * {@link #trustEdgeSourceSigner}). Safe to repeat.
     */
    private static void markInvalidated(ClientSession mongoSession, String pubkeyHash, String invalidatedAc) {
        collection("listEntries").updateMany(mongoSession, new Document("np", invalidatedAc).append("pubkey", pubkeyHash), new Document("$set", new Document("invalidated", true)));
        for (String fromPubkey : trustEdgeOrigins(mongoSession, invalidatedAc, true)) {
            if (pubkeyHash.equals(trustEdgeSourceSigner(mongoSession, invalidatedAc, fromPubkey))) {
                collection("trustEdges").updateMany(mongoSession, new Document("source", invalidatedAc).append("fromPubkey", fromPubkey), new Document("$set", new Document("invalidated", true)));
            }
        }
    }

    /** The {@code fromPubkey} values of the trust edges sourced from a nanopub, usually none. */
    private static Set<String> trustEdgeOrigins(ClientSession mongoSession, String sourceAc, boolean onlyUnmarked) {
        Document filter = new Document("source", sourceAc);
        if (onlyUnmarked) {
            filter.append("invalidated", new Document("$ne", true));
        }
        Set<String> fromPubkeys = new HashSet<>();
        for (Document edge : collection("trustEdges").find(mongoSession, filter).projection(new Document("fromPubkey", 1))) {
            fromPubkeys.add(edge.getString("fromPubkey"));
        }
        return fromPubkeys;
    }

    /**
     * The pubkey that signed the source of a trust edge. Only an invalidation made with that
     * pubkey counts for the edge: nobody else can take back an endorsement, just as an
     * invalidation only counts on the lists of the pubkey that made it.
     * <p>
     * An edge is sourced from a nanopub of the pubkey it starts from, so that is its signer.
     * The exception are the root edges of an instance, which start from the placeholder
     * {@code $} and are sourced from the setting; there the signer is looked up.
     *
     * @return the pubkey hash, or null if it cannot be determined
     */
    private static String trustEdgeSourceSigner(ClientSession mongoSession, String sourceAc, String fromPubkey) {
        if (!"$".equals(fromPubkey)) {
            return fromPubkey;
        }
        Document source = collection(Collection.NANOPUBS.toString()).find(mongoSession, new Document("_id", sourceAc)).projection(new Document("pubkey", 1)).first();
        return source == null ? null : source.getString("pubkey");
    }

    /**
     * Whether the source of a trust edge is invalidated, that is, whether there is an
     * invalidation of it on record that was made with the pubkey that signed it.
     */
    public static boolean isTrustEdgeSourceInvalidated(ClientSession mongoSession, String sourceAc, String fromPubkey) {
        String signer = trustEdgeSourceSigner(mongoSession, sourceAc, fromPubkey);
        return signer != null && has(mongoSession, "invalidations", new Document("invalidatedNp", sourceAc).append("invalidatingPubkey", signer));
    }

    /**
     * Whether an invalidation of the given nanopub made with the given pubkey would invalidate
     * a trust edge, that is, whether the nanopub is the source of one and that pubkey signed it.
     */
    static boolean invalidatesTrustEdges(ClientSession mongoSession, String pubkeyHash, String invalidatedAc) {
        for (String fromPubkey : trustEdgeOrigins(mongoSession, invalidatedAc, false)) {
            if (pubkeyHash.equals(trustEdgeSourceSigner(mongoSession, invalidatedAc, fromPubkey))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends a nanopub to the list of the given pubkey and type, unless it is on it already.
     * <p>
     * Positions and checksums form a chain: the entry at position {@code p} carries the XOR
     * checksum of the entries at positions {@code 0..p}. That is only true if every entry is
     * computed from its direct predecessor, so the append is driven by the entries themselves
     * rather than by a separate counter: the writer reads the entry with the highest position,
     * derives the next position and checksum from it, and inserts. The unique index on the
     * position lets only one of several concurrent writers have a given position, and that
     * writer has by construction chained off the entry right before it; the others lose the
     * insert and start over from the new end of the chain. (Claiming the position with an
     * atomic counter first and looking the predecessor up afterwards, as earlier versions did,
     * let concurrent writers chain off whatever entry happened to be visible, which corrupted
     * the chains; see issue #205 and {@link com.knowledgepixels.registry.db.DataRepair}.)
     * <p>
     * In a parallel load the appends are ordered and batched by the load's
     * {@link ListAppendBatch}, which keeps the tail of each list in memory and writes the
     * entries in batches; the direct path below is then only the fallback for entries the
     * batch could not place.
     * <p>
     * {@code lists.maxPosition} is a derived counter for the pages that show list sizes. It is
     * advanced with {@code $max} after the insert, so it never moves backwards and catches up
     * on the next append if a crash left it behind.
     *
     * @param mayBeListed whether the nanopub may already be on the list, in which case the
     *                    database is asked first. A nanopub stored by this very call is not,
     *                    short of another writer loading the same nanopub at the same moment;
     *                    the unique index on the nanopub then refuses the second entry
     * @return whether an entry was added (or queued); false if the nanopub is on the list already
     */
    private static boolean addToList(ClientSession mongoSession, IRI nanopubUri, String ac, String pubkeyHash, String typeHash, boolean mayBeListed) {
        Document listFilter = new Document("pubkey", pubkeyHash).append("type", typeHash);
        ListAppendBatch batch = AppendOrder.currentBatch();
        if (batch == null || !batch.isListKnown(pubkeyHash, typeHash)) {
            try {
                insert(mongoSession, "lists", new Document(listFilter).append("maxPosition", -1L));
                logger.debug("Ensured list document exists for pubkey={} type={}", pubkeyHash, typeHash);
            } catch (MongoWriteException e) {
                // Duplicate key error -- ignore it
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    logger.error("Failed to create list document for pubkey={} type={}: {}", pubkeyHash, typeHash, e.getMessage(), e);
                    throw e;
                }
                logger.trace("List document already existed for pubkey={} type={}", pubkeyHash, typeHash);
            }
            if (batch != null) {
                batch.listKnown(pubkeyHash, typeHash);
            }
        }

        if (batch != null && batch.isPending(pubkeyHash, typeHash, ac)) {
            logger.debug("Already pending: nanopub {} for pubkey={} type={}", ac, pubkeyHash, typeHash);
            return false;
        }
        if (mayBeListed && has(mongoSession, "listEntries", new Document(listFilter).append("np", ac))) {
            logger.debug("Already listed: nanopub {} for pubkey={} type={}", ac, pubkeyHash, typeHash);
            return false;
        }

        if (batch != null) {
            batch.append(mongoSession, pubkeyHash, typeHash, nanopubUri, ac);
            return true;
        }
        return appendDirectly(mongoSession, nanopubUri, ac, pubkeyHash, typeHash, false);
    }

    /**
     * Appends an entry off the actual end of the list, retrying when another writer gets the
     * position first. See {@link #addToList} for why this yields a sequential chain.
     *
     * @return whether the entry was added; false if another writer listed the nanopub meanwhile
     */
    static boolean appendDirectly(ClientSession mongoSession, IRI nanopubUri, String ac, String pubkeyHash, String typeHash, boolean invalidated) {
        Document listFilter = new Document("pubkey", pubkeyHash).append("type", typeHash);
        for (int attempt = 0; ; attempt++) {
            // The end of the chain, via the (pubkey, type, position) index
            Document last = getMaxValueDocument(mongoSession, "listEntries", listFilter, "position");
            long position = (last == null) ? 0L : last.getLong("position") + 1;
            String prevChecksum = (last == null || last.getString("checksum") == null) ? NanopubUtils.INIT_CHECKSUM : last.getString("checksum");
            String checksum = NanopubUtils.updateXorChecksum(nanopubUri, prevChecksum);

            try {
                collection("listEntries").insertOne(mongoSession, new Document(listFilter).append("position", position).append("np", ac).append("checksum", checksum).append("invalidated", invalidated));
                collection("lists").updateOne(mongoSession, listFilter, new Document("$max", new Document("maxPosition", position)));
                logger.debug("Inserted list entry: pubkey={} type={} np={} position={} checksum={}", pubkeyHash, typeHash, ac, position, checksum);
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    logger.error("Failed to insert list entry for pubkey={} type={} np={}: {}", pubkeyHash, typeHash, ac, e.getMessage(), e);
                    throw e;
                }
                if (has(mongoSession, "listEntries", new Document(listFilter).append("np", ac))) {
                    logger.debug("Concurrent insert detected and entry already exists for pubkey={} type={} np={}", pubkeyHash, typeHash, ac);
                    return false; // Already listed by concurrent thread
                }
                if (attempt >= 100) {
                    logger.error("Failed to insert list entry after {} attempts for pubkey={} type={} np={}", attempt + 1, pubkeyHash, typeHash, ac);
                    throw new RuntimeException("Failed to insert list entry after " + (attempt + 1) + " attempts");
                }
                logger.debug("Position {} of list pubkey={} type={} was taken concurrently; retrying (attempt {}) for np={}", position, pubkeyHash, typeHash, attempt + 1, ac);
            }
        }
    }

    /**
     * Builds a comma-separated list of checksums at geometric positions for a given list,
     * for use with the afterChecksums parameter during peer sync.
     * Returns checksums at positions: max, max-10, max-100, max-1000, max-10000, ...
     * Returns null if the list has no entries.
     */
    public static String buildChecksumFallbacks(ClientSession mongoSession, String pubkeyHash, String typeHash) {
        Document maxDoc = getMaxValueDocument(mongoSession, "listEntries", new Document("pubkey", pubkeyHash).append("type", typeHash), "position");
        if (maxDoc == null) {
            logger.debug("buildChecksumFallbacks: no entries for pubkey={} type={}", pubkeyHash, typeHash);
            return null;
        }

        long maxPosition = maxDoc.getLong("position");
        StringBuilder sb = new StringBuilder();
        sb.append(maxDoc.getString("checksum"));

        for (long offset = 10; offset <= maxPosition; offset *= 10) {
            long targetPos = maxPosition - offset;
            Document entry = collection("listEntries").find(mongoSession, new Document("pubkey", pubkeyHash).append("type", typeHash).append("position", targetPos)).first();
            if (entry != null) {
                sb.append(",").append(entry.getString("checksum"));
            }
        }
        String result = sb.toString();
        logger.debug("buildChecksumFallbacks for pubkey={} type={} -> {}", pubkeyHash, typeHash, result);
        return result;
    }

    /**
     * Checks whether the nanopub's artifact code is a well-formed Trusty artifact code that
     * matches the nanopub's content.
     * <p>
     * A valid signature does not imply this. Signature verification normalizes the artifact code
     * out of all URIs before hashing, so a correctly signed nanopub whose artifact code was
     * replaced by an arbitrary string still verifies. Such a nanopub has to be rejected at ingest:
     * it would be stored under an id this registry cannot serve on {@code /np/}, while still being
     * counted and emitted to peers and consumers.
     *
     * @param nanopub the nanopub to check
     * @return true if the artifact code is well-formed and verifies against the content
     */
    public static boolean hasValidTrustyArtifactCode(Nanopub nanopub) {
        String ac = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
        if (ac == null) {
            logger.debug("Nanopub {} has no artifact code in its URI", nanopub.getUri());
            return false;
        }
        if (!TrustyUriUtils.isPotentialArtifactCode(ac)) {
            // Cheap pre-check: wrong module or wrong length, so the hash cannot match either.
            logger.debug("Artifact code '{}' of nanopub {} is not a well-formed Trusty artifact code", ac, nanopub.getUri());
            return false;
        }
        return TrustyNanopubUtils.isValidTrustyNanopub(nanopub);
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
                logger.trace("Valid signature found for nanopub {}", nanopub.getUri());
                return el.getPublicKeyString();
            }
            logger.debug("No valid signature element or public key present for nanopub {}", nanopub.getUri());
        } catch (MalformedCryptoElementException | GeneralSecurityException ex) {
            logger.error("Failed to verify signature of nanopub {}: {}", nanopub.getUri(), ex.getMessage(), ex);
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
        // Accounts still in the 'toLoad' staging status are not yet servable and must not enter the
        // public trust state: a path's head account flips 'toLoad' -> 'loaded' between UPDATE cycles
        // (in LOAD_FULL) without changing any trust path, so if those paths were hashed, a leaf
        // account's promotion would never change the hash and never trigger a new snapshot, leaving
        // it published as 'toLoad' indefinitely (see issue #119). By excluding 'toLoad' paths here,
        // crossing out of 'toLoad' is itself a membership change -> hash change -> emission.
        Set<String> toLoadAccounts = new HashSet<>();
        try (MongoCursor<Document> ac = collection("accounts_loading")
                .find(mongoSession, new Document("status", EntryStatus.toLoad.getValue()))
                .projection(new Document("agent", 1).append("pubkey", 1))
                .cursor()) {
            while (ac.hasNext()) {
                Document a = ac.next();
                toLoadAccounts.add(a.getString("agent") + "|" + a.getString("pubkey"));
            }
        }

        String s;
        try (MongoCursor<Document> tp = collection("trustPaths_loading").find(mongoSession).sort(ascending("_id")).cursor()) {
            // TODO Improve this so we don't create the full string just for calculating its hash:
            s = "";
            while (tp.hasNext()) {
                Document d = tp.next();
                if (toLoadAccounts.contains(d.getString("agent") + "|" + d.getString("pubkey"))) {
                    continue;
                }
                s += d.getString("_id") + " (" + d.getString("type") + ")\n";
            }
        }

        // Reaching a quota changes an account's published status without changing a single trust
        // path, so without this a 'loaded' -> 'capped' transition would leave the hash alone, no
        // new snapshot would be emitted, and the published state would keep calling a capped
        // account 'loaded' (the same trap as issue #119, from the other side). Nothing is appended
        // while no account is capped — which is every registry that does not enforce quotas — so
        // this does not move any hash that exists today.
        try (MongoCursor<Document> ac = collection("accounts_loading")
                .find(mongoSession, new Document("status", EntryStatus.capped.getValue()))
                .projection(new Document("agent", 1).append("pubkey", 1))
                .sort(ascending("agent", "pubkey"))
                .cursor()) {
            while (ac.hasNext()) {
                Document d = ac.next();
                s += "capped " + d.getString("agent") + "|" + d.getString("pubkey") + "\n";
            }
        }

        String hash = Utils.getHash(s);
        logger.debug("Calculated trust state hash: {}", hash);
        return hash;
    }

}
