package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.EntryStatus;
import com.knowledgepixels.registry.Utils;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.jelly.NanopubStream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.LongFunction;
import java.util.stream.LongStream;

import static com.knowledgepixels.registry.store.RegistryMeta.*;

/**
 * The reads of the public API as SPARQL queries over a registry file (layout in
 * {@link RegistryMeta}). Every query is a constant-subject, constant-graph or
 * constant-object lookup; the three range reads (nanopubs after a counter, list entries
 * after a position, id listings) walk fixed-size batch nodes from a computed start batch,
 * sorting one batch at a time in memory, so no query ever filters or orders the whole set.
 */
public class SparqlRegistryReader implements RegistryReader {

    private static final String PREFIX = "PREFIX npa: <" + NS + ">\n";
    private static final String REG = "<" + GRAPH_REGISTRY + ">";
    private static final String BLOBS = "<" + GRAPH_BLOBS + ">";

    private static final List<String> NANOPUB_FIELDS = List.of("_id", "fullId", "counter", "pubkey", "content", "jelly");
    private static final List<String> LIST_FIELDS = List.of("pubkey", "type", "maxPosition", "status");
    private static final List<String> ENTRY_FIELDS = List.of("pubkey", "type", "position", "np", "checksum", "invalidated");
    private static final List<String> ACCOUNT_FIELDS = List.of("agent", "pubkey", "status", "depth", "name", "nameCreatedAt", "introNanopub", "ratio", "pathCount", "quota");
    private static final List<String> AGENT_FIELDS = List.of("agent", "status", "accountCount", "avgPathCount", "totalRatio", "name");

    private static final List<String> QUOTA_BEARING_STATUSES = List.of(
            EntryStatus.loaded.getValue(), EntryStatus.toLoad.getValue(), EntryStatus.capped.getValue());

    private final SparqlRegistryStore store;
    private final RepositoryConnection conn;

    SparqlRegistryReader(SparqlRegistryStore store, RepositoryConnection conn) {
        this.store = store;
        this.conn = conn;
    }

    // --- serverInfo and setting --------------------------------------------------------

    @Override
    public Map<String, Object> serverInfo() {
        return new LinkedHashMap<>(store.roots(this).serverInfo());
    }

    @Override
    public Object serverInfoValue(String key) {
        return store.roots(this).serverInfo().get(key);
    }

    @Override
    public boolean isTestInstance() {
        return Boolean.TRUE.equals(serverInfoValue("testInstance"));
    }

    @Override
    public Object settingValue(String key) {
        return first(select("SELECT ?v WHERE { GRAPH " + REG + " { <" + SETTING + "> <" + iri(key) + "> ?v } }"), "v");
    }

    private Map<String, Object> registry() {
        return store.roots(this).registry();
    }

    private long batchSize() {
        Object b = registry().get("batchSize");
        return b instanceof Number n ? n.longValue() : 1000L;
    }

