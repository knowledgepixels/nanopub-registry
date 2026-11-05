package com.knowledgepixels.registry.readonly;

import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.store.CloseableIterator;
import com.knowledgepixels.registry.store.RegistryMeta;
import com.knowledgepixels.registry.store.RegistryReader;
import com.knowledgepixels.registry.store.SparqlRegistryStore;
import net.trustyuri.TrustyUriUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.JellyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a built registry file through its SPARQL endpoint: that every stored nanopub
 * verifies (trusty artifact code, signature, pubkey), that its TriG and Jelly blobs agree
 * with each other and with the content quads, that every list is a contiguous chain of
 * positions with a correct XOR checksum chain, that the counter and id chains are complete,
 * and that the trust state is the empty one a read-only registry publishes.
 *
 * <pre>
 * java -cp nanopub-registry-VERSION-fat.jar com.knowledgepixels.registry.readonly.Validate --sparql http://localhost:7878/sparql [--sample N]
 * </pre>
 *
 * <p>{@code --sample N} verifies only every N-th nanopub's content (lists and chains are
 * always walked in full). Exit code 1 on any failure.
 */
public class Validate {

    private static final Logger logger = LoggerFactory.getLogger(Validate.class);

    public static void main(String[] args) throws Exception {
        String sparql = null;
        int sample = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sparql" -> sparql = args[++i];
                case "--sample" -> sample = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown argument " + args[i]);
                    System.err.println("Usage: Validate --sparql URL [--sample N]");
                    System.exit(2);
                }
            }
        }
        if (sparql == null) {
            System.err.println("Usage: Validate --sparql URL [--sample N]");
            System.exit(2);
        }
        ValidationReport report;
        try (SparqlRegistryStore store = SparqlRegistryStore.forEndpoint(sparql)) {
            report = run(store, sample);
        }
        System.out.print(report);
        System.exit(report.ok() ? 0 : 1);
    }

    /**
     * Validates the file behind the store.
     */
    public static ValidationReport run(SparqlRegistryStore store, int sample) {
        ValidationReport report = new ValidationReport();
        try (RegistryReader r = store.reader()) {
            checkCounterChain(store, r, report, sample);
            checkIdChain(r, report);
            checkLists(r, report);
            checkTrustState(r, report);
        }
        return report;
    }

    private static void checkCounterChain(SparqlRegistryStore store, RegistryReader r, ValidationReport report, int sample) {
        Long max = r.maxCounter();
        long count = r.nanopubCount();
        long expected = max == null ? 0 : max;
        if (count != expected) {
            report.fail("nanopubCount " + count + " differs from loadCounter " + expected);
        }
        Set<String> seen = new HashSet<>();
        long n = 0;
        try (CloseableIterator<String> ids = r.nanopubIdsByCounterDesc()) {
            while (ids.hasNext()) {
                String ac = ids.next();
                n++;
                if (!seen.add(ac)) {
                    report.fail("artifact code " + ac + " appears twice in the counter chain");
                }
                report.count("nanopubs in counter chain");
                if ((n - 1) % sample != 0) {
                    continue;
                }
                checkNanopub(store, r, ac, report);
            }
        }
        if (n != expected) {
            report.fail("counter chain lists " + n + " nanopubs, loadCounter says " + expected);
        }
    }

    private static void checkNanopub(SparqlRegistryStore store, RegistryReader r, String ac, ValidationReport report) {
        Document d = r.getNanopub(ac);
        if (d == null) {
            report.fail("nanopub " + ac + " is in the counter chain but has no record");
            return;
        }
        report.count("nanopubs verified");
        Nanopub np;
        try {
            np = new NanopubImpl(d.getString("content"), RDFFormat.TRIG);
        } catch (Exception ex) {
            report.fail("nanopub " + ac + ": TriG does not parse: " + ex.getMessage());
            return;
        }
        if (!ac.equals(TrustyUriUtils.getArtifactCode(np.getUri().stringValue()))) {
            report.fail("nanopub " + ac + ": TriG is of " + np.getUri());
        }
        if (!RegistryDB.hasValidTrustyArtifactCode(np)) {
            report.fail("nanopub " + ac + ": artifact code does not verify against the content");
        }
        String pubkey = RegistryDB.getPubkey(np);
        if (pubkey == null) {
            report.fail("nanopub " + ac + ": no valid signature");
        } else if (!Utils.getHash(pubkey).equals(d.getString("pubkey"))) {
            report.fail("nanopub " + ac + ": pubkey hash does not match the signature");
        }
        try {
            Nanopub fromJelly = JellyUtils.readFromDB(d.get("jelly", Binary.class).getData());
            if (!NanopubUtils.writeToString(fromJelly, RDFFormat.TRIG).equals(d.getString("content"))) {
                report.fail("nanopub " + ac + ": Jelly and TriG differ");
            }
        } catch (Exception ex) {
            report.fail("nanopub " + ac + ": Jelly does not decode: " + ex.getMessage());
        }
        checkContentQuads(store, np, ac, report);
    }

    private static void checkContentQuads(SparqlRegistryStore store, Nanopub np, String ac, ValidationReport report) {
        Repository repo = store.repository();
        try (RepositoryConnection c = repo.getConnection()) {
            String q = "SELECT ?v WHERE { GRAPH <" + RegistryMeta.GRAPH_REGISTRY + "> { <" + RegistryMeta.REGISTRY + "> <" + RegistryMeta.CONTENT_QUADS + "> ?v } }";
            boolean contentQuads = false;
            try (TupleQueryResult res = c.prepareTupleQuery(q).evaluate()) {
                if (res.hasNext()) {
                    contentQuads = Boolean.parseBoolean(res.next().getValue("v").stringValue());
                }
            }
            if (!contentQuads) {
                return;
            }
            Set<String> expected = new HashSet<>();
            for (Statement st : NanopubUtils.getStatements(np)) {
                expected.add(key(st.getContext().stringValue(), st.getSubject().stringValue(), st.getPredicate().stringValue(), st.getObject().toString()));
            }
            Set<String> found = new HashSet<>();
            for (var graph : np.getGraphUris()) {
                String gq = "SELECT ?s ?p ?o WHERE { GRAPH <" + graph + "> { ?s ?p ?o } }";
                try (TupleQueryResult res = c.prepareTupleQuery(gq).evaluate()) {
                    while (res.hasNext()) {
                        BindingSet b = res.next();
                        found.add(key(graph.stringValue(), b.getValue("s").stringValue(), b.getValue("p").stringValue(), b.getValue("o").toString()));
                    }
                }
            }
            if (!expected.equals(found)) {
                report.fail("nanopub " + ac + ": content quads differ from the TriG (" + expected.size() + " expected, " + found.size() + " found)");
            }
            report.count("content graphs compared");
        }
    }

    private static String key(String g, String s, String p, String o) {
        return g + " " + s + " " + p + " " + o;
    }

    private static void checkIdChain(RegistryReader r, ValidationReport report) {
        List<String> ids = new ArrayList<>();
        try (CloseableIterator<String> it = r.nanopubIdsByIdAsc("")) {
            while (it.hasNext()) {
                ids.add(it.next());
            }
        }
        for (int i = 1; i < ids.size(); i++) {
            if (ids.get(i - 1).compareTo(ids.get(i)) >= 0) {
                report.fail("id chain is not strictly ascending at " + ids.get(i));
                break;
            }
        }
        if (ids.size() != r.nanopubCount()) {
            report.fail("id chain lists " + ids.size() + " nanopubs, nanopubCount says " + r.nanopubCount());
        }
        for (int i = 0; i < ids.size(); i++) {
            report.count("id chain entries");
        }
    }

    private static void checkLists(RegistryReader r, ValidationReport report) {
        for (String pk : r.pubkeys()) {
            report.count("pubkeys");
            List<Document> lists = r.listsForPubkey(pk);
            if (lists.stream().noneMatch(l -> RegistryMeta.ALL_TYPES.equals(l.getString("type")))) {
                report.fail("pubkey " + pk + " has no $ list");
            }
            for (Document list : lists) {
                String type = list.getString("type");
                long maxPosition = list.getLong("maxPosition");
                long expectedPos = 0;
                String checksum = NanopubUtils.INIT_CHECKSUM;
                report.count("lists");
                try (CloseableIterator<Document> entries = r.listEntries(pk, type)) {
                    while (entries.hasNext()) {
                        Document e = entries.next();
                        report.count("list entries");
                        long pos = e.getLong("position");
                        if (pos != expectedPos) {
                            report.fail("list " + pk + "/" + type + ": position " + pos + " where " + expectedPos + " was expected");
                            expectedPos = pos;
                        }
                        expectedPos++;
                        Document np = r.getNanopub(e.getString("np"));
                        if (np == null) {
                            report.fail("list " + pk + "/" + type + " position " + pos + ": nanopub " + e.getString("np") + " has no record");
                            continue;
                        }
                        checksum = NanopubUtils.updateXorChecksum(org.eclipse.rdf4j.model.util.Values.iri(np.getString("fullId")), checksum);
                        if (!checksum.equals(e.getString("checksum"))) {
                            report.fail("list " + pk + "/" + type + " position " + pos + ": checksum chain broken");
                        }
                        if (RegistryMeta.ALL_TYPES.equals(type) && !pk.equals(np.getString("pubkey"))) {
                            report.fail("list " + pk + "/$ position " + pos + ": nanopub " + e.getString("np") + " is signed with another pubkey");
                        }
                        Long found = r.listEntryPosition(pk, type, e.getString("checksum"));
                        if (found == null || found != pos) {
                            report.fail("list " + pk + "/" + type + " position " + pos + ": checksum lookup answers " + found);
                        }
                    }
                }
                if (expectedPos - 1 != maxPosition) {
                    report.fail("list " + pk + "/" + type + ": maxPosition " + maxPosition + " but the last position is " + (expectedPos - 1));
                }
            }
        }
    }

    private static void checkTrustState(RegistryReader r, ValidationReport report) {
        // a read-only registry computes no trust and publishes none
        List<Document> accounts = r.accounts();
        if (!accounts.isEmpty()) {
            report.fail(accounts.size() + " accounts published; a read-only registry publishes none");
        }
        if (!r.agents().isEmpty()) {
            report.fail(r.agents().size() + " agents published; a read-only registry publishes none");
        }
        List<Document> snapshots = r.trustStateSnapshots();
        if (snapshots.size() != 1) {
            report.fail("expected one trust state snapshot, found " + snapshots.size());
            return;
        }
        String hash = snapshots.getFirst().getString("_id");
        if (!hash.equals(r.serverInfoValue("trustStateHash"))) {
            report.fail("snapshot hash " + hash + " differs from serverInfo trustStateHash " + r.serverInfoValue("trustStateHash"));
        }
        Document snapshot = r.trustStateSnapshot(hash);
        StringBuilder lines = new StringBuilder();
        for (Document a : snapshot.getList("accounts", Document.class)) {
            lines.append(a.getString("agent")).append('|').append(a.getString("pubkey")).append('\n');
        }
        if (!Utils.getHash(lines.toString()).equals(hash)) {
            report.fail("trust state hash does not recompute from the snapshot's accounts");
        }
        if (snapshot.getList("accounts", Document.class).size() != accounts.size()) {
            report.fail("snapshot has " + snapshot.getList("accounts", Document.class).size() + " accounts, the registry " + accounts.size());
        }
        report.count("trust state snapshots");
        logger.debug("Trust state {} checked", hash);
    }

}
