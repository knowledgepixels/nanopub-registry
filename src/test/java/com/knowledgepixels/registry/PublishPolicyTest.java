package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for who may publish here once quotas are enforced.
 *
 * <p>
 * The cases that matter are the ones that keep the trust network enterable: an approved agent
 * publishes up to their quota, an unapproved one publishes out of a shared allowance, and an
 * introduction that an approved agent has already endorsed is taken whatever the allowance
 * says. Fixtures come from the nanopub test suite, so the introduction used here is a real
 * one, with a real key declaration and signature.
 */
@Testcontainers
class PublishPolicyTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    /**
     * An agent introduction from the test suite, declaring one RSA key.
     */
    private static final String INTRO_AC = "RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE";

    /**
     * A nanopub from the test suite that is not an agent introduction.
     */
    private static final String PLAIN_AC = "RArZHDDWzq3MYkBQ5FyWrhJJnfVYuE6Y9BmipJQVLLjNY";

    private static final String APPROVED_ENDORSER = "endorserpubkeyhash";

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        session = RegistryDB.getClient().startSession();
        AgentFilter.init();
        setEnforceQuota(true);
    }

    @AfterEach
    void tearDown() {
        setEnforceQuota(false);
        session.close();
        RegistryDB.getDB().drop();
        RegistryDB.getClient().close();
        fakeEnv.reset();
    }

    /**
     * Quota enforcement is read straight from {@code System.getenv}, so it is set by writing
     * the resolved static field.
     */
    private static void setEnforceQuota(boolean value) {
        TestUtils.clearStaticFields(AgentFilter.class, Map.of("enforceQuota", value));
    }

    private static Nanopub nanopub(String artifactCode) throws Exception {
        return new NanopubImpl(NanopubTestSuite.getLatest().getByArtifactCode(artifactCode).getFirst().toFile());
    }

    /**
     * The hash of the pubkey a nanopub is signed with, which is what the policy is asked about.
     */
    private static String pubkeyHashOf(Nanopub np) {
        return Utils.getHash(RegistryDB.getPubkey(np));
    }

    private void seedAccount(String pubkeyHash, String status, Integer quota) {
        Document account = new Document("agent", "https://example.org/agent")
                .append("pubkey", pubkeyHash).append("status", status);
        if (quota != null) {
            account.append("quota", quota);
        }
        RegistryDB.insert(session, Collection.ACCOUNTS.toString(), account);
    }

    private void seedCount(String pubkeyHash, long count) {
        RegistryDB.insert(session, "lists", new Document("pubkey", pubkeyHash)
                .append("type", "$").append("maxPosition", count - 1).append("status", "loaded"));
    }

    private void seedEndorsement(String endorsedArtifactCode, String endorserPubkey, String status) {
        RegistryDB.insert(session, "endorsements", new Document("agent", "https://example.org/endorser")
                .append("pubkey", endorserPubkey)
                .append("endorsedNanopub", endorsedArtifactCode)
                .append("source", "RAsomeEndorsementNanopub")
                .append("status", status));
    }

    private void configureAllowance(int quota) {
        fakeEnv.addVariable("REGISTRY_UNAPPROVED_PUBLISH_QUOTA", String.valueOf(quota))
                .addVariable("REGISTRY_UNAPPROVED_PUBLISH_WINDOW", "3600").build();
    }

    // --- approved pubkeys ----------------------------------------------------

    @Test
    void anApprovedPubkeyUnderItsQuotaIsAccepted() throws Exception {
        Nanopub np = nanopub(PLAIN_AC);
        String pubkeyHash = pubkeyHashOf(np);
        seedAccount(pubkeyHash, EntryStatus.loaded.getValue(), 100);
        seedCount(pubkeyHash, 5);

        assertDoesNotThrow(() -> PublishPolicy.check(session, np, pubkeyHash));
    }

    @Test
    void anApprovedPubkeyAtItsQuotaIsRefusedWithForbidden() throws Exception {
        Nanopub np = nanopub(PLAIN_AC);
        String pubkeyHash = pubkeyHashOf(np);
        seedAccount(pubkeyHash, EntryStatus.loaded.getValue(), 5);
        seedCount(pubkeyHash, 5);

        NanopubRejectedException e = assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, np, pubkeyHash));
        assertEquals(NanopubRejectedException.FORBIDDEN, e.getStatusCode(),
                "a spent lifetime quota sends the publisher to another registry, it does not ask them to wait");
        assertTrue(e.getMessage().contains("quota exceeded"), e.getMessage());
        assertEquals(0, e.getRetryAfterSeconds());
    }

    /**
     * A capped account is approved and full, which has to read as "quota exceeded" rather than
     * "not authorized" — the latter would send the publisher looking for permission they have.
     */
    @Test
    void aCappedAccountIsToldItsQuotaIsSpent() throws Exception {
        Nanopub np = nanopub(PLAIN_AC);
        String pubkeyHash = pubkeyHashOf(np);
        seedAccount(pubkeyHash, EntryStatus.capped.getValue(), 5);
        seedCount(pubkeyHash, 5);

        NanopubRejectedException e = assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, np, pubkeyHash));
        assertEquals(NanopubRejectedException.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getMessage().contains("quota exceeded"), e.getMessage());
    }

    @Test
    void withEnforcementOffAnUnknownPubkeyIsStillAccepted() throws Exception {
        setEnforceQuota(false);
        configureAllowance(0);
        Nanopub np = nanopub(PLAIN_AC);

        assertDoesNotThrow(() -> PublishPolicy.check(session, np, pubkeyHashOf(np)));
    }

    // --- the endorsed-introduction way in ------------------------------------

    @Test
    void anIntroductionEndorsedByAnApprovedAgentIsAcceptedWithNoAllowanceLeft() throws Exception {
        configureAllowance(0); // nothing to be had from the shared allowance
        Nanopub intro = nanopub(INTRO_AC);
        seedAccount(APPROVED_ENDORSER, EntryStatus.loaded.getValue(), 1000);
        seedEndorsement(INTRO_AC, APPROVED_ENDORSER, EntryStatus.retrieved.getValue());

        assertDoesNotThrow(() -> PublishPolicy.check(session, intro, pubkeyHashOf(intro)));
    }

    /**
     * The endorsement of an introduction that no registry holds yet cannot be resolved, so the
     * cycle marks it {@code discarded}. That is exactly the state this way in has to work from.
     */
    @Test
    void aDiscardedEndorsementStillOpensTheDoor() throws Exception {
        configureAllowance(0);
        Nanopub intro = nanopub(INTRO_AC);
        seedAccount(APPROVED_ENDORSER, EntryStatus.loaded.getValue(), 1000);
        seedEndorsement(INTRO_AC, APPROVED_ENDORSER, EntryStatus.discarded.getValue());

        assertDoesNotThrow(() -> PublishPolicy.check(session, intro, pubkeyHashOf(intro)));
    }

    @Test
    void anEndorsementFromTheSettingItselfOpensTheDoor() throws Exception {
        configureAllowance(0);
        Nanopub intro = nanopub(INTRO_AC);
        seedEndorsement(INTRO_AC, "$", EntryStatus.toRetrieve.getValue());

        assertDoesNotThrow(() -> PublishPolicy.check(session, intro, pubkeyHashOf(intro)));
    }

    @Test
    void anEndorsementFromAnUnapprovedAgentDoesNotOpenTheDoor() throws Exception {
        configureAllowance(0);
        Nanopub intro = nanopub(INTRO_AC);
        // The endorser has no account at all, so it is not approved here.
        seedEndorsement(INTRO_AC, "strangerpubkeyhash", EntryStatus.retrieved.getValue());

        NanopubRejectedException e = assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, intro, pubkeyHashOf(intro)));
        assertEquals(NanopubRejectedException.FORBIDDEN, e.getStatusCode());
    }

    /**
     * An endorsement names one artifact code, and may only be spent on it: a nanopublication
     * that is not that introduction gets nothing out of it.
     */
    @Test
    void anEndorsementOfAnotherNanopubDoesNotOpenTheDoor() throws Exception {
        configureAllowance(0);
        Nanopub plain = nanopub(PLAIN_AC);
        seedAccount(APPROVED_ENDORSER, EntryStatus.loaded.getValue(), 1000);
        seedEndorsement(INTRO_AC, APPROVED_ENDORSER, EntryStatus.retrieved.getValue());

        assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, plain, pubkeyHashOf(plain)));
    }

    /**
     * An endorsement is for the agent whose keys the introduction declares. Someone else
     * holding the same introduction must not be able to spend it on their own pubkey.
     */
    @Test
    void anIntroductionThatDoesNotDeclareTheSigningKeyIsNotCovered() throws Exception {
        Nanopub intro = nanopub(INTRO_AC);
        seedAccount(APPROVED_ENDORSER, EntryStatus.loaded.getValue(), 1000);
        seedEndorsement(INTRO_AC, APPROVED_ENDORSER, EntryStatus.retrieved.getValue());

        assertFalse(PublishPolicy.isEndorsedIntro(session, intro, "somebodyelsespubkeyhash"));
    }

    @Test
    void theIntroductionFixtureDeclaresTheKeyItIsSignedWith() throws Exception {
        // Guards the fixture the cases above rest on: if this ever stopped holding, the
        // endorsed-introduction way in would be silently untested.
        Nanopub intro = nanopub(INTRO_AC);
        boolean declaresOwnKey = false;
        for (KeyDeclaration kd : new IntroNanopub(intro).getKeyDeclarations()) {
            if (pubkeyHashOf(intro).equals(Utils.getHash(kd.getPublicKeyString()))) {
                declaresOwnKey = true;
            }
        }
        assertTrue(declaresOwnKey, "fixture " + INTRO_AC + " should declare its own signing key");
        assertEquals(INTRO_AC, TrustyUriUtils.getArtifactCode(intro.getUri().stringValue()));
    }

    // --- the shared allowance ------------------------------------------------

    @Test
    void anUnapprovedPubkeyPublishesOutOfTheSharedAllowance() throws Exception {
        configureAllowance(2);
        Nanopub np = nanopub(PLAIN_AC);
        String pubkeyHash = pubkeyHashOf(np);

        assertDoesNotThrow(() -> PublishPolicy.check(session, np, pubkeyHash));
        assertDoesNotThrow(() -> PublishPolicy.check(session, np, pubkeyHash));

        NanopubRejectedException e = assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, np, pubkeyHash));
        assertEquals(NanopubRejectedException.TOO_MANY_REQUESTS, e.getStatusCode(),
                "the allowance comes back, so the publisher is asked to wait rather than sent away");
        assertTrue(e.getRetryAfterSeconds() > 0, "a 429 has to carry a Retry-After");
        assertTrue(e.getMessage().contains("endorse an introduction"),
                "the refusal should name the way in that does not depend on the allowance: " + e.getMessage());
    }

    /**
     * The allowance is one budget for the registry, not one per pubkey: pubkeys are free to
     * make, so a per-pubkey allowance would bound nothing.
     */
    @Test
    void theAllowanceIsSharedBetweenPubkeys() throws Exception {
        configureAllowance(1);
        Nanopub np = nanopub(PLAIN_AC);

        assertDoesNotThrow(() -> PublishPolicy.check(session, np, "firstunapprovedpubkey"));

        NanopubRejectedException e = assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, np, "secondunapprovedpubkey"));
        assertEquals(NanopubRejectedException.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    /**
     * Being over the shared allowance must not stop an introduction that is already endorsed:
     * that is the way out of a storm.
     */
    @Test
    void theEndorsedWayInSurvivesASpentAllowance() throws Exception {
        configureAllowance(1);
        Nanopub plain = nanopub(PLAIN_AC);
        assertDoesNotThrow(() -> PublishPolicy.check(session, plain, "someunapprovedpubkey"));
        assertThrows(NanopubRejectedException.class,
                () -> PublishPolicy.check(session, plain, "someunapprovedpubkey"));

        Nanopub intro = nanopub(INTRO_AC);
        seedAccount(APPROVED_ENDORSER, EntryStatus.loaded.getValue(), 1000);
        seedEndorsement(INTRO_AC, APPROVED_ENDORSER, EntryStatus.retrieved.getValue());

        assertDoesNotThrow(() -> PublishPolicy.check(session, intro, pubkeyHashOf(intro)));
    }

    @Test
    void anEndorsedIntroductionDoesNotSpendTheAllowance() throws Exception {
        configureAllowance(1);
        Nanopub intro = nanopub(INTRO_AC);
        seedAccount(APPROVED_ENDORSER, EntryStatus.loaded.getValue(), 1000);
        seedEndorsement(INTRO_AC, APPROVED_ENDORSER, EntryStatus.retrieved.getValue());

        PublishPolicy.check(session, intro, pubkeyHashOf(intro));

        assertEquals(0, UnapprovedPublishBudget.used(session),
                "the endorsed way in is free, so the allowance is untouched");
    }

}
