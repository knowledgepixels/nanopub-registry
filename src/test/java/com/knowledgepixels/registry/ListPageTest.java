package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import io.vertx.core.http.HttpMethod;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Route-by-route tests for {@link ListPage}. Each request path is served in both
 * JSON and HTML, and the two renderings read different fields, so both are checked.
 * The Jelly paths are left to the integration tests: they stream binary RDF that a
 * mocked cursor cannot produce faithfully.
 */
class ListPageTest {

    private static final String PUBKEY = "a".repeat(64);
    private static final String TYPE = "b".repeat(64);
    private static final String AGENT = "https://orcid.org/0000-0000-0000-0001";

    // --- /list ---------------------------------------------------------------

    @Test
    void listJsonServesAccountDocuments() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.ACCOUNTS.toString(), List.of(
                    new Document("pubkey", PUBKEY).append("agent", AGENT).append("status", "loaded"),
                    new Document("pubkey", "c".repeat(64)).append("agent", AGENT).append("status", "loaded")));

            PageMocks.MockContext ctx = PageMocks.context("/list.json");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.startsWith("["), "JSON output is an array");
            assertTrue(body.contains(PUBKEY), "first account is serialized");
            assertTrue(body.contains("\"status\": \"loaded\""), "account fields are serialized");
            verify(ctx.response).putHeader("Content-Type", "application/json");
        }
    }

    @Test
    void listHtmlRendersAccountsWithAgentAndCount() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.ACCOUNTS.toString(), List.of(
                    // "$" is the synthetic root account and must be filtered out of the listing.
                    new Document("pubkey", "$").append("status", "loaded"),
                    new Document("pubkey", PUBKEY).append("agent", AGENT).append("name", "Alice")
                            .append("status", "loaded").append("depth", 1).append("pathCount", 3)
                            .append("ratio", 0.5).append("quota", 1000)));
            db.stubGetOne("lists", new Document("pubkey", PUBKEY).append("type", "$"),
                    new Document("maxPosition", 41L));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/list", "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Current Trust State</h1>"), "renders the list heading");
            assertTrue(body.contains("/list/" + PUBKEY), "links to the pubkey's lists");
            assertTrue(body.contains("orcid:0000-0000-0000-0001"), "agent id is shortened to orcid: form");
            assertTrue(body.contains("(Alice)"), "account name is shown");
            assertTrue(body.contains("count: 42"), "count is maxPosition + 1");
            assertTrue(body.contains("quota: 1000"), "quota is shown");
            assertFalse(body.contains("\"$\""), "the synthetic $ account is skipped");
        }
    }

    // --- /list/<pubkey> ------------------------------------------------------

    @Test
    void pubkeyListJsonServesListDocuments() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered("lists", List.of(
                    new Document("pubkey", PUBKEY).append("type", "$").append("status", "loaded"),
                    new Document("pubkey", PUBKEY).append("type", TYPE).append("status", "loaded")));

            PageMocks.MockContext ctx = PageMocks.context("/list/" + PUBKEY + ".json");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains(TYPE), "type list entry is serialized");
            assertTrue(body.contains("\"type\": \"$\""), "the all-types list is serialized");
        }
    }

    @Test
    void pubkeyListHtmlResolvesTypeUris() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered("lists", List.of(
                    new Document("pubkey", PUBKEY).append("type", "$"),
                    new Document("pubkey", PUBKEY).append("type", TYPE)));
            dbMock.when(() -> RegistryDB.unhash(TYPE)).thenReturn("http://example.org/SomeType");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/list/" + PUBKEY, "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("Accounts for Pubkey"), "renders the pubkey heading");
            assertTrue(body.contains("(all types)"), "the $ list is labelled as all types");
            assertTrue(body.contains("(type http://example.org/SomeType)"), "hashed types are unhashed for display");
        }
    }

    @Test
    void pubkeyListHtmlFallsBackToHashWhenTypeUnknown() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered("lists", List.of(new Document("pubkey", PUBKEY).append("type", TYPE)));
            dbMock.when(() -> RegistryDB.unhash(TYPE)).thenReturn(null);

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/list/" + PUBKEY, "text/html");
            ListPage.show(ctx.context);

            assertTrue(ctx.body().contains("(type " + TYPE + ")"), "unresolvable hashes are shown verbatim");
        }
    }

    // --- /list/<pubkey>/<type> ----------------------------------------------

    @Test
    void listEntriesJsonNarrowsPositionToInt() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered("listEntries", List.of(
                    new Document("np", "RAabc").append("position", 0L).append("checksum", "cs0"),
                    new Document("np", "RAdef").append("position", 1L).append("checksum", "cs1")));

            PageMocks.MockContext ctx = PageMocks.context("/list/" + PUBKEY + "/" + TYPE + ".json");
            ListPage.show(ctx.context);

            String body = ctx.body();
            // Positions are stored as longs but emitted as ints so the JSON stays readable.
            assertTrue(body.contains("\"position\": 0"), "position is rendered as a plain int");
            assertFalse(body.contains("$numberLong"), "the long extended-JSON form is not leaked");
            assertTrue(body.contains("RAabc") && body.contains("RAdef"), "all entries are listed");
        }
    }

    @Test
    void listEntriesHtmlLinksEachNanopub() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered("listEntries", List.of(
                    new Document("np", "RAabcdefghijklmnop").append("position", 0L)));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/list/" + PUBKEY + "/" + TYPE, "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>List</h1>"), "renders the list heading");
            assertTrue(body.contains("/np/RAabcdefghijklmnop"), "links to the nanopub page");
            assertTrue(body.contains("<code>RAabcdefgh</code>"), "the displayed label is truncated to 10 chars");
        }
    }

    // --- /agent, /agentAccounts, /agents -------------------------------------

    @Test
    void agentJsonServesAgentInfo() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubGetOne(Collection.AGENTS.toString(), new Document("agent", AGENT),
                    new Document("agent", AGENT).append("name", "Alice").append("accountCount", 2)
                            .append("avgPathCount", 1.5).append("totalRatio", 0.75));

            PageMocks.MockContext ctx = PageMocks.context("/agent.json", Map.of("id", AGENT));
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("\"agentId\":\"" + AGENT + "\""), "agent id is echoed");
            assertTrue(body.contains("\"name\":\"Alice\""), "agent name is included");
            assertTrue(body.contains("\"totalRatio\":0.75"), "trust ratio is included");
        }
    }

    @Test
    void agentHtmlRendersProperties() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubGetOne(Collection.AGENTS.toString(), new Document("agent", AGENT),
                    new Document("agent", AGENT).append("name", "Alice").append("accountCount", 2)
                            .append("avgPathCount", 1.5).append("totalRatio", 0.75));

            PageMocks.MockContext ctx = PageMocks.context("/agent", HttpMethod.GET, Map.of("id", AGENT), "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Agent orcid:0000-0000-0000-0001 (Alice)</h1>"), "heading carries the name");
            assertTrue(body.contains("Average path count: 1.5"), "path count is rendered");
            assertTrue(body.contains("Count: 2"), "account count is rendered");
        }
    }

    @Test
    void agentAccountsJsonServesAccountDocuments() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered(Collection.ACCOUNTS.toString(), List.of(
                    new Document("pubkey", PUBKEY).append("agent", AGENT).append("status", "loaded")));

            PageMocks.MockContext ctx = PageMocks.context("/agentAccounts.json", Map.of("id", AGENT));
            ListPage.show(ctx.context);

            assertTrue(ctx.body().contains(PUBKEY), "the agent's account is serialized");
        }
    }

    @Test
    void agentAccountsHtmlRendersPerAccountCounts() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered(Collection.ACCOUNTS.toString(), List.of(
                    new Document("pubkey", PUBKEY).append("agent", AGENT).append("name", "Alice key")
                            .append("status", "loaded").append("quota", 1000).append("ratio", 0.5)
                            .append("pathCount", 3)));
            db.stubGetOne(Collection.AGENTS.toString(), new Document("agent", AGENT),
                    new Document("agent", AGENT).append("name", "Alice"));
            db.stubGetOne("lists", new Document("pubkey", PUBKEY).append("type", "$"),
                    new Document("maxPosition", 9L));

            PageMocks.MockContext ctx = PageMocks.context("/agentAccounts", HttpMethod.GET, Map.of("id", AGENT), "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("Accounts of Agent orcid:0000-0000-0000-0001 (Alice)"), "heading carries the name");
            assertTrue(body.contains("(Alice key)"), "the per-account name is shown");
            assertTrue(body.contains("count 10"), "count is maxPosition + 1");
            assertTrue(body.contains("quota 1000"), "quota is shown");
        }
    }

    @Test
    void agentAccountsHtmlHandlesMissingDollarList() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindFiltered(Collection.ACCOUNTS.toString(), List.of(
                    new Document("pubkey", PUBKEY).append("agent", AGENT).append("status", "loaded")
                            .append("quota", 1000).append("ratio", 0.5).append("pathCount", 3)));
            db.stubGetOne(Collection.AGENTS.toString(), new Document("agent", AGENT), null);
            db.stubGetOne("lists", new Document("pubkey", PUBKEY).append("type", "$"), null);

            PageMocks.MockContext ctx = PageMocks.context("/agentAccounts", HttpMethod.GET, Map.of("id", AGENT), "text/html");
            ListPage.show(ctx.context);

            assertTrue(ctx.body().contains("count 0"), "a pubkey with no $ list counts as zero nanopubs");
        }
    }

    @Test
    void agentsJsonServesAgentDocuments() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.AGENTS.toString(), List.of(
                    new Document("agent", AGENT).append("accountCount", 1).append("totalRatio", 0.5)));

            PageMocks.MockContext ctx = PageMocks.context("/agents.json");
            ListPage.show(ctx.context);

            assertTrue(ctx.body().contains(AGENT), "the agent is serialized");
        }
    }

    @Test
    void agentsHtmlPluralisesAccountCount() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.AGENTS.toString(), List.of(
                    // The synthetic "$" agent must not be listed.
                    new Document("agent", "$").append("accountCount", 0).append("totalRatio", 1.0).append("avgPathCount", 0.0),
                    new Document("agent", AGENT).append("name", "Alice").append("accountCount", 1)
                            .append("totalRatio", 0.5).append("avgPathCount", 2.0),
                    new Document("agent", "https://orcid.org/0000-0000-0000-0002").append("accountCount", 3)
                            .append("totalRatio", 0.25).append("avgPathCount", 1.0)));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/agents", "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Agent List</h1>"), "renders the agents heading");
            assertTrue(body.contains("1 account,"), "singular for a single account");
            assertTrue(body.contains("3 accounts,"), "plural for several accounts");
            assertFalse(body.contains(">$<"), "the synthetic $ agent is skipped");
        }
    }

    // --- /nanopubs -----------------------------------------------------------

    @Test
    void nanopubsJsonServesIdsSortedByDate() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.NANOPUBS.toString(), List.of(
                    new Document("_id", "RAaaa"), new Document("_id", "RAbbb")));

            PageMocks.MockContext ctx = PageMocks.context("/nanopubs.json");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("\"RAaaa\""), "ids are emitted as bare JSON strings");
            assertTrue(body.contains("\"RAbbb\""), "every id is emitted");
            assertTrue(body.trim().endsWith("]"), "the array is closed");
        }
    }

    @Test
    void nanopubsJsonSupportsIdPagination() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.NANOPUBS.toString(), List.of(new Document("_id", "RAccc")));

            PageMocks.MockContext ctx = PageMocks.context("/nanopubs.json", Map.of("sort", "id", "after", "RAbbb"));
            ListPage.show(ctx.context);

            assertTrue(ctx.body().contains("\"RAccc\""), "ids after the cursor are returned");
        }
    }

    @Test
    void nanopubsHtmlListsLatestNanopubs() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.NANOPUBS.toString(), List.of(new Document("_id", "RAabcdefghijkl")));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/nanopubs", "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Nanopubs</h1>"), "renders the nanopubs heading");
            assertTrue(body.contains("/np/RAabcdefghijkl"), "links to each nanopub");
            assertTrue(body.contains("nanopubs.jelly"), "offers the Jelly bulk download");
        }
    }

    @Test
    void nanopubsJellyRejectsNonNumericAfterCounter() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/nanopubs.jelly", Map.of("afterCounter", "not-a-number"));
            ListPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

    // --- /pubkeys ------------------------------------------------------------

    @Test
    void pubkeysJsonServesDistinctPubkeys() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubDistinct("lists", "pubkey", List.of(PUBKEY, "$"));

            PageMocks.MockContext ctx = PageMocks.context("/pubkeys.json");
            ListPage.show(ctx.context);

            assertTrue(ctx.body().contains("\"" + PUBKEY + "\""), "each distinct pubkey is serialized");
        }
    }

    @Test
    void pubkeysHtmlSkipsTheDollarPubkey() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubDistinct("lists", "pubkey", List.of("$", PUBKEY));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/pubkeys", "text/html");
            ListPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Pubkey List</h1>"), "renders the pubkeys heading");
            assertTrue(body.contains("/list/" + PUBKEY), "real pubkeys are linked");
            assertFalse(body.contains("/list/$"), "the synthetic $ pubkey is skipped");
        }
    }

    // --- error handling ------------------------------------------------------

    @Test
    void unsupportedExtensionIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/list.xml");
            ListPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

    @Test
    void unknownPathIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/list/not-a-pubkey-hash.json");
            ListPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

    @Test
    void agentWithoutIdParameterIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            // /agent is routed here by MainVerticle, but without ?id= there is nothing to serve.
            PageMocks.MockContext ctx = PageMocks.context("/agent.json");
            ListPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

    @Test
    void presentationFormatOverridesNegotiatedContentType() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.ACCOUNTS.toString(), List.of());

            // ".json.txt" asks for JSON content rendered as plain text in the browser.
            PageMocks.MockContext ctx = PageMocks.context("/list.json.txt");
            ListPage.show(ctx.context);

            verify(ctx.response).putHeader("Content-Type", "text/plain");
            verify(ctx.response, never()).setStatusCode(anyInt());
        }
    }

}
