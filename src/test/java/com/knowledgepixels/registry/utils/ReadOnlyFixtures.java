package com.knowledgepixels.registry.utils;

import com.knowledgepixels.registry.CoverageFilter;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.readonly.Build;
import com.knowledgepixels.registry.readonly.BuildOptions;
import com.knowledgepixels.registry.readonly.RegistryModelWriter;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.extra.security.SignNanopub;
import org.nanopub.extra.security.SignatureAlgorithm;
import org.nanopub.extra.security.TransformContext;
import org.nanopub.testsuite.NanopubTestSuite;
import org.nanopub.testsuite.SigningKeyPair;
import org.nanopub.vocabulary.NPX;

import java.security.KeyPair;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds small, signed nanopub sets and the read-only registry model over them, for the
 * builder and SPARQL store tests.
 */
public final class ReadOnlyFixtures {

    private ReadOnlyFixtures() {
    }

    public static final ValueFactory vf = SimpleValueFactory.getInstance();
    public static final String KEY1 = "rsa-key1";
    public static final String KEY2 = "rsa-key2";
    public static final IRI ALICE = vf.createIRI("https://orcid.org/0000-0000-0000-0001");
    public static final IRI BOB = vf.createIRI("https://orcid.org/0000-0000-0000-0002");
    public static final IRI TYPE_A = vf.createIRI("http://example.org/TypeA");
    public static final IRI TYPE_B = vf.createIRI("http://example.org/TypeB");

    private static final AtomicInteger seq = new AtomicInteger();

    public static TransformContext context(String keyName, IRI signer) throws Exception {
        SigningKeyPair kp = NanopubTestSuite.getLatest().getSigningKey(keyName);
        KeyPair key = SignNanopub.loadKey(kp.getPrivateKeyFile().getPath(), SignatureAlgorithm.RSA);
        return new TransformContext(SignatureAlgorithm.RSA, key, signer, false, false, false);
    }

    private static NanopubCreator creator(String label, Date created, IRI... types) throws Exception {
        NanopubCreator c = new NanopubCreator("http://example.org/np/" + seq.incrementAndGet() + "/");
        IRI thing = vf.createIRI("http://example.org/thing/" + seq.get());
        c.addAssertionStatement(thing, RDFS.LABEL, vf.createLiteral(label));
        c.addProvenanceStatement(vf.createIRI("http://www.w3.org/ns/prov#wasAttributedTo"), thing);
        c.addPubinfoStatement(DCTERMS.CREATED, vf.createLiteral(created));
        for (IRI t : types) {
            c.addPubinfoStatement(NPX.HAS_NANOPUB_TYPE, t);
        }
        return c;
    }

    /**
     * A signed nanopub with the given label and types.
     */
    public static Nanopub simple(String keyName, String label, IRI... types) throws Exception {
        return SignNanopub.signAndTransform(creator(label, new Date(), types).finalizeNanopub(), context(keyName, ALICE));
    }

    /**
     * A signed nanopub that supersedes {@code target}.
     */
    public static Nanopub superseding(String keyName, Nanopub target, IRI... types) throws Exception {
        NanopubCreator c = creator("supersedes " + target.getUri(), new Date(), types);
        c.addPubinfoStatement(NPX.SUPERSEDES, target.getUri());
        return SignNanopub.signAndTransform(c.finalizeNanopub(), context(keyName, ALICE));
    }

    /**
     * An introduction nanopub declaring the key {@code keyName} for {@code agent}, signed with it.
     */
    public static Nanopub intro(String keyName, IRI agent, String name, Date created) throws Exception {
        String pubkey = RegistryDB.getPubkey(simple(keyName, "probe"));
        NanopubCreator c = new NanopubCreator("http://example.org/np/" + seq.incrementAndGet() + "/");
        IRI keyDecl = vf.createIRI("http://example.org/np/" + seq.get() + "/keyDecl");
        c.addAssertionStatement(keyDecl, NPX.DECLARED_BY, agent);
        c.addAssertionStatement(keyDecl, NPX.HAS_ALGORITHM, vf.createLiteral("RSA"));
        c.addAssertionStatement(keyDecl, NPX.HAS_PUBLIC_KEY, vf.createLiteral(pubkey));
        c.addAssertionStatement(agent, FOAF.NAME, vf.createLiteral(name));
        c.addProvenanceStatement(vf.createIRI("http://www.w3.org/ns/prov#wasAttributedTo"), agent);
        c.addPubinfoStatement(DCTERMS.CREATED, vf.createLiteral(created));
        c.addPubinfoStatement(NPX.INTRODUCES, agent);
        return SignNanopub.signAndTransform(c.finalizeNanopub(), context(keyName, agent));
    }

    /**
     * An unsigned (plain trusty) nanopub.
     */
    public static Nanopub unsigned(String label) throws Exception {
        return creator(label, new Date()).finalizeTrustyNanopub();
    }

    public static BuildOptions options(long batchSize) {
        BuildOptions o = new BuildOptions();
        o.batchSize = batchSize;
        o.setupId = 4242L;
        return o;
    }

    /**
     * The registry model of the nanopubs, in order, as an RDF4J model.
     */
    public static Model build(BuildOptions opts, List<Nanopub> nanopubs) {
        if (opts.coverageTypes == null) {
            // keep whatever environment reader the test installed (e.g. a FakeEnv)
            CoverageFilter.init();
        } else {
            Build.configureCoverage(opts.coverageTypes);
        }
        Model model = new LinkedHashModel();
        RegistryModelWriter w = new RegistryModelWriter(new StatementCollector(model), opts);
        for (Nanopub np : nanopubs) {
            w.add(np);
        }
        w.finish();
        return model;
    }

    /**
     * An in-memory repository holding the model.
     */
    public static Repository repository(Model model) {
        Repository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(model);
        }
        return repo;
    }

}
