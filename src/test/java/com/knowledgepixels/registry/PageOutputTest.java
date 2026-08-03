package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import io.vertx.core.http.HttpMethod;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link Page}'s output helpers and the registry headers it stamps on every
 * response. Those headers are the contract peers use to decide whether to sync, so
 * they are asserted explicitly. {@link PageTest} covers request parsing.
 */
class PageOutputTest {

    /**
     * A minimal concrete Page; the base class does all the work under test.
     */
    private static Page page(PageMocks.Db db, PageMocks.MockContext ctx) {
        return new Page(db.session, ctx.context) {
            @Override
            protected void show() {
            }
        };
    }

    @Test
    void printAndPrintlnWriteToTheResponse() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            PageMocks.MockContext ctx = PageMocks.context("/anything");

            Page p = page(db, ctx);
            p.print("a");
            p.println("b");
            p.print("c");

            assertEquals("ab\nc", ctx.body());
        }
    }

    @Test
    void headRequestsGetHeadersButNoBody() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            PageMocks.MockContext ctx = PageMocks.context("/anything", HttpMethod.HEAD, Map.of(), null);

            Page p = page(db, ctx);
            p.println("this must not be sent");

            assertEquals("", ctx.body(), "HEAD responses carry no body");
        }
    }

    @Test
    void htmlHeaderAndFooterWrapTheDocument() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            PageMocks.MockContext ctx = PageMocks.context("/anything");

            Page p = page(db, ctx);
            p.printHtmlHeader("My Title");
            p.printHtmlFooter();

            String body = ctx.body();
            assertTrue(body.startsWith("<!DOCTYPE HTML>"), "emits a doctype");
            assertTrue(body.contains("<title>My Title</title>"), "uses the given title");
            assertTrue(body.contains("href=\"/style.css\""), "links the stylesheet");
            assertTrue(body.trim().endsWith("</body></html>"), "closes the document");
        }
    }

    @Test
    void escapeHtmlNeutralisesMarkup() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            PageMocks.MockContext ctx = PageMocks.context("/anything");

            assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;",
                    page(db, ctx).escapeHtml("<script>alert(1)</script>"));
        }
    }

    @Test
    void everyResponseCarriesTheRegistryHeaders() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            PageMocks.MockContext ctx = PageMocks.context("/anything");

            page(db, ctx);

            // Peers read these headers to decide whether and from where to sync.
            verify(ctx.response).putHeader("Nanopub-Registry-Status", "ready");
            verify(ctx.response).putHeader("Nanopub-Registry-Setup-Id", "1");
            verify(ctx.response).putHeader("Nanopub-Registry-Trust-State-Counter", "0");
            verify(ctx.response).putHeader("Nanopub-Registry-Trust-State-Hash", "abcdef1234567890");
            verify(ctx.response).putHeader("Nanopub-Registry-Load-Counter", "0");
            verify(ctx.response).putHeader("Nanopub-Registry-Nanopub-Count", "0");
            verify(ctx.response).putHeader("Nanopub-Registry-Test-Instance", "false");
            verify(ctx.response).putHeader("Nanopub-Registry-Version", Utils.getVersion());
            // Unset coverage falls back to the permissive defaults.
            verify(ctx.response).putHeader("Nanopub-Registry-Coverage-Types", "all");
            verify(ctx.response).putHeader("Nanopub-Registry-Coverage-Agents", "viaSetting");
        }
    }

    @Test
    void configuredCoverageIsAdvertisedInTheHeaders() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock, List.of(
                    new Document("_id", "status").append("value", "ready"),
                    new Document("_id", "setupId").append("value", 1L),
                    new Document("_id", "testInstance").append("value", true),
                    new Document("_id", "coverageTypes").append("value", "http://example.org/TypeA"),
                    new Document("_id", "coverageAgents").append("value", "abc123:5000")));
            PageMocks.MockContext ctx = PageMocks.context("/anything");

            page(db, ctx);

            verify(ctx.response).putHeader("Nanopub-Registry-Coverage-Types", "http://example.org/TypeA");
            verify(ctx.response).putHeader("Nanopub-Registry-Coverage-Agents", "abc123:5000");
            verify(ctx.response).putHeader("Nanopub-Registry-Test-Instance", "true");
        }
    }

    @Test
    void missingServerInfoIsRenderedAsNullRatherThanFailing() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            // A registry that has not finished INIT_DB yet has no serverInfo at all.
            PageMocks.Db db = PageMocks.mockDb(dbMock, List.of());
            PageMocks.MockContext ctx = PageMocks.context("/anything");

            page(db, ctx);

            verify(ctx.response).putHeader("Nanopub-Registry-Status", "null");
            verify(ctx.response).putHeader("Nanopub-Registry-Test-Instance", "false");
        }
    }

}
