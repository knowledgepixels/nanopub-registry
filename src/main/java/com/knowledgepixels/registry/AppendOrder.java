package com.knowledgepixels.registry;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the list appends of a parallel load in the order of the stream.
 * <p>
 * The parallel loader verifies and stores nanopubs on several workers at once, and the order in
 * which the workers reach the list appends is up to the scheduler. Left alone, the lists of a
 * registry would hold the nanopubs of a peer in a shuffled order, and the checksums at the
 * geometric fallback positions used in peer sync would almost never match between registries
 * (see {@code RegistryDB.buildChecksumFallbacks}). The last checksum of a list matches in any
 * order, since the XOR is order-independent, but a peer that is behind only profits from the
 * fallbacks if the prefix sets agree, and that takes the same order.
 * <p>
 * The loader therefore issues a ticket per nanopub in stream order and runs each worker's job
 * under its ticket. The expensive part of loading, signature and hash verification and the
 * serialization and storage of the nanopub itself, runs in parallel as before. When the job
 * reaches the list appends it calls {@link #awaitTurn()}, which blocks until every earlier
 * ticket is finished. Appends therefore happen one at a time in stream order, which also
 * removes the contention between workers on the same list. A job that never reaches the
 * appends, because the nanopub is skipped or rejected or the worker fails, passes its turn on
 * when it ends.
 * <p>
 * So that the ordered part stays cheap, the appends are not written one at a time: the
 * {@link ListAppendBatch} of the load computes positions and checksums in memory and writes
 * the entries in batches. The loader flushes what is left when the stream is done.
 * <p>
 * Outside a parallel load, that is on a thread that runs no ticketed job, {@link #awaitTurn()}
 * is a no-op and {@link #currentBatch()} is null; the POST handler and the sequential loaders
 * write their entries directly.
 */
final class AppendOrder {

    private record Turn(AppendOrder order, long ticket) {
    }

    private static final ThreadLocal<Turn> CURRENT = new ThreadLocal<>();

    /** The ticket whose turn it is; every ticket below it has finished. */
    private long next = 0;
    private long issued = 0;
    /** Tickets that finished before their turn came, to be skipped when it does. */
    private final Set<Long> finishedEarly = new HashSet<>();
    private final ListAppendBatch batch = new ListAppendBatch();

    /** The pending list appends of this load. */
    ListAppendBatch batch() {
        return batch;
    }

    /** The batch of the parallel load the current thread is working for, or null. */
    static ListAppendBatch currentBatch() {
        Turn turn = CURRENT.get();
        return (turn == null) ? null : turn.order().batch;
    }

    /** Hands out the next ticket; the caller runs its job with {@link #run}. */
    synchronized long issue() {
        return issued++;
    }

    /**
     * Runs a job under the given ticket on the current thread and passes the turn on when it
     * ends, however it ends.
     */
    void run(long ticket, Runnable job) {
        CURRENT.set(new Turn(this, ticket));
        try {
            job.run();
        } finally {
            CURRENT.remove();
            finish(ticket);
        }
    }

    /**
     * Blocks until it is the current job's turn, i.e. every job with an earlier ticket has
     * ended. The turn is kept until the job ends. A no-op on a thread without a ticket.
     */
    static void awaitTurn() {
        Turn turn = CURRENT.get();
        if (turn != null) {
            turn.order().await(turn.ticket());
        }
    }

    private synchronized void await(long ticket) {
        while (next != ticket) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for the turn to append to the lists", e);
            }
        }
    }

    synchronized void finish(long ticket) {
        finishedEarly.add(ticket);
        while (finishedEarly.remove(next)) {
            next++;
        }
        notifyAll();
    }

}
