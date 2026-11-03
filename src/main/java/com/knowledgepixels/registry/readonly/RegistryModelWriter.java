package com.knowledgepixels.registry.readonly;

import com.knowledgepixels.registry.CoverageFilter;
import com.knowledgepixels.registry.RegistryDB;
import com.knowledgepixels.registry.Utils;
import com.knowledgepixels.registry.store.RegistryMeta;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.NanopubServerUtils;
import org.nanopub.jelly.JellyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import static com.knowledgepixels.registry.store.RegistryMeta.*;

/**
 * Turns a stream of nanopubs into the quads of a read-only registry file: the registry
 * meta graph (records, lists with positions and checksums, batches, accounts, server info),
 * the blob graph (TriG and Jelly per nanopub) and, optionally, the nanopubs' own graphs.
 *
 * <p>Nanopubs are placed on lists the way {@code RegistryDB.loadNanopubOrReject} places them
 * in MongoDB: on the pubkey's all-types list ({@code $}) and on one list per covered type,
 * with the load counter assigned in input order. An invalidating nanopub (retraction, new
 * version) joins every list the invalidated nanopub is on, in either arrival order, and the
 * invalidated nanopub is flagged.
 *
 * <p>Everything about an entry is written when it is appended, so memory holds only one small
 * record per nanopub plus the running state of each list.
 */
public class RegistryModelWriter {

    private static final Logger logger = LoggerFactory.getLogger(RegistryModelWriter.class);

    private static final int MAX_TRIPLES = 1200;
    private static final int MAX_BYTES = 1000000;

    private record NpRecord(int idx, String ac, String fullId, int pubkeyIdx, int[] typeIdx) {
    }

    private static final class ListState {
        final int id;
        final int pubkeyIdx;
        final int typeIdx;
        long maxPosition = -1;
        String checksum = NanopubUtils.INIT_CHECKSUM;

        ListState(int id, int pubkeyIdx, int typeIdx) {
            this.id = id;
            this.pubkeyIdx = pubkeyIdx;
            this.typeIdx = typeIdx;
        }
    }

    private static final class PubkeyState {
        final String hash;
        final String pubkey;

        PubkeyState(String hash, String pubkey) {
            this.hash = hash;
            this.pubkey = pubkey;
        }
    }

    private final RDFHandler out;
    private final BuildOptions opts;
    private final BiConsumer<String, String> rejectListener;
    private final ValueFactory vf = SimpleValueFactory.getInstance();

    private final List<NpRecord> records = new ArrayList<>();
    private final Map<String, Integer> indexByAc = new HashMap<>();
    private final Set<Integer> invalidated = new HashSet<>();

    private final List<PubkeyState> pubkeys = new ArrayList<>();
    private final Map<String, Integer> pubkeyIdxByHash = new HashMap<>();

    private final List<String> typeHashes = new ArrayList<>();
    private final Map<String, Integer> typeIdxByHash = new HashMap<>();
    private final Map<String, String> typeUriByHash = new TreeMap<>();

    private final Map<Long, ListState> lists = new LinkedHashMap<>();
    private final Set<Long> extraMemberships = new HashSet<>();
    private final Map<Integer, List<ListState>> extraListsByNp = new HashMap<>();
    private final Map<String, List<Integer>> pendingInvalidations = new HashMap<>();

    private long counter = 0;
    private int rejected = 0;
    private int duplicates = 0;
    private String settingAc;

    public RegistryModelWriter(RDFHandler out, BuildOptions opts) {
        this(out, opts, (ac, reason) -> {
        });
    }

    /**
     * @param rejectListener told about every rejected nanopub (artifact code or URI, reason)
     */
    public RegistryModelWriter(RDFHandler out, BuildOptions opts, BiConsumer<String, String> rejectListener) {
        this.out = out;
        this.opts = opts;
        this.rejectListener = rejectListener;
        typeHashes.add(ALL_TYPES);
        typeIdxByHash.put(ALL_TYPES, 0);
    }

    /**
     * Records which nanopub is the registry's setting (served as {@code original} and {@code current}).
     */
    public void setSettingAc(String settingAc) {
        this.settingAc = settingAc;
    }

    public long getCount() {
        return counter;
    }

    public int getRejectedCount() {
        return rejected;
    }

    public int getDuplicateCount() {
        return duplicates;
    }

    public int getPubkeyCount() {
        return pubkeys.size();
    }

    public int getListCount() {
        return lists.size();
    }

