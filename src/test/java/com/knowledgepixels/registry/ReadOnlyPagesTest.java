package com.knowledgepixels.registry;

import com.knowledgepixels.registry.store.MongoRegistryStore;
import com.knowledgepixels.registry.store.RegistryStores;
import com.knowledgepixels.registry.store.SparqlRegistryStore;
import com.knowledgepixels.registry.utils.PageMocks;
import com.knowledgepixels.registry.utils.ReadOnlyFixtures;
import com.knowledgepixels.registry.utils.TestUtils;
import com.google.gson.Gson;
import net.trustyuri.TrustyUriUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * The pages served from a read-only store: the same {@code Page} classes, with the
 * SPARQL-backed store registered instead of MongoDB.
 */
class ReadOnlyPagesTest {

    private static Nanopub a, b;
    private static String pk;
    private static SparqlRegistryStore store;

    @BeforeAll
    static void setUp() throws Exception {
        // the pages consult the mode switch, not just the store
        TestUtils.setupFakeEnv().addVariable(ReadOnlyMode.ENV, "http://rete:7878/sparql").build();
        Nanopub intro = intro(KEY1, ALICE, "Alice", new Date());
        a = simple(KEY1, "a", TYPE_A);
        b = superseding(KEY1, a, TYPE_A);
        pk = Utils.getHash(RegistryDB.getPubkey(a));
        store = new SparqlRegistryStore(repository(build(options(2), List.of(intro, a, b))), 0);
        RegistryStores.set(store);
    }

    @AfterAll
    static void tearDown() {
        RegistryStores.set(new MongoRegistryStore());
        store.close();
        TestUtils.setupFakeEnv().reset();
    }

    private static String ac(Nanopub np) {
        return TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
    }

    @Test
    void mainPageJsonAndHeaders() {
        PageMocks.MockContext ctx = PageMocks.context("/.json");
        MainPage.show(ctx.context);
        Map<?, ?> info = new Gson().fromJson(ctx.body(), Map.class);
        assertEquals("ready", info.get("status"));
        assertEquals(3.0, info.get("nanopubCount"));
        assertEquals(3.0, info.get("loadCounter"));
        assertEquals(0.0, info.get("accountCount"));
        verify(ctx.response).putHeader("Nanopub-Registry-Status", "ready");
        verify(ctx.response).putHeader("Nanopub-Registry-Load-Counter", "3");
        verify(ctx.response).putHeader("Nanopub-Registry-Nanopub-Count", "3");
        verify(ctx.response).putHeader("Nanopub-Registry-Setup-Id", "4242");
    }

    @Test
    void mainPageHtmlWithoutSetting() {
        PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
        MainPage.show(ctx.context);
        assertTrue(ctx.body().contains("<em>originalSetting:</em> none"));
        assertTrue(ctx.body().contains("<em>nanopubCount:</em> 3"));
        assertTrue(ctx.body().contains("<h3>Pubkeys</h3>"));
        assertTrue(ctx.body().contains("<a href=\"/pubkeys\">"));
        assertFalse(ctx.body().contains("<h3>Agents</h3>"), "no agent section on a read-only registry");
        assertFalse(ctx.body().contains("<h3>Current Trust State</h3>"), "no trust state section on a read-only registry");
        assertFalse(ctx.body().contains("<h3>Trust State History</h3>"), "no history section on a read-only registry");
    }

    @Test
    void pubkeyPageLinksBackToPubkeys() {
        PageMocks.MockContext ctx = PageMocks.contextAccepting("/list/" + pk, "text/html");
        ListPage.show(ctx.context);
        assertTrue(ctx.body().contains("<a href=\"/pubkeys\">&lt; Pubkeys</a>"), ctx.body());
        assertFalse(ctx.body().contains("Current Trust State"), "the empty trust state page is not offered");
    }

    @Test
    void pubkeysHtmlShowsCounts() {
        PageMocks.MockContext ctx = PageMocks.contextAccepting("/pubkeys", "text/html");
        ListPage.show(ctx.context);
        assertTrue(ctx.body().contains("/list/" + pk), ctx.body());
        assertTrue(ctx.body().contains("(3 nanopubs)"), ctx.body());
    }

    @Test
    void nanopubPageServesTrig() throws Exception {
        PageMocks.MockContext ctx = PageMocks.context("/np/" + ac(a) + ".trig");
        NanopubPage.show(ctx.context);
        assertEquals(NanopubUtils.writeToString(a, org.eclipse.rdf4j.rio.RDFFormat.TRIG) + "\n", ctx.body());
    }

    @Test
    void listPagesServeJson() {
        PageMocks.MockContext ctx = PageMocks.context("/list/" + pk + "/$.json");
        ListPage.show(ctx.context);
        String body = ctx.body();
        assertTrue(body.contains("\"position\": 0"));
        assertTrue(body.contains("\"np\": \"" + ac(a) + "\""));
        assertTrue(body.contains("\"invalidated\": true"), body);
        assertTrue(body.contains("\"checksum\": \""));

        ctx = PageMocks.context("/list/" + pk + ".json");
        ListPage.show(ctx.context);
        assertTrue(ctx.body().contains("\"type\": \"$\""));
        assertTrue(ctx.body().contains("\"maxPosition\": 2"));

        ctx = PageMocks.context("/list.json");
        ListPage.show(ctx.context);
        assertEquals("[\n]\n", ctx.body(), "no accounts are published");

        ctx = PageMocks.context("/nanopubs.json", Map.of("sort", "id"));
        ListPage.show(ctx.context);
        assertTrue(ctx.body().contains("\"" + ac(a) + "\""));

        ctx = PageMocks.context("/pubkeys.json");
        ListPage.show(ctx.context);
        assertTrue(ctx.body().contains("\"" + pk + "\""));

        ctx = PageMocks.context("/agents.json");
        ListPage.show(ctx.context);
        assertEquals("[\n]\n", ctx.body(), "no agents are published");
    }

    @Test
    void listHtmlLabelsTypes() {
        PageMocks.MockContext ctx = PageMocks.contextAccepting("/list/" + pk, "text/html");
        ListPage.show(ctx.context);
        assertTrue(ctx.body().contains("(type " + TYPE_A + ")"));
    }

    @Test
    void trustStatePages() {
        PageMocks.MockContext ctx = PageMocks.context("/trust-state.json");
        TrustStatePage.show(ctx.context);
        assertTrue(ctx.body().contains("\"trustStateCounter\": 1"));
        String hash = ctx.body().replaceAll("(?s).*\"trustStateHash\": \"([0-9a-f]+)\".*", "$1");
        ctx = PageMocks.context("/trust-state/" + hash + ".json");
        TrustStatePage.show(ctx.context);
        assertTrue(ctx.body().contains("\"accounts\": []"));
        assertTrue(ctx.body().contains("\"edges\": []"));
    }

}
