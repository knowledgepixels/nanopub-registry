package com.knowledgepixels.registry;

import org.bson.codecs.pojo.annotations.BsonProperty;

/**
 * The status field of several Documents, especially:
 * endorsements-loading, intro-lists, and accounts-loading.
 * <p>
 * The states of the different Document Types are not dependant of each other.
 * <p>
 * We decided to break with Java Conventions and have lowercase Enum Values,
 * which directly represent the string in the MongoDB for aesthetic reasons.
 */
public enum EntryStatus {

    /**
     * endorsements_loading,
     */
    toRetrieve, // WARNING: Breaking Change! it was "to-retrieve" before
    // We may avoid the change by adding an annotation
    // @BsonProperty(value = "to-retrieve") or having a custom toString()
    /**
     * accounts-loading
     */
    seen,
    /**
     * endorsements_loading,
     */
    discarded,
    /**
     * endorsements_loading,
     */
    retrieved,
    /**
     * accounts-loading
     */
    visited,
    /**
     * accounts-loading
     */
    expanded,
    /**
     * accounts-loading
     */
    skipped,
    /**
     * Pub-key, intro_type_hash,
     */
    encountered,
    /**
     * intro-list, endorse-list
     */
    loading,
    /**
     * accounts-loading
     */
    toLoad,
    /**
     * accountId, account-loading, intro-list, endorse-list
     */
    loaded,
    /**
     * accounts-loading, agent,
     */
    processed,
    /**
     * accounts-loading
     */
    aggregated,
    /**
     * accounts-loading
     */
    approved,
    /**
     * accounts-loading
     */
    contested;

    // The code is inspired by: https://www.mongodb.com/community/forums/t/cannot-store-java-enum-values-in-mongodb/99719/3
    // It's not really necessary right now, since we call getValue by hand,
    // we may also just call toString()...
    @BsonProperty(value = "status")
    final String status;

    EntryStatus() {
        this.status = this.toString();
    }

    public static EntryStatus fromValue(String value) {
        for (EntryStatus e : EntryStatus.values()) {
            if (e.toString().equals(value)) {
                return e;
            }
        }
        throw new UnsupportedEntryStatusValueException("Unsupported EntryStatus Value" + value);
    }

    public String getValue() {
        return this.status;
    }
}