    /**
     * Adds one nanopub.
     *
     * @return true if it was stored; false if it was rejected or a duplicate
     */
    public boolean add(Nanopub np) {
        String uri = np.getUri().stringValue();
        String ac = TrustyUriUtils.getArtifactCode(uri);
        if (ac == null) {
            return reject(uri, "could not extract artifact code from Trusty URI");
        }
        if (indexByAc.containsKey(ac)) {
            duplicates++;
            logger.debug("Skipping duplicate nanopub {}", ac);
            return false;
        }
        String pubkey;
        try {
            pubkey = checkAndGetPubkey(np, ac);
        } catch (RejectedException ex) {
            return reject(ac, ex.getMessage());
        }

        String pubkeyHash = Utils.getHash(pubkey);
        int pIdx = pubkeyIndex(pubkeyHash, pubkey);

        Set<IRI> types = NanopubUtils.getTypes(np);
        List<Integer> covered = new ArrayList<>();
        for (IRI t : types) {
            String th = Utils.getHash(t.stringValue());
            if (CoverageFilter.isCoveredType(th)) {
                covered.add(typeIndex(th, t.stringValue()));
            }
        }
        int[] typeIdx = covered.stream().mapToInt(Integer::intValue).toArray();

        NpRecord rec = new NpRecord(records.size(), ac, uri, pIdx, typeIdx);
        records.add(rec);
        indexByAc.put(ac, rec.idx);
        counter++;

        // the record and its blobs
        IRI npNode = npNode(ac);
        emit(npNode, NANOPUB, vf.createIRI(uri), GRAPH_REGISTRY);
        emit(npNode, ARTIFACT_CODE, vf.createLiteral(ac), GRAPH_REGISTRY);
        emit(npNode, COUNTER, vf.createLiteral(counter), GRAPH_REGISTRY);
        emit(npNode, PUBKEY, vf.createLiteral(pubkeyHash), GRAPH_REGISTRY);
        emit(counterBatchNode(counterBatchIndex(counter, opts.batchSize)), MEMBER, npNode, GRAPH_REGISTRY);
        try {
            emit(npNode, TRIG, vf.createLiteral(NanopubUtils.writeToString(np, RDFFormat.TRIG)), GRAPH_BLOBS);
            emit(npNode, JELLY, vf.createLiteral(Base64.getEncoder().encodeToString(JellyUtils.writeNanopubForDB(np)), XSD.BASE64BINARY), GRAPH_BLOBS);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to serialize nanopub " + ac, ex);
        }
        if (opts.contentQuads) {
            for (Statement st : NanopubUtils.getStatements(np)) {
                out.handleStatement(st);
            }
        }

        // an invalidating nanopub joins the lists of what it invalidates (same pubkey only)
        for (IRI invalidatedId : Utils.getInvalidatedNanopubIds(np)) {
            String invalidatedAc = TrustyUriUtils.getArtifactCode(invalidatedId.stringValue());
            if (invalidatedAc == null) {
                continue;
            }
            Integer yIdx = indexByAc.get(invalidatedAc);
            if (yIdx == null) {
                pendingInvalidations.computeIfAbsent(invalidatedAc, k -> new ArrayList<>()).add(rec.idx);
            } else {
                NpRecord y = records.get(yIdx);
                if (y.pubkeyIdx == pIdx) {
                    invalidated.add(y.idx);
                    for (ListState l : listsOf(y)) {
                        appendIfAbsent(l, rec);
                    }
                }
            }
        }

        // its own lists
        appendEntry(list(pIdx, 0), rec);
        for (int t : typeIdx) {
            appendEntry(list(pIdx, t), rec);
        }

        // invalidators that arrived before it
        List<Integer> invalidators = pendingInvalidations.remove(ac);
        if (invalidators != null) {
            for (int i : invalidators) {
                NpRecord inv = records.get(i);
                if (inv.pubkeyIdx == pIdx) {
                    invalidated.add(rec.idx);
                    for (ListState l : listsOf(rec)) {
                        appendIfAbsent(l, inv);
                    }
                }
            }
        }

        return true;
    }

