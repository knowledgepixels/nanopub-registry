package com.knowledgepixels.registry.readonly;

import com.knowledgepixels.registry.store.RegistryMeta;
import com.knowledgepixels.registry.store.SparqlRegistryStore;
import com.knowledgepixels.registry.utils.ReadOnlyFixtures;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;

import java.util.Date;
import java.util.List;

import static com.knowledgepixels.registry.utils.ReadOnlyFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidateTest {

    private static Model model() throws Exception {
        Nanopub intro = intro(KEY1, ALICE, "Alice", new Date());
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = superseding(KEY1, a, TYPE_B);
        Nanopub c = simple(KEY2, "c", TYPE_A);
        return build(options(2), List.of(intro, a, b, c));
    }

    @Test
    void cleanBuildPasses() throws Exception {
        try (SparqlRegistryStore store = new SparqlRegistryStore(repository(model()), 0)) {
            ValidationReport report = Validate.run(store, 1);
            assertTrue(report.ok(), report.toString());
            assertEquals(4L, report.counts().get("nanopubs verified"));
            assertEquals(4L, report.counts().get("content graphs compared"));
            assertTrue(report.counts().get("list entries") > 4);
            assertEquals(1L, report.counts().get("trust state snapshots"));
        }
    }

    @Test
    void tamperedChecksumAndBlobAreReported() throws Exception {
        Nanopub intro = intro(KEY1, ALICE, "Alice", new Date());
        Nanopub a = simple(KEY1, "a", TYPE_A);
        Nanopub b = superseding(KEY1, a, TYPE_B);
        Model m = build(options(2), List.of(intro, a, b));
        String acA = net.trustyuri.TrustyUriUtils.getArtifactCode(a.getUri().stringValue());
        Model tampered = new LinkedHashModel();
        var vf = SimpleValueFactory.getInstance();
        boolean checksumChanged = false;
        for (Statement st : m) {
            if (!checksumChanged && RegistryMeta.CHECKSUM.equals(st.getPredicate())) {
                // breaks the chain at this entry (a lookup of the bogus value still finds it)
                tampered.add(st.getSubject(), st.getPredicate(), vf.createLiteral("bogus"), st.getContext());
                checksumChanged = true;
            } else if (RegistryMeta.TRIG.equals(st.getPredicate()) && st.getSubject().equals(RegistryMeta.npNode(acA))) {
                // content no longer matches the artifact code, and differs from the Jelly
                tampered.add(st.getSubject(), st.getPredicate(), vf.createLiteral(st.getObject().stringValue().replace("\"a\"", "\"A\"")), st.getContext());
            } else {
                tampered.add(st);
            }
        }
        try (SparqlRegistryStore store = new SparqlRegistryStore(repository(tampered), 0)) {
            ValidationReport report = Validate.run(store, 1);
            assertFalse(report.ok());
            assertTrue(report.failures().stream().anyMatch(f -> f.contains("checksum chain broken")), report.toString());
            assertTrue(report.failures().stream().anyMatch(f -> f.contains("does not verify")), report.toString());
            assertTrue(report.failures().stream().anyMatch(f -> f.contains("Jelly and TriG differ")), report.toString());
            assertTrue(report.failures().stream().anyMatch(f -> f.contains("content quads differ")), report.toString());
        }
    }

}
