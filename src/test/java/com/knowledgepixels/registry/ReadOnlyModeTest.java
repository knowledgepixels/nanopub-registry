package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.PageMocks;
import com.knowledgepixels.registry.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class ReadOnlyModeTest {

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() {
        fakeEnv = TestUtils.setupFakeEnv();
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
    }

    @Test
    void disabledWithoutTheVariable() {
        fakeEnv.build();
        assertFalse(ReadOnlyMode.isEnabled());
        assertNull(ReadOnlyMode.sparqlUrl());
    }

    @Test
    void blankDoesNotEnable() {
        fakeEnv.addVariable(ReadOnlyMode.ENV, "  ").build();
        assertFalse(ReadOnlyMode.isEnabled());
    }

    @Test
    void enabledWithAnEndpoint() {
        fakeEnv.addVariable(ReadOnlyMode.ENV, "http://rete:7878/sparql").build();
        assertTrue(ReadOnlyMode.isEnabled());
        assertEquals("http://rete:7878/sparql", ReadOnlyMode.sparqlUrl());
    }

    @Test
    void postIsAnsweredWith405AndAReason() {
        PageMocks.MockContext ctx = PageMocks.context("/");
        MainVerticle.respondReadOnly(ctx.context);
        verify(ctx.response).setStatusCode(405);
        verify(ctx.response).putHeader("Allow", "GET, HEAD");
        verify(ctx.response).end(org.mockito.ArgumentMatchers.contains("read-only"));
    }

}
