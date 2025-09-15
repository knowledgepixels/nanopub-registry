package com.knowledgepixels.registry;

import org.bson.Document;

/**
 * Wrapper for a MongoDB Entry
 * <p>
 * This allows us to have more control over some fields, which are just strings on the database.
 * <p>
 * Currently used for:
 * - status: can only take values of the @EntryStatus Enum.
 */
public class DbEntryWrapper {

    public static final String statusField = "status";

    private final Document document;

    public DbEntryWrapper(EntryStatus status) {
        this.document = new Document(statusField, status.getValue());
    }

    public DbEntryWrapper(Document document) {
        this.document = document;
    }

    public DbEntryWrapper(Document document, EntryStatus status) {
        this.document = document.append(statusField, status.getValue());
    }

    public Document getDocument() {
        return document;
    }

    public Document setStatus(EntryStatus status) {
        document.append(statusField, status.getValue());
        return document;
    }
}
