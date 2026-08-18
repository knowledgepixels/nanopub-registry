package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import com.mongodb.client.FindIterable;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link NanopubPage} renderings that {@link NanopubPageTest} leaves out:
 * the HTML detail view, the RDF serialisation formats and the not-found handling on
 * a test instance.
 */
class NanopubPageFormatsTest {

    private static final String ARTIFACT_CODE = "RAeFsphUvGCAkryLarEz5mTQm3Wk4Yx5XCi5jY3Rfkn6k";
    private static final String FULL_ID = "https://w3id.org/np/" + ARTIFACT_CODE;

    /**
     * A structurally valid nanopub, needed by the formats that re-parse the stored TriG.
     */
    private static final String TRIG_CONTENT = """
            @prefix : <http://example.org/np1#> .
            @prefix np: <http://www.nanopub.org/nschema#> .
            @prefix prov: <http://www.w3.org/ns/prov#> .
            @prefix dct: <http://purl.org/dc/terms/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            :Head {
              <http://example.org/np1> a np:Nanopublication ;
                np:hasAssertion :assertion ;
                np:hasProvenance :provenance ;
                np:hasPublicationInfo :pubinfo .
            }
            :assertion {
              <http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .
            }
            :provenance {
              :assertion prov:wasAttributedTo <http://example.org/someone> .
            }
            :pubinfo {
              <http://example.org/np1> dct:created "2026-01-01T00:00:00Z"^^xsd:dateTime .
            }
            """;

    private static Document npDoc(String content) {
        return new Document("_id", ARTIFACT_CODE).append("fullId", FULL_ID).append("content", content);
    }

    /**
     * Wires the nanopub lookup, which NanopubPage performs without a session.
     */
    private static void stubNanopubLookup(PageMocks.Db db, Document doc) {
        FindIterable<Document> it = PageMocks.findIterable(doc == null ? List.of() : List.of(doc));
        when(db.collection(Collection.NANOPUBS.toString()).find(any(Document.class))).thenReturn(it);
    }

    @Test
    void htmlDetailViewListsEveryFormatAndEscapesContent() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc("<a> <b> \"<script>\" ."));

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/np/" + ARTIFACT_CODE, "text/html");
            NanopubPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Nanopublication</h1>"), "renders the detail heading");
            assertTrue(body.contains(FULL_ID), "shows the full nanopub id");
            assertTrue(body.contains("/np/" + ARTIFACT_CODE + ".trig"), "offers TriG");
            assertTrue(body.contains("/np/" + ARTIFACT_CODE + ".jelly"), "offers Jelly");
            assertTrue(body.contains("/np/" + ARTIFACT_CODE + ".jsonld"), "offers JSON-LD");
            assertTrue(body.contains("/np/" + ARTIFACT_CODE + ".nq"), "offers N-Quads");
            assertTrue(body.contains("/np/" + ARTIFACT_CODE + ".xml"), "offers TriX");
            // Stored content is attacker-influenced and must never be rendered as live markup.
            assertTrue(body.contains("&lt;script&gt;"), "content is HTML-escaped");
        }
    }

    @Test
    void nquadsExtensionServesNQuads() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc(TRIG_CONTENT));

            PageMocks.MockContext ctx = PageMocks.context("/np/" + ARTIFACT_CODE + ".nq");
            NanopubPage.show(ctx.context);

            verify(ctx.response).putHeader("Content-Type", Utils.TYPE_NQUADS);
            verify(ctx.response).write(org.mockito.ArgumentMatchers.contains("http://example.org/subject"),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void jsonldExtensionServesJsonLd() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc(TRIG_CONTENT));

            PageMocks.MockContext ctx = PageMocks.context("/np/" + ARTIFACT_CODE + ".jsonld");
            NanopubPage.show(ctx.context);

            verify(ctx.response).putHeader("Content-Type", Utils.TYPE_JSONLD);
            verify(ctx.response).write(org.mockito.ArgumentMatchers.contains("http://example.org/subject"),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void trixExtensionServesTrix() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc(TRIG_CONTENT));

            PageMocks.MockContext ctx = PageMocks.context("/np/" + ARTIFACT_CODE + ".xml");
            NanopubPage.show(ctx.context);

            verify(ctx.response).putHeader("Content-Type", Utils.TYPE_TRIX);
            verify(ctx.response).write(org.mockito.ArgumentMatchers.contains("TriX"),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void unparseableStoredContentYields500() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc("this is not RDF at all"));

            PageMocks.MockContext ctx = PageMocks.context("/np/" + ARTIFACT_CODE + ".nq");
            NanopubPage.show(ctx.context);

            verify(ctx.response).setStatusCode(500);
        }
    }

    @Test
    void testInstanceReturns404InsteadOfRedirecting() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, null);
            // On a test instance there is no upstream worth redirecting to.
            dbMock.when(() -> RegistryDB.isSet(db.session, Collection.SERVER_INFO.toString(), "testInstance")).thenReturn(true);

            PageMocks.MockContext ctx = PageMocks.context("/np/" + ARTIFACT_CODE);
            NanopubPage.show(ctx.context);

            verify(ctx.response).setStatusCode(404);
        }
    }

    @Test
    void malformedArtifactCodeIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/np/not-an-artifact-code");
            NanopubPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

    @Test
    void unnegotiableAcceptFallsBackToTheHtmlView() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc(TRIG_CONTENT));

            // Nothing in the Accept header is servable, so no specific format matches and
            // the request lands in the human-readable view rather than erroring out.
            PageMocks.MockContext ctx = PageMocks.contextAccepting("/np/" + ARTIFACT_CODE, "image/png");
            NanopubPage.show(ctx.context);

            assertTrue(ctx.body().contains("<h1>Nanopublication</h1>"));
        }
    }

    @Test
    void textPresentationOverridesTheContentType() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            stubNanopubLookup(db, npDoc(TRIG_CONTENT));

            // ".trig.txt" serves TriG content but asks the browser to display it as text.
            PageMocks.MockContext ctx = PageMocks.context("/np/" + ARTIFACT_CODE + ".trig.txt");
            NanopubPage.show(ctx.context);

            verify(ctx.response).putHeader("Content-Type", "text/plain");
            assertTrue(ctx.body().contains("np:hasAssertion"), "the TriG body is still served");
        }
    }

}
