package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.CoverageFilter;
import com.knowledgepixels.registry.ListPage;
import com.knowledgepixels.registry.NanopubPage;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.PageMocks;
import com.knowledgepixels.registry.utils.ReadOnlyFixtures;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.jelly.MaybeNanopub;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The same nanopubs loaded into MongoDB through {@code RegistryDB} and built into a
 * read-only file must be served identically by both stores, down to the JSON and TriG
 * the pages write.
 */
@Testcontainers
class StoreParityTest {

    @Container
    private final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0.0");

    private FakeEnv fakeEnv;
    private Nanopub a, b, c;
    private String pk;
    private SparqlRegistryStore sparql;
    private RegistryStore mongoStore;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongo, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        CoverageFilter.init();
        RegistryDB.init();

        a = simple(KEY1, "a", TYPE_A);
        b = superseding(KEY1, a, TYPE_B);
        c = simple(KEY1, "c", TYPE_A, TYPE_B);
        pk = Utils.getHash(RegistryDB.getPubkey(a));
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            for (Nanopub np : List.of(a, b, c)) {
                assertTrue(RegistryDB.loadNanopub(s, np, pk, "$"));
            }
        }
        // The pages that take a snapshot start a transaction, which this single-node test
        // container cannot serve; parity is about the reads, so snapshots read plainly here.
        mongoStore = new MongoRegistryStore() {
            @Override
            public RegistryReader snapshotReader() {
                return reader();
            }
        };
        sparql = new SparqlRegistryStore(repository(build(options(2), List.of(a, b, c))), 0);
    }

    @AfterEach
    void tearDown() {
        RegistryStores.set(new MongoRegistryStore());
        sparql.close();
        fakeEnv.reset();
    }

    private static String ac(Nanopub np) {
        return TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
    }

    private <T> void assertSame(Function<RegistryReader, T> read) {
        T fromMongo;
        T fromSparql;
        try (RegistryReader r = mongoStore.reader()) {
            fromMongo = read.apply(r);
        }
        try (RegistryReader r = sparql.reader()) {
            fromSparql = read.apply(r);
        }
        assertEquals(fromMongo, fromSparql);
    }

    private static List<String> drain(CloseableIterator<String> it) {
        List<String> out = new ArrayList<>();
        try (it) {
            it.forEachRemaining(out::add);
        }
        return out;
    }

    private static List<Document> drainDocs(CloseableIterator<Document> it) {
        List<Document> out = new ArrayList<>();
        try (it) {
            it.forEachRemaining(out::add);
        }
        return out;
    }

    @Test
    void readersAgree() {
        for (Nanopub np : List.of(a, b, c)) {
            assertSame(r -> {
                Document d = r.getNanopub(ac(np));
                return List.of(d.getString("fullId"), d.getLong("counter"), d.getString("pubkey"), d.getString("content"),
                        java.util.Arrays.hashCode(d.get("jelly", Binary.class).getData()));
            });
        }
        assertSame(RegistryReader::maxCounter);
        assertSame(RegistryReader::nanopubCountExact);
        assertSame(r -> drain(r.nanopubIdsByCounterDesc()));
        assertSame(r -> drain(r.nanopubIdsByIdAsc("")));
        assertSame(r -> drain(r.nanopubIdsByIdAsc(ac(a))));
        assertSame(r -> r.latestNanopubIds(2));
        assertSame(RegistryReader::pubkeys);
        assertSame(RegistryReader::pubkeyNanopubCounts);
        assertSame(r -> r.listsForPubkey(pk).stream().map(l -> l.getString("type") + "=" + l.getLong("maxPosition")).sorted().toList());
        for (String type : List.of("$", Utils.getHash(TYPE_A.stringValue()), Utils.getHash(TYPE_B.stringValue()))) {
            assertSame(r -> r.getList(pk, type).getLong("maxPosition"));
            assertSame(r -> drainDocs(r.listEntries(pk, type)).stream().map(Document::toJson).toList());
            assertSame(r -> {
                List<Document> entries = drainDocs(r.listEntries(pk, type));
                return entries.stream().map(e -> r.listEntryPosition(pk, type, e.getString("checksum"))).toList();
            });
            assertSame(r -> MongoRegistryStoreTest.decode(r.listEntriesAfterPosition(pk, type, 0)).stream().map(m -> m.getNanopub().getUri()).toList());
        }
        assertSame(r -> MongoRegistryStoreTest.decode(r.nanopubsAfterCounter(1)).stream().map(m -> m.getCounter() + " " + m.getNanopub().getUri()).toList());
        assertSame(r -> r.unhash(Utils.getHash(TYPE_B.stringValue())));
        assertSame(r -> r.unhash(pk));
    }

    @Test
    void pagesAgree() {
        for (String path : List.of("/list/" + pk + "/$.json", "/list/" + pk + "/" + Utils.getHash(TYPE_A.stringValue()) + ".json",
                "/nanopubs.json", "/pubkeys.json")) {
            assertEquals(pageBody(mongoStore, path, ListPage::show), pageBody(sparql, path, ListPage::show), path);
        }
        assertEquals(pageBody(mongoStore, "/nanopubs.json", Map.of("sort", "id"), ListPage::show),
                pageBody(sparql, "/nanopubs.json", Map.of("sort", "id"), ListPage::show));
        for (Nanopub np : List.of(a, b, c)) {
            assertEquals(pageBody(mongoStore, "/np/" + ac(np) + ".trig", NanopubPage::show), pageBody(sparql, "/np/" + ac(np) + ".trig", NanopubPage::show));
            assertEquals(pageBody(mongoStore, "/np/" + ac(np) + ".nq", NanopubPage::show), pageBody(sparql, "/np/" + ac(np) + ".nq", NanopubPage::show));
        }
    }

    private static String pageBody(RegistryStore store, String path, java.util.function.Consumer<io.vertx.ext.web.RoutingContext> show) {
        return pageBody(store, path, Map.of(), show);
    }

    private static String pageBody(RegistryStore store, String path, Map<String, String> params, java.util.function.Consumer<io.vertx.ext.web.RoutingContext> show) {
        RegistryStores.set(store);
        PageMocks.MockContext ctx = PageMocks.context(path, params);
        show.accept(ctx.context);
        return ctx.body();
    }

}
