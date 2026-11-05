package com.knowledgepixels.registry.readonly;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Options of the read-only registry file builder; see {@link Build} for the command line.
 */
public class BuildOptions {

    /**
     * Nanopub files or directories, in the order that defines the load counters.
     */
    public List<Path> inputs = new ArrayList<>();

    /**
     * The N-Quads output (gzip-compressed when the name ends in {@code .gz}).
     */
    public Path out;

    /**
     * The setting nanopub, as a file path; null for none.
     */
    public Path settingFile;

    /**
     * Whitespace-separated covered type URIs, as {@code REGISTRY_COVERAGE_TYPES}; null = all.
     */
    public String coverageTypes;

    /**
     * The {@code REGISTRY_COVERAGE_AGENTS} value to publish; null = unset.
     */
    public String coverageAgents;

    public boolean contentQuads = true;
    public boolean verify = true;
    public boolean testInstance = false;
    public boolean localInstance = false;
    public Long setupId;
    public long batchSize = 1000;

    /**
     * Where to write the artifact codes and reasons of rejected nanopubs; null for none.
     */
    public Path rejectsFile;

}
