package com.knowledgepixels.registry;

import org.bson.Document;

public class MongoDbDocumentWrapper {

    // Inspired by https://www.mongodb.com/community/forums/t/cannot-store-java-enum-values-in-mongodb/99719/3
    // TODO discuss: Do we want Document<T> extends Document and have a specific Config with Writer and readers,
    // which gave us more Type Savety  @see: @RegistryDB :112

    private final Document document;

    public MongoDbDocumentWrapper(Document document) {
        this.document = document;
    }
    public Document getDocument() {
        return document;
    }
    public Document setStatus(EntryStatus status) {
        document.append("status", status.getValue());
        return document;
    }
}
