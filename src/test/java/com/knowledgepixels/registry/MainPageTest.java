package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.PageMocks;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MainPage}, the registry's landing page. It is the endpoint that
 * monitoring and peer registries poll, so both the JSON envelope and the HTML
 * rendering (including the "still loading" variants) are covered.
 */
class MainPageTest {

    private static final String SETTING_ID = "RAsettingArtifactCode1234567890";

    private static PageMocks.Db mockDbWithStatus(MockedStatic<RegistryDB> dbMock, String status) {
        List<Document> serverInfo = new ArrayList<>(PageMocks.DEFAULT_SERVER_INFO);
        serverInfo.removeIf(d -> "status".equals(d.getString("_id")));
        serverInfo.add(new Document("_id", "status").append("value", status));

        PageMocks.Db db = PageMocks.mockDb(dbMock, serverInfo);
        dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "original")).thenReturn(SETTING_ID);
        dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "current")).thenReturn(SETTING_ID);
        when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(3L);
        when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(7L);
        when(db.collection(Collection.NANOPUBS.toString()).countDocuments(db.session)).thenReturn(11L);
        when(db.collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(db.session)).thenReturn(2L);
        return db;
    }

    @Test
    void jsonServesTheRegistryInfoEnvelope() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.context("/.json");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("\"status\":\"ready\""), "status is reported");
            assertTrue(body.contains("\"setupId\":1"), "setupId is reported");
            assertTrue(body.contains("\"currentSetting\":\"" + SETTING_ID + "\""), "current setting is reported");
            assertTrue(body.contains("\"accountCount\":7"), "account count is reported");
            assertTrue(body.contains("\"registryVersion\""), "the registry version is reported");
            verify(ctx.response).putHeader("Content-Type", "application/json");
        }
    }

    @Test
    void htmlRendersServerSectionWhenReady() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "ready");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<h1>Nanopub Registry</h1>"), "renders the main heading");
            assertTrue(body.contains("<em>status:</em> ready"), "status is rendered");
            assertTrue(body.contains("<em>coverageTypes:</em> all"), "coverage defaults to all types");
            assertTrue(body.contains("<em>coverageAgents:</em> viaSetting"), "coverage defaults to viaSetting");
            // The fractional seconds are trimmed off the timestamp for readability.
            assertTrue(body.contains("<em>lastTrustStateUpdate:</em> 2026-04-15T12:00:00"), "timestamp is trimmed");
            assertFalse(body.contains("12:00:00.123"), "fractional seconds are not shown");
            assertTrue(body.contains("<em>trustStateHash:</em> abcdef1234"), "hash is truncated to 10 chars");
            assertTrue(body.contains("Count: 3"), "agent count is rendered");
            assertTrue(body.contains("Accounts: 7"), "account count is rendered");
            assertTrue(body.contains("Retained snapshots: 2"), "snapshot count is rendered");
            assertFalse(body.contains("(loading...)"), "nothing is marked as loading when ready");
        }
    }

    @Test
    void htmlHidesCountsWhileStillLoading() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            mockDbWithStatus(dbMock, "coreLoading");

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            String body = ctx.body();
            assertTrue(body.contains("<em>status:</em> coreLoading"), "status is rendered");
            assertTrue(body.contains("(loading...)"), "counts are replaced by a loading marker");
            assertFalse(body.contains("Accounts: 7"), "account count is withheld while loading");
        }
    }

    @Test
    void htmlFlagsTestInstances() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            List<Document> serverInfo = new ArrayList<>(PageMocks.DEFAULT_SERVER_INFO);
            serverInfo.removeIf(d -> "testInstance".equals(d.getString("_id")));
            serverInfo.add(new Document("_id", "testInstance").append("value", true));

            PageMocks.Db db = PageMocks.mockDb(dbMock, serverInfo);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "original")).thenReturn(SETTING_ID);
            dbMock.when(() -> RegistryDB.getValue(db.session, Collection.SETTING.toString(), "current")).thenReturn(SETTING_ID);
            when(db.collection(Collection.AGENTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.ACCOUNTS.toString()).countDocuments(db.session)).thenReturn(0L);
            when(db.collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).countDocuments(db.session)).thenReturn(0L);

            PageMocks.MockContext ctx = PageMocks.contextAccepting("/", "text/html");
            MainPage.show(ctx.context);

            assertTrue(ctx.body().contains("This is a test instance."), "test instances carry a visible warning");
        }
    }

    @Test
    void unsupportedExtensionIsRejected() {
        try (MockedStatic<RegistryDB> dbMock = mockStatic(RegistryDB.class)) {
            PageMocks.mockDb(dbMock);

            PageMocks.MockContext ctx = PageMocks.context("/.trig");
            MainPage.show(ctx.context);

            verify(ctx.response).setStatusCode(400);
        }
    }

}
