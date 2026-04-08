package com.knowledgepixels.registry;

import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NanopubPageTest {

    private static final String ARTIFACT_CODE = "RAeFsphUvGCAkryLarEz5mTQm3Wk4Yx5XCi5jY3Rfkn6k";
    private static final String FULL_ID = "https://w3id.org/np/" + ARTIFACT_CODE;
    private static final String TRIG_CONTENT = "@prefix this: <" + FULL_ID + "> .";

    private Document makeNpDoc() {
        return new Document("_id", ARTIFACT_CODE)
                .append("fullId", FULL_ID)
                .append("content", TRIG_CONTENT);
    }

    @Test
    void getPathWithHtmlAcceptRedirectsToNanodash() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE, "text/html", true, makeNpDoc());
            verify(response).setStatusCode(302);
            verify(response).putHeader("Location",
                    NanopubPage.NANODASH_BASE_URL_DEFAULT + "explore?id=" + Utils.urlEncode(FULL_ID));
        }
    }

    @Test
    void getPathWithTrigExtensionReturnsTrig() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE + ".trig", null, true, makeNpDoc());
            verify(response, never()).setStatusCode(302);
            verify(response).write(TRIG_CONTENT + "\n");
        }
    }

    @Test
    void npPathWithHtmlRendersPage() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/np/" + ARTIFACT_CODE, "text/html", false, makeNpDoc());
            verify(response, never()).setStatusCode(302);
            verify(response).write("<!DOCTYPE HTML>\n");
        }
    }

    @Test
    void getPathWithNoAcceptHeaderDefaultsToTrig() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            // No Accept header: format defaults to first in SUPPORTED_TYPES_NANOPUB (application/trig)
            // so no redirect happens — trig is served
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE, null, true, makeNpDoc());
            verify(response, never()).setStatusCode(302);
            verify(response).write(TRIG_CONTENT + "\n");
        }
    }

    @Test
    void getPathWithWildcardAcceptDefaultsToTrig() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            // Accept: */* (curl default) should serve trig, not redirect
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE, "*/*", true, makeNpDoc());
            verify(response, never()).setStatusCode(302);
            verify(response).write(TRIG_CONTENT + "\n");
        }
    }

    @Test
    void getPathWithTrigAcceptReturnsTrig() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE, "application/trig", true, makeNpDoc());
            verify(response, never()).setStatusCode(302);
            verify(response).write(TRIG_CONTENT + "\n");
        }
    }

    @Test
    void getPathNotFoundOnNonTestInstanceRedirects() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE, "text/html", true, null);
            verify(response).setStatusCode(307);
            verify(response).putHeader("Location", "https://np.knowledgepixels.com/" + ARTIFACT_CODE);
        }
    }

    @Test
    void getPathWithCustomNanodashUrl() {
        String customUrl = "https://custom.nanodash.example.org/";
        com.knowledgepixels.registry.utils.FakeEnv fakeEnv = com.knowledgepixels.registry.utils.FakeEnv.getInstance();
        fakeEnv.addVariable("REGISTRY_NANODASH_BASE_URL", customUrl).build();
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/get/" + ARTIFACT_CODE, "text/html", true, makeNpDoc());
            verify(response).setStatusCode(302);
            verify(response).putHeader("Location",
                    customUrl + "explore?id=" + Utils.urlEncode(FULL_ID));
        } finally {
            fakeEnv.reset();
        }
    }

    @SuppressWarnings("unchecked")
    private HttpServerResponse setupMocks(MockedStatic<RegistryDB> dbMock, String path, String acceptHeader, boolean forwardHtml, Document npDoc) {
        MongoClient mongoClient = mock(MongoClient.class);
        ClientSession session = mock(ClientSession.class);
        dbMock.when(RegistryDB::getClient).thenReturn(mongoClient);
        when(mongoClient.startSession()).thenReturn(session);

        MongoCollection<Document> serverInfoCollection = mock(MongoCollection.class);
        FindIterable<Document> serverInfoFindIterable = mock(FindIterable.class);
        dbMock.when(() -> RegistryDB.collection(Collection.SERVER_INFO.toString())).thenReturn(serverInfoCollection);
        java.util.List<Document> serverInfoDocs = java.util.List.of(
                new Document("_id", "status").append("value", "ready"),
                new Document("_id", "setupId").append("value", 1L),
                new Document("_id", "trustStateCounter").append("value", 0L),
                new Document("_id", "lastTrustStateUpdate").append("value", ""),
                new Document("_id", "trustStateHash").append("value", ""),
                new Document("_id", "testInstance").append("value", false)
        );
        when(serverInfoCollection.find(session)).thenReturn(serverInfoFindIterable);
        when(serverInfoFindIterable.iterator()).thenAnswer(invocation -> {
            java.util.Iterator<Document> it = serverInfoDocs.iterator();
            com.mongodb.client.MongoCursor<Document> cursor = mock(com.mongodb.client.MongoCursor.class);
            when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
            when(cursor.next()).thenAnswer(inv -> it.next());
            return cursor;
        });
        when(serverInfoFindIterable.spliterator()).thenAnswer(invocation -> serverInfoDocs.spliterator());

        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        dbMock.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(collection);
        when(collection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(npDoc);
        when(collection.estimatedDocumentCount()).thenReturn(0L);
        dbMock.when(() -> RegistryDB.getMaxValue(session, Collection.NANOPUBS.toString(), "counter")).thenReturn(0L);

        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(request.path()).thenReturn(path);
        when(request.getHeader("Accept")).thenReturn(acceptHeader);
        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.setStatusMessage(anyString())).thenReturn(response);
        when(response.write(anyString())).thenReturn(null);

        NanopubPage.show(context, forwardHtml);

        return response;
    }

}
