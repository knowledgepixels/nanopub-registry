package com.knowledgepixels.registry.readonly;

import com.knowledgepixels.registry.CoverageFilter;
import com.knowledgepixels.registry.ReadsEnvironment;
import com.knowledgepixels.registry.Utils;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the N-Quads source of a read-only registry file from nanopub files.
 *
 * <pre>
 * java -cp nanopub-registry-VERSION-fat.jar com.knowledgepixels.registry.readonly.Build \
 *     [inputs...] --out registry.nq.gz [--setting setting.trig] \
 *     [--coverage-types "uri uri ..."] [--coverage-agents "..."] \
 *     [--no-content-quads] [--skip-verify] [--setup-id N] [--test-instance] \
 *     [--local-instance] [--batch-size 1000] [--rejects rejects.txt]
 * </pre>
 *
 * <p>Inputs are files or directories (TriG, N-Quads, Jelly; optionally gzipped), read in
 * argument order and sorted order within a directory. That order defines the load counters.
 * The setting nanopub, if given, is loaded first. The output is then turned into a rete file
 * with {@code rete build registry.nq.gz -o registry.rete} (see {@code scripts/build-readonly.sh}).
 */
public class Build {

    private static final Logger logger = LoggerFactory.getLogger(Build.class);

    public static void main(String[] args) throws Exception {
        BuildOptions opts;
        try {
            opts = parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(2);
            return;
        }
        Summary summary = run(opts);
        System.out.println(summary);
        if (summary.rejected > 0 && opts.rejectsFile == null) {
            System.out.println("(run with --rejects FILE to record the rejected nanopubs)");
        }
    }

    public record Summary(long stored, int rejected, int duplicates, int pubkeys, int lists, Path out) {
        @Override
        public String toString() {
            return "Stored " + stored + " nanopubs (" + pubkeys + " pubkeys, " + lists + " lists) in " + out
                    + "; rejected " + rejected + ", duplicates " + duplicates;
        }
    }

    /**
     * Runs a build with the given options.
     */
    public static Summary run(BuildOptions opts) throws Exception {
        if (opts.out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if (opts.inputs.isEmpty() && opts.settingFile == null) {
            throw new IllegalArgumentException("no inputs given");
        }
        configureCoverage(opts.coverageTypes);

        List<Path> files = NanopubInputs.expand(opts.inputs);
        String outName = opts.out.getFileName().toString();
        try (OutputStream raw = Files.newOutputStream(opts.out);
             OutputStream out = new BufferedOutputStream(outName.endsWith(".gz") ? new GZIPOutputStream(raw) : raw);
             PrintWriter rejects = opts.rejectsFile == null ? null
                     : new PrintWriter(new BufferedWriter(Files.newBufferedWriter(opts.rejectsFile)))) {
            RDFWriter writer = Rio.createWriter(RDFFormat.NQUADS, out);
            writer.startRDF();
            RegistryModelWriter model = new RegistryModelWriter(writer, opts, (id, reason) -> {
                if (rejects != null) {
                    rejects.println(id + "\t" + reason);
                }
            });
            if (opts.settingFile != null) {
                Nanopub setting = new NanopubImpl(opts.settingFile.toFile());
                String ac = TrustyUriUtils.getArtifactCode(setting.getUri().stringValue());
                if (!model.add(setting)) {
                    throw new IOException("The setting nanopub " + opts.settingFile + " was not accepted");
                }
                model.setSettingAc(ac);
                logger.info("Setting nanopub {} loaded as counter 1", ac);
            }
            long[] seen = {0};
            for (Path file : files) {
                NanopubInputs.read(file, np -> {
                    model.add(np);
                    if (++seen[0] % 10000 == 0) {
                        logger.info("Processed {} nanopubs ({} stored)", seen[0], model.getCount());
                    }
                });
            }
            model.finish();
            writer.endRDF();
            return new Summary(model.getCount(), model.getRejectedCount(), model.getDuplicateCount(),
                    model.getPubkeyCount(), model.getListCount(), opts.out);
        }
    }

    /**
     * Makes {@code CoverageFilter} see the builder's coverage setting instead of the process
     * environment.
     */
    public static void configureCoverage(String coverageTypes) {
        Utils.setEnvReader(new ReadsEnvironment(name ->
                "REGISTRY_COVERAGE_TYPES".equals(name) ? coverageTypes : System.getenv(name)));
        CoverageFilter.init();
    }

    static BuildOptions parse(String[] args) {
        BuildOptions o = new BuildOptions();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--out" -> o.out = Path.of(value(args, ++i, a));
                case "--setting" -> o.settingFile = Path.of(value(args, ++i, a));
                case "--coverage-types" -> o.coverageTypes = value(args, ++i, a);
                case "--coverage-agents" -> o.coverageAgents = value(args, ++i, a);
                case "--no-content-quads" -> o.contentQuads = false;
                case "--skip-verify" -> o.verify = false;
                case "--test-instance" -> o.testInstance = true;
                case "--local-instance" -> o.localInstance = true;
                case "--setup-id" -> o.setupId = Long.parseLong(value(args, ++i, a));
                case "--batch-size" -> o.batchSize = Long.parseLong(value(args, ++i, a));
                case "--rejects" -> o.rejectsFile = Path.of(value(args, ++i, a));
                case "--help", "-h" -> {
                    System.out.println(usage());
                    System.exit(0);
                }
                default -> {
                    if (a.startsWith("--")) {
                        throw new IllegalArgumentException("unknown option " + a);
                    }
                    o.inputs.add(Path.of(a));
                }
            }
        }
        if (o.out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if (o.batchSize < 1) {
            throw new IllegalArgumentException("--batch-size must be positive");
        }
        return o;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    static String usage() {
        return """
                Usage: Build [inputs...] --out registry.nq.gz [options]

                Inputs are nanopub files (TriG, N-Quads, Jelly; optionally .gz) or directories.
                Their order defines the load counters.

                Options:
                  --out FILE              N-Quads output (gzip when the name ends in .gz)
                  --setting FILE          the setting nanopub (loaded first)
                  --coverage-types "..."  whitespace-separated covered type URIs (default: all)
                  --coverage-agents "..." the REGISTRY_COVERAGE_AGENTS value to publish
                  --no-content-quads      store only the TriG/Jelly literals, not the nanopub graphs
                  --skip-verify           do not check trusty artifact codes and signatures
                  --setup-id N            the setupId to publish (default: random)
                  --test-instance         mark the registry as a test instance
                  --local-instance        accept protected nanopubs
                  --batch-size N          entries per batch node (default 1000)
                  --rejects FILE          write rejected nanopubs and reasons here""";
    }

}
