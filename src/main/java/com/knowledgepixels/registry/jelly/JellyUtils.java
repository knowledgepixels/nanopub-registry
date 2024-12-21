package com.knowledgepixels.registry.jelly;

import eu.ostrzyciel.jelly.core.JellyOptions$;
import eu.ostrzyciel.jelly.core.proto.v1.*;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

/**
 * Utility functions for working with Jelly RDF data.
 * TODO: consider putting this in the org.nanopub.nanopub library?
 */
public class JellyUtils {
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
        JellyNanopubRDFHandler handler = new JellyNanopubRDFHandler(jellyOptionsForDB);
        NanopubUtils.propagateToHandler(np, handler);
        RdfStreamFrame frame = handler.getFrame();
        return frame.toByteArray();
    }
}
