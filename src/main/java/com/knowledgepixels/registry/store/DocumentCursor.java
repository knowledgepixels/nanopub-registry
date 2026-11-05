package com.knowledgepixels.registry.store;

import com.mongodb.ServerAddress;
import com.mongodb.ServerCursor;
import com.mongodb.client.MongoCursor;
import org.bson.Document;

import java.util.Iterator;

/**
 * Presents any iterator of documents as a {@link MongoCursor}, which is the only input
 * {@code NanopubStream.fromMongoCursor} and {@code fromMongoCursorWithCounter} accept.
 */
public final class DocumentCursor implements MongoCursor<Document> {

    private final Iterator<Document> it;
    private final Runnable onClose;

    public DocumentCursor(Iterator<Document> it) {
        this(it, () -> {
        });
    }

    public DocumentCursor(Iterator<Document> it, Runnable onClose) {
        this.it = it;
        this.onClose = onClose;
    }

    @Override
    public void close() {
        onClose.run();
    }

    @Override
    public boolean hasNext() {
        return it.hasNext();
    }

    @Override
    public Document next() {
        return it.next();
    }

    @Override
    public int available() {
        return 0;
    }

    @Override
    public Document tryNext() {
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public ServerCursor getServerCursor() {
        return null;
    }

    @Override
    public ServerAddress getServerAddress() {
        return null;
    }

}
