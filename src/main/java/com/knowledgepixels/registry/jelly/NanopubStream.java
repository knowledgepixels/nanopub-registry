package com.knowledgepixels.registry.jelly;

import com.mongodb.client.MongoCursor;
import eu.ostrzyciel.jelly.convert.rdf4j.Rdf4jConverterFactory$;
import eu.ostrzyciel.jelly.convert.rdf4j.Rdf4jProtoEncoder;
import eu.ostrzyciel.jelly.core.JellyOptions$;
import eu.ostrzyciel.jelly.core.proto.v1.*;
import org.bson.Document;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import scala.jdk.CollectionConverters;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.Vector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class NanopubStream {
    /**
     * Create a NanopubStream from a MongoDB cursor in the "nanopubs" collection.
     * @param cursor MongoDB cursor
     * @return NanopubStream
     */
    public static NanopubStream fromMongoCursor(MongoCursor<Document> cursor) {
        Stream<String> trigStream = StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(cursor, Spliterator.ORDERED), false)
                .map(doc -> doc.getString("content"));
        return new NanopubStream(trigStream);
    }

    /**
     * Direct handler for RDF parser outputs that immediately sends the statements to the Jelly stream.
     */
    private static class JellyStatementHandler extends AbstractRDFHandler {
        private final Rdf4jProtoEncoder encoder;
        private final Vector<RdfStreamRow> rowBuffer = new Vector<>();

        JellyStatementHandler(Rdf4jProtoEncoder encoder) {
            this.encoder = encoder;
        }

        @Override
        public void handleStatement(Statement st) {
            encoder.addQuadStatement(st).foreach(rowBuffer::add);
        }

        /**
         * Call this at the end of a nanopub.
         * This flushes the buffer and returns the RdfStreamFrame corresponding to one nanopub.
         * @return RdfStreamFrame
         */
        public RdfStreamFrame getFrame() {
            var rows = CollectionConverters.CollectionHasAsScala(rowBuffer).asScala().toSeq();
            rowBuffer.clear();
            return RdfStreamFrame$.MODULE$.apply(rows);
        }
    }

    private final Stream<RdfStreamFrame> frameStream;

    private NanopubStream(Stream<String> trigStream) {
        // Using the low-level Jelly API here
        Rdf4jConverterFactory$ jellyConverter = Rdf4jConverterFactory$.MODULE$;
        // TODO: factor out the shared options somewhere
        RdfStreamOptions options = JellyOptions$.MODULE$.bigStrict()
            .withStreamName("nanopub")  // TODO: use a more descriptive name
            .withPhysicalType(PhysicalStreamType.QUADS$.MODULE$)
            .withLogicalType(LogicalStreamType.DATASETS$.MODULE$);

        Rdf4jProtoEncoder encoder = jellyConverter.encoder(options);
        JellyStatementHandler handler = new JellyStatementHandler(encoder);

        this.frameStream = trigStream.map(trig -> {
            // TODO: this is slow, we are parsing TriG files here...
            //   Maybe instead store the files as Jelly in the DB and here just repack them?
            RDFParser parser = Rio.createParser(RDFFormat.TRIG);
            parser.setRDFHandler(handler);
            try {
                parser.parse(new java.io.StringReader(trig));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return handler.getFrame();
        });
    }

    public void writeToByteStream(OutputStream os) {
        frameStream.forEach(frame -> frame.writeDelimitedTo(os));
    }
}
