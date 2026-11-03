package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.Collection;
import com.knowledgepixels.registry.EntryStatus;
import com.knowledgepixels.registry.RegistryDB;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.nanopub.jelly.NanopubStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.mongodb.client.model.Aggregates.lookup;
import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Aggregates.sort;
import static com.mongodb.client.model.Aggregates.unwind;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.descending;
import static com.mongodb.client.model.Projections.exclude;
import static com.mongodb.client.model.Projections.include;

/**
 * The reads of the public API against MongoDB, lifted verbatim from the page classes so
 * that output does not change. Every query goes through {@code RegistryDB}'s static
 * accessors with the reader's session, which is also what the page tests stub.
 */
public class MongoRegistryReader implements RegistryReader {

    private static final List<String> QUOTA_BEARING_STATUSES = List.of(
            EntryStatus.loaded.getValue(), EntryStatus.toLoad.getValue(), EntryStatus.capped.getValue());

    private final ClientSession session;
    private final boolean ownsSession;

    MongoRegistryReader(ClientSession session, boolean ownsSession) {
        this.session = session;
        this.ownsSession = ownsSession;
    }

    /**
     * A reader over a session the caller manages (the write path, which runs the reads of
     * {@code AgentFilter} inside its own transaction); closing the reader leaves the session open.
     */
    public static MongoRegistryReader of(ClientSession session) {
        return new MongoRegistryReader(session, false);
    }

    /**
     * The underlying session, for code that still needs MongoDB directly ({@code DebugPage}).
     */
    public ClientSession session() {
        return session;
    }

    @Override
    public Map<String, Object> serverInfo() {
        Map<String, Object> si = new LinkedHashMap<>();
        for (Document d : collection(Collection.SERVER_INFO.toString()).find(session)) {
            si.put(d.getString("_id"), d.get("value"));
        }
        return si;
    }

