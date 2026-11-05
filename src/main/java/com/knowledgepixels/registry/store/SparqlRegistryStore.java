package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.Utils;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A read-only backend over a SPARQL repository that holds a registry file built by
 * {@code com.knowledgepixels.registry.readonly.Build} (see {@link RegistryMeta} for its
 * layout): typically the {@code rete serve} sidecar, or an in-memory store in tests.
 */
public class SparqlRegistryStore implements RegistryStore {

    private static final Logger logger = LoggerFactory.getLogger(SparqlRegistryStore.class);

    private final Repository repository;
    private final long cacheMillis;

    public SparqlRegistryStore(Repository repository) {
        this(repository, Long.parseLong(Utils.getEnv("REGISTRY_READ_ONLY_CACHE_SECONDS", "60")) * 1000);
    }

    public SparqlRegistryStore(Repository repository, long cacheMillis) {
        this.repository = repository;
        this.cacheMillis = cacheMillis;
        if (!repository.isInitialized()) {
            repository.init();
        }
        logger.info("Read-only store initialized (cache {} ms)", cacheMillis);
    }

    /**
     * A store over a SPARQL 1.1 Protocol endpoint.
     */
    public static SparqlRegistryStore forEndpoint(String sparqlUrl) {
        logger.info("Connecting read-only store to SPARQL endpoint {}", sparqlUrl);
        return new SparqlRegistryStore(new SPARQLRepository(sparqlUrl));
    }

    /**
     * The underlying repository, for checks that go beyond the registry's read API.
     */
    public Repository repository() {
        return repository;
    }

    @Override
    public RegistryReader reader() {
        return new SparqlRegistryReader(this, repository.getConnection());
    }

    @Override
    public RegistryReader snapshotReader() {
        // the file is immutable, so every read is a snapshot
        return reader();
    }

    @Override
    public void close() {
        repository.shutDown();
    }

    // --- the cached roots: server info and the registry node ---------------------------

    record Roots(java.util.Map<String, Object> serverInfo, java.util.Map<String, Object> registry, long readAt) {
    }

    private volatile Roots roots;

    Roots roots(SparqlRegistryReader reader) {
        Roots r = roots;
        if (r == null || System.currentTimeMillis() - r.readAt > cacheMillis) {
            r = new Roots(reader.readProperties(RegistryMeta.SERVER_INFO), reader.readProperties(RegistryMeta.REGISTRY), System.currentTimeMillis());
            roots = r;
        }
        return r;
    }

}
