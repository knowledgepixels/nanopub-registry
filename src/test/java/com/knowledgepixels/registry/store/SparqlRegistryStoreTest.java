package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.readonly.BuildOptions;
import com.knowledgepixels.registry.utils.ReadOnlyFixtures;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.JellyUtils;
import org.nanopub.jelly.MaybeNanopub;
import org.nanopub.jelly.NanopubStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every read of {@link SparqlRegistryReader}, against an in-memory repository holding a
 * file the builder produced from a handful of signed nanopubs (batch size 2, so that every
 * batch walk crosses a boundary).
 */
class SparqlRegistryStoreTest {

    private static Nanopub intro, a, b, c, d;
    private static String pk1, pk2;
    private static Repository repo;
    private static SparqlRegistryStore store;

    private static String ac(Nanopub np) {
        return TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
    }

    @BeforeAll
    static void setUp() throws Exception {
        intro = intro(KEY1, ALICE, "Alice", new Date());
        a = simple(KEY1, "a", TYPE_A);
        b = simple(KEY1, "b", TYPE_A, TYPE_B);
        c = superseding(KEY1, a, TYPE_B);
        d = simple(KEY2, "d", TYPE_A);
        pk1 = Utils.getHash(RegistryDB.getPubkey(a));
        pk2 = Utils.getHash(RegistryDB.getPubkey(d));
        BuildOptions opts = options(2);
        opts.coverageAgents = "viaSetting";
        Model model = build(opts, List.of(intro, a, b, c, d));
        repo = repository(model);
        store = new SparqlRegistryStore(repo, 0);
    }

    @AfterAll
    static void tearDown() {
        store.close();
    }

    private static List<String> drain(CloseableIterator<String> it) {
        List<String> out = new ArrayList<>();
        try (it) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    private static List<MaybeNanopub> decode(JellyStream js) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (js) {
            js.stream().writeToByteStream(bytes);
        }
        return NanopubStream.fromByteStream(new ByteArrayInputStream(bytes.toByteArray())).getAsNanopubs().toList();
    }

    @Test
    void serverInfoAndSetting() {
        try (RegistryReader r = store.reader()) {
            Map<String, Object> si = r.serverInfo();
            assertEquals("ready", si.get("status"));
            assertEquals(4242L, si.get("setupId"));
            assertEquals(false, si.get("testInstance"));
            assertEquals(1L, si.get("trustStateCounter"));
            assertEquals("viaSetting", si.get("coverageAgents"));
            assertNull(si.get("coverageTypes"));
            assertEquals("ready", r.serverInfoValue("status"));
            assertFalse(r.isTestInstance());
            assertNull(r.settingValue("original"));
        }
    }

    @Test
    void nanopubLookupAndCounts() throws Exception {
        try (RegistryReader r = store.reader()) {
            Document doc = r.getNanopub(ac(b));
            assertEquals(ac(b), doc.getString("_id"));
            assertEquals(b.getUri().stringValue(), doc.getString("fullId"));
            assertEquals(3L, doc.getLong("counter"));
            assertEquals(pk1, doc.getString("pubkey"));
            assertEquals(NanopubUtils.writeToString(b, org.eclipse.rdf4j.rio.RDFFormat.TRIG), doc.getString("content"));
            Nanopub fromJelly = JellyUtils.readFromDB(doc.get("jelly", Binary.class).getData());
            assertEquals(b.getUri(), fromJelly.getUri());
            assertNull(r.getNanopub("RA" + "x".repeat(43)));

            assertEquals(5L, r.maxCounter());
            assertEquals(5L, r.nanopubCount());
            assertEquals(5L, r.nanopubCountExact());
            assertEquals(0L, r.accountCount());
            assertEquals(0L, r.agentCount());
        }
    }

    @Test
    void idListings() {
        try (RegistryReader r = store.reader()) {
            assertEquals(List.of(ac(d), ac(c), ac(b), ac(a), ac(intro)), drain(r.nanopubIdsByCounterDesc()));
            assertEquals(List.of(ac(d), ac(c)), r.latestNanopubIds(2));

            List<String> sorted = List.of(ac(intro), ac(a), ac(b), ac(c), ac(d)).stream().sorted().toList();
            assertEquals(sorted, drain(r.nanopubIdsByIdAsc("")));
            assertEquals(sorted.subList(1, 5), drain(r.nanopubIdsByIdAsc(sorted.get(0))));
            assertEquals(sorted.subList(3, 5), drain(r.nanopubIdsByIdAsc(sorted.get(2))));
            assertEquals(List.of(), drain(r.nanopubIdsByIdAsc(sorted.get(4))));
            assertEquals(List.of(), drain(r.nanopubIdsByIdAsc("RAzzzzzzzz")));
        }
    }

    @Test
    void jellyStreamAfterCounter() {
        try (RegistryReader r = store.reader()) {
            List<MaybeNanopub> all = decode(r.nanopubsAfterCounter(-1));
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), all.stream().map(MaybeNanopub::getCounter).toList());
            assertEquals(intro.getUri(), all.getFirst().getNanopub().getUri());

