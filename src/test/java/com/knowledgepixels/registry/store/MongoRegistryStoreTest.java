package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.CoverageFilter;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.jelly.MaybeNanopub;
import org.nanopub.jelly.NanopubStream;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The MongoDB store's reads against a real database, loaded through {@code RegistryDB}.
 */
@Testcontainers
class MongoRegistryStoreTest {

    @Container
    private final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0.0");

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongo, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        CoverageFilter.init();
        RegistryDB.init();
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
    }

    private static String ac(Nanopub np) {
        return TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
    }

    static List<MaybeNanopub> decode(JellyStream js) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (js) {
            js.stream().writeToByteStream(bytes);
        }
        return NanopubStream.fromByteStream(new ByteArrayInputStream(bytes.toByteArray())).getAsNanopubs().toList();
    }

    @Test
    void readsWhatRegistryDbLoaded() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = superseding(KEY1, a, TYPE_A);
        String pk = Utils.getHash(RegistryDB.getPubkey(a));
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            assertTrue(RegistryDB.loadNanopub(s, a, pk, "$"));
            assertTrue(RegistryDB.loadNanopub(s, b, pk, "$"));
        }
        String tA = Utils.getHash(TYPE_A.stringValue());

        try (RegistryReader r = new MongoRegistryStore().reader()) {
            Document doc = r.getNanopub(ac(a));
            assertEquals(a.getUri().stringValue(), doc.getString("fullId"));
            assertEquals(1L, doc.getLong("counter"));
            assertEquals(pk, doc.getString("pubkey"));
            assertEquals(2L, r.maxCounter());
            assertEquals(2L, r.nanopubCountExact());

            List<String> desc = new ArrayList<>();
            try (CloseableIterator<String> it = r.nanopubIdsByCounterDesc()) {
                it.forEachRemaining(desc::add);
            }
            assertEquals(List.of(ac(b), ac(a)), desc);
            assertEquals(List.of(ac(b)), r.latestNanopubIds(1));

            Document list = r.getList(pk, tA);
            assertEquals(1L, list.getLong("maxPosition"));
            assertTrue(r.pubkeys().contains(pk));
            List<Document> entries = new ArrayList<>();
            try (CloseableIterator<Document> it = r.listEntries(pk, tA)) {
                it.forEachRemaining(entries::add);
            }
            assertEquals(List.of(ac(a), ac(b)), entries.stream().map(e -> e.getString("np")).toList());
            assertTrue(entries.get(0).getBoolean("invalidated"));
            assertEquals(1L, r.listEntryPosition(pk, tA, entries.get(1).getString("checksum")));

            assertEquals(List.of(b.getUri()), decode(r.listEntriesAfterPosition(pk, tA, 0)).stream().map(m -> m.getNanopub().getUri()).toList());
            List<MaybeNanopub> all = decode(r.nanopubsAfterCounter(-1));
            assertEquals(List.of(1L, 2L), all.stream().map(MaybeNanopub::getCounter).toList());
            assertEquals(a.getUri(), r.unhash(pk) == null ? null : a.getUri());
            assertEquals(TYPE_A.stringValue(), r.unhash(tA));
        }
    }

}
