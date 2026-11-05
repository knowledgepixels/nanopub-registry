package com.knowledgepixels.registry.readonly;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.MalformedNanopubException;
import org.nanopub.MultiNanopubRdfHandler;
import org.nanopub.Nanopub;
import org.nanopub.jelly.MaybeNanopub;
import org.nanopub.jelly.NanopubStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Streams the nanopubs out of input files: TriG, N-Quads and other RDF formats RDF4J knows
 * by extension, plus Jelly ({@code .jelly}), each optionally gzip-compressed. A directory
 * is expanded to its files in sorted order.
 */
public final class NanopubInputs {

    private static final Logger logger = LoggerFactory.getLogger(NanopubInputs.class);

    private NanopubInputs() {
    }

    /**
     * Expands directories and keeps the given order otherwise.
     */
    public static List<Path> expand(List<Path> inputs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path p : inputs) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        return files;
    }

    /**
     * Feeds every nanopub of the file to the consumer, in file order.
     */
    public static void read(Path file, Consumer<Nanopub> consumer) throws IOException, MalformedNanopubException {
        String name = file.getFileName().toString();
        boolean gz = name.endsWith(".gz");
        String plainName = gz ? name.substring(0, name.length() - 3) : name;
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = new BufferedInputStream(gz ? new GZIPInputStream(raw) : raw)) {
            if (plainName.endsWith(".jelly")) {
                logger.info("Reading Jelly input {}", file);
                NanopubStream.fromByteStream(in).getAsNanopubs().forEach(m -> accept(m, consumer, file));
            } else {
                RDFFormat format = Rio.getParserFormatForFileName(plainName).orElseThrow(
                        () -> new IOException("Cannot determine the RDF format of " + file));
                logger.info("Reading {} input {}", format.getName(), file);
                MultiNanopubRdfHandler.process(format, in, consumer::accept);
            }
        }
    }

    private static void accept(MaybeNanopub m, Consumer<Nanopub> consumer, Path file) {
        if (m.isSuccess()) {
            consumer.accept(m.getNanopub());
        } else {
            logger.warn("Skipping unreadable nanopub in {}: {}", file, m.getException().getMessage());
        }
    }

}