    private long longProp(Map<String, Object> props, String key) {
        Object v = props.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // --- nanopubs ----------------------------------------------------------------------

    @Override
    public Document getNanopub(String artifactCode) {
        String np = "<" + npNode(artifactCode) + ">";
        List<BindingSet> rows = select("SELECT ?full ?c ?pk ?trig ?jelly WHERE { GRAPH " + REG + " { " + np
                + " npa:nanopub ?full ; npa:counter ?c ; npa:pubkey ?pk } GRAPH " + BLOBS + " { " + np
                + " npa:trig ?trig ; npa:jelly ?jelly } }");
        if (rows.isEmpty()) {
            return null;
        }
        BindingSet r = rows.getFirst();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("_id", artifactCode);
        f.put("fullId", SparqlRows.toJava(r.getValue("full")));
        f.put("counter", SparqlRows.toJava(r.getValue("c")));
        f.put("pubkey", SparqlRows.toJava(r.getValue("pk")));
        f.put("content", SparqlRows.toJava(r.getValue("trig")));
        f.put("jelly", SparqlRows.toJava(r.getValue("jelly")));
        return SparqlRows.toDocument(f, NANOPUB_FIELDS);
    }

    @Override
    public Long maxCounter() {
        long c = longProp(registry(), "loadCounter");
        return c == 0 ? null : c;
    }

    @Override
    public long nanopubCount() {
        return longProp(registry(), "nanopubCount");
    }

    @Override
    public long nanopubCountExact() {
        return nanopubCount();
    }

    private long counterBatchCount() {
        long n = longProp(registry(), "loadCounter");
        return n == 0 ? 0 : (n - 1) / batchSize() + 1;
    }

    /**
     * The artifact codes of a counter batch, with their counters.
     */
    private List<Object[]> counterBatch(long k) {
        List<Object[]> rows = new ArrayList<>();
        for (BindingSet r : select("SELECT ?ac ?c WHERE { GRAPH " + REG + " { <" + counterBatchNode(k)
                + "> npa:member ?n . ?n npa:artifactCode ?ac ; npa:counter ?c } }")) {
            rows.add(new Object[]{r.getValue("ac").stringValue(), (Long) SparqlRows.toJava(r.getValue("c"))});
        }
        return rows;
    }

    @Override
    public CloseableIterator<String> nanopubIdsByCounterDesc() {
        long n = counterBatchCount();
        return batches(LongStream.iterate(n - 1, k -> k >= 0, k -> k - 1), k -> {
            List<Object[]> rows = counterBatch(k);
            rows.sort(Comparator.comparingLong((Object[] o) -> (Long) o[1]).reversed());
            return rows.stream().map(o -> (String) o[0]).toList();
        });
    }

    @Override
    public List<String> latestNanopubIds(int limit) {
        List<String> ids = new ArrayList<>();
        try (CloseableIterator<String> it = nanopubIdsByCounterDesc()) {
            while (it.hasNext() && ids.size() < limit) {
                ids.add(it.next());
            }
        }
        return ids;
    }

    @Override
    public CloseableIterator<String> nanopubIdsByIdAsc(String afterId) {
        String after = afterId == null ? "" : afterId;
        long batchCount = longProp(readProperties(CHAIN_ID), "batchCount");
        long start = 0;
        if (!after.isEmpty()) {
            // the first batch whose last id lies beyond the cursor; the directory is small
            start = batchCount;
            for (BindingSet r : select("SELECT ?k ?l WHERE { GRAPH " + REG + " { <" + CHAIN_ID
                    + "> npa:batch ?b . ?b npa:index ?k ; npa:lastId ?l } }")) {
                long k = (Long) SparqlRows.toJava(r.getValue("k"));
                if (r.getValue("l").stringValue().compareTo(after) > 0 && k < start) {
                    start = k;
                }
            }
        }
        return batches(LongStream.range(start, batchCount), k -> {
            List<String> ids = new ArrayList<>();
            for (BindingSet r : select("SELECT ?ac WHERE { GRAPH " + REG + " { <" + idBatchNode(k)
                    + "> npa:member ?n . ?n npa:artifactCode ?ac } }")) {
                String ac = r.getValue("ac").stringValue();
                if (ac.compareTo(after) > 0) {
                    ids.add(ac);
                }
            }
            ids.sort(null);
            return ids;
        });
    }

    @Override
    public JellyStream nanopubsAfterCounter(long afterCounter) {
        long b = batchSize();
        long start = Math.max(0, afterCounter) / b;
        Iterator<Document> docs = batches(LongStream.range(start, counterBatchCount()), k -> {
            List<Object[]> rows = new ArrayList<>();
            for (BindingSet r : select("SELECT ?c ?j WHERE { GRAPH " + REG + " { <" + counterBatchNode(k)
                    + "> npa:member ?n . ?n npa:counter ?c } GRAPH " + BLOBS + " { ?n npa:jelly ?j } }")) {
                long c = (Long) SparqlRows.toJava(r.getValue("c"));
                if (c > afterCounter) {
                    rows.add(new Object[]{c, SparqlRows.toJava(r.getValue("j"))});
                }
            }
            rows.sort(Comparator.comparingLong(o -> (Long) o[0]));
            return rows.stream().map(o -> new Document("jelly", o[1]).append("counter", o[0])).toList();
        });
        return new JellyStream(NanopubStream.fromMongoCursorWithCounter(new DocumentCursor(docs)), null);
    }

    // --- lists -------------------------------------------------------------------------

    @Override
    public List<Document> listsForPubkey(String pubkeyHash) {
        List<Document> docs = new ArrayList<>();
        for (BindingSet r : select("SELECT ?t ?mp ?st WHERE { GRAPH " + REG + " { <" + pubkeyNode(pubkeyHash)
                + "> npa:hasList ?l . ?l npa:type ?t ; npa:maxPosition ?mp ; npa:status ?st } }")) {
            docs.add(listDocument(pubkeyHash, r.getValue("t").stringValue(), r));
        }
        docs.sort(Comparator.comparing(d -> d.getString("type")));
        return docs;
    }

    @Override
    public Document getList(String pubkeyHash, String typeHash) {
        List<BindingSet> rows = select("SELECT ?mp ?st WHERE { GRAPH " + REG + " { <" + listNode(pubkeyHash, typeHash)
                + "> npa:maxPosition ?mp ; npa:status ?st } }");
        return rows.isEmpty() ? null : listDocument(pubkeyHash, typeHash, rows.getFirst());
    }

    private static Document listDocument(String pubkeyHash, String typeHash, BindingSet r) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pubkey", pubkeyHash);
        f.put("type", typeHash);
        f.put("maxPosition", SparqlRows.toJava(r.getValue("mp")));
        f.put("status", SparqlRows.toJava(r.getValue("st")));
        return SparqlRows.toDocument(f, LIST_FIELDS);
    }