    /**
     * Writes everything that depends on the complete input: flags, list and chain nodes,
     * pubkeys, hashes, accounts, agents, the trust state snapshot, server info and the root.
     */
    public void finish() {
        long b = opts.batchSize;
        String now = ZonedDateTime.now(ZoneOffset.UTC).toString();

        for (NpRecord r : records) {
            emit(npNode(r.ac), INVALIDATED, vf.createLiteral(invalidated.contains(r.idx)), GRAPH_REGISTRY);
        }

        for (ListState l : lists.values()) {
            String pk = pubkeys.get(l.pubkeyIdx).hash;
            String th = typeHashes.get(l.typeIdx);
            IRI listNode = listNode(pk, th);
            long batchCount = l.maxPosition / b + 1;
            emit(pubkeyNode(pk), HAS_LIST, listNode, GRAPH_REGISTRY);
            emit(listNode, PUBKEY, vf.createLiteral(pk), GRAPH_REGISTRY);
            emit(listNode, TYPE, vf.createLiteral(th), GRAPH_REGISTRY);
            emit(listNode, MAX_POSITION, vf.createLiteral(l.maxPosition), GRAPH_REGISTRY);
            emit(listNode, STATUS, vf.createLiteral("loaded"), GRAPH_REGISTRY);
            emit(listNode, BATCH_COUNT, vf.createLiteral(batchCount), GRAPH_REGISTRY);
            for (long k = 0; k < batchCount; k++) {
                IRI batch = listBatchNode(pk, th, k);
                emit(batch, INDEX, vf.createLiteral(k), GRAPH_REGISTRY);
                emit(batch, FIRST, vf.createLiteral(k * b), GRAPH_REGISTRY);
                emit(batch, LAST, vf.createLiteral(Math.min((k + 1) * b - 1, l.maxPosition)), GRAPH_REGISTRY);
                if (k + 1 < batchCount) {
                    emit(batch, NEXT, listBatchNode(pk, th, k + 1), GRAPH_REGISTRY);
                }
            }
        }

        long counterBatchCount = counter == 0 ? 0 : (counter - 1) / b + 1;
        emit(CHAIN_COUNTER, BATCH_COUNT, vf.createLiteral(counterBatchCount), GRAPH_REGISTRY);
        for (long k = 0; k < counterBatchCount; k++) {
            IRI batch = counterBatchNode(k);
            emit(CHAIN_COUNTER, BATCH, batch, GRAPH_REGISTRY);
            emit(batch, INDEX, vf.createLiteral(k), GRAPH_REGISTRY);
            emit(batch, FIRST, vf.createLiteral(k * b + 1), GRAPH_REGISTRY);
            emit(batch, LAST, vf.createLiteral(Math.min((k + 1) * b, counter)), GRAPH_REGISTRY);
            if (k + 1 < counterBatchCount) {
                emit(batch, NEXT, counterBatchNode(k + 1), GRAPH_REGISTRY);
            }
        }

        List<String> sortedAcs = new ArrayList<>(indexByAc.keySet());
        Collections.sort(sortedAcs);
        long idBatchCount = sortedAcs.isEmpty() ? 0 : (sortedAcs.size() - 1) / b + 1;
        emit(CHAIN_ID, BATCH_COUNT, vf.createLiteral(idBatchCount), GRAPH_REGISTRY);
        for (long k = 0; k < idBatchCount; k++) {
            IRI batch = idBatchNode(k);
            int from = (int) (k * b);
            int to = (int) Math.min((k + 1) * b, sortedAcs.size());
            emit(CHAIN_ID, BATCH, batch, GRAPH_REGISTRY);
            emit(batch, INDEX, vf.createLiteral(k), GRAPH_REGISTRY);
            emit(batch, FIRST_ID, vf.createLiteral(sortedAcs.get(from)), GRAPH_REGISTRY);
            emit(batch, LAST_ID, vf.createLiteral(sortedAcs.get(to - 1)), GRAPH_REGISTRY);
            for (int i = from; i < to; i++) {
                emit(batch, MEMBER, npNode(sortedAcs.get(i)), GRAPH_REGISTRY);
            }
            if (k + 1 < idBatchCount) {
                emit(batch, NEXT, idBatchNode(k + 1), GRAPH_REGISTRY);
            }
        }

        for (Map.Entry<String, String> e : typeUriByHash.entrySet()) {
            emit(hashNode(e.getKey()), VALUE, vf.createLiteral(e.getValue()), GRAPH_REGISTRY);
        }

        // pubkeys and the hashes behind them
        List<PubkeyState> sortedPubkeys = new ArrayList<>(pubkeys);
        sortedPubkeys.sort((x, y) -> x.hash.compareTo(y.hash));
        for (PubkeyState ps : sortedPubkeys) {
            IRI pubkeyNode = pubkeyNode(ps.hash);
            emit(REGISTRY, HAS_PUBKEY, pubkeyNode, GRAPH_REGISTRY);
            emit(pubkeyNode, PUBKEY, vf.createLiteral(ps.hash), GRAPH_REGISTRY);
            emit(hashNode(ps.hash), VALUE, vf.createLiteral(ps.pubkey), GRAPH_REGISTRY);
        }

        // A read-only registry computes no trust, so it publishes none: no accounts, no
        // agents, and one empty trust state snapshot, so that a consumer following the
        // trust state hash header finds the (empty) snapshot it points at. The hash is what
        // RegistryDB.calculateTrustStateHash yields for no trust paths at all.
        String trustStateHash = Utils.getHash("");
        IRI trustState = trustStateNode(trustStateHash);
        emit(REGISTRY, HAS_TRUST_STATE, trustState, GRAPH_REGISTRY);
        emit(trustState, TRUST_STATE_COUNTER, vf.createLiteral(1L), GRAPH_REGISTRY);
        emit(trustState, CREATED_AT, vf.createLiteral(now), GRAPH_REGISTRY);

        long setupId = opts.setupId != null ? opts.setupId : Math.abs(Utils.getRandom().nextLong());
        emit(SERVER_INFO, STATUS, vf.createLiteral("ready"), GRAPH_REGISTRY);
        emit(SERVER_INFO, SETUP_ID, vf.createLiteral(setupId), GRAPH_REGISTRY);
        emit(SERVER_INFO, TEST_INSTANCE, vf.createLiteral(opts.testInstance), GRAPH_REGISTRY);
        emit(SERVER_INFO, TRUST_STATE_COUNTER, vf.createLiteral(1L), GRAPH_REGISTRY);
        emit(SERVER_INFO, LAST_TRUST_STATE_UPDATE, vf.createLiteral(now), GRAPH_REGISTRY);
        emit(SERVER_INFO, TRUST_STATE_HASH, vf.createLiteral(trustStateHash), GRAPH_REGISTRY);
        if (opts.coverageTypes != null) {
            emit(SERVER_INFO, COVERAGE_TYPES, vf.createLiteral(opts.coverageTypes), GRAPH_REGISTRY);
        }
        if (opts.coverageAgents != null) {
            emit(SERVER_INFO, COVERAGE_AGENTS, vf.createLiteral(opts.coverageAgents), GRAPH_REGISTRY);
        }
        if (settingAc != null) {
            emit(SETTING, ORIGINAL, vf.createLiteral(settingAc), GRAPH_REGISTRY);
            emit(SETTING, CURRENT, vf.createLiteral(settingAc), GRAPH_REGISTRY);
        }

        emit(REGISTRY, NANOPUB_COUNT, vf.createLiteral(counter), GRAPH_REGISTRY);
        emit(REGISTRY, LOAD_COUNTER, vf.createLiteral(counter), GRAPH_REGISTRY);
        emit(REGISTRY, ACCOUNT_COUNT, vf.createLiteral(0), GRAPH_REGISTRY);
        emit(REGISTRY, AGENT_COUNT, vf.createLiteral(0), GRAPH_REGISTRY);
        emit(REGISTRY, BATCH_SIZE, vf.createLiteral(b), GRAPH_REGISTRY);
        emit(REGISTRY, BUILT_AT, vf.createLiteral(now), GRAPH_REGISTRY);
        emit(REGISTRY, BUILDER_VERSION, vf.createLiteral(Utils.getVersion()), GRAPH_REGISTRY);
        emit(REGISTRY, CONTENT_QUADS, vf.createLiteral(opts.contentQuads), GRAPH_REGISTRY);

        if (!pendingInvalidations.isEmpty()) {
            logger.info("{} invalidation targets are not part of this file", pendingInvalidations.size());
        }
    }

