package com.knowledgepixels.registry.readonly;

import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.store.RegistryMeta;
import com.knowledgepixels.registry.utils.ReadOnlyFixtures;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static com.knowledgepixels.registry.store.RegistryMeta.*;
import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The builder's placement of nanopubs on lists, checksum chains, invalidation handling,
 * batch nodes and the synthetic trust state, checked on the quads it writes.
 */
class BuildTest {

    private static String ac(Nanopub np) {
        return TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
    }

    private static String pubkeyHash(Nanopub np) {
        return Utils.getHash(RegistryDB.getPubkey(np));
    }

    private static String typeHash(IRI type) {
        return Utils.getHash(type.stringValue());
    }

    private static Value one(Model m, Resource s, IRI p) {
        Set<Value> objects = m.filter(s, p, null).objects();
        assertEquals(1, objects.size(), "exactly one value of " + p.getLocalName() + " on " + s);
        return objects.iterator().next();
    }

    private static long longOf(Model m, Resource s, IRI p) {
        return ((Literal) one(m, s, p)).longValue();
    }

    private static Set<Value> members(Model m, Resource batch) {
        return m.filter(batch, MEMBER, null).objects();
    }

    /**
     * The entries of a list, as the (artifact code, checksum) pairs in position order.
     */
    private static List<String[]> entries(Model m, String pk, String th) {
        long max = longOf(m, listNode(pk, th), MAX_POSITION);
        return java.util.stream.LongStream.rangeClosed(0, max).mapToObj(pos -> {
            IRI e = entryNode(pk, th, pos);
            return new String[]{one(m, e, NP_ID).stringValue(), one(m, e, CHECKSUM).stringValue()};
        }).toList();
    }

    @Test
    void placesNanopubsOnTheirListsWithCheckedChecksums() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = simple(KEY1, "b", TYPE_A, TYPE_B);
        Model m = build(options(1000), List.of(a, b));
        String pk = pubkeyHash(a);

        assertEquals(1L, longOf(m, npNode(ac(a)), COUNTER));
        assertEquals(2L, longOf(m, npNode(ac(b)), COUNTER));
        assertEquals(pk, one(m, npNode(ac(a)), PUBKEY).stringValue());

        List<String[]> all = entries(m, pk, ALL_TYPES);
        assertEquals(List.of(ac(a), ac(b)), all.stream().map(e -> e[0]).toList());
        String c0 = NanopubUtils.updateXorChecksum(a.getUri(), NanopubUtils.INIT_CHECKSUM);
        assertEquals(c0, all.get(0)[1]);
        assertEquals(NanopubUtils.updateXorChecksum(b.getUri(), c0), all.get(1)[1]);

