package com.knowledgepixels.registry;

/**
 * Thrown when a nanopublication is refused, carrying the reason together with the HTTP
 * status that says what <em>kind</em> of refusal it is.
 * <p>
 * The status is part of the answer, not decoration: a publishing client acts on it.
 * nanopub-java (as of Nanopublication/nanopub-java#177) treats 400, 409, 413, 415 and 422
 * as a permanent rejection of that nanopublication, retries 408, 429 and 5xx with backoff,
 * and moves on to the next registry on 401, 403, 404 and 405. So the distinction to keep is
 * whether a nanopublication breaks a rule that every registry applies, or only one that
 * this instance applies:
 * <ul>
 *   <li>{@link #UNPROCESSABLE_CONTENT} and {@link #CONTENT_TOO_LARGE} for the content rules
 *       (triple and byte limits, a creation time in the future, a graph URI outside the
 *       nanopublication, an artifact code that does not verify) — no registry would take it,
 *       so the client should stop offering it.</li>
 *   <li>{@link #FORBIDDEN} for what only this instance refuses (uncovered types, an
 *       unauthorized pubkey, an exhausted quota, a protected nanopublication on a public
 *       instance) — another registry may well accept it, so the client should try one.</li>
 *   <li>{@link #TOO_MANY_REQUESTS} for what this instance refuses <em>for now</em>: the
 *       shared budget for publishing by not-yet-approved pubkeys is spent for this window.
 *       The same nanopublication offered later is accepted, so the client should wait out
 *       the {@code Retry-After} it carries rather than give up or move on.</li>
 * </ul>
 */
public class NanopubRejectedException extends RuntimeException {

    /**
     * The request itself could not be understood — a body that is not a nanopublication.
     */
    public static final int BAD_REQUEST = 400;

    /**
     * This registry will not take this nanopublication, but another one might.
     */
    public static final int FORBIDDEN = 403;

    /**
     * This registry will not take this nanopublication <em>yet</em>, but will later.
     */
    public static final int TOO_MANY_REQUESTS = 429;

    /**
     * The nanopublication is over a size limit that every registry enforces.
     */
    public static final int CONTENT_TOO_LARGE = 413;

    /**
     * The nanopublication is well-formed RDF but breaks a content rule that every registry
     * enforces.
     */
    public static final int UNPROCESSABLE_CONTENT = 422;

    private final int statusCode;
    private final long retryAfterSeconds;

    /**
     * Constructs a new NanopubRejectedException.
     *
     * @param statusCode the HTTP status expressing the kind of refusal
     * @param message    the reason, as a standalone phrase without the nanopub URI, which
     *                   the caller adds where it has one
     */
    public NanopubRejectedException(int statusCode, String message) {
        this(statusCode, message, 0);
    }

    /**
     * Constructs a new NanopubRejectedException that tells the publisher when to come back.
     *
     * @param statusCode        the HTTP status expressing the kind of refusal
     * @param message           the reason, as a standalone phrase without the nanopub URI,
     *                          which the caller adds where it has one
     * @param retryAfterSeconds how long until the same nanopublication would be accepted,
     *                          sent as {@code Retry-After}; 0 for a refusal that waiting
     *                          out does not help
     */
    public NanopubRejectedException(int statusCode, String message, long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the HTTP status expressing what kind of refusal this is.
     *
     * @return the HTTP status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns how long until this nanopublication would be accepted.
     *
     * @return the seconds to put in {@code Retry-After}, or 0 if waiting does not help
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

}
