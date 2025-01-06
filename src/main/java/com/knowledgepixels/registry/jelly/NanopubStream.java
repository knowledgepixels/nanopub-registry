package com.knowledgepixels.registry.jelly;

import com.mongodb.client.MongoCursor;
import eu.ostrzyciel.jelly.core.ProtoTranscoder;
import eu.ostrzyciel.jelly.core.ProtoTranscoder$;
import eu.ostrzyciel.jelly.core.proto.v1.*;
import org.bson.Document;
import org.bson.types.Binary;

import java.io.OutputStream;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class NanopubStream {
    /**
     * Create a NanopubStream from a MongoDB cursor in the "nanopubs" collection.
     * The cursor must include the "jelly" field.
     * @param cursor MongoDB cursor
     * @return NanopubStream
     */
    public static NanopubStream fromMongoCursor(MongoCursor<Document> cursor) {
        Stream<byte[]> jellyStream = StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(cursor, Spliterator.ORDERED), false)
                .map(doc -> ((Binary) doc.get("jelly")).getData());
        return new NanopubStream(jellyStream);
    }

    private final Stream<RdfStreamFrame> frameStream;

    private NanopubStream(Stream<byte[]> jellyStream) {
        // Merge multiple input Jelly streams (one per nanopub) into a single stream of frames.
        //
        // "unsafe" here is 100% fine, because we are parsing trusted input. The data comes from the DB,
        // and it was written there by the nanopub-registry itself.
        ProtoTranscoder transcoder = ProtoTranscoder$.MODULE$.fastMergingTranscoderUnsafe(
            JellyUtils.jellyOptionsForTransmission
        );
        this.frameStream = jellyStream.map(jellyContent -> {
            if (jellyContent == null) {
                throw new RuntimeException("Jelly content stored in DB is null. " +
                        "Either the database query is incorrect or the DB must be reinitialized.");
            }
            return transcoder.ingestFrame(RdfStreamFrame$.MODULE$.parseFrom(jellyContent));
        });
    }

    /**
     * Write the NanopubStream to a byte stream (delimited).
     * This data can be returned safely as an HTTP response body.
     * @param os OutputStream
     */
    public void writeToByteStream(OutputStream os) {
        frameStream.forEach(frame -> frame.writeDelimitedTo(os));
    }
}