    // --- checks ------------------------------------------------------------------------

    private static final class RejectedException extends Exception {
        RejectedException(String message) {
            super(message);
        }
    }

    private String checkAndGetPubkey(Nanopub np, String ac) throws RejectedException {
        if (np.getTripleCount() > MAX_TRIPLES) {
            throw new RejectedException("triple count " + np.getTripleCount() + " exceeds limit of " + MAX_TRIPLES);
        }
        if (np.getByteCount() > MAX_BYTES) {
            throw new RejectedException("size " + np.getByteCount() + " bytes exceeds limit of " + MAX_BYTES);
        }
        if (!opts.localInstance && NanopubServerUtils.isProtectedNanopub(np)) {
            throw new RejectedException("has type npx:ProtectedNanopub, which is only accepted on local/private instances");
        }
        String nanopubUriStr = np.getUri().stringValue();
        for (IRI graphUri : np.getGraphUris()) {
            if (!graphUri.stringValue().startsWith(nanopubUriStr)) {
                throw new RejectedException("graph URI " + graphUri + " does not start with the nanopub base URI");
            }
        }
        if (opts.verify) {
            if (!RegistryDB.hasValidTrustyArtifactCode(np)) {
                throw new RejectedException("artifact code " + ac + " does not verify against its content");
            }
            String pubkey = RegistryDB.getPubkey(np);
            if (pubkey == null) {
                throw new RejectedException("no valid public key / signature found");
            }
            return pubkey;
        }
        try {
            NanopubSignatureElement el = SignatureUtils.getSignatureElement(np);
            if (el == null || el.getPublicKeyString() == null) {
                throw new RejectedException("no public key / signature found");
            }
            return el.getPublicKeyString();
        } catch (RejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RejectedException("malformed signature element: " + ex.getMessage());
        }
    }