    private long listBatchCount(String pubkeyHash, String typeHash) {
        Object v = first(select("SELECT ?v WHERE { GRAPH " + REG + " { <" + listNode(pubkeyHash, typeHash) + "> npa:batchCount ?v } }"), "v");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    public List<String> pubkeys() {
        List<String> pubkeys = new ArrayList<>();
        for (BindingSet r : select("SELECT ?pk WHERE { GRAPH " + REG + " { <" + REGISTRY + "> npa:hasPubkey ?p . ?p npa:pubkey ?pk } }")) {
            pubkeys.add(r.getValue("pk").stringValue());
        }
        pubkeys.sort(null);
        return pubkeys;
    }

    @Override
    public CloseableIterator<Document> listEntries(String pubkeyHash, String typeHash) {
        return batches(LongStream.range(0, listBatchCount(pubkeyHash, typeHash)), k -> {
            List<Document> docs = new ArrayList<>();
            for (BindingSet r : select("SELECT ?pos ?np ?cs ?inv WHERE { GRAPH " + REG + " { <" + listBatchNode(pubkeyHash, typeHash, k)
                    + "> npa:member ?e . ?e npa:position ?pos ; npa:npId ?np ; npa:checksum ?cs ; npa:np ?n . ?n npa:invalidated ?inv } }")) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("pubkey", pubkeyHash);
                f.put("type", typeHash);
                f.put("position", SparqlRows.toJava(r.getValue("pos")));
                f.put("np", SparqlRows.toJava(r.getValue("np")));
                f.put("checksum", SparqlRows.toJava(r.getValue("cs")));
                f.put("invalidated", SparqlRows.toJava(r.getValue("inv")));
                docs.add(SparqlRows.toDocument(f, ENTRY_FIELDS));
            }
            docs.sort(Comparator.comparingLong(d -> d.getLong("position")));
            return docs;
        });
    }

    @Override
    public Long listEntryPosition(String pubkeyHash, String typeHash, String checksum) {
        Object v = first(select("SELECT ?pos WHERE { GRAPH " + REG + " { ?e npa:checksum " + literal(checksum)
                + " ; npa:list <" + listNode(pubkeyHash, typeHash) + "> ; npa:position ?pos } }"), "pos");
        return v instanceof Number n ? n.longValue() : null;
    }

    @Override
    public JellyStream listEntriesAfterPosition(String pubkeyHash, String typeHash, long afterPosition) {
        long start = (Math.max(-1, afterPosition) + 1) / batchSize();
        Iterator<Document> docs = batches(LongStream.range(start, listBatchCount(pubkeyHash, typeHash)), k -> {
            List<Object[]> rows = new ArrayList<>();
            for (BindingSet r : select("SELECT ?pos ?j WHERE { GRAPH " + REG + " { <" + listBatchNode(pubkeyHash, typeHash, k)
                    + "> npa:member ?e . ?e npa:position ?pos ; npa:np ?n } GRAPH " + BLOBS + " { ?n npa:jelly ?j } }")) {
                long pos = (Long) SparqlRows.toJava(r.getValue("pos"));
                if (pos > afterPosition) {
                    rows.add(new Object[]{pos, SparqlRows.toJava(r.getValue("j"))});
                }
            }
            rows.sort(Comparator.comparingLong(o -> (Long) o[0]));
            return rows.stream().map(o -> new Document("jelly", o[1])).toList();
        });
        return new JellyStream(NanopubStream.fromMongoCursor(new DocumentCursor(docs)), null);
    }

    // --- trust state -------------------------------------------------------------------

    @Override
    public List<Document> accounts() {
        List<Document> docs = groupedDocuments("<" + REGISTRY + "> npa:hasAccount ?s", ACCOUNT_FIELDS);
        docs.sort(Comparator.comparing(d -> d.getString("pubkey")));
        return docs;
    }

    @Override
    public List<Document> accountsByAgent(String agentId) {
        List<Document> docs = groupedDocuments("<" + agentNode(Utils.getHash(agentId)) + "> npa:hasAccount ?s", ACCOUNT_FIELDS);
        docs.sort(Comparator.comparing(d -> d.getString("pubkey")));
        return docs;
    }

    @Override
    public Document quotaBearingAccount(String pubkeyHash) {
        for (Document d : groupedDocuments("<" + pubkeyNode(pubkeyHash) + "> npa:hasAccount ?s", ACCOUNT_FIELDS)) {
            if (QUOTA_BEARING_STATUSES.contains(d.getString("status"))) {
                return d;
            }
        }
        return null;
    }

    @Override
    public long accountCount() {
        return longProp(registry(), "accountCount");
    }

