package com.knowledgepixels.registry.utils;

import com.knowledgepixels.registry.Collection;
import com.knowledgepixels.registry.RegistryDB;
import com.mongodb.MongoClient;
import com.mongodb.client.ClientSession;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared mocking helpers for the {@code Page} subclasses. Every page runs
 * {@code XyzPage.show(context)}, which opens a Mongo session and builds a
 * {@link com.knowledgepixels.registry.Page}; the base constructor alone needs the
 * {@code serverInfo} and {@code nanopubs} collections plus a max-counter lookup.
 * These helpers stub that once so each test only has to describe the collection
 * contents its own code path reads.
 */
public final class PageMocks {

    private PageMocks() {
    }

    /**
     * The {@code serverInfo} documents a page sees unless a test overrides them.
     */
    public static final List<Document> DEFAULT_SERVER_INFO = List.of(
            new Document("_id", "status").append("value", "ready"),
            new Document("_id", "setupId").append("value", 1L),
            new Document("_id", "trustStateCounter").append("value", 0L),
            new Document("_id", "lastTrustStateUpdate").append("value", "2026-04-15T12:00:00.123Z"),
            new Document("_id", "trustStateHash").append("value", "abcdef1234567890"),
            new Document("_id", "testInstance").append("value", false)
    );

    /**
     * Registers a lazily-created mock {@link MongoCollection} per collection name, so
     * repeated {@code RegistryDB.collection(name)} calls from production code and from
     * the test hand back the same mock and can be stubbed further.
     */
    public static final class Db {

        private final MockedStatic<RegistryDB> dbMock;
        private final Map<String, MongoCollection<Document>> collections = new HashMap<>();

        /**
         * The session that {@code getClient().startSession()} returns.
         */
        public final ClientSession session;

        private Db(MockedStatic<RegistryDB> dbMock) {
            this.dbMock = dbMock;
            this.session = mock(ClientSession.class);
            MongoClient client = mock(MongoClient.class);
            dbMock.when(RegistryDB::getClient).thenReturn(client);
            when(client.startSession()).thenReturn(session);
        }

        /**
         * Returns (creating on first use) the mock collection registered under {@code name}.
         */
        @SuppressWarnings("unchecked")
        public MongoCollection<Document> collection(String name) {
            return collections.computeIfAbsent(name, n -> {
                MongoCollection<Document> c = mock(MongoCollection.class);
                dbMock.when(() -> RegistryDB.collection(n)).thenReturn(c);
                return c;
            });
        }

        /**
         * Makes {@code collection(name).find(session)} yield the given documents.
         */
        public FindIterable<Document> stubFindAll(String name, List<Document> docs) {
            FindIterable<Document> it = findIterable(docs);
            when(collection(name).find(session)).thenReturn(it);
            return it;
        }

        /**
         * Makes {@code collection(name).find(session, <any filter>)} yield the given documents.
         */
        public FindIterable<Document> stubFindFiltered(String name, List<Document> docs) {
            FindIterable<Document> it = findIterable(docs);
            when(collection(name).find(eq(session), any(Document.class))).thenReturn(it);
            return it;
        }

        /**
         * Makes {@code collection(name).distinct(session, field, String.class)} yield the given values.
         */
        @SuppressWarnings("unchecked")
        public void stubDistinct(String name, String field, List<String> values) {
            DistinctIterable<String> distinct = mock(DistinctIterable.class);
            when(collection(name).distinct(session, field, String.class)).thenReturn(distinct);
            when(distinct.cursor()).thenAnswer(inv -> cursor(values));
            when(distinct.iterator()).thenAnswer(inv -> cursor(values));
        }

        /**
         * Stubs a static {@code RegistryDB.getOne(session, collection, query)} result.
         */
        public void stubGetOne(String name, Document query, Document result) {
            dbMock.when(() -> RegistryDB.getOne(session, name, query)).thenReturn(result);
        }

        /**
         * Gives access to the underlying static mock for one-off stubbing.
         */
        public MockedStatic<RegistryDB> staticMock() {
            return dbMock;
        }
    }

