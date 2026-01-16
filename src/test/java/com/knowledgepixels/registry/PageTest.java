package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageTest {

    @Test
    void construct() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession mongoSession = mock(ClientSession.class);
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
