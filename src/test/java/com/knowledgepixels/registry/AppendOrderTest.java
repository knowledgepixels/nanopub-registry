package com.knowledgepixels.registry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ticket order of {@link AppendOrder}: jobs may run and verify in any order, but the part
 * after {@code awaitTurn()} happens in ticket order, and a job that ends without ever waiting
 * must not hold the others up.
 */
class AppendOrderTest {

    private static void runAll(AppendOrder order, int jobs, int threads, JobBody body) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                long ticket = order.issue();
                futures.add(executor.submit(() -> order.run(ticket, () -> body.run(ticket))));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (!(e.getCause() instanceof IllegalStateException)) {
                        throw e;
                    }
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private interface JobBody {
        void run(long ticket);
    }

    @Test
    void appendsHappenInTicketOrderWhateverTheWorkersDoBefore() throws Exception {
        AppendOrder order = new AppendOrder();
        List<Long> appended = Collections.synchronizedList(new ArrayList<>());
        List<Long> verified = Collections.synchronizedList(new ArrayList<>());
        int jobs = 200;

        runAll(order, jobs, 8, ticket -> {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            verified.add(ticket);
            AppendOrder.awaitTurn();
            appended.add(ticket);
        });

        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < jobs; i++) {
            expected.add(i);
        }
        assertEquals(expected, appended, "the appends follow the tickets");
        assertEquals(jobs, verified.size());
        assertTrue(!verified.equals(expected) || jobs < 50, "the work before the turn runs in parallel and does get reordered");
    }

    @Test
    void aJobThatNeverWaitsPassesItsTurnOn() throws Exception {
        AppendOrder order = new AppendOrder();
        List<Long> appended = Collections.synchronizedList(new ArrayList<>());

        runAll(order, 50, 4, ticket -> {
            if (ticket % 3 == 0) {
                return; // skipped or rejected nanopub: ends without appending
            }
            AppendOrder.awaitTurn();
            appended.add(ticket);
        });

        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < 50; i++) {
            if (i % 3 != 0) {
                expected.add(i);
            }
        }
        assertEquals(expected, appended);
    }

    @Test
    void aFailingJobPassesItsTurnOn() throws Exception {
        AppendOrder order = new AppendOrder();
        AtomicInteger appended = new AtomicInteger();

        runAll(order, 20, 4, ticket -> {
            if (ticket == 7) {
                throw new IllegalStateException("worker failed before its turn");
            }
            AppendOrder.awaitTurn();
            appended.incrementAndGet();
        });

        assertEquals(19, appended.get(), "the jobs after the failed one still get their turn");
    }

    @Test
    void awaitTurnIsANoOpWithoutATicket() {
        AppendOrder.awaitTurn(); // must return at once on a thread that runs no ticketed job
    }

}