    /**
     * Stubs everything {@code Page}'s constructor reads, with {@link #DEFAULT_SERVER_INFO}.
     */
    public static Db mockDb(MockedStatic<RegistryDB> dbMock) {
        return mockDb(dbMock, DEFAULT_SERVER_INFO);
    }

    /**
     * Stubs everything {@code Page}'s constructor reads, with custom {@code serverInfo} documents.
     */
    public static Db mockDb(MockedStatic<RegistryDB> dbMock, List<Document> serverInfo) {
        Db db = new Db(dbMock);
        db.stubFindAll(Collection.SERVER_INFO.toString(), serverInfo);
        when(db.collection(Collection.NANOPUBS.toString()).estimatedDocumentCount()).thenReturn(0L);
        dbMock.when(() -> RegistryDB.getMaxValue(db.session, Collection.NANOPUBS.toString(), "counter")).thenReturn(0L);
        return db;
    }

    /**
     * A mocked routing context that records everything written to the response body.
     */
    public static final class MockContext {

        public final RoutingContext context;
        public final HttpServerRequest request;
        public final HttpServerResponse response;

        private final StringBuilder body = new StringBuilder();

        private MockContext(RoutingContext context, HttpServerRequest request, HttpServerResponse response) {
            this.context = context;
            this.request = request;
            this.response = response;
        }

        /**
         * Everything the page wrote to the response, concatenated.
         */
        public String body() {
            return body.toString();
        }
    }

    /**
     * A GET context for the given path, with no query parameters and no Accept header.
     */
    public static MockContext context(String path) {
        return context(path, HttpMethod.GET, Map.of(), null);
    }

    /**
     * A GET context for the given path with query parameters.
     */
    public static MockContext context(String path, Map<String, String> params) {
        return context(path, HttpMethod.GET, params, null);
    }

    /**
     * A GET context for the given path with an Accept header.
     */
    public static MockContext contextAccepting(String path, String acceptHeader) {
        return context(path, HttpMethod.GET, Map.of(), acceptHeader);
    }

    /**
     * A fully configurable mocked routing context.
     */
    public static MockContext context(String path, HttpMethod method, Map<String, String> params, String acceptHeader) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        MockContext mc = new MockContext(context, request, response);

        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(request.path()).thenReturn(path);
        when(request.method()).thenReturn(method);
        when(request.getHeader("Accept")).thenReturn(acceptHeader);
        when(request.getParam(anyString())).thenAnswer(inv -> params.get(inv.<String>getArgument(0)));

        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.setStatusMessage(anyString())).thenReturn(response);
        when(response.write(anyString())).thenAnswer(inv -> {
            mc.body.append(inv.<String>getArgument(0));
            return null;
        });
        when(response.write(any(Buffer.class))).thenAnswer(inv -> {
            mc.body.append(inv.<Buffer>getArgument(0).toString());
            return null;
        });

        return mc;
    }

    /**
     * A {@link FindIterable} whose chaining methods return itself and whose cursors
     * replay the given documents. Each {@code cursor()} call starts from the beginning.
     */
    @SuppressWarnings("unchecked")
    public static FindIterable<Document> findIterable(List<Document> docs) {
        FindIterable<Document> it = mock(FindIterable.class);
        when(it.projection(any())).thenReturn(it);
        when(it.sort(any())).thenReturn(it);
        when(it.filter(any())).thenReturn(it);
        when(it.limit(anyInt())).thenReturn(it);
        when(it.first()).thenReturn(docs.isEmpty() ? null : docs.getFirst());
        when(it.cursor()).thenAnswer(inv -> cursor(docs));
        when(it.iterator()).thenAnswer(inv -> cursor(docs));
        when(it.spliterator()).thenAnswer(inv -> docs.spliterator());
        return it;
    }

    /**
     * A {@link MongoCursor} replaying the given values once.
     */
    @SuppressWarnings("unchecked")
    public static <T> MongoCursor<T> cursor(List<T> values) {
        Iterator<T> iter = values.iterator();
        MongoCursor<T> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(cursor.next()).thenAnswer(inv -> iter.next());
        return cursor;
    }

}
