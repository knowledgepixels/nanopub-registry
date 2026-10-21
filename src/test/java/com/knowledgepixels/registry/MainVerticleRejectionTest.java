package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Covers how a refused POST is answered (issue #187): the reason has to reach the publisher in
 * the response <em>body</em>, since the reason phrase is dropped by HTTP/2 and by many clients,
 * and the status has to say what kind of refusal it is so a publishing client can tell "no
 * registry will take this" from "try the next registry".
 */
class MainVerticleRejectionTest {

    private String bodyOf(PageMocks.MockContext mc) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mc.response).end(body.capture());
        return body.getValue();
    }

    @Test
    void putsTheReasonInTheBodyAndKeepsItInTheReasonPhrase() {
        PageMocks.MockContext mc = PageMocks.context("/");

        MainVerticle.respondRejected(mc.context,
                new NanopubRejectedException(NanopubRejectedException.UNPROCESSABLE_CONTENT,
                        "timestamp 2026-10-21T16:00:00Z is more than 60s in the future"),
                "https://w3id.org/np/RARdG3Kb");

        HttpServerResponse response = mc.response;
        verify(response).setStatusCode(422);
        verify(response).putHeader("Content-Type", "text/plain; charset=utf-8");
        verify(response).setStatusMessage("Nanopub https://w3id.org/np/RARdG3Kb rejected: "
                + "timestamp 2026-10-21T16:00:00Z is more than 60s in the future");

        String body = bodyOf(mc);
        assertTrue(body.contains("is more than 60s in the future"), body);
        assertTrue(body.contains("https://w3id.org/np/RARdG3Kb"), body);
        assertTrue(body.endsWith("\n"), "the body should be a readable line: " + body);
    }

    @Test
    void usesTheStatusCarriedByTheRejection() {
        PageMocks.MockContext mc = PageMocks.context("/");

        MainVerticle.respondRejected(mc.context,
                new NanopubRejectedException(NanopubRejectedException.CONTENT_TOO_LARGE,
                        "triple count 1201 exceeds limit of 1200"),
                "https://w3id.org/np/RAabc");

        verify(mc.response).setStatusCode(413);
        assertEquals("Nanopub https://w3id.org/np/RAabc rejected: triple count 1201 exceeds limit of 1200\n",
                bodyOf(mc));
    }

    /**
     * A failure that is not a rejection is still answered, and still says something — but it must
     * not borrow a rejection's status, because that would tell a client to give up on a
     * nanopublication over a problem on our side.
     */
    @Test
    void fallsBackToBadRequestForAnythingElse() {
        PageMocks.MockContext mc = PageMocks.context("/");

        MainVerticle.respondRejected(mc.context, new RuntimeException("something else broke"), null);

        verify(mc.response).setStatusCode(400);
        assertEquals("Nanopub rejected: something else broke\n", bodyOf(mc));
    }

    @Test
    void survivesAFailureWithNoMessageAtAll() {
        PageMocks.MockContext mc = PageMocks.context("/");

        MainVerticle.respondRejected(mc.context, null, null);

        verify(mc.response).setStatusCode(400);
        assertEquals("Nanopub rejected: unknown error\n", bodyOf(mc));
    }

    /**
     * The reason phrase goes on the status line, so a newline in it would split the response.
     * Parser errors quote the offending input, which is exactly where a newline comes from.
     */
    @Test
    void keepsControlCharactersOffTheStatusLine() {
        String phrase = MainVerticle.toReasonPhrase("body is not a nanopublication:\r\nExpected ':', found '\t'");

        assertFalse(phrase.contains("\r"), phrase);
        assertFalse(phrase.contains("\n"), phrase);
        assertFalse(phrase.contains("\t"), phrase);
        assertEquals("body is not a nanopublication: Expected ':', found ' '", phrase);
    }

    @Test
    void truncatesAnOverlongReasonPhrase() {
        String phrase = MainVerticle.toReasonPhrase("x".repeat(500));

        assertEquals(200, phrase.length());
        assertTrue(phrase.endsWith("..."), phrase);
    }
}
