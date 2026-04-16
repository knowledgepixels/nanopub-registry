package com.knowledgepixels.registry;

import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrustStatePageTest {

    private static final String HASH = "abc123def456";

    private Document makeSnapshot() {
        List<Document> accounts = List.of(
                new Document("pubkey", "pk1").append("agent", "https://orcid.org/0000-0000-0000-0001")
                        .append("status", "loaded").append("depth", 1).append("pathCount", 2)
                        .append("ratio", 0.5).append("quota", 1000),
                new Document("pubkey", "pk2").append("agent", "https://orcid.org/0000-0000-0000-0002")
                        .append("status", "loaded").append("depth", 2).append("pathCount", 1)
                        .append("ratio", 0.25).append("quota", 500)
        );
        return new Document("_id", HASH)
                .append("trustStateCounter", 42L)
                .append("createdAt", "2026-04-15T12:00:00Z")
                .append("accounts", accounts);
    }

    @Test
    void returns200WithEnvelopeForExistingHash() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/trust-state/" + HASH + ".json", makeSnapshot());
            verify(response, never()).setStatusCode(anyInt());
            verify(response).putHeader("Content-Type", "application/json");
            verify(response).putHeader("Cache-Control", "public, immutable, max-age=31536000");

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(response, atLeastOnce()).write(body.capture());
            String fullBody = String.join("", body.getAllValues());
            assertTrue(fullBody.contains("\"trustStateHash\""), "envelope includes trustStateHash");
            assertTrue(fullBody.contains(HASH), "envelope echoes the hash");
            assertTrue(fullBody.contains("\"trustStateCounter\""), "envelope includes counter");
            assertTrue(fullBody.contains("\"accounts\""), "envelope includes accounts array");
            assertTrue(fullBody.contains("pk1") && fullBody.contains("pk2"), "accounts are serialized");
        }
    }

    @Test
    void returns404WhenHashNotFound() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            HttpServerResponse response = setupMocks(dbMock, "/trust-state/missing.json", null);
            verify(response).setStatusCode(404);
            verify(response, never()).putHeader(eq("Cache-Control"), anyString());
        }
    }

    @Test
    void returns400WhenExtensionIsUnsupported() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            // .xml is not in the supported set → 400 before any DB lookup
            HttpServerResponse response = setupMocks(dbMock, "/trust-state/" + HASH + ".xml", null);
            verify(response).setStatusCode(400);
        }
    }

    @Test
    void returns400WhenPathMalformed() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            // Hash contains disallowed character → doesn't match the regex, isn't the list path
            HttpServerResponse response = setupMocks(dbMock, "/trust-state/bad!hash.json", null);
            verify(response).setStatusCode(400);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpServerResponse setupMocks(MockedStatic<RegistryDB> dbMock, String path, Document snapshotDoc) {
        MongoClient mongoClient = mock(MongoClient.class);
        ClientSession session = mock(ClientSession.class);
        dbMock.when(RegistryDB::getClient).thenReturn(mongoClient);
        when(mongoClient.startSession()).thenReturn(session);

        // Page base-class needs serverInfo + nanopubs + counter lookups.
        MongoCollection<Document> serverInfoCollection = mock(MongoCollection.class);
        FindIterable<Document> serverInfoFindIterable = mock(FindIterable.class);
        dbMock.when(() -> RegistryDB.collection(Collection.SERVER_INFO.toString())).thenReturn(serverInfoCollection);
        List<Document> serverInfoDocs = List.of(
                new Document("_id", "status").append("value", "ready"),
                new Document("_id", "setupId").append("value", 1L),
                new Document("_id", "trustStateCounter").append("value", 0L),
                new Document("_id", "lastTrustStateUpdate").append("value", ""),
                new Document("_id", "trustStateHash").append("value", ""),
                new Document("_id", "testInstance").append("value", false)
        );
        when(serverInfoCollection.find(session)).thenReturn(serverInfoFindIterable);
        when(serverInfoFindIterable.iterator()).thenAnswer(inv -> {
            Iterator<Document> it = serverInfoDocs.iterator();
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenAnswer(i -> it.hasNext());
            when(cursor.next()).thenAnswer(i -> it.next());
            return cursor;
        });
        when(serverInfoFindIterable.spliterator()).thenAnswer(inv -> serverInfoDocs.spliterator());

        MongoCollection<Document> nanopubs = mock(MongoCollection.class);
        dbMock.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(nanopubs);
        when(nanopubs.estimatedDocumentCount()).thenReturn(0L);
        dbMock.when(() -> RegistryDB.getMaxValue(session, Collection.NANOPUBS.toString(), "counter")).thenReturn(0L);

        // Snapshot lookup.
        MongoCollection<Document> snapshots = mock(MongoCollection.class);
        FindIterable<Document> snapshotFind = mock(FindIterable.class);
        dbMock.when(() -> RegistryDB.collection(Collection.TRUST_STATE_SNAPSHOTS.toString())).thenReturn(snapshots);
        when(snapshots.find(eq(session), any(Document.class))).thenReturn(snapshotFind);
        when(snapshotFind.first()).thenReturn(snapshotDoc);

        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(request.path()).thenReturn(path);
        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.setStatusMessage(anyString())).thenReturn(response);
        when(response.write(anyString())).thenReturn(null);

        TrustStatePage.show(context);

        return response;
    }

}