    @Override
    public List<Document> agents() {
        List<Document> docs = groupedDocuments("<" + REGISTRY + "> npa:hasAgent ?s", AGENT_FIELDS);
        docs.sort(Comparator.comparingDouble((Document d) -> d.getDouble("totalRatio")).reversed());
        return docs;
    }

    @Override
    public Document getAgent(String agentId) {
        Map<String, Object> props = readProperties(agentNode(Utils.getHash(agentId)));
        return props.isEmpty() ? null : SparqlRows.toDocument(props, AGENT_FIELDS);
    }

    @Override
    public long agentCount() {
        return longProp(registry(), "agentCount");
    }

    @Override
    public List<Document> trustStateSnapshots() {
        List<Document> docs = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : grouped("<" + REGISTRY + "> npa:hasTrustState ?s").entrySet()) {
            docs.add(new Document("_id", hashOf(e.getKey()))
                    .append("trustStateCounter", e.getValue().get("trustStateCounter"))
                    .append("createdAt", e.getValue().get("createdAt")));
        }
        docs.sort(Comparator.comparingLong((Document d) -> d.getLong("trustStateCounter")).reversed());
        return docs;
    }

    @Override
    public Document trustStateSnapshot(String hash) {
        IRI node = trustStateNode(hash);
        Map<String, Object> props = readProperties(node);
        if (props.isEmpty()) {
            return null;
        }
        List<Document> accounts = groupedDocuments("<" + node + "> npa:hasAccount ?s", ACCOUNT_FIELDS);
        accounts.sort(Comparator.comparing(d -> d.getString("pubkey")));
        return new Document("_id", hash)
                .append("trustStateCounter", props.get("trustStateCounter"))
                .append("createdAt", props.get("createdAt"))
                .append("accounts", accounts)
                .append("edges", List.of());
    }

    @Override
    public long trustStateSnapshotCount() {
        return select("SELECT ?s WHERE { GRAPH " + REG + " { <" + REGISTRY + "> npa:hasTrustState ?s } }").size();
    }

    @Override
    public String unhash(String hash) {
        Object v = first(select("SELECT ?v WHERE { GRAPH " + REG + " { <" + hashNode(hash) + "> npa:value ?v } }"), "v");
        return v == null ? null : v.toString();
    }

    @Override
    public void close() {
        conn.close();
    }

    // --- helpers -----------------------------------------------------------------------

    /**
     * All literal and IRI-valued properties of a node, keyed by predicate local name.
     */
    Map<String, Object> readProperties(IRI node) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (BindingSet r : select("SELECT ?p ?v WHERE { GRAPH " + REG + " { <" + node + "> ?p ?v } }")) {
            props.put(((IRI) r.getValue("p")).getLocalName(), SparqlRows.toJava(r.getValue("v")));
        }
        return props;
    }

    /**
     * The properties of every node ?s matched by the pattern, keyed by node IRI.
     */
    private Map<String, Map<String, Object>> grouped(String pattern) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (BindingSet r : select("SELECT ?s ?p ?v WHERE { GRAPH " + REG + " { " + pattern + " . ?s ?p ?v } }")) {
            groups.computeIfAbsent(r.getValue("s").stringValue(), k -> new LinkedHashMap<>())
                    .put(((IRI) r.getValue("p")).getLocalName(), SparqlRows.toJava(r.getValue("v")));
        }
        return groups;
    }

    private List<Document> groupedDocuments(String pattern, List<String> fields) {
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> props : grouped(pattern).values()) {
            docs.add(SparqlRows.toDocument(props, fields));
        }
        return docs;
    }

    private static String hashOf(String trustStateIri) {
        return trustStateIri.substring(trustStateIri.lastIndexOf('/') + 1);
    }

    private static String literal(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Object first(List<BindingSet> rows, String var) {
        return rows.isEmpty() ? null : SparqlRows.toJava(rows.getFirst().getValue(var));
    }

    private List<BindingSet> select(String query) {
        List<BindingSet> rows = new ArrayList<>();
        try (TupleQueryResult result = conn.prepareTupleQuery(PREFIX + query).evaluate()) {
            while (result.hasNext()) {
                rows.add(result.next());
            }
        }
        return rows;
    }

    /**
     * A lazy iterator over the elements of consecutive batches: a batch is fetched when the
     * previous one is exhausted, so memory holds one batch at a time.
     */
    private static <T> CloseableIterator<T> batches(LongStream batchIndexes, LongFunction<List<T>> fetch) {
        Iterator<Long> ks = batchIndexes.boxed().iterator();
        return new CloseableIterator<>() {
            private Iterator<T> current = List.<T>of().iterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    if (!ks.hasNext()) {
                        return false;
                    }
                    current = fetch.apply(ks.next()).iterator();
                }
                return true;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }

            @Override
            public void close() {
            }
        };
    }


}
