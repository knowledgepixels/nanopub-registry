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
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.SimpleTimestampPattern;
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
import static org.mockito.Mockito.mockStatic;

/**
 * Quota grants (issue #220): nanopublications by which a configured authority sets the
 * quota of an account, named by agent and pubkey. {@link QuotaGrant#extract} reads the
 * grants out of one nanopub; {@link QuotaGrant#readGranted} folds the authorities' lists
 * into the quota that currently holds per account.
 */
@Testcontainers
class QuotaGrantTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    private static final IRI SOMEONE = iri("http://example.org/someone");
    private static final IRI AGENT = iri("https://orcid.org/0000-0000-0000-0001");
    private static final IRI OTHER_AGENT = iri("https://orcid.org/0000-0000-0000-0002");
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

    /** A signed grant of the given quota for the given pubkey hash of {@link #AGENT}. */
    private Nanopub grant(String pubkeyHash, int quota, KeyPair signingKey, Date created) throws Exception {
        return grant(AGENT, pubkeyHash, quota, signingKey, created);
    }

    /** A signed grant of the given quota for the given agent and pubkey hash. */
    private Nanopub grant(IRI agent, String pubkeyHash, int quota, KeyPair signingKey, Date created) throws Exception {
        NanopubCreator creator = grantCreator(created);
        IRI subject = iri(creator.getNanopubUri() + "grant");
        creator.addAssertionStatement(subject, RDF.TYPE, QuotaGrant.QUOTA_GRANT);
        creator.addAssertionStatement(subject, QuotaGrant.FOR_AGENT, agent);
        creator.addAssertionStatement(subject, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(pubkeyHash));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(quota));
        return sign(creator, signingKey);
    }

    /** The account of {@link #AGENT} with the given pubkey hash. */
    private static QuotaGrant.Target target(String pubkeyHash) {
        return new QuotaGrant.Target(AGENT.stringValue(), pubkeyHash);
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
        creator.addAssertionStatement(first, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(first, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(first, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(20000));
        creator.addAssertionStatement(second, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(second, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(OTHER_TARGET));
        creator.addAssertionStatement(second, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral("7000"));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(2, grants.size());
        assertEquals(AGENT.stringValue(), grants.get(0).getAgent());
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
        creator.addAssertionStatement(subject, QuotaGrant.FOR_AGENT, AGENT);
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
        IRI noAgent = iri(creator.getNanopubUri() + "noAgent");
        IRI agentAsText = iri(creator.getNanopubUri() + "agentAsText");
        IRI negative = iri(creator.getNanopubUri() + "negative");
        IRI notANumber = iri(creator.getNanopubUri() + "notANumber");
        IRI good = iri(creator.getNanopubUri() + "good");
        creator.addAssertionStatement(noQuota, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(noQuota, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(noPubkey, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(noPubkey, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(100));
        // A grant says whose account it is for, and says so with an IRI.
        creator.addAssertionStatement(noAgent, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral("c".repeat(64)));
        creator.addAssertionStatement(noAgent, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(100));
        creator.addAssertionStatement(agentAsText, QuotaGrant.FOR_AGENT, SimpleValueFactory.getInstance().createLiteral(AGENT.stringValue()));
        creator.addAssertionStatement(agentAsText, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral("d".repeat(64)));
        creator.addAssertionStatement(agentAsText, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(100));
        creator.addAssertionStatement(negative, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(negative, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(negative, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(-1));
        creator.addAssertionStatement(notANumber, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(notANumber, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET));
        creator.addAssertionStatement(notANumber, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral("plenty"));
        creator.addAssertionStatement(good, QuotaGrant.FOR_AGENT, AGENT);
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
        IRI twoAgents = iri(creator.getNanopubUri() + "twoAgents");
        IRI one = iri(creator.getNanopubUri() + "one");
        IRI other = iri(creator.getNanopubUri() + "other");
        IRI same1 = iri(creator.getNanopubUri() + "same1");
        IRI same2 = iri(creator.getNanopubUri() + "same2");
        creator.addAssertionStatement(twoQuotas, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(twoQuotas, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral("b".repeat(64)));
        creator.addAssertionStatement(twoQuotas, QuotaGrant.HAS_QUOTA, vf.createLiteral(100));
        creator.addAssertionStatement(twoQuotas, QuotaGrant.HAS_QUOTA, vf.createLiteral(200));
        creator.addAssertionStatement(twoAgents, QuotaGrant.FOR_AGENT, OTHER_AGENT);
        creator.addAssertionStatement(twoAgents, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(twoAgents, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral("e".repeat(64)));
        creator.addAssertionStatement(twoAgents, QuotaGrant.HAS_QUOTA, vf.createLiteral(400));
        creator.addAssertionStatement(one, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(one, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(TARGET));
        creator.addAssertionStatement(one, QuotaGrant.HAS_QUOTA, vf.createLiteral(100));
        creator.addAssertionStatement(other, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(other, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(TARGET));
        creator.addAssertionStatement(other, QuotaGrant.HAS_QUOTA, vf.createLiteral(200));
        creator.addAssertionStatement(same1, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(same1, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(third));
        creator.addAssertionStatement(same1, QuotaGrant.HAS_QUOTA, vf.createLiteral(300));
        creator.addAssertionStatement(same2, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(same2, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(third));
        creator.addAssertionStatement(same2, QuotaGrant.HAS_QUOTA, vf.createLiteral(300));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(1, grants.size(), "two quotas or agents on one subject, and two subjects disagreeing on an account, are dropped");
        assertEquals(third, grants.get(0).getPubkeyHash(), "saying the same thing twice is no conflict");
        assertEquals(300, grants.get(0).getQuota());
    }

    @Test
    void extractLeavesOutAGrantForSomethingThatIsNotAPubkeyHash() throws Exception {
        NanopubCreator creator = grantCreator(new Date());
        IRI subject = iri(creator.getNanopubUri() + "grant");
        // Uppercase hex is not how lists and accounts are keyed, so it would match nothing.
        creator.addAssertionStatement(subject, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(subject, QuotaGrant.HAS_PUBLIC_KEY_HASH, SimpleValueFactory.getInstance().createLiteral(TARGET.toUpperCase()));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_QUOTA, SimpleValueFactory.getInstance().createLiteral(100));

        assertTrue(QuotaGrant.extract(sign(creator, authorityKey)).isEmpty());
    }

    /**
     * The same key under two agents is two accounts, and each grant stays with its own.
     */
    @Test
    void extractKeepsTheGrantsForTwoAgentsOfOneKeyApart() throws Exception {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        NanopubCreator creator = grantCreator(new Date());
        IRI first = iri(creator.getNanopubUri() + "first");
        IRI second = iri(creator.getNanopubUri() + "second");
        creator.addAssertionStatement(first, QuotaGrant.FOR_AGENT, AGENT);
        creator.addAssertionStatement(first, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(TARGET));
        creator.addAssertionStatement(first, QuotaGrant.HAS_QUOTA, vf.createLiteral(100));
        creator.addAssertionStatement(second, QuotaGrant.FOR_AGENT, OTHER_AGENT);
        creator.addAssertionStatement(second, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(TARGET));
        creator.addAssertionStatement(second, QuotaGrant.HAS_QUOTA, vf.createLiteral(200));

        List<QuotaGrant> grants = QuotaGrant.extract(sign(creator, authorityKey));

        assertEquals(2, grants.size(), "different quotas for different accounts are no conflict");
        assertEquals(new QuotaGrant.Target(AGENT.stringValue(), TARGET), grants.get(0).getTarget());
        assertEquals(100, grants.get(0).getQuota());
        assertEquals(new QuotaGrant.Target(OTHER_AGENT.stringValue(), TARGET), grants.get(1).getTarget());
        assertEquals(200, grants.get(1).getQuota());
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

        assertEquals(Map.of(target(TARGET), 20000, target(OTHER_TARGET), 7000), QuotaGrant.readGranted(session));
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

        assertEquals(Map.of(target(TARGET), 30000), QuotaGrant.readGranted(session));
    }

    @Test
    void readGrantedIgnoresAnInvalidatedGrant() throws Exception {
        authorities(authority);
        Nanopub withdrawn = grant(TARGET, 30000, authorityKey, daysAgo(1));
        store(grant(TARGET, 10000, authorityKey, daysAgo(3)));
        store(withdrawn);
        store(retractionOf(withdrawn, authorityKey));

        assertEquals(Map.of(target(TARGET), 10000), QuotaGrant.readGranted(session),
                "a retracted grant drops out and the one before it holds again");
    }

    @Test
    void readGrantedFoldsTheGrantsOfSeveralAuthorities() throws Exception {
        authorities(authority, other);
        store(grant(TARGET, 20000, authorityKey, daysAgo(2)));
        store(grant(TARGET, 25000, otherKey, daysAgo(1)));
        store(grant(OTHER_TARGET, 7000, otherKey, daysAgo(1)));

        assertEquals(Map.of(target(TARGET), 25000, target(OTHER_TARGET), 7000), QuotaGrant.readGranted(session),
                "between authorities, too, the most recent grant decides");
    }

    /**
     * The registry stores a nanopublication whose timestamp does not parse, and reading that
     * timestamp throws. Such a grant must not fail the trust cycle task that reads the
     * grants, which would be retried without end: it ranks as undated instead. The Java
     * library refuses to sign such a nanopublication, though other clients need not, so the
     * unreadable timestamp is simulated here.
     */
    @Test
    void readGrantedSurvivesAGrantWithAMalformedTimestamp() throws Exception {
        authorities(authority);
        Nanopub unreadable = grant(TARGET, 99999, authorityKey, new Date());
        store(unreadable);
        store(grant(OTHER_TARGET, 7000, authorityKey, daysAgo(1)));

        try (MockedStatic<SimpleTimestampPattern> mocked = mockStatic(SimpleTimestampPattern.class, invocation -> {
            if (invocation.getMethod().getName().equals("getCreationTime")
                    && ((Nanopub) invocation.getArgument(0)).getUri().equals(unreadable.getUri())) {
                throw new IllegalArgumentException("malformed timestamp");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(Map.of(target(TARGET), 99999, target(OTHER_TARGET), 7000), QuotaGrant.readGranted(session),
                    "it still counts where nothing else is said about its account");

            store(grant(TARGET, 10000, authorityKey, daysAgo(400)));

            assertEquals(Map.of(target(TARGET), 10000, target(OTHER_TARGET), 7000), QuotaGrant.readGranted(session),
                    "and yields to any dated grant for the same account");
        }
    }

    @Test
    void readGrantedRanksTheGrantsOfEachAccountOnTheirOwn() throws Exception {
        authorities(authority);
        store(grant(AGENT, TARGET, 10000, authorityKey, daysAgo(2)));
        store(grant(OTHER_AGENT, TARGET, 500, authorityKey, daysAgo(1)));

        assertEquals(Map.of(target(TARGET), 10000, new QuotaGrant.Target(OTHER_AGENT.stringValue(), TARGET), 500),
                QuotaGrant.readGranted(session),
                "a later grant for another agent's account of the same key replaces nothing");
    }

}
