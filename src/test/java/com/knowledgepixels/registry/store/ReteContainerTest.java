package com.knowledgepixels.registry.store;

import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.readonly.BuildOptions;
import com.knowledgepixels.registry.readonly.Validate;
import com.knowledgepixels.registry.readonly.ValidationReport;
import com.knowledgepixels.registry.utils.ReadOnlyFixtures;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real thing: a file built with the rete CLI container and served by {@code rete serve},
 * read through RDF4J's SPARQL repository the way the read-only registry does. Needs Docker
 * and network access to pull the image; runs only with {@code -Drete.image=ghcr.io/caviri/rete-cli:0.5.0}
 * (or any other tag).
 */
class ReteContainerTest {

    private static final String IMAGE = System.getProperty("rete.image");

    private static GenericContainer<?> server;
    private static SparqlRegistryStore store;
    private static Nanopub a, b, c;
    private static String pk;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(IMAGE != null && !IMAGE.isBlank(), "set -Drete.image to run against a rete container");
        Path dir = Files.createTempDirectory("rete-registry");
        Nanopub intro = intro(KEY1, ALICE, "Alice", new Date());
        a = simple(KEY1, "a", TYPE_A);
        b = superseding(KEY1, a, TYPE_B);
        c = simple(KEY2, "c", TYPE_A);
        pk = Utils.getHash(RegistryDB.getPubkey(a));
        Model model = build(options(2), List.of(intro, a, b, c));
        Path nq = dir.resolve("registry.nq");
        try (OutputStream out = Files.newOutputStream(nq)) {
            Rio.write(model, out, RDFFormat.NQUADS);
        }

        try (GenericContainer<?> builder = new GenericContainer<>(IMAGE)
                .withFileSystemBind(dir.toString(), "/data", BindMode.READ_WRITE)
                .withCommand("build", "/data/registry.nq", "-o", "/data/registry.rete")
                .withStartupCheckStrategy(new org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy())) {
            builder.start();
        }
        assertTrue(Files.exists(dir.resolve("registry.rete")), "rete build produced a file");

        server = new GenericContainer<>(IMAGE)
                .withFileSystemBind(dir.toString(), "/data", BindMode.READ_ONLY)
                .withCommand("serve", "/data/registry.rete", "--bind", "0.0.0.0:7878")
                .withExposedPorts(7878)
                .waitingFor(Wait.forHttp("/sparql?query=ASK%20%7B%7D").forPort(7878));
        server.start();
        store = SparqlRegistryStore.forEndpoint("http://" + server.getHost() + ":" + server.getMappedPort(7878) + "/sparql");
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    private static String ac(Nanopub np) {
        return TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
    }

    @Test
    void readsThroughTheRealEndpoint() {
        try (RegistryReader r = store.reader()) {
            assertEquals("ready", r.serverInfoValue("status"));
            assertEquals(4L, r.nanopubCount());
            Document d = r.getNanopub(ac(a));
            assertEquals(a.getUri().stringValue(), d.getString("fullId"));
            List<Document> entries = new ArrayList<>();
            try (CloseableIterator<Document> it = r.listEntries(pk, "$")) {
                it.forEachRemaining(entries::add);
            }
            assertEquals(3, entries.size());
            assertEquals(List.of(ac(c), ac(b), ac(a)), r.latestNanopubIds(3));
            assertEquals(2, MongoRegistryStoreTest.decode(r.nanopubsAfterCounter(2)).size());
        }
    }

    @Test
    void validates() {
        ValidationReport report = Validate.run(store, 1);
        assertTrue(report.ok(), report.toString());
    }

}
