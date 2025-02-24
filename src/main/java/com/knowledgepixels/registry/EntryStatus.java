package com.knowledgepixels.registry;

/**
 * The status field of several Documents, especially:
 * endorsements-loading, intro-lists, and accounts-loading.
 *
 * The states of the different Document Types are not dependant of each other.
 */
public enum EntryStatus {
    /** endorsements_loading, */
    to_retrieve {

    @Override
    public String toString () {
    return "to-retrieve"; // cannot name the enum with a dash
    }
    },
    /** accounts-loading */
    seen,
    /** endorsements_loading, */
    discarded,
    /** endorsements_loading, */
    retrieved,
    /** accounts-loading */
    visited,
    /** accounts-loading */
    expanded,
    /** accounts-loading */
    skipped,
    /** Pub-key, intro_type_hash,  */
    encountered,
    /** intro-list, endorse-list */
    loading,
    /** accounts-loading */
    toLoad,
    /** accountId, account-loading, intro-list, endorse-list */
    loaded,
    /** accounts-loading, agent,  */
    processed,
    /** accounts-loading */
    aggregated,
    /** accounts-loading */
    approved,
    /** accounts-loading */
    contested
}
