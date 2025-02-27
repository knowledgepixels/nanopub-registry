package com.knowledgepixels.registry;

import org.bson.Document;

public class DocumentWithStatusWrapper {

    public static final String statusField = "status";

    private final Document document;

    public DocumentWithStatusWrapper(EntryStatus status) {
        this.document = new Document(statusField, status.getValue());
    }
    public DocumentWithStatusWrapper(Document document) {
        this.document = document;
    }
    public DocumentWithStatusWrapper(Document document, EntryStatus status) {
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
