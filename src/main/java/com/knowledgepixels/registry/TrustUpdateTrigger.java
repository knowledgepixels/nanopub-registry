package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.nanopub.Nanopub;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.knowledgepixels.registry.RegistryDB.has;

/**
 * Pulls the next trust state update forward when trust-relevant data arrives,
 * instead of waiting out the full update interval.
 *
 * <p>
 * The trust state is recomputed on a fixed schedule
 * ({@link Task#UPDATE_INTERVAL_MS} after the end of the previous cycle), which
 * means a new approval, introduction or revocation waits up to that long before
 * it is published, even though the registry knew about it immediately. This
 * class shortens that wait: nanopubs arriving through the ingest channels are
 * classified as trust-relevant or not, and if any were, the queued
 * {@code UPDATE} task is moved earlier.
 *
 * <p>
 * A cycle costs two list fetches per account against every peer, so triggering
 * is bounded on both sides:
 * <ul>
 * <li><em>Floor</em>: an update is never pulled earlier than
 * {@link Task#UPDATE_MIN_INTERVAL_MS} after the end of the previous cycle,
 * recorded as {@code not-before-floor} on the queued task. Peer load therefore
 * stays bounded by the floor exactly as it is bounded by the interval today.
 * <li><em>Debounce</em>: arrivals set a single flag, and the trigger moves the
 * one queued {@code UPDATE} document rather than scheduling another task, so a
 * burst of arrivals collapses into one early update.
 * <li><em>Jitter</em>: up to {@link Task#UPDATE_TRIGGER_JITTER_MS} is added, so
 * that registries reacting to the same publication do not all recompute at the
 * same moment.
 * </ul>
 *
 * <p>
 * Getting the classification wrong is cheap in both directions: a missed
 * arrival just waits for the regular interval, and a false positive costs one
 * cycle that finds the trust state unchanged and publishes nothing.
 */
public class TrustUpdateTrigger {

    private TrustUpdateTrigger() {}

    private static final Logger logger = LoggerFactory.getLogger(TrustUpdateTrigger.class);

    /**
     * Set when trust-relevant data has arrived and no early update has been
     * applied for it yet. Written from the loader threads, read and cleared on
     * the task thread.
     */
    private static final AtomicBoolean pending = new AtomicBoolean(false);

    /**
     * Records the arrival of a nanopub from outside this registry, marking an
     * early update as pending if it can change the trust state.
     *
     * <p>
     * Only called from the ingest channels (peer sync, the legacy connector and
     * the POST endpoint), never from the trust cycle's own loading: a cycle
     * re-fetches the intro and endorsement lists of every account, and the new
     * nanopubs it stores are ones it is in the middle of processing anyway, so
     * triggering on those would systematically schedule a follow-up cycle that
     * has nothing left to do.
     *
     * @param mongoSession the MongoDB client session
     * @param np the arriving nanopub, before it is stored
     */
    public static void noteIncoming(ClientSession mongoSession, Nanopub np) {
        if (pending.get()) {
            // Already triggered; nothing a further arrival could add before the next cycle.
            return;
        }
        // Classification is in-memory and rules out almost every arrival, so it goes before the
        // lookup that asks whether we have this one already.
        if (!isTrustRelevant(mongoSession, np) || !isNew(mongoSession, np)) {
            return;
        }
        if (pending.compareAndSet(false, true)) {
            logger.info("Trust-relevant nanopub {} arrived; requesting an early trust state update", np.getUri());
        }
    }

    /**
     * Applies a pending early update, if there is one, by moving the queued
     * {@code UPDATE} task forward.
     *
     * <p>
     * Called from {@link Task#CHECK_NEW}, which runs on the task thread between
     * loading rounds. While a cycle is running there is no queued {@code UPDATE}
     * to move — it was consumed when the cycle started — so the request is kept
     * and applied on a later round instead of being dropped.
     *
     * @param mongoSession the MongoDB client session
     */
    public static void applyIfPending(ClientSession mongoSession) {
        if (!pending.compareAndSet(true, false)) {
            return;
        }
        if (!Task.pullUpdateForward(mongoSession)) {
            // No UPDATE is queued, so a cycle is in progress: keep the request for the next round.
            logger.debug("No queued UPDATE to pull forward; keeping the early update request");
            pending.set(true);
        }
    }

    /**
     * Whether this registry does not have the nanopub yet. Arrivals that are
     * already stored carry no new information, and the ingest channels re-offer
     * known nanopubs routinely.
     */
    private static boolean isNew(ClientSession mongoSession, Nanopub np) {
        String ac = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
        return ac != null && !has(mongoSession, Collection.NANOPUBS.toString(), ac);
    }

    /**
     * Whether this nanopub can change the trust state: an introduction, an
     * endorsement, or something that invalidates a nanopub currently sourcing a
     * trust edge (a retracted approval, typically).
     *
     * <p>
     * These are exactly the inputs the cycle consumes:
     * {@link Task#LOAD_CORE} extracts endorsements by {@code npx:approvesOf},
     * and {@link Task#LOAD_DECLARATIONS} reads key declarations out of agent
     * introductions.
     */
    static boolean isTrustRelevant(ClientSession mongoSession, Nanopub np) {
        for (Statement st : np.getAssertion()) {
            IRI p = st.getPredicate();
            if (p.equals(Utils.APPROVES_OF) || p.equals(NPX.INTRODUCES) || p.equals(NPX.DECLARED_BY)) {
                return true;
            }
        }
        for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(np)) {
            String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
            if (invalidatedAc == null) {
                continue;
            }
            if (has(mongoSession, "trustEdges", new Document("source", invalidatedAc))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops a pending request. For tests, which share one JVM across cases.
     */
    static void reset() {
        pending.set(false);
    }

    /**
     * Whether an early update is currently requested. For tests.
     */
    static boolean isPending() {
        return pending.get();
    }

}
