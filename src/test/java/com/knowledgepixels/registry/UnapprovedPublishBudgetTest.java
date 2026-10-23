package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared allowance that lets not-yet-approved pubkeys publish in small
 * numbers. The counter is kept in the database and rolled over by an aggregation-pipeline
 * update, so these run against a real MongoDB rather than a mock.
 */
@Testcontainers
class UnapprovedPublishBudgetTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");
        RegistryDB.init();
        session = RegistryDB.getClient().startSession();
    }

    @AfterEach
    void tearDown() {
        session.close();
        RegistryDB.getDB().drop();
        RegistryDB.getClient().close();
        fakeEnv.reset();
    }

    private void configure(int quota, long windowSeconds) {
        fakeEnv.addVariable("REGISTRY_UNAPPROVED_PUBLISH_QUOTA", String.valueOf(quota))
                .addVariable("REGISTRY_UNAPPROVED_PUBLISH_WINDOW", String.valueOf(windowSeconds))
                .build();
    }

    private Document budgetDoc() {
        return RegistryDB.collection(UnapprovedPublishBudget.COLLECTION)
                .find(session, new Document("_id", UnapprovedPublishBudget.DOC_ID)).first();
    }

    @Test
    void grantsUpToTheQuotaAndThenRefuses() {
        configure(3, 3600);

        for (int i = 1; i <= 3; i++) {
            UnapprovedPublishBudget.Decision d = UnapprovedPublishBudget.tryConsume(session);
            assertTrue(d.granted(), "publication " + i + " is within the allowance of 3");
            assertEquals(i, UnapprovedPublishBudget.used(session));
        }

        UnapprovedPublishBudget.Decision refused = UnapprovedPublishBudget.tryConsume(session);
        assertFalse(refused.granted(), "the fourth publication is over the allowance of 3");
        assertTrue(refused.retryAfterSeconds() > 0, "a refusal has to say when to come back");
        assertTrue(refused.retryAfterSeconds() <= 3600, "and not beyond the window: " + refused.retryAfterSeconds());
    }

    /**
     * A refusal must not count, or a flood of requests that are already being turned away
     * would keep pushing the window's tally up and hold the allowance shut for everyone.
     */
    @Test
    void refusalsDoNotCountTowardsTheWindow() {
        configure(1, 3600);

        assertTrue(UnapprovedPublishBudget.tryConsume(session).granted());
        for (int i = 0; i < 5; i++) {
            assertFalse(UnapprovedPublishBudget.tryConsume(session).granted());
        }

        assertEquals(1, UnapprovedPublishBudget.used(session),
                "only the one accepted publication is counted");
    }

    @Test
    void startsAFreshWindowOnceTheOldOneHasRunOut() {
        configure(2, 60);

        assertTrue(UnapprovedPublishBudget.tryConsume(session).granted());
        assertTrue(UnapprovedPublishBudget.tryConsume(session).granted());
        assertFalse(UnapprovedPublishBudget.tryConsume(session).granted());

        // Age the window out, rather than waiting for it.
        RegistryDB.collection(UnapprovedPublishBudget.COLLECTION).updateOne(session,
                new Document("_id", UnapprovedPublishBudget.DOC_ID),
                new Document("$set", new Document("windowStart", new Date(System.currentTimeMillis() - 61_000))));

        assertEquals(0, UnapprovedPublishBudget.used(session), "an expired window reads as unused");
        assertTrue(UnapprovedPublishBudget.tryConsume(session).granted(),
                "the first publication after the window has run out starts a new one");
        assertEquals(1, UnapprovedPublishBudget.used(session), "and the new window starts its count at 1");
    }

    @Test
    void retryAfterShrinksAsTheWindowRunsDown() {
        configure(1, 600);
        assertTrue(UnapprovedPublishBudget.tryConsume(session).granted());

        // Half a window has passed since the count started.
        RegistryDB.collection(UnapprovedPublishBudget.COLLECTION).updateOne(session,
                new Document("_id", UnapprovedPublishBudget.DOC_ID),
                new Document("$set", new Document("windowStart", new Date(System.currentTimeMillis() - 300_000))));

        long retryAfter = UnapprovedPublishBudget.tryConsume(session).retryAfterSeconds();
        assertTrue(retryAfter > 290 && retryAfter <= 300,
                "Retry-After should be what is left of the window, not the whole of it: " + retryAfter);
    }

    @Test
    void firstPublicationCreatesTheCounter() {
        configure(10, 3600);
        assertEquals(0, UnapprovedPublishBudget.used(session));

        assertTrue(UnapprovedPublishBudget.tryConsume(session).granted());

        Document d = budgetDoc();
        assertEquals(1, d.getInteger("count"));
        assertTrue(d.getDate("windowStart") != null, "the window has to be dated for roll-over to work");
    }

    /**
     * An allowance of zero switches unapproved publishing off rather than rationing it, so no
     * window opens and no retry time is offered — which is how the caller knows to refuse
     * outright instead of telling the publisher to come back later.
     */
    @Test
    void aQuotaOfZeroRefusesEverythingWithoutOfferingARetry() {
        configure(0, 3600);

        UnapprovedPublishBudget.Decision d = UnapprovedPublishBudget.tryConsume(session);
        assertFalse(d.granted(), "nothing is allowed through when the allowance is zero");
        assertEquals(0, d.retryAfterSeconds(), "waiting does not help, so no Retry-After");
        assertEquals(0, UnapprovedPublishBudget.used(session));
        assertNull(budgetDoc(), "a disabled allowance does not even start counting");
    }

}