    private boolean reject(String id, String reason) {
        rejected++;
        logger.warn("Rejecting nanopub {}: {}", id, reason);
        rejectListener.accept(id, reason);
        return false;
    }

    // --- lists -------------------------------------------------------------------------

    private int pubkeyIndex(String hash, String pubkey) {
        Integer idx = pubkeyIdxByHash.get(hash);
        if (idx == null) {
            idx = pubkeys.size();
            pubkeys.add(new PubkeyState(hash, pubkey));
            pubkeyIdxByHash.put(hash, idx);
        }
        return idx;
    }

    private int typeIndex(String hash, String uri) {
        Integer idx = typeIdxByHash.get(hash);
        if (idx == null) {
            idx = typeHashes.size();
            typeHashes.add(hash);
            typeIdxByHash.put(hash, idx);
            typeUriByHash.put(hash, uri);
        }
        return idx;
    }

    private ListState list(int pubkeyIdx, int typeIdx) {
        long key = ((long) pubkeyIdx << 32) | typeIdx;
        return lists.computeIfAbsent(key, k -> new ListState(lists.size(), pubkeyIdx, typeIdx));
    }

    private static boolean isOwnList(NpRecord r, ListState l) {
        if (r.pubkeyIdx != l.pubkeyIdx) {
            return false;
        }
        if (l.typeIdx == 0) {
            return true;
        }
        for (int t : r.typeIdx) {
            if (t == l.typeIdx) {
                return true;
            }
        }
        return false;
    }

    private static long membershipKey(ListState l, NpRecord r) {
        return ((long) l.id << 32) | r.idx;
    }

    /**
     * The lists a nanopub is on: its own plus any it joined as an invalidator.
     */
    private List<ListState> listsOf(NpRecord r) {
        List<ListState> result = new ArrayList<>();
        result.add(list(r.pubkeyIdx, 0));
        for (int t : r.typeIdx) {
            result.add(list(r.pubkeyIdx, t));
        }
        List<ListState> extra = extraListsByNp.get(r.idx);
        if (extra != null) {
            result.addAll(extra);
        }
        return result;
    }

    /**
     * Appends a nanopub to a list it joins as an invalidator. Its own lists are left to
     * {@link #add}, which appends to them exactly once, so they are skipped here whether
     * that step has run yet (the nanopub is the one being processed) or already has.
     */
    private void appendIfAbsent(ListState l, NpRecord r) {
        if (isOwnList(r, l)) {
            return;
        }
        if (!extraMemberships.add(membershipKey(l, r))) {
            return;
        }
        extraListsByNp.computeIfAbsent(r.idx, k -> new ArrayList<>()).add(l);
        appendEntry(l, r);
    }

    private void appendEntry(ListState l, NpRecord r) {
        long position = ++l.maxPosition;
        String checksum = NanopubUtils.updateXorChecksum(vf.createIRI(r.fullId), l.checksum);
        l.checksum = checksum;
        String pk = pubkeys.get(l.pubkeyIdx).hash;
        String th = typeHashes.get(l.typeIdx);
        IRI entry = entryNode(pk, th, position);
        emit(entry, LIST, listNode(pk, th), GRAPH_REGISTRY);
        emit(entry, POSITION, vf.createLiteral(position), GRAPH_REGISTRY);
        emit(entry, NP, npNode(r.ac), GRAPH_REGISTRY);
        emit(entry, NP_ID, vf.createLiteral(r.ac), GRAPH_REGISTRY);
        emit(entry, CHECKSUM, vf.createLiteral(checksum), GRAPH_REGISTRY);
        emit(listBatchNode(pk, th, positionBatchIndex(position, opts.batchSize)), MEMBER, entry, GRAPH_REGISTRY);
    }

    // --- output ------------------------------------------------------------------------

    private void emit(Resource s, IRI p, Value o, IRI graph) {
        out.handleStatement(vf.createStatement(s, p, o, graph));
    }

}
