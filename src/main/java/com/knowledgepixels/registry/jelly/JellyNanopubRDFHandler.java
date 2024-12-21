package com.knowledgepixels.registry.jelly;

import eu.ostrzyciel.jelly.convert.rdf4j.Rdf4jConverterFactory$;
import eu.ostrzyciel.jelly.convert.rdf4j.Rdf4jProtoEncoder;
import eu.ostrzyciel.jelly.core.proto.v1.RdfStreamFrame;
import eu.ostrzyciel.jelly.core.proto.v1.RdfStreamFrame$;
import eu.ostrzyciel.jelly.core.proto.v1.RdfStreamOptions;
import eu.ostrzyciel.jelly.core.proto.v1.RdfStreamRow;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import scala.jdk.CollectionConverters;

import java.util.Vector;

/**
 * RDF4J Rio RDFHandler that converts nanopubs into Jelly RdfStreamFrames.
 */
public class JellyNanopubRDFHandler extends AbstractRDFHandler {
    private final Rdf4jProtoEncoder encoder;
    private final Vector<RdfStreamRow> rowBuffer = new Vector<>();

    JellyNanopubRDFHandler(RdfStreamOptions options) {
        // Enabling namespace declarations -- so we are using Jelly 1.1.0 here.
        this.encoder = Rdf4jConverterFactory$.MODULE$.encoder(options, true);
    }

    @Override
    public void handleStatement(Statement st) {
        encoder.addQuadStatement(st).foreach(rowBuffer::add);;
    }

    @Override
    public void handleNamespace(String prefix, String uri) {
        encoder.declareNamespace(prefix, uri).foreach(rowBuffer::add);;
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
