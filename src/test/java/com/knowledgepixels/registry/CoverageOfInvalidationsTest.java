package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.extra.security.SignNanopub;
import org.nanopub.extra.security.SignatureAlgorithm;
import org.nanopub.extra.security.TransformContext;
import org.nanopub.jelly.MaybeNanopub;
import org.nanopub.vocabulary.NPX;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A registry that is restricted to some types takes the nanopubs that invalidate the ones it
 * holds, whatever their own types. Without that it would keep the nanopubs of its types and
 * refuse their retractions, and list as valid what its peers list as invalidated.
 */
@Testcontainers
class CoverageOfInvalidationsTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    private static final String COVERED_TYPE = "http://example.org/CoveredType";
    private static final String COVERED_TYPE_HASH = Utils.getHash(COVERED_TYPE);
    private static final IRI SOMEONE = iri("http://example.org/someone");

    private static KeyPair key, otherKey;

    private FakeEnv fakeEnv;
    private ClientSession session;
    private int count = 0;

    @BeforeAll
    static void makeKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", COVERED_TYPE);
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        CoverageFilter.init();
        session = RegistryDB.getClient().startSession();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        if (RegistryDB.getDB() != null) {
            RegistryDB.getDB().drop();
        }
        if (RegistryDB.getClient() != null) {
            RegistryDB.getClient().close();
        }
        fakeEnv.reset();
        // Not leaving the restriction behind for the test classes that run after this one.
        CoverageFilter.init();
    }

    // --- helpers -------------------------------------------------------------

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static String artifactCode(Nanopub nanopub) {
        return TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
    }

    private static String pubkeyHash(Nanopub nanopub) {
        return Utils.getHash(RegistryDB.getPubkey(nanopub));
    }

    private static Nanopub sign(NanopubCreator creator, KeyPair signingKey) throws Exception {
        creator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), SOMEONE);
        creator.addPubinfoStatement(iri("http://purl.org/dc/terms/creator"), SOMEONE);
        return SignNanopub.signAndTransform(creator.finalizeNanopub(), new TransformContext(SignatureAlgorithm.RSA, signingKey, SOMEONE, false, false, false));
    }

    /** A signed nanopub of the given type; each call makes a different one. */
    private Nanopub nanopubOfType(String type, KeyPair signingKey) throws Exception {
        NanopubCreator creator = new NanopubCreator("http://example.org/np" + (count++) + "/");
        creator.addAssertionStatement(iri("http://example.org/thing" + count), RDF.TYPE, iri("http://example.org/Thing"));
        creator.addPubinfoStatement(NPX.HAS_NANOPUB_TYPE, iri(type));
        return sign(creator, signingKey);
    }

    /** A signed retraction of the given nanopub. Its only type is {@code npx:retracts}. */
    private Nanopub retractionOf(Nanopub target, KeyPair signingKey) throws Exception {
        NanopubCreator creator = new NanopubCreator("http://example.org/np" + (count++) + "/");
        creator.addAssertionStatement(SOMEONE, NPX.RETRACTS, target.getUri());
        return sign(creator, signingKey);
    }

    private Document listEntry(String pubkeyHash, String typeHash, Nanopub nanopub) {
        return RegistryDB.collection("listEntries").find(session,
                new Document("pubkey", pubkeyHash).append("type", typeHash).append("np", artifactCode(nanopub))).first();
    }

    // --- tests ---------------------------------------------------------------

    @Test
    void takesTheRetractionOfANanopubItHolds() throws Exception {
        Nanopub target = nanopubOfType(COVERED_TYPE, key);
        Nanopub retraction = retractionOf(target, key);
        String pubkeyHash = pubkeyHash(target);
        assertTrue(CoverageFilter.isCovered(session, target));
        assertTrue(RegistryDB.loadNanopub(session, target, pubkeyHash, "$"));

        assertFalse(CoverageFilter.isCovered(retraction), "by its types alone, the retraction is not covered");
        assertTrue(CoverageFilter.isCovered(session, retraction));
        assertTrue(RegistryDB.loadNanopub(session, retraction, pubkeyHash, "$"));

        for (String typeHash : List.of("$", COVERED_TYPE_HASH)) {
            assertTrue(listEntry(pubkeyHash, typeHash, target).getBoolean("invalidated"));
            assertNotNull(listEntry(pubkeyHash, typeHash, retraction), "the retraction is on the lists of what it retracts");
        }
        assertNull(listEntry(pubkeyHash, Utils.getHash(NPX.RETRACTS.stringValue()), retraction), "its own type still gets no list");
    }

    @Test
    void refusesTheRetractionOfANanopubItDoesNotHold() throws Exception {
        Nanopub neverLoaded = nanopubOfType(COVERED_TYPE, key);

        assertFalse(CoverageFilter.isCovered(session, retractionOf(neverLoaded, key)));
        assertFalse(CoverageFilter.isCovered(session, nanopubOfType("http://example.org/OtherType", key)));
    }

    /** Only whoever signed a nanopub can invalidate it, so somebody else's retraction has no place here. */
    @Test
    void refusesARetractionSignedWithAnotherKey() throws Exception {
        Nanopub target = nanopubOfType(COVERED_TYPE, key);
        assertTrue(RegistryDB.loadNanopub(session, target, pubkeyHash(target), "$"));

        assertFalse(CoverageFilter.isCovered(session, retractionOf(target, otherKey)));
    }

    /** A retraction of a retraction is taken as well, as the first one is held by then. */
    @Test
    void takesTheRetractionOfARetractionItHolds() throws Exception {
        Nanopub target = nanopubOfType(COVERED_TYPE, key);
        Nanopub retraction = retractionOf(target, key);
        String pubkeyHash = pubkeyHash(target);
        assertTrue(RegistryDB.loadNanopub(session, target, pubkeyHash, "$"));
        assertTrue(RegistryDB.loadNanopub(session, retraction, pubkeyHash, "$"));

        assertTrue(CoverageFilter.isCovered(session, retractionOf(retraction, key)));
    }

    /**
     * The loaders check the coverage on their workers, which run side by side. A retraction
     * that comes right after its target in a stream is still taken: the check waits for the
     * nanopubs ahead of it in the stream.
     */
    @Test
    void aParallelLoadTakesARetractionThatFollowsItsTargetInTheStream() throws Exception {
        List<Nanopub> stream = new ArrayList<>();
        List<Nanopub> targets = new ArrayList<>(), retractions = new ArrayList<>(), uncovered = new ArrayList<>();
        Nanopub neverLoaded = nanopubOfType(COVERED_TYPE, key);
        for (int i = 0; i < 40; i++) {
            Nanopub target = nanopubOfType(COVERED_TYPE, key);
            Nanopub retraction = retractionOf(target, key);
            Nanopub other = (i % 2 == 0) ? nanopubOfType("http://example.org/OtherType", key) : retractionOf(neverLoaded, key);
            targets.add(target);
            retractions.add(retraction);
            uncovered.add(other);
            stream.addAll(List.of(target, retraction, other));
        }
        String pubkeyHash = pubkeyHash(targets.get(0));

        NanopubLoader.loadStreamInParallel(stream.stream().map(MaybeNanopub::new), nanopub -> {
            try (ClientSession workerSession = RegistryDB.getClient().startSession()) {
                if (CoverageFilter.isCovered(workerSession, nanopub)) {
                    assertTrue(RegistryDB.loadNanopub(workerSession, nanopub, pubkeyHash, "$"));
                }
            }
        });

        for (int i = 0; i < targets.size(); i++) {
            for (String typeHash : List.of("$", COVERED_TYPE_HASH)) {
                assertTrue(listEntry(pubkeyHash, typeHash, targets.get(i)).getBoolean("invalidated"), "target " + i + " is marked on list " + typeHash);
                assertNotNull(listEntry(pubkeyHash, typeHash, retractions.get(i)), "retraction " + i + " is on list " + typeHash);
            }
            assertFalse(RegistryDB.has(session, Collection.NANOPUBS.toString(), artifactCode(uncovered.get(i))), "uncovered nanopub " + i + " is not stored");
        }
        assertEquals(2L * targets.size(), RegistryDB.collection("listEntries").countDocuments(session, new Document("pubkey", pubkeyHash).append("type", "$")));
    }

}
