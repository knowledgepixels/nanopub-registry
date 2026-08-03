package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Task}'s transaction-cleanup helpers. Both retry until they succeed,
 * because leaving a transaction open would wedge the task runner's shared session.
 * <p>
 * Each test runs inside a {@code mockStatic(RegistryDB.class)} scope: Task's static
 * initialiser resolves the tasks collection eagerly, which would otherwise require a
 * live database just to load the class.
 */
class TaskTransactionTest {

    @Test
    void abortTransactionDoesNothingWithoutAnActiveTransaction() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession session = mock(ClientSession.class);
            when(session.hasActiveTransaction()).thenReturn(false);

            Task.abortTransaction(session, "nothing to do");

            verify(session, never()).abortTransaction();
        }
    }

    @Test
    void abortTransactionAbortsAnActiveTransaction() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession session = mock(ClientSession.class);
            when(session.hasActiveTransaction()).thenReturn(true);

            Task.abortTransaction(session, "task failed");

            verify(session).abortTransaction();
        }
    }

    @Test
    void abortTransactionRetriesUntilItSucceeds() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession session = mock(ClientSession.class);
            when(session.hasActiveTransaction()).thenReturn(true);
            doThrow(new IllegalStateException("transient mongo error")).doNothing().when(session).abortTransaction();

            Task.abortTransaction(session, "task failed");

            verify(session, times(2)).abortTransaction();
        }
    }

    @Test
    void cleanTransactionWithRetryAbortsALingeringTransaction() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession session = mock(ClientSession.class);
            when(session.hasActiveTransaction()).thenReturn(true);
            doNothing().when(session).abortTransaction();

            Task.cleanTransactionWithRetry(session);

            verify(session).abortTransaction();
        }
    }

    @Test
    void cleanTransactionWithRetryRetriesUntilItSucceeds() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            ClientSession session = mock(ClientSession.class);
            when(session.hasActiveTransaction()).thenReturn(true);
            doThrow(new IllegalStateException("transient mongo error")).doNothing().when(session).abortTransaction();

            Task.cleanTransactionWithRetry(session);

            verify(session, times(2)).abortTransaction();
        }
    }

    @Test
    void tasksRunInATransactionUnlessTheyStreamFromPeers() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            // Long-running streaming fetches would blow MongoDB's transaction timeout,
            // so exactly these two opt out; every other task must stay transactional.
            assertFalse(Task.LOAD_FULL.runAsTransaction(), "LOAD_FULL streams from peers");
            assertFalse(Task.CHECK_NEW.runAsTransaction(), "CHECK_NEW streams from peers");
            for (Task task : Task.values()) {
                if (task == Task.LOAD_FULL || task == Task.CHECK_NEW) {
                    continue;
                }
                assertTrue(task.runAsTransaction(), task.name() + " runs as a transaction");
            }
        }
    }

    @Test
    void asDocumentProducesAQueueEntryThatIsDueImmediately() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            long before = System.currentTimeMillis();
            Document doc = Task.INIT_DB.asDocument();

            assertEquals("INIT_DB", doc.getString("action"), "the action names the task to run");
            // The runner only picks up entries whose "not-before" is already in the past.
            assertTrue(doc.getLong("not-before") >= before);
            assertTrue(doc.getLong("not-before") <= System.currentTimeMillis());
        }
    }

    @Test
    void noTaskIsReportedAsRunningWhenIdle() {
        try (MockedStatic<RegistryDB> ignored = mockStatic(RegistryDB.class)) {
            // The task runner clears the name in a finally block, so an idle runner reports null.
            assertNull(Task.getCurrentTaskName());
        }
    }

}