            List<MaybeNanopub> after = decode(r.nanopubsAfterCounter(3));
            assertEquals(List.of(4L, 5L), after.stream().map(MaybeNanopub::getCounter).toList());
            assertEquals(List.of(c.getUri(), d.getUri()), after.stream().map(m -> m.getNanopub().getUri()).toList());
            assertTrue(decode(r.nanopubsAfterCounter(5)).isEmpty());
        }
    }

    @Test
    void lists() {
        try (RegistryReader r = store.reader()) {
            String tA = Utils.getHash(TYPE_A.stringValue());
            String tB = Utils.getHash(TYPE_B.stringValue());
            String tIntro = com.knowledgepixels.registry.NanopubLoader.INTRO_TYPE_HASH;
            List<Document> lists = r.listsForPubkey(pk1);
            // $, TypeA, TypeB, the intro type, and rdfs:label inferred from the one-predicate assertions
            assertEquals(5, lists.size());
            Document dollar = r.getList(pk1, "$");
            assertEquals(3L, dollar.getLong("maxPosition"));
            assertEquals("loaded", dollar.getString("status"));
            assertEquals("{\"pubkey\": \"" + pk1 + "\", \"type\": \"$\", \"maxPosition\": 3, \"status\": \"loaded\"}", dollar.toJson());
            assertNull(r.getList(pk1, "nope"));
            assertEquals(List.of(pk1, pk2).stream().sorted().toList(), r.pubkeys());
            assertEquals(Map.of(pk1, 4L, pk2, 1L), r.pubkeyNanopubCounts());
            assertTrue(lists.stream().anyMatch(l -> l.getString("type").equals(tIntro)));

            List<Document> entries = new ArrayList<>();
            try (CloseableIterator<Document> it = r.listEntries(pk1, tA)) {
                it.forEachRemaining(entries::add);
            }
            assertEquals(List.of(ac(a), ac(b), ac(c)), entries.stream().map(e -> e.getString("np")).toList());
            assertEquals(List.of(0L, 1L, 2L), entries.stream().map(e -> e.getLong("position")).toList());
            assertEquals(List.of(true, false, false), entries.stream().map(e -> e.getBoolean("invalidated")).toList());
            String c0 = NanopubUtils.updateXorChecksum(a.getUri(), NanopubUtils.INIT_CHECKSUM);
            assertEquals(c0, entries.get(0).getString("checksum"));
            assertEquals(1L, r.listEntryPosition(pk1, tA, entries.get(1).getString("checksum")));
            assertNull(r.listEntryPosition(pk1, tA, "nope"));
            assertNull(r.listEntryPosition(pk1, tB, c0));

            List<MaybeNanopub> afterFirst = decode(r.listEntriesAfterPosition(pk1, tA, 0));
            assertEquals(List.of(b.getUri(), c.getUri()), afterFirst.stream().map(m -> m.getNanopub().getUri()).toList());
            assertEquals(3, decode(r.listEntriesAfterPosition(pk1, tA, -1)).size());
            assertTrue(decode(r.listEntriesAfterPosition(pk1, tA, 2)).isEmpty());
        }
    }

    @Test
    void trustStateIsEmpty() {
        try (RegistryReader r = store.reader()) {
            assertEquals(List.of(), r.accounts());
            assertEquals(List.of(), r.accountsByAgent(ALICE.stringValue()));
            assertNull(r.quotaBearingAccount(pk1));
            assertEquals(List.of(), r.agents());
            assertNull(r.getAgent(ALICE.stringValue()));

            List<Document> snapshots = r.trustStateSnapshots();
            assertEquals(1, snapshots.size());
            String hash = snapshots.getFirst().getString("_id");
            assertEquals(Utils.getHash(""), hash);
            assertEquals(r.serverInfoValue("trustStateHash"), hash);
            assertEquals(1L, snapshots.getFirst().getLong("trustStateCounter"));
            assertEquals(1L, r.trustStateSnapshotCount());
            Document snapshot = r.trustStateSnapshot(hash);
            assertEquals(List.of(), snapshot.getList("accounts", Document.class));
            assertEquals(List.of(), snapshot.getList("edges", Document.class));
            assertNull(r.trustStateSnapshot("nope"));

            assertEquals(TYPE_A.stringValue(), r.unhash(Utils.getHash(TYPE_A.stringValue())));
            assertEquals(RegistryDB.getPubkey(a), r.unhash(pk1));
            assertNull(r.unhash("nope"));
        }
    }

    @Test
    void emptyFileServesNothingButDoesNotFail() {
        Model model = build(options(2), List.of());
        Repository empty = repository(model);
        try (SparqlRegistryStore s = new SparqlRegistryStore(empty, 0); RegistryReader r = s.reader()) {
            assertNull(r.maxCounter());
            assertEquals(0L, r.nanopubCount());
            assertEquals(List.of(), drain(r.nanopubIdsByCounterDesc()));
            assertEquals(List.of(), drain(r.nanopubIdsByIdAsc("")));
            assertTrue(decode(r.nanopubsAfterCounter(-1)).isEmpty());
            assertEquals(List.of(), r.accounts());
            assertEquals(List.of(), r.pubkeys());
        }
    }

}
