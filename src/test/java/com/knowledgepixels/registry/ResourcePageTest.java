package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link ResourcePage}, which streams static files bundled in the jar.
 */
class ResourcePageTest {

    @Test
    void servesBundledStylesheetWithGivenContentType() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/style.css");
            ResourcePage.show(ctx.context, "style.css", "text/css");

            verify(ctx.response).putHeader("Content-Type", "text/css");
            assertFalse(ctx.body().isEmpty(), "the bundled stylesheet is written to the response");
            verify(ctx.response).end();
        }
    }

    @Test
    void headRequestSuppressesTheContentTypeHeader() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/style.css", HttpMethod.HEAD, Map.of(), null);
            ResourcePage.show(ctx.context, "style.css", "text/css");

            verify(ctx.response, never()).putHeader("Content-Type", "text/css");
            // The Page base class still emits its registry headers even for HEAD.
            verify(ctx.response).putHeader(eq("Nanopub-Registry-Status"), anyString());
        }
    }

    @Test
    void writesTheFullResourceBody() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/style.css");
            ResourcePage.show(ctx.context, "style.css", "text/css");

            // Sanity-check that we streamed the real file rather than an empty buffer.
            assertTrue(ctx.body().contains("{"), "the response looks like CSS");
        }
    }

}
