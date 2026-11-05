package com.knowledgepixels.registry.store;

import java.util.Iterator;

/**
 * An iterator over a result set that may hold a resource (a database cursor, a
 * connection) until it is closed. Callers iterate it in a try-with-resources block.
 *
 * @param <T> the element type
 */
public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {

    @Override
    void close();

    /**
     * Wraps a plain iterator whose close is a no-op.
     */
    static <T> CloseableIterator<T> of(Iterator<T> iterator) {
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public T next() {
                return iterator.next();
            }

            @Override
            public void close() {
            }
        };
    }

}
