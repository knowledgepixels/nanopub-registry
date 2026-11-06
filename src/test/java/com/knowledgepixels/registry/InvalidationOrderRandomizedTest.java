package com.knowledgepixels.registry;

import com.knowledgepixels.registry.db.DataRepair;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.MaybeNanopub;
import org.nanopub.vocabulary.NPX;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which nanopubs are on a list follows from the nanopubs alone: the types of an invalidated
 * nanopub also apply to the ones that invalidate it. This test generates nanopubs that
 * invalidate each other in chains and branches, some of them signed with another pubkey,
 * computes from that rule what the lists have to hold, and compares it with what a registry
 * ends up with when the nanopubs reach it in a random order, by different ways of loading.
 * <p>
 * The nanopubs and the orders are derived from fixed seeds. The streams are long enough for the
 * parallel loader to write its batches in mid-stream.
 */
@Testcontainers
class InvalidationOrderRandomizedTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    private static final String PUBKEY = "randomized-order-key";
    private static final String OTHER_PUBKEY = "randomized-order-other-key";
    private static final int NANOPUBS_PER_SEED = 130;

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        CoverageFilter.init();
        session = RegistryDB.getClient().startSession();
    }

    @AfterEach
    void tearDown() {
        session.close();
        RegistryDB.getDB().drop();
        RegistryDB.getClient().close();
        fakeEnv.reset();
    }

    private static IRI iri(String v) {
        return SimpleValueFactory.getInstance().createIRI(v);
    }

    /** A generated nanopub, the pubkey it counts as signed with, and the ones it invalidates. */
    private record Generated(Nanopub nanopub, String artifactCode, String pubkey, List<Integer> invalidates) {
        String pubkeyHash() {
            return Utils.getHash(pubkey);
        }
    }

    private static List<Generated> generate(long seed) throws Exception {
        Random random = new Random(seed);
        List<Generated> all = new ArrayList<>();
        for (int i = 0; i < NANOPUBS_PER_SEED; i++) {
            String pubkey = random.nextInt(100) < 6 ? OTHER_PUBKEY : PUBKEY;
            NanopubCreator creator = new NanopubCreator("http://example.org/randomized/" + seed + "/" + i + "/");
            creator.addAssertionStatement(iri("http://example.org/thing/" + i), iri("http://example.org/relatedTo"), iri("http://example.org/other/" + i));
            creator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), iri("http://example.org/someone"));
            creator.addPubinfoStatement(iri("http://purl.org/dc/terms/description"), SimpleValueFactory.getInstance().createLiteral("randomized " + seed + " " + i));
            if (random.nextBoolean()) {
                creator.addPubinfoStatement(RDF.TYPE, iri("http://example.org/Type" + random.nextInt(6)));
            }
            // More than half invalidate an earlier one, some of them two, so that there are
            // chains of versions, nanopubs invalidated several times, and both kinds mixed.
            List<Integer> invalidates = new ArrayList<>();
            int roll = random.nextInt(100);
            int targets = (i == 0) ? 0 : roll < 55 ? 1 : roll < 63 ? 2 : 0;
            for (int t = 0; t < targets; t++) {
                int target = Math.max(0, i - 1 - random.nextInt(30));
                if (invalidates.contains(target)) {
                    continue;
                }
                invalidates.add(target);
                if (random.nextInt(4) == 0) {
                    creator.addAssertionStatement(iri("http://example.org/someone"), NPX.RETRACTS, all.get(target).nanopub().getUri());
                } else {
                    creator.addPubinfoStatement(NPX.SUPERSEDES, all.get(target).nanopub().getUri());
                }
            }
            Nanopub nanopub = creator.finalizeTrustyNanopub();
            all.add(new Generated(nanopub, TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue()), pubkey, invalidates));
        }
        return all;
    }

    /**
     * What the lists have to hold, from the rule alone: "pubkey|type" -> the nanopubs on the
     * list, with their invalidated flag. An invalidation only counts for the pubkey that made it.
     */
    private static Map<String, Set<String>> expectedLists(List<Generated> all) {
        List<Set<String>> lists = new ArrayList<>();
        for (Generated g : all) {
            Set<String> own = new HashSet<>();
            own.add("$");
            for (IRI type : NanopubUtils.getTypes(g.nanopub())) {
                own.add(Utils.getHash(type.stringValue()));
            }
            lists.add(own);
        }
        Set<Integer> invalidated = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < all.size(); i++) {
                for (int target : all.get(i).invalidates()) {
                    if (all.get(target).pubkey().equals(all.get(i).pubkey())) {
                        invalidated.add(target);
                        changed |= lists.get(i).addAll(lists.get(target));
                    }
                }
            }
        }
        Map<String, Set<String>> expected = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            for (String type : lists.get(i)) {
                expected.computeIfAbsent(all.get(i).pubkeyHash() + "|" + type, k -> new TreeSet<>())
                        .add(all.get(i).artifactCode() + (invalidated.contains(i) ? " (invalidated)" : ""));
            }
        }
        return expected;
    }

    private Map<String, Set<String>> storedLists() {
        Map<String, Set<String>> contents = new TreeMap<>();
        for (Document entry : RegistryDB.collection("listEntries").find(session)) {
            contents.computeIfAbsent(entry.getString("pubkey") + "|" + entry.getString("type"), k -> new TreeSet<>())
                    .add(entry.getString("np") + (entry.getBoolean("invalidated") ? " (invalidated)" : ""));
        }
        return contents;
    }

    /** The lists hold what the rule says, each as one sequential chain, and no invalidation is on record twice. */
    private void assertListsFollowTheRule(List<Generated> all, String scenario) {
        Map<String, Set<String>> stored = storedLists();
        assertEquals(expectedLists(all), stored, scenario + ": the nanopubs on the lists");
        for (String key : stored.keySet()) {
            String[] list = key.split("\\|");
            Document listFilter = new Document("pubkey", list[0]).append("type", list[1]);
            String checksum = NanopubUtils.INIT_CHECKSUM;
            long position = 0;
            for (Document entry : RegistryDB.collection("listEntries").find(session, listFilter).sort(new Document("position", 1))) {
                checksum = NanopubUtils.updateXorChecksum(iri("https://w3id.org/np/" + entry.getString("np")), checksum);
                assertEquals(position++, entry.getLong("position"), scenario + ": positions of list " + key);
                assertEquals(checksum, entry.getString("checksum"), scenario + ": checksum chain of list " + key);
            }
            assertEquals(position - 1, RegistryDB.collection("lists").find(session, listFilter).first().getLong("maxPosition"),
                    scenario + ": counter of list " + key);
        }
        Set<String> records = new HashSet<>();
        for (Document record : RegistryDB.collection("invalidations").find(session)) {
            assertEquals(true, records.add(record.getString("invalidatingNp") + "|" + record.getString("invalidatingPubkey") + "|" + record.getString("invalidatedNp")),
                    scenario + ": an invalidation is on record once");
        }
    }

    private void startOver() {
        for (String c : List.of(Collection.NANOPUBS.toString(), "listEntries", "lists", "invalidations")) {
            RegistryDB.collection(c).deleteMany(session, new Document());
        }
    }

    private static List<Generated> shuffled(List<Generated> all, Random random, boolean someTwice) {
        List<Generated> order = new ArrayList<>(all);
        if (someTwice) {
            for (int i = 0; i < all.size() / 10; i++) {
                order.add(all.get(random.nextInt(all.size())));
            }
        }
        Collections.shuffle(order, random);
        return order;
    }

    /** Stores the nanopub and then puts it on its lists, as the POST handler does. */
    private static void post(ClientSession s, Generated g) {
        RegistryDB.loadNanopubOrReject(s, g.nanopub(), g.pubkey(), null);
        RegistryDB.loadNanopubVerified(s, g.nanopub(), g.pubkey(), g.pubkeyHash(), "$");
    }

    private static void loadInParallel(List<Generated> order) {
        Map<String, Generated> byUri = new HashMap<>();
        for (Generated g : order) {
            byUri.put(g.nanopub().getUri().stringValue(), g);
        }
        NanopubLoader.loadStreamInParallel(order.stream().map(g -> new MaybeNanopub(g.nanopub())), nanopub -> {
            Generated g = byUri.get(nanopub.getUri().stringValue());
            try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                RegistryDB.loadNanopubVerified(workerSession, nanopub, g.pubkey(), g.pubkeyHash(), "$");
            }
        });
    }

    @Test
    void theListsFollowFromTheNanopubsWhateverTheOrderAndWayOfLoading() throws Exception {
        for (long seed = 1; seed <= 2; seed++) {
            List<Generated> all = generate(seed);
            Random random = new Random(seed * 31);

            startOver();
            for (Generated g : shuffled(all, random, true)) {
                if (random.nextBoolean()) {
                    post(session, g);
                } else {
                    RegistryDB.loadNanopubVerified(session, g.nanopub(), g.pubkey(), g.pubkeyHash(), "$");
                }
            }
            assertListsFollowTheRule(all, "seed " + seed + ", one by one");

            startOver();
            loadInParallel(shuffled(all, random, true));
            assertListsFollowTheRule(all, "seed " + seed + ", parallel stream");

            // Half of the nanopubs are posted while the stream is loaded
            startOver();
            List<Generated> posted = shuffled(all, random, false).subList(0, all.size() / 2);
            Thread poster = new Thread(() -> {
                try (ClientSession posterSession = RegistryDB.getClient().startSession()) {
                    for (Generated g : posted) {
                        post(posterSession, g);
                    }
                }
            });
            poster.start();
            loadInParallel(shuffled(all, random, false));
            poster.join();
            assertListsFollowTheRule(all, "seed " + seed + ", parallel stream and posts side by side");

            // A stream with half of the nanopubs, then one with all of them
            startOver();
            loadInParallel(shuffled(all, random, false).subList(0, all.size() / 2));
            loadInParallel(shuffled(all, random, true));
            assertListsFollowTheRule(all, "seed " + seed + ", partial stream and then a full one");

            // What earlier versions could leave behind: every nanopub stored and on record,
            // but on the lists of its own types only, and no entry marked. The repair has to
            // get from there to the same lists.
            startOver();
            for (Generated g : shuffled(all, random, false)) {
                RegistryDB.loadNanopubOrReject(session, g.nanopub(), g.pubkey(), null);
            }
            RegistryDB.collection("listEntries").deleteMany(session, new Document());
            RegistryDB.collection("lists").deleteMany(session, new Document());
            for (Generated g : shuffled(all, random, false)) {
                List<String> ownTypes = new ArrayList<>(List.of("$"));
                for (IRI type : NanopubUtils.getTypes(g.nanopub())) {
                    ownTypes.add(Utils.getHash(type.stringValue()));
                }
                for (String typeHash : ownTypes) {
                    Document listFilter = new Document("pubkey", g.pubkeyHash()).append("type", typeHash);
                    if (!RegistryDB.has(session, "lists", listFilter)) {
                        RegistryDB.insert(session, "lists", new Document(listFilter).append("maxPosition", -1L));
                    }
                    RegistryDB.appendDirectly(session, g.nanopub().getUri(), g.artifactCode(), g.pubkeyHash(), typeHash, false);
                }
            }
            RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "repairVersion", 2);
            DataRepair.runIfNeeded(session);
            assertListsFollowTheRule(all, "seed " + seed + ", repair");
        }
    }

}
