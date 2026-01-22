package com.knowledgepixels.registry.db;

import com.knowledgepixels.registry.Collection;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.mongodb.client.model.Indexes.*;

/**
 * Initializes indexes for MongoDB collections used in the application.
 */
public final class IndexInitializer {

    /**
     * Initializes indexes for the main MongoDB collections.
     *
     * @param mongoSession the client session for MongoDB operations
     */
    public static void initCollections(ClientSession mongoSession) {
        final IndexOptions unique = new IndexOptions().unique(true);

        collection(Collection.TASKS.toString()).createIndex(mongoSession, Indexes.descending("not-before"));

        collection(Collection.NANOPUBS.toString()).createIndex(mongoSession, ascending("fullId"), unique);
        collection(Collection.NANOPUBS.toString()).createIndex(mongoSession, descending("counter"), unique);
        collection(Collection.NANOPUBS.toString()).createIndex(mongoSession, ascending("pubkey"));

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

    /**
     * Initializes indexes for loading-related MongoDB collections.
     */
    public static void initLoadingCollections(ClientSession mongoSession) {
        final IndexOptions unique = new IndexOptions().unique(true);

        collection("endorsements_loading").createIndex(mongoSession, ascending("agent"));
        collection("endorsements_loading").createIndex(mongoSession, ascending("pubkey"));
        collection("endorsements_loading").createIndex(mongoSession, ascending("endorsedNanopub"));
        collection("endorsements_loading").createIndex(mongoSession, ascending("source"));
        collection("endorsements_loading").createIndex(mongoSession, ascending("status"));
        // zip: Hmm, not possible to set com.mongodb.client.model.WriteModel<T> here
        // I'd currently recommend to have a package with the DB related stuff
        // and therein a Custom Extension T of <Document>, where we could put that WriteModel<T>.

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

}
