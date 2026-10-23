package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

import static com.knowledgepixels.registry.RegistryDB.collection;

/**
 * The shared, time-bound allowance for publishing by pubkeys the trust network has not
 * approved (yet).
 *
 * <p>
 * Quota enforcement on its own would close the door new agents come through: becoming
 * approved means getting an introduction endorsed, and an introduction that no registry
 * accepts cannot be endorsed. This budget keeps that door open in the ordinary case —
 * a new agent publishes a handful of nanopublications before anyone has approved them —
 * while bounding what the door is worth to a flooder.
 *
 * <p>
 * The allowance is <em>one budget for the whole registry</em>, not one per pubkey: pubkeys
 * are free to make, so a per-pubkey allowance would bound nothing at all. The cost of that
 * choice is that a flood can spend the budget and leave honest newcomers waiting; they are
 * not stuck, because an introduction endorsed by an approved agent is accepted whatever the
 * budget says (see {@link PublishPolicy}).
 *
 * <p>
 * The window is fixed, not sliding: the first publication after a window has run out starts
 * a new one. Only accepted publications are counted — a refusal that was already over the
 * limit must not extend the window, or a flooder could hold the budget shut indefinitely by
 * doing nothing but getting refused.
 */
public final class UnapprovedPublishBudget {

    private static final Logger logger = LoggerFactory.getLogger(UnapprovedPublishBudget.class);

    /**
     * The collection holding the one counter document.
     */
    static final String COLLECTION = "publishBudget";

    /**
     * The id of the counter document, which is the only one in the collection.
     */
    static final String DOC_ID = "unapproved";

    private UnapprovedPublishBudget() {
    }

    /**
     * How many nanopublications all unapproved pubkeys together may publish per window.
     */
    private static int quota() {
        return Integer.parseInt(Utils.getEnv("REGISTRY_UNAPPROVED_PUBLISH_QUOTA", "100"));
    }

    /**
     * The length of one window, in seconds.
     */
    private static long windowSeconds() {
        return Long.parseLong(Utils.getEnv("REGISTRY_UNAPPROVED_PUBLISH_WINDOW", "3600"));
    }

    /**
     * The outcome of asking for one publication out of the budget.
     *
     * @param granted           whether the publication may go ahead
     * @param retryAfterSeconds how long until the window resets, when it was not granted
     */
    public record Decision(boolean granted, long retryAfterSeconds) {
    }

    /**
     * Takes one publication out of the budget, if there is room in the current window.
     *
     * <p>
     * The read, the window roll-over and the increment are one update, so concurrent POSTs
     * cannot both take the last slot: the counter is incremented only while it is below the
     * limit, and the document says which of the two happened.
     *
     * @param mongoSession the MongoDB client session
     * @return whether the publication may go ahead, and when to come back if not
     */
    public static Decision tryConsume(ClientSession mongoSession) {
        int quota = quota();
        long windowSeconds = windowSeconds();
        if (quota <= 0) {
            // Unapproved publishing is switched off rather than rationed, so no window will ever
            // open and there is nothing to count. Reported with no retry time, which is what
            // tells the caller to refuse outright instead of asking for patience.
            logger.debug("Unapproved publishing is disabled (REGISTRY_UNAPPROVED_PUBLISH_QUOTA={})", quota);
            return new Decision(false, 0);
        }
        Date now = new Date();
        Date windowCutoff = new Date(now.getTime() - windowSeconds * 1000);

        // Everything below is evaluated against the document as it was before this update, so
        // all three fields see the same window and count, and a missing document reads as an
        // expired window (and therefore starts a fresh one).
        Document windowStart = new Document("$ifNull", List.of("$windowStart", new Date(0)));
        Document expired = new Document("$lt", List.of(windowStart, windowCutoff));
        Document count = new Document("$ifNull", List.of("$count", 0));
        Document hasRoom = new Document("$lt", List.of(count, quota));
        // An expired window is replaced by a fresh one holding this publication, so the room in
        // it is not the old count's business; a live window has room while it is under the quota.
        Document granted = new Document("$cond", List.of(expired, true, hasRoom));

        Document update = new Document("$set", new Document()
                .append("windowStart", new Document("$cond", List.of(expired, now, windowStart)))
                .append("count", new Document("$cond", List.of(expired, 1,
                        new Document("$cond", List.of(hasRoom, new Document("$add", List.of(count, 1)), count)))))
                .append("granted", granted));

        Document result = collection(COLLECTION).findOneAndUpdate(mongoSession,
                new Document("_id", DOC_ID), List.of(update),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

        if (result == null || !Boolean.TRUE.equals(result.getBoolean("granted"))) {
            Date start = (result == null) ? now : result.getDate("windowStart");
            long retryAfter = Math.max(1, windowSeconds - (now.getTime() - start.getTime()) / 1000);
            logger.info("Unapproved publishing budget spent ({} per {}s); next window in {}s",
                    quota, windowSeconds, retryAfter);
            return new Decision(false, retryAfter);
        }
        logger.debug("Unapproved publishing budget: {} of {} used in the current window",
                result.getInteger("count"), quota);
        return new Decision(true, 0);
    }

    /**
     * Returns how much of the budget the current window has used, for the status page and
     * for tests. A window that has already run out reads as unused, which is what the next
     * publication would find.
     *
     * @param mongoSession the MongoDB client session
     * @return the number of publications counted in the current window
     */
    public static int used(ClientSession mongoSession) {
        Document d = collection(COLLECTION).find(mongoSession, new Document("_id", DOC_ID)).first();
        if (d == null || d.getDate("windowStart") == null) {
            return 0;
        }
        long age = (System.currentTimeMillis() - d.getDate("windowStart").getTime()) / 1000;
        return (age >= windowSeconds()) ? 0 : d.getInteger("count", 0);
    }

}
