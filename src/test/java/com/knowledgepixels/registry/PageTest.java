package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PageTest {

    @SuppressWarnings("unchecked")
    private static void mockRegistryDB(MockedStatic<RegistryDB> registry, ClientSession session) {
        // Mock collection(SERVER_INFO) -> find(session) -> first() returning a serverInfo Document
        MongoCollection<Document> serverInfoCollection = mock(MongoCollection.class);
        FindIterable<Document> serverInfoFindIterable = mock(FindIterable.class);
        registry.when(() -> RegistryDB.collection(Collection.SERVER_INFO.toString())).thenReturn(serverInfoCollection);
        when(serverInfoCollection.find(session)).thenReturn(serverInfoFindIterable);
        java.util.List<Document> serverInfoDocs = java.util.List.of(
            new Document("_id", "status").append("value", "ready"),
            new Document("_id", "setupId").append("value", 1L),
            new Document("_id", "trustStateCounter").append("value", 0L),
            new Document("_id", "lastTrustStateUpdate").append("value", ""),
            new Document("_id", "trustStateHash").append("value", ""),
            new Document("_id", "testInstance").append("value", false)
        );
        when(serverInfoFindIterable.iterator()).thenAnswer(invocation -> {
            java.util.Iterator<Document> it = serverInfoDocs.iterator();
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
            when(cursor.next()).thenAnswer(inv -> it.next());
            return cursor;
        });
        when(serverInfoFindIterable.spliterator()).thenAnswer(invocation -> serverInfoDocs.spliterator());

        // Mock collection(NANOPUBS) -> estimatedDocumentCount() returning 0L
        MongoCollection<Document> nanopubsCollection = mock(MongoCollection.class);
        registry.when(() -> RegistryDB.collection(Collection.NANOPUBS.toString())).thenReturn(nanopubsCollection);
        when(nanopubsCollection.estimatedDocumentCount()).thenReturn(0L);

        // Mock getMaxValue(session, NANOPUBS, "counter") returning 0L
        registry.when(() -> RegistryDB.getMaxValue(session, Collection.NANOPUBS.toString(), "counter")).thenReturn(0L);
    }

    @Test
    void construct() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().path()).thenReturn("/test/path/example.html");

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertNotNull(page);
            assertNotNull(page.getContext());
        }
    }

    @Test
    void getFullRequest() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(context.request()).thenReturn(request);
            when(request.path()).thenReturn("/test/path/example.html");

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals(request.path(), page.getFullRequest());
        }
    }

    @Test
    void getContext() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().path()).thenReturn("/test/path/example.html");

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals(page.getContext(), context);
        }
    }

    @Test
    void getPresentationFormatHTML() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().path()).thenReturn("/test/path/example.html");

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals("text/html", page.getPresentationFormat());
        }
    }

    @Test
    void getPresentationFormatPlainText() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().path()).thenReturn("/test/path/example.txt");

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals("text/plain", page.getPresentationFormat());
        }
    }

    @Test
    void getExtension() {
        String expectedExtension = ".trig";
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().path()).thenReturn("/test/path/data" + expectedExtension);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals(expectedExtension.replace(".", ""), page.getExtension());
        }
    }

    @Test
    void getExtensionNull() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().path()).thenReturn("/test/path/example.txt");

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertNull(page.getExtension());
        }
    }

    @Test
    void getRequestString() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            String requestPath = "/test/path/example.txt";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals(requestPath.replace(".txt", ""), page.getRequestString());

            requestPath = "/test/path/data.trig";
            when(context.request().path()).thenReturn(requestPath);

            Page page2 = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals(requestPath.replace(".trig", ""), page2.getRequestString());
        }
    }

    @Test
    void hasArtifactCode() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            String requestPath = "/RAXH93wfOaQRwDpxwr-E_s10kCQubHZ6O19h-cz3YlNGI.trig";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertTrue(page.hasArtifactCode());

            requestPath = "/RAXH93-cz3YlNGI.trig"; // not a valid artifact code
            when(context.request().path()).thenReturn(requestPath);

            Page page2 = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertFalse(page2.hasArtifactCode());
        }
    }

    @Test
    void getArtifactCodeWhenExists() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            String requestPath = "/RAXH93wfOaQRwDpxwr-E_s10kCQubHZ6O19h-cz3YlNGI.trig";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertEquals(requestPath.substring(1, requestPath.indexOf(".trig")), page.getArtifactCode());
        }
    }

    @Test
    void getArtifactCodeWhenNotExists() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            String requestPath = "/data.json";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertNull(page.getArtifactCode());
        }
    }

    @Test
    void getParam() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));

            String requestPath = "/data.json";
            when(context.request().path()).thenReturn(requestPath);

            String defaultValue = "default";
            String expectedValue = "loading";
            when(context.request().getParam("name")).thenReturn(null);
            when(context.request().getParam("status")).thenReturn(expectedValue);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };

            assertEquals(defaultValue, page.getParam("name", defaultValue));
            assertEquals(expectedValue, page.getParam("status", defaultValue));
        }
    }

    @Test
    void isEmpty() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));

            String requestPath = "/.txt";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            assertTrue(page.isEmpty());
        }
    }

    @Test
    void setCanonicalLink() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            when(context.response().headers()).thenReturn(headers);
            when(context.response().putHeader(anyString(), anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                headers.add(key, value);
                return context.response();
            });

            String requestPath = "/data.txt";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            String url = "https://example.com";
            page.setCanonicalLink(url);
            assertEquals("<" + url + ">; rel=\"canonical\"", page.getContext().response().headers().get("Link"));
        }
    }

    @Test
    void setResponseContentType() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            when(context.response().headers()).thenReturn(headers);
            when(context.response().putHeader(anyString(), anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                headers.add(key, value);
                return context.response();
            });

            String requestPath = "/data.txt";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            String responseContentType = "text/html";
            page.setRespContentType(responseContentType);
            assertEquals(responseContentType, page.getContext().response().headers().get("Content-Type"));
        }
    }

    @Test
    void setResponseContentTypeWhenHTTPMethoIsHead() {
        try (MockedStatic<RegistryDB> registry = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
            mockRegistryDB(registry, mongoSession);
            RoutingContext context = mock(RoutingContext.class);
            when(context.response()).thenReturn(mock(HttpServerResponse.class));
            when(context.request()).thenReturn(mock(HttpServerRequest.class));
            when(context.request().method()).thenReturn(HttpMethod.HEAD);

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            when(context.response().headers()).thenReturn(headers);
            when(context.response().putHeader(anyString(), anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                headers.add(key, value);
                return context.response();
            });

            String requestPath = "/data.txt";
            when(context.request().path()).thenReturn(requestPath);

            Page page = new Page(mongoSession, context) {
                @Override
                protected void show() {
                    // not implemented for this test
                }
            };
            String responseContentType = "text/html";
            page.setRespContentType(responseContentType);
            assertNull(page.getContext().response().headers().get("Content-Type"));
        }
    }

}
