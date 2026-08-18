package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DebugPage}, the plain-text introspection endpoints under {@code /debug/}.
 */
class DebugPageTest {

    @Test
    void trustPathsRendersArrowSeparatedPaths() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll("trustPaths", List.of(
                    new Document("_id", "$ pk1 pk2").append("type", "full"),
                    new Document("_id", "$ pk1 pk3").append("type", "extended")));

            PageMocks.MockContext ctx = PageMocks.context("/debug/trustPaths");
            DebugPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("$ > pk1 > pk2"), "path segments are arrow-separated");
            // Extended paths mark their last hop with "~" to show it is inferred, not endorsed.
            assertTrue(body.contains("$ > pk1 ~ pk3"), "the last hop of an extended path is marked with ~");
            verify(ctx.response).putHeader("Content-Type", "text/plain");
        }
    }

    @Test
    void trustPathsServesArchivedSnapshotWhenCounterGiven() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubGetOne("debug_trustPaths", new Document("trustStateCounter", 7L),
                    new Document("trustStateTxt", "$ > pk1\n"));

            PageMocks.MockContext ctx = PageMocks.context("/debug/trustPaths", Map.of("trustStateCounter", "7"));
            DebugPage.show(ctx.context);

            assertEquals("$ > pk1\n", ctx.body(), "the archived text is served verbatim");
        }
    }

    @Test
    void endorsementsListsOneLinePerEndorsement() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll("endorsements", List.of(
                    new Document("agent", "agentA").append("pubkey", "pk1")
                            .append("endorsedNanopub", "RAxyz").append("source", "RAsrc").append("status", "retrieved")));

            PageMocks.MockContext ctx = PageMocks.context("/debug/endorsements");
            DebugPage.show(ctx.context);

            assertEquals("agentA>pk1 RAxyz RAsrc (retrieved)\n", ctx.body());
            verify(ctx.response).putHeader("Content-Type", "text/plain");
        }
    }

    @Test
    void accountsListsOneLinePerAccount() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll("accounts", List.of(
                    new Document("agent", "agentA").append("pubkey", "pk1").append("depth", 2).append("status", "loaded")));

            PageMocks.MockContext ctx = PageMocks.context("/debug/accounts");
            DebugPage.show(ctx.context);

            assertEquals("agentA>pk1 2 (loaded)\n", ctx.body());
        }
    }

    @Test
    void tasksReportsQueueAndIdleRunner() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            db.stubFindAll(Collection.TASKS.toString(), List.of(
                    new Document("action", "LOAD_ALL").append("not-before", 0L)));

            PageMocks.MockContext ctx = PageMocks.context("/debug/tasks");
            DebugPage.show(ctx.context);

            String body = ctx.body();
            // No task runner is active in a unit test, so the "none" branch is what we see.
            assertTrue(body.contains("Currently running: (none)"), "an idle runner is reported as none");
            assertTrue(body.contains("LOAD_ALL"), "queued tasks are dumped as JSON");
            assertTrue(body.contains("Total queued tasks: 1"), "the queue length is reported");
        }
    }

    @Test
    void tasksReportsErrorInsteadOfFailing() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            when(db.collection(Collection.TASKS.toString()).find(db.session))
                    .thenThrow(new IllegalStateException("mongo is down"));

            PageMocks.MockContext ctx = PageMocks.context("/debug/tasks");
            DebugPage.show(ctx.context);

            // A debug endpoint should degrade to an error line rather than blowing up the request.
            assertTrue(ctx.body().contains("Error: java.lang.IllegalStateException: mongo is down"));
        }
    }

    @Test
    void peerStateDumpsDocumentsWithCount() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            when(db.collection(Collection.PEER_STATE.toString()).countDocuments(db.session)).thenReturn(1L);
            db.stubFindAll(Collection.PEER_STATE.toString(), List.of(
                    new Document("_id", "https://peer.example.org/").append("setupId", 42L)));

            PageMocks.MockContext ctx = PageMocks.context("/debug/peerState");
            DebugPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("peerState documents: 1"), "the document count is reported");
            assertTrue(body.contains("https://peer.example.org/"), "each peer state is dumped");
        }
    }

    @Test
    void peerStateReportsErrorInsteadOfFailing() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.Db db = PageMocks.mockDb(dbMock);
            when(db.collection(Collection.PEER_STATE.toString()).countDocuments(db.session))
                    .thenThrow(new IllegalStateException("mongo is down"));

            PageMocks.MockContext ctx = PageMocks.context("/debug/peerState");
            DebugPage.show(ctx.context);

            assertTrue(ctx.body().contains("Error: java.lang.IllegalStateException: mongo is down"));
        }
    }

    @Test
    void unknownDebugPathIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/debug/nope");
            DebugPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

}
