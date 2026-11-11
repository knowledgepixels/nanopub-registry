package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
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
import org.nanopub.vocabulary.NPX;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quota grants (issue #220): nanopublications by which a configured authority sets the
 * quota of a pubkey. {@link QuotaGrant#extract} reads the grants out of one nanopub;
 * {@link QuotaGrant#readGranted} folds the authorities' lists into the quota that currently
 * holds per pubkey.
 */
@Testcontainers
class QuotaGrantTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    private static final IRI SOMEONE = iri("http://example.org/someone");
    private static final String TARGET = "403d674304853131343af2fd56c48a808c8754d0a7aa32ffb35aa0960cdbd97b";
    private static final String OTHER_TARGET = "5cbb6723f232f7f716bc687ec2f4e4247650cae2a23e804504c235f590a21db1";

    private static KeyPair authorityKey, otherKey;
    private static String authority, other;

    private FakeEnv fakeEnv;
    private ClientSession session;
    private int count = 0;

    @BeforeAll
    static void makeKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        authorityKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        CoverageFilter.init();
        session = RegistryDB.getClient().startSession();
        // The pubkey hashes come from the keys, so they are only known once a nanopub is signed.
        authority = pubkeyHash(grant(TARGET, 1, authorityKey, new Date()));
        other = pubkeyHash(grant(TARGET, 1, otherKey, new Date()));
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
        AgentFilter.init();
    }

    // --- helpers -------------------------------------------------------------

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static String pubkeyHash(Nanopub nanopub) {
        return Utils.getHash(RegistryDB.getPubkey(nanopub));
    }

    private static Nanopub sign(NanopubCreator creator, KeyPair signingKey) throws Exception {
        creator.addProvenanceStatement(iri("http://www.w3.org/ns/prov#wasAttributedTo"), SOMEONE);
        creator.addPubinfoStatement(iri("http://purl.org/dc/terms/creator"), SOMEONE);
        return SignNanopub.signAndTransform(creator.finalizeNanopub(), new TransformContext(SignatureAlgorithm.RSA, signingKey, SOMEONE, false, false, false));
    }

    private NanopubCreator grantCreator(Date created) throws Exception {
        NanopubCreator creator = new NanopubCreator("http://example.org/grant" + (count++) + "/");
        creator.addPubinfoStatement(NPX.HAS_NANOPUB_TYPE, QuotaGrant.QUOTA_GRANT);
        creator.addTimestamp(created);
        return creator;
    }

    /** A signed grant of the given quota for the given pubkey hash. */
    private Nanopub grant(String pubkeyHash, int quota, KeyPair signingKey, Date created) throws Exception {
        NanopubCreator creator = grantCreator(created);
        IRI subject = iri(creator.getNanopubUri() + "grant");
        creator.addAssertionStatement(subject, RDF.TYPE, QuotaGrant.QUOTA_GRANT);
        creator.addAssertionStatement(subject, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(pubkeyHash));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(quota));
        return sign(creator, signingKey);
    }

    private void authorities(String... pubkeyHashes) {
        fakeEnv.addVariable("REGISTRY_QUOTA_AUTHORITIES", String.join(" ", pubkeyHashes)).build();
        AgentFilter.init();
    }

    /** Stores a nanopub the way the loaders do: on the "$" list and the type lists of its pubkey. */
    private void store(Nanopub nanopub) {
        assertTrue(RegistryDB.loadNanopub(session, nanopub, pubkeyHash(nanopub), "$"));
    }

    private Nanopub retractionOf(Nanopub target, KeyPair signingKey) throws Exception {
        NanopubCreator creator = new NanopubCreator("http://example.org/retraction" + (count++) + "/");
        creator.addAssertionStatement(SOMEONE, NPX.RETRACTS, target.getUri());
        return sign(creator, signingKey);
    }

    private static Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - days * 86_400_000L);
    }

    // --- extract -------------------------------------------------------------

    @Test
    void extractReadsOneGrantPerSubject() throws Exception {
        NanopubCreator creator = grantCreator(new Date());
        IRI first = iri(creator.getNanopubUri() + "first");
        IRI second = iri(creator.getNanopubUri() + "second");
        creator.addAssertionStatement(first, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(first, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(20000));
        creator.addAssertionStatement(second, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(OTHER_TARGET));
        creator.addAssertionStatement(second, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral("7000"));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(2, grants.size());
        assertEquals(TARGET, grants.get(0).getPubkeyHash());
        assertEquals(20000, grants.get(0).getQuota());
        assertEquals(OTHER_TARGET, grants.get(1).getPubkeyHash());
        assertEquals(7000, grants.get(1).getQuota(), "a quota given as a plain string literal is read too");
    }

    @Test
    void extractAcceptsTheFullPublicKeyInPlaceOfItsHash() throws Exception {
        String publicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAexample";
        NanopubCreator creator = grantCreator(new Date());
        IRI subject = iri(creator.getNanopubUri() + "grant");
        creator.addAssertionStatement(subject, NPX.HAS_PUBLIC_KEY, SimpleValueFactory.getInstance().createLiteral(publicKey));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(500));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(1, grants.size());
        assertEquals(Utils.getHash(publicKey), grants.get(0).getPubkeyHash(), "the key is hashed the way lists and accounts are keyed");
    }

    @Test
    void extractLeavesOutIncompleteAndUnusableGrants() throws Exception {
        NanopubCreator creator = grantCreator(new Date());
        IRI noQuota = iri(creator.getNanopubUri() + "noQuota");
        IRI noPubkey = iri(creator.getNanopubUri() + "noPubkey");
        IRI negative = iri(creator.getNanopubUri() + "negative");
        IRI notANumber = iri(creator.getNanopubUri() + "notANumber");
        IRI good = iri(creator.getNanopubUri() + "good");
        creator.addAssertionStatement(noQuota, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(noPubkey, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(100));
        creator.addAssertionStatement(negative, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(negative, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(-1));
        creator.addAssertionStatement(notANumber, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(notANumber, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral("plenty"));
        creator.addAssertionStatement(good, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(OTHER_TARGET));
        creator.addAssertionStatement(good, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(0));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(1, grants.size());
        assertEquals(OTHER_TARGET, grants.get(0).getPubkeyHash());
        assertEquals(0, grants.get(0).getQuota(), "zero is a valid quota: it shuts a pubkey off");
    }

    /**
     * Statements have no order that every registry agrees on, so whatever would need one to
     * be resolved is not a grant.
     */
    @Test
    void extractLeavesOutWhatOnlyStatementOrderCouldResolve() throws Exception {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        String third = "a".repeat(64);
        NanopubCreator creator = grantCreator(new Date());
        IRI twoQuotas = iri(creator.getNanopubUri() + "twoQuotas");
        IRI one = iri(creator.getNanopubUri() + "one");
        IRI other = iri(creator.getNanopubUri() + "other");
        IRI same1 = iri(creator.getNanopubUri() + "same1");
        IRI same2 = iri(creator.getNanopubUri() + "same2");
        creator.addAssertionStatement(twoQuotas, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral("b".repeat(64)));
        creator.addAssertionStatement(twoQuotas, QuotaGrant.HAS_QUOTA, vf.createLiteral(100));
        creator.addAssertionStatement(twoQuotas, QuotaGrant.HAS_QUOTA, vf.createLiteral(200));
        creator.addAssertionStatement(one, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(TARGET));
        creator.addAssertionStatement(one, QuotaGrant.HAS_QUOTA, vf.createLiteral(100));
        creator.addAssertionStatement(other, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(TARGET));
        creator.addAssertionStatement(other, QuotaGrant.HAS_QUOTA, vf.createLiteral(200));
        creator.addAssertionStatement(same1, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(third));
        creator.addAssertionStatement(same1, QuotaGrant.HAS_QUOTA, vf.createLiteral(300));
        creator.addAssertionStatement(same2, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(third));
        creator.addAssertionStatement(same2, QuotaGrant.HAS_QUOTA, vf.createLiteral(300));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(1, grants.size(), "two quotas on one subject, and two subjects disagreeing on a pubkey, are dropped");
        assertEquals(third, grants.get(0).getPubkeyHash(), "saying the same thing twice is no conflict");
        assertEquals(300, grants.get(0).getQuota());
    }

    @Test
    void extractLeavesOutAGrantForSomethingThatIsNotAPubkeyHash() throws Exception {
        NanopubCreator creator = grantCreator(new Date());
        IRI subject = iri(creator.getNanopubUri() + "grant");
        // Uppercase hex is not how lists and accounts are keyed, so it would match nothing.
        creator.addAssertionStatement(subject, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET.toUpperCase()));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(100));

        assertTrue(QuotaGrant.extract(sign(creator, authorityKey)).isEmpty());
    }

    // --- readGranted ---------------------------------------------------------

    @Test
    void readGrantedIsEmptyWithoutAuthorities() throws Exception {
        store(grant(TARGET, 20000, authorityKey, new Date()));

        assertTrue(QuotaGrant.readGranted(session).isEmpty(), "with nobody configured, no grant counts");
    }

    @Test
    void readGrantedTakesTheGrantsOfAnAuthority() throws Exception {
        authorities(authority);
        store(grant(TARGET, 20000, authorityKey, new Date()));
        store(grant(OTHER_TARGET, 7000, authorityKey, new Date()));

        assertEquals(Map.of(TARGET, 20000, OTHER_TARGET, 7000), QuotaGrant.readGranted(session));
    }

    @Test
    void readGrantedIgnoresGrantsSignedByOthers() throws Exception {
        authorities(authority);
        store(grant(TARGET, 20000, otherKey, new Date()));

        assertTrue(QuotaGrant.readGranted(session).isEmpty(), "a grant is only as good as the key that signed it");
    }

    @Test
    void readGrantedLetsTheLatestGrantWin() throws Exception {
        authorities(authority);
        // Stored in the opposite order of their creation: the arrival order on a registry
        // is no basis for a decision that has to come out the same on every registry.
        store(grant(TARGET, 30000, authorityKey, daysAgo(1)));
        store(grant(TARGET, 10000, authorityKey, daysAgo(3)));
        store(grant(TARGET, 20000, authorityKey, daysAgo(2)));

        assertEquals(Map.of(TARGET, 30000), QuotaGrant.readGranted(session));
    }

    @Test
    void readGrantedIgnoresAnInvalidatedGrant() throws Exception {
        authorities(authority);
        Nanopub withdrawn = grant(TARGET, 30000, authorityKey, daysAgo(1));
        store(grant(TARGET, 10000, authorityKey, daysAgo(3)));
        store(withdrawn);
        store(retractionOf(withdrawn, authorityKey));

        assertEquals(Map.of(TARGET, 10000), QuotaGrant.readGranted(session),
                "a retracted grant drops out and the one before it holds again");
    }

    @Test
    void readGrantedFoldsTheGrantsOfSeveralAuthorities() throws Exception {
        authorities(authority, other);
        store(grant(TARGET, 20000, authorityKey, daysAgo(2)));
        store(grant(TARGET, 25000, otherKey, daysAgo(1)));
        store(grant(OTHER_TARGET, 7000, otherKey, daysAgo(1)));

        assertEquals(Map.of(TARGET, 25000, OTHER_TARGET, 7000), QuotaGrant.readGranted(session),
                "between authorities, too, the most recent grant decides");
    }

}
