package com.knowledgepixels.registry.jelly;

import eu.ostrzyciel.jelly.convert.rdf4j.Rdf4jConverterFactory$;
import eu.ostrzyciel.jelly.convert.rdf4j.rio.package$;
import eu.ostrzyciel.jelly.core.JellyOptions$;
import eu.ostrzyciel.jelly.core.ProtoDecoder;
import eu.ostrzyciel.jelly.core.proto.v1.*;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import scala.Option;
import scala.Some;
import scala.jdk.CollectionConverters;
import scala.runtime.BoxedUnit;

import java.util.Vector;

/**
 * Utility functions for working with Jelly RDF data.
 * TODO: consider putting this in the nanopub-java library?
 */
public class JellyUtils {

    /**
     * Jelly RDF format for use with RDF4J Rio.
     */
    public final static RDFFormat JELLY_FORMAT = package$.MODULE$.JELLY();

    public final static Option<RdfStreamOptions> defaultSupportedOptions =
        Some.apply(JellyOptions$.MODULE$.defaultSupportedOptions());

    /**
     * Options for Jelly RDF streams that are written to the database.
     */
    public static RdfStreamOptions jellyOptionsForDB = JellyOptions$.MODULE$.smallStrict()
        .withPhysicalType(PhysicalStreamType.QUADS$.MODULE$)
        .withLogicalType(LogicalStreamType.DATASETS$.MODULE$);

    /**
     * Options for Jelly RDF streams that are transmitted between services.
     */
    public static RdfStreamOptions jellyOptionsForTransmission = JellyOptions$.MODULE$.bigStrict()
        .withPhysicalType(PhysicalStreamType.QUADS$.MODULE$)
        .withLogicalType(LogicalStreamType.DATASETS$.MODULE$);

    /**
     * Write a Nanopub to bytes in the Jelly format to be stored in the database.
     * @param np Nanopub
     * @return Jelly RDF bytes (non-delimited)
     */
    public static byte[] writeNanopubForDB(Nanopub np) {
        JellyWriterRDFHandler handler = new JellyWriterRDFHandler(jellyOptionsForDB);
        NanopubUtils.propagateToHandler(np, handler);
        RdfStreamFrame frame = handler.getFrame();
        return frame.toByteArray();
    }

    /**
     * Read a Nanopub from bytes in the Jelly format stored in the database.
     * <p>
     * This is only needed because nanopub-java does not support parsing binary data as input.
     * Nonetheless, this should be a bit faster than going through RDF4J Rio, because we are
     * dealing with a special (simpler) case here.
     * TODO: fix this in nanopub-java?
     * @param jellyBytes Jelly RDF bytes (non-delimited)
     * @return Nanopub
     * @throws MalformedNanopubException if this is not a valid Nanopub
     */
    public static Nanopub readFromDB(byte[] jellyBytes) throws MalformedNanopubException {
        Vector<Statement> statements = new Vector<>();
        Vector<Pair<String, String>> namespaces = new Vector<>();
        ProtoDecoder<Statement> decoder = Rdf4jConverterFactory$.MODULE$.quadsDecoder(
            defaultSupportedOptions,
            ((prefix, node) -> {
                namespaces.add(Pair.of(prefix, node.stringValue()));
                return BoxedUnit.UNIT;
            })
        );
        RdfStreamFrame frame = RdfStreamFrame$.MODULE$.parseFrom(jellyBytes);
        CollectionConverters.SeqHasAsJava(frame.rows()).asJava().forEach(row -> {
            Option<Statement> maybeSt = decoder.ingestRow(row);
            if (maybeSt.isDefined()) {
                statements.add(maybeSt.get());
            }
        });
        return new NanopubImpl(statements, namespaces);
    }
}