    @Override
    public Object serverInfoValue(String key) {
        return RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), key);
    }

    @Override
    public boolean isTestInstance() {
        return RegistryDB.isSet(session, Collection.SERVER_INFO.toString(), "testInstance");
    }

    @Override
    public Object settingValue(String key) {
        return RegistryDB.getValue(session, Collection.SETTING.toString(), key);
    }

    @Override
    public Document getNanopub(String artifactCode) {
        return collection(Collection.NANOPUBS.toString()).find(new Document("_id", artifactCode)).first();
    }

    @Override
    public Long maxCounter() {
        Object v = RegistryDB.getMaxValue(session, Collection.NANOPUBS.toString(), "counter");
        return v instanceof Number n ? n.longValue() : null;
    }

    @Override
    public long nanopubCount() {
        return collection(Collection.NANOPUBS.toString()).estimatedDocumentCount();
    }

    @Override
    public long nanopubCountExact() {
        return collection(Collection.NANOPUBS.toString()).countDocuments(session);
    }

    @Override
    public CloseableIterator<String> nanopubIdsByCounterDesc() {
        MongoCursor<Document> c = collection(Collection.NANOPUBS.toString()).find(session)
                .filter(new Document()).sort(descending("counter")).projection(include("_id")).cursor();
        return ids(c);
    }

    @Override
    public CloseableIterator<String> nanopubIdsByIdAsc(String afterId) {
        Bson filter = (afterId == null || afterId.isEmpty()) ? new Document() : gt("_id", afterId);
        MongoCursor<Document> c = collection(Collection.NANOPUBS.toString()).find(session)
                .filter(filter).sort(ascending("_id")).projection(include("_id")).cursor();
        return ids(c);
    }

    @Override
    public List<String> latestNanopubIds(int limit) {
        List<String> ids = new ArrayList<>();
        try (MongoCursor<Document> c = collection(Collection.NANOPUBS.toString()).find(session)
                .sort(descending("counter")).limit(limit).cursor()) {
            while (c.hasNext()) {
                ids.add(c.next().getString("_id"));
            }
        }
        return ids;
    }

    @Override
    public JellyStream nanopubsAfterCounter(long afterCounter) {
        MongoCursor<Document> c = collection(Collection.NANOPUBS.toString()).find(session)
                .filter(gt("counter", afterCounter)).sort(ascending("counter"))
                .projection(include("jelly", "counter")).cursor();
        return new JellyStream(NanopubStream.fromMongoCursorWithCounter(c), c);
    }

    @Override
    public List<Document> listsForPubkey(String pubkeyHash) {
        try (MongoCursor<Document> c = collection("lists").find(session, new Document("pubkey", pubkeyHash))
                .projection(exclude("_id")).cursor()) {
            return drain(c);
        }
    }

    @Override
    public Document getList(String pubkeyHash, String typeHash) {
        return RegistryDB.getOne(session, "lists", new Document("pubkey", pubkeyHash).append("type", typeHash));
    }

    @Override
    public List<String> pubkeys() {
        List<String> pubkeys = new ArrayList<>();
        try (MongoCursor<String> c = collection("lists").distinct(session, "pubkey", String.class).cursor()) {
            while (c.hasNext()) {
                pubkeys.add(c.next());
            }
        }
        return pubkeys;
    }

    @Override
    public CloseableIterator<Document> listEntries(String pubkeyHash, String typeHash) {
        MongoCursor<Document> c = collection("listEntries")
                .find(session, new Document("pubkey", pubkeyHash).append("type", typeHash))
                .projection(exclude("_id")).sort(ascending("position")).cursor();
        return wrap(c);
    }

    @Override
    public Long listEntryPosition(String pubkeyHash, String typeHash, String checksum) {
        Document match = collection("listEntries").find(session,
                new Document("pubkey", pubkeyHash).append("type", typeHash).append("checksum", checksum)).first();
        return match == null ? null : match.getLong("position");
    }

    @Override
    public JellyStream listEntriesAfterPosition(String pubkeyHash, String typeHash, long afterPosition) {
        Document matchFilter = new Document("pubkey", pubkeyHash).append("type", typeHash);
        if (afterPosition >= 0) {
            matchFilter.append("position", new Document("$gt", afterPosition));
        }
        List<Bson> pipeline = List.of(match(matchFilter), sort(ascending("position")),
                lookup("nanopubs", "np", "_id", "nanopub"), project(new Document("jelly", "$nanopub.jelly")), unwind("$jelly"));
        MongoCursor<Document> c = collection("listEntries").aggregate(session, pipeline).cursor();
        return new JellyStream(NanopubStream.fromMongoCursor(c), c);
    }

    @Override
    public List<Document> accounts() {
        try (MongoCursor<Document> c = collection(Collection.ACCOUNTS.toString()).find(session)
                .sort(ascending("pubkey")).projection(exclude("_id")).cursor()) {
            return drain(c);
        }
    }

    @Override
    public List<Document> accountsByAgent(String agentId) {
        try (MongoCursor<Document> c = collection(Collection.ACCOUNTS.toString())
                .find(session, new Document("agent", agentId)).projection(exclude("_id")).cursor()) {
            return drain(c);
        }
    }

    @Override
    public Document quotaBearingAccount(String pubkeyHash) {
        return collection(Collection.ACCOUNTS.toString()).find(session,
                new Document("pubkey", pubkeyHash)
                        .append("status", new Document("$in", QUOTA_BEARING_STATUSES))).first();
    }

    @Override
    public long accountCount() {
        return collection(Collection.ACCOUNTS.toString()).countDocuments(session);
    }

    @Override
    public List<Document> agents() {
        try (MongoCursor<Document> c = collection(Collection.AGENTS.toString()).find(session)
                .sort(descending("totalRatio")).projection(exclude("_id")).cursor()) {
            return drain(c);
        }
    }

    @Override
    public Document getAgent(String agentId) {
        return RegistryDB.getOne(session, Collection.AGENTS.toString(), new Document("agent", agentId));
    }

    @Override
    public long agentCount() {
        return collection(Collection.AGENTS.toString()).countDocuments(session);
    }

    @Override
    public List<Document> trustStateSnapshots() {
        try (MongoCursor<Document> c = collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                .find(session)
                .projection(exclude("accounts", "edges"))
                .sort(descending("trustStateCounter"))
                .cursor()) {
            return drain(c);
        }
    }

    @Override
    public Document trustStateSnapshot(String hash) {
        return collection(Collection.TRUST_STATE_SNAPSHOTS.toString())
                .find(session, new Document("_id", hash)).first();
    }

    @Override
    public long trustStateSnapshotCount() {
        return collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(session);
    }

    @Override
    public String unhash(String hash) {
        return RegistryDB.unhash(hash);
    }

    @Override
    public void close() {
        if (ownsSession) {
            session.close();
        }
    }

    private static List<Document> drain(MongoCursor<Document> c) {
        List<Document> docs = new ArrayList<>();
        while (c.hasNext()) {
            docs.add(c.next());
        }
        return docs;
    }

    private static CloseableIterator<Document> wrap(MongoCursor<Document> c) {
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return c.hasNext();
            }

            @Override
            public Document next() {
                return c.next();
            }

            @Override
            public void close() {
                c.close();
            }
        };
    }

    private static CloseableIterator<String> ids(MongoCursor<Document> c) {
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return c.hasNext();
            }

            @Override
            public String next() {
                return c.next().getString("_id");
            }

            @Override
            public void close() {
                c.close();
            }
        };
    }

}