        assertEquals(List.of(ac(a), ac(b)), entries(m, pk, typeHash(TYPE_A)).stream().map(e -> e[0]).toList());
        assertEquals(List.of(ac(b)), entries(m, pk, typeHash(TYPE_B)).stream().map(e -> e[0]).toList());
        // nanopub-java also infers rdfs:label as a type (the only predicate in the assertion)
        assertEquals(Set.of(listNode(pk, ALL_TYPES), listNode(pk, typeHash(TYPE_A)), listNode(pk, typeHash(TYPE_B)),
                        listNode(pk, typeHash(org.eclipse.rdf4j.model.vocabulary.RDFS.LABEL))),
                m.filter(pubkeyNode(pk), HAS_LIST, null).objects());
        assertEquals("loaded", one(m, listNode(pk, ALL_TYPES), STATUS).stringValue());
        assertEquals(TYPE_A.stringValue(), one(m, hashNode(typeHash(TYPE_A)), VALUE).stringValue());
        assertFalse(((Literal) one(m, npNode(ac(a)), INVALIDATED)).booleanValue());
    }

    @Test
    void invalidatorArrivingAfterItsTargetJoinsTheTargetsLists() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = superseding(KEY1, a, TYPE_B);
        Model m = build(options(1000), List.of(a, b));
        String pk = pubkeyHash(a);

        assertTrue(((Literal) one(m, npNode(ac(a)), INVALIDATED)).booleanValue());
        assertFalse(((Literal) one(m, npNode(ac(b)), INVALIDATED)).booleanValue());
        // b follows a on a's type list, and is on its own as well; nothing is listed twice
        assertEquals(List.of(ac(a), ac(b)), entries(m, pk, typeHash(TYPE_A)).stream().map(e -> e[0]).toList());
        assertEquals(List.of(ac(b)), entries(m, pk, typeHash(TYPE_B)).stream().map(e -> e[0]).toList());
        assertEquals(List.of(ac(a), ac(b)), entries(m, pk, ALL_TYPES).stream().map(e -> e[0]).toList());
    }

    @Test
    void invalidatorArrivingBeforeItsTargetJoinsTheTargetsLists() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = superseding(KEY1, a, TYPE_B);
        Model m = build(options(1000), List.of(b, a));
        String pk = pubkeyHash(a);

        assertTrue(((Literal) one(m, npNode(ac(a)), INVALIDATED)).booleanValue());
        assertEquals(List.of(ac(a), ac(b)), entries(m, pk, typeHash(TYPE_A)).stream().map(e -> e[0]).toList());
        assertEquals(List.of(ac(b)), entries(m, pk, typeHash(TYPE_B)).stream().map(e -> e[0]).toList());
        assertEquals(List.of(ac(b), ac(a)), entries(m, pk, ALL_TYPES).stream().map(e -> e[0]).toList());
    }

    @Test
    void invalidationByAnotherPubkeyIsIgnored() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = superseding(KEY2, a, TYPE_B);
        Model m = build(options(1000), List.of(a, b));

        assertFalse(((Literal) one(m, npNode(ac(a)), INVALIDATED)).booleanValue());
        assertEquals(List.of(ac(a)), entries(m, pubkeyHash(a), typeHash(TYPE_A)).stream().map(e -> e[0]).toList());
        assertEquals(List.of(ac(b)), entries(m, pubkeyHash(b), ALL_TYPES).stream().map(e -> e[0]).toList());
        assertEquals(2, m.filter(REGISTRY, HAS_PUBKEY, null).size());
    }

    @Test
    void writesBatchNodesAndChains() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = simple(KEY1, "b", TYPE_A);
        Nanopub c = simple(KEY1, "c", TYPE_A);
        Model m = build(options(2), List.of(a, b, c));
        String pk = pubkeyHash(a);

        assertEquals(2L, longOf(m, CHAIN_COUNTER, BATCH_COUNT));
        assertEquals(Set.of(npNode(ac(a)), npNode(ac(b))), members(m, counterBatchNode(0)));
        assertEquals(Set.of(npNode(ac(c))), members(m, counterBatchNode(1)));
        assertEquals(1L, longOf(m, counterBatchNode(0), FIRST));
        assertEquals(2L, longOf(m, counterBatchNode(0), LAST));
        assertEquals(counterBatchNode(1), one(m, counterBatchNode(0), NEXT));
        assertEquals(3L, longOf(m, counterBatchNode(1), LAST));
        assertTrue(m.filter(counterBatchNode(1), NEXT, null).isEmpty());

        List<String> sorted = java.util.stream.Stream.of(ac(a), ac(b), ac(c)).sorted().toList();
        assertEquals(2L, longOf(m, CHAIN_ID, BATCH_COUNT));
        assertEquals(sorted.get(0), one(m, idBatchNode(0), FIRST_ID).stringValue());
        assertEquals(sorted.get(1), one(m, idBatchNode(0), LAST_ID).stringValue());
        assertEquals(Set.of(npNode(sorted.get(2))), members(m, idBatchNode(1)));

        assertEquals(2L, longOf(m, listNode(pk, ALL_TYPES), BATCH_COUNT));
        assertEquals(Set.of(entryNode(pk, ALL_TYPES, 0), entryNode(pk, ALL_TYPES, 1)), members(m, listBatchNode(pk, ALL_TYPES, 0)));
        assertEquals(Set.of(entryNode(pk, ALL_TYPES, 2)), members(m, listBatchNode(pk, ALL_TYPES, 1)));
        assertEquals(2L, longOf(m, listBatchNode(pk, ALL_TYPES, 1), FIRST));
        assertEquals(2L, longOf(m, listBatchNode(pk, ALL_TYPES, 1), LAST));

        assertEquals(3L, longOf(m, REGISTRY, NANOPUB_COUNT));
        assertEquals(3L, longOf(m, REGISTRY, LOAD_COUNTER));
        assertEquals(2L, longOf(m, REGISTRY, BATCH_SIZE));
    }

    @Test
    void derivesAccountsAgentsAndTrustStateFromIntros() throws Exception {
        Nanopub intro = intro(KEY1, ALICE, "Alice", new Date());
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = simple(KEY2, "b", TYPE_A);
        Model m = build(options(1000), List.of(intro, a, b));
        String pk1 = pubkeyHash(a);
        String pk2 = pubkeyHash(b);

        IRI acc1 = accountNode(pk1);
        assertEquals(ALICE.stringValue(), one(m, acc1, AGENT).stringValue());
        assertEquals("Alice", one(m, acc1, NAME).stringValue());
        assertEquals(intro.getUri().stringValue(), one(m, acc1, INTRO_NANOPUB).stringValue());
        assertEquals("loaded", one(m, acc1, STATUS).stringValue());
        assertEquals(2, ((Literal) one(m, acc1, QUOTA)).intValue());
        assertEquals(0.5, ((Literal) one(m, acc1, RATIO)).doubleValue(), 1e-9);
        // the other key has no intro: its account names the signer of its nanopubs
        assertEquals(ALICE.stringValue(), one(m, accountNode(pk2), AGENT).stringValue());
        assertTrue(m.filter(accountNode(pk2), INTRO_NANOPUB, null).isEmpty());

        IRI agent = agentNode(Utils.getHash(ALICE.stringValue()));
        assertEquals(Set.of(agent), m.filter(REGISTRY, HAS_AGENT, null).objects());
        assertEquals(2, ((Literal) one(m, agent, ACCOUNT_COUNT)).intValue());
        assertEquals(Set.of(acc1, accountNode(pk2)), m.filter(agent, HAS_ACCOUNT, null).objects());

        String expectedHash = Utils.getHash(java.util.stream.Stream.of(pk1, pk2).sorted()
                .map(pk -> ALICE.stringValue() + "|" + pk + "\n").reduce("", String::concat));
        assertEquals(expectedHash, one(m, SERVER_INFO, TRUST_STATE_HASH).stringValue());
        assertEquals(Set.of(trustStateNode(expectedHash)), m.filter(REGISTRY, HAS_TRUST_STATE, null).objects());
        assertEquals("ready", one(m, SERVER_INFO, STATUS).stringValue());
        assertEquals(4242L, longOf(m, SERVER_INFO, SETUP_ID));
        assertEquals(1L, longOf(m, SERVER_INFO, TRUST_STATE_COUNTER));
        assertEquals(RegistryDB.getPubkey(a), one(m, hashNode(pk1), VALUE).stringValue());
    }

    @Test
    void rejectsUnsignedAndSkipsDuplicates() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub u = unsigned("u");
        Model model = new LinkedHashModel();
        RegistryModelWriter w = new RegistryModelWriter(new StatementCollector(model), options(1000));
        assertTrue(w.add(a));
        assertFalse(w.add(a));
        assertFalse(w.add(u));
        w.finish();
        assertEquals(1, w.getCount());
        assertEquals(1, w.getDuplicateCount());
        assertEquals(1, w.getRejectedCount());
        assertTrue(model.filter(npNode(ac(u)), null, null).isEmpty());
    }

    @Test
    void storesBlobsAndOptionallyContentQuads() throws Exception {
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Model with = build(options(1000), List.of(a));
        assertEquals(NanopubUtils.writeToString(a, org.eclipse.rdf4j.rio.RDFFormat.TRIG),
                one(with, npNode(ac(a)), TRIG).stringValue());
        assertFalse(with.filter(null, null, null, a.getAssertionUri()).isEmpty(), "content quads present");
        assertEquals(Set.of(GRAPH_BLOBS), with.filter(npNode(ac(a)), JELLY, null).contexts());

        BuildOptions opts = options(1000);
        opts.contentQuads = false;
        Model without = build(opts, List.of(a));
        assertTrue(without.filter(null, null, null, a.getAssertionUri()).isEmpty());
        assertFalse(((Literal) one(without, RegistryMeta.REGISTRY, CONTENT_QUADS)).booleanValue());
    }

}
