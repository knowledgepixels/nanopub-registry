package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Covers the {@link TrustStatePage} renderings that {@link TrustStatePageTest} leaves
 * out: the snapshot index (both formats) and the human-readable snapshot detail view.
 */
class TrustStatePageHtmlTest {

    private static final String HASH = "abcdef1234567890";

    private static Document snapshot() {
        return new Document("_id", HASH)
                .append("trustStateCounter", 42L)
                .append("createdAt", "2026-04-15T12:00:00.123Z")
                .append("accounts", List.of(
                        new Document("pubkey", "pk1234567890abc")
                                .append("agent", "https://orcid.org/0000-0000-0000-0001")
                                .append("name", "Alice")
                                .append("introNanopub", "http://example.org/RAXH93wfOaQRwDpxwr-E_s10kCQubHZ6O19h-cz3YlNGI")
                                .append("status", "loaded").append("depth", 1)
                                .append("pathCount", 2).append("ratio", 0.5).append("quota", 1000),
                        // An account with no agent yet: the "by ..." clause must be skipped.
                        new Document("pubkey", "pk2").append("status", "toLoad").append("depth", 2),
                        // Defensive: a non-Document entry must not break the rendering.
                        "unexpected-entry"));
    }

    @Test
    void listJsonServesSnapshotMetadataOnly() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.TRUST_STATE_SNAPSHOTS.toString(), List.of(
                    new Document("_id", HASH).append("trustStateCounter", 42L).append("createdAt", "2026-04-15T12:00:00Z"),
                    new Document("_id", "0987654321fedcba").append("trustStateCounter", 41L).append("createdAt", "2026-04-14T12:00:00Z")));

            PageMocks.MockContext ctx = PageMocks.context("/trust-state.json");
            TrustStatePage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("\"trustStateHash\""), "each entry carries its hash");
            assertTrue(body.contains(HASH), "the newest snapshot is listed");
            assertTrue(body.contains("\"trustStateCounter\""), "each entry carries its counter");
            // The accounts array is heavy and deliberately excluded from the index.
            assertFalse(body.contains("\"accounts\""), "the index omits the accounts array");
        }
    }

    @Test
    void listHtmlLinksEachSnapshot() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.TRUST_STATE_SNAPSHOTS.toString(), List.of(
                    new Document("_id", HASH).append("trustStateCounter", 42L).append("createdAt", "2026-04-15T12:00:00.987Z")));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/trust-state", "text/html");
            TrustStatePage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Trust State History</h1>"), "renders the history heading");
            assertTrue(body.contains("/trust-state/" + HASH), "links to each snapshot");
            assertTrue(body.contains("counter 42"), "shows the counter");
            assertFalse(body.contains(".987"), "fractional seconds are trimmed from the timestamp");
        }
    }

    @Test
    void listHtmlHandlesAnEmptyHistory() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.TRUST_STATE_SNAPSHOTS.toString(), List.of());

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/trust-state", "text/html");
            TrustStatePage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Trust State History</h1>"), "the page still renders");
            assertTrue(body.contains("<ol>\n</ol>"), "the list is simply empty");
        }
    }

    @Test
    void detailHtmlRendersMetadataAndAccounts() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered(Collection.TRUST_STATE_SNAPSHOTS.toString(), List.of(snapshot()));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/trust-state/" + HASH, "text/html");
            TrustStatePage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Trust State <code>abcdef1234</code></h1>"), "hash is truncated in the heading");
            assertTrue(body.contains("<em>trustStateCounter:</em> 42"), "counter is rendered");
            assertTrue(body.contains("<em>createdAt:</em> 2026-04-15T12:00:00"), "timestamp is trimmed");
            // Only the two Document entries count; the stray string is ignored.
            assertTrue(body.contains("<em>accountCount:</em> 3"), "the raw account list size is reported");
            assertTrue(body.contains("orcid:0000-0000-0000-0001"), "the agent is linked with a short label");
            assertTrue(body.contains("(Alice)"), "the account name is shown");
            assertTrue(body.contains("/np/RAXH93wfOaQRwDpxwr-E_s10kCQubHZ6O19h-cz3YlNGI"), "the intro nanopub is linked by artifact code");
            assertTrue(body.contains("quota: 1000"), "quota is shown when present");
            assertTrue(body.contains("depth: 2"), "the agent-less account is still listed");
        }
    }

    @Test
    void detailHtmlReturns404ForAnUnknownHash() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered(Collection.TRUST_STATE_SNAPSHOTS.toString(), List.of());

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/trust-state/deadbeef", "text/html");
            TrustStatePage.show(ctx.context);

            verify(ctx.response).setStatusCode(404);
        }
    }

    @Test
    void trailingSlashIsTreatedAsTheIndex() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.TRUST_STATE_SNAPSHOTS.toString(), List.of());

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/trust-state/", "text/html");
            TrustStatePage.show(ctx.context);

            assertTrue(ctx.body().contains("<h1>Trust State History</h1>"));
        }
    }

}
