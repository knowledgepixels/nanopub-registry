package com.knowledgepixels.registry;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for a MongoDB Entry
 * <p>
 * This allows us to have more control over some fields, which are just strings on the database.
 * <p>
 * Currently used for:
 * - status: can only take values of the @EntryStatus Enum.
 */
public class DbEntryWrapper {

    /**
     * Field name for the status of the entry.
     */
    public static final String statusField = "status";

    private static final Logger logger = LoggerFactory.getLogger(DbEntryWrapper.class);

    private final Document document;

    /**
     * Constructor for DbEntryWrapper with only status.
     *
     * @param status The status of the entry.
     */
    public DbEntryWrapper(EntryStatus status) {
        this.document = new Document(statusField, status.getValue());
        logger.debug("Created DbEntryWrapper with status='{}'", status.getValue());
    }

    /**
     * Constructor for DbEntryWrapper with existing document.
     *
     * @param document The MongoDB document.
     */
    public DbEntryWrapper(Document document) {
        this.document = document;
    }

    /**
     * Constructor for DbEntryWrapper with existing document and status.
     *
     * @param document The MongoDB document.
     * @param status   The status of the entry.
     */
    public DbEntryWrapper(Document document, EntryStatus status) {
        this.document = document.append(statusField, status.getValue());
    }

    /**
     * Get the underlying MongoDB document.
     *
     * @return The MongoDB document.
     */
    public Document getDocument() {
        return document;
    }

    /**
     * Set the status of the entry.
     *
     * @param status The new status of the entry.
     */
    public void setStatus(EntryStatus status) {
        document.append(statusField, status.getValue());
    }

}
