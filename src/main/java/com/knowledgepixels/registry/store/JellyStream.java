package com.knowledgepixels.registry.store;

import org.nanopub.jelly.NanopubStream;

/**
 * A Jelly nanopub stream together with the resource that feeds it (a database cursor,
 * a query result), which stays open until the stream has been written out.
 *
 * @param stream   the nanopub stream to write to the response
 * @param resource what to release afterwards; may be null
 */
public record JellyStream(NanopubStream stream, AutoCloseable resource) implements AutoCloseable {

    @Override
    public void close() {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to close Jelly stream resource", ex);
        }
    }

}
