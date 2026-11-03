package com.knowledgepixels.registry.store;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * The vocabulary and node naming of the registry meta graph in a read-only registry file.
 * Shared by the builder that writes the file and the SPARQL-backed store that reads it.
 *
 * <p>Namespace {@code npa:} = {@code http://purl.org/nanopub/admin/}. Two graphs:
 * {@link #GRAPH_REGISTRY} holds every small meta triple, {@link #GRAPH_BLOBS} holds only
 * the per-nanopub TriG and Jelly literals, so a text index can leave them out.
 */
public final class RegistryMeta {

    private RegistryMeta() {
    }

    public static final String NS = "http://purl.org/nanopub/admin/";

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    // graphs
    public static final IRI GRAPH_REGISTRY = iri("graph/registry");
    public static final IRI GRAPH_BLOBS = iri("graph/blobs");

    // singletons
    public static final IRI REGISTRY = iri("registry");
    public static final IRI SERVER_INFO = iri("serverInfo");
    public static final IRI SETTING = iri("setting");
    public static final IRI CHAIN_COUNTER = iri("chain/counter");
    public static final IRI CHAIN_ID = iri("chain/id");

    // predicates
    public static final IRI NANOPUB = iri("nanopub");
    public static final IRI ARTIFACT_CODE = iri("artifactCode");
    public static final IRI COUNTER = iri("counter");
    public static final IRI PUBKEY = iri("pubkey");
    public static final IRI INVALIDATED = iri("invalidated");
    public static final IRI TRIG = iri("trig");
    public static final IRI JELLY = iri("jelly");
    public static final IRI BATCH_COUNT = iri("batchCount");
    public static final IRI BATCH = iri("batch");
    public static final IRI INDEX = iri("index");
    public static final IRI FIRST = iri("first");
    public static final IRI LAST = iri("last");
    public static final IRI FIRST_ID = iri("firstId");
    public static final IRI LAST_ID = iri("lastId");
    public static final IRI MEMBER = iri("member");
    public static final IRI NEXT = iri("next");
    public static final IRI HAS_PUBKEY = iri("hasPubkey");
    public static final IRI HAS_LIST = iri("hasList");
    public static final IRI HAS_ACCOUNT = iri("hasAccount");
    public static final IRI HAS_AGENT = iri("hasAgent");
    public static final IRI HAS_TRUST_STATE = iri("hasTrustState");
    public static final IRI TYPE = iri("type");
    public static final IRI MAX_POSITION = iri("maxPosition");
    public static final IRI STATUS = iri("status");
    public static final IRI LIST = iri("list");
    public static final IRI POSITION = iri("position");
    public static final IRI NP = iri("np");
    public static final IRI NP_ID = iri("npId");
    public static final IRI CHECKSUM = iri("checksum");
    public static final IRI VALUE = iri("value");
    public static final IRI NANOPUB_COUNT = iri("nanopubCount");
    public static final IRI LOAD_COUNTER = iri("loadCounter");
    public static final IRI ACCOUNT_COUNT = iri("accountCount");
    public static final IRI AGENT_COUNT = iri("agentCount");
    public static final IRI BATCH_SIZE = iri("batchSize");
    public static final IRI BUILT_AT = iri("builtAt");
    public static final IRI BUILDER_VERSION = iri("builderVersion");
    public static final IRI CONTENT_QUADS = iri("contentQuads");
    public static final IRI SETUP_ID = iri("setupId");
    public static final IRI TEST_INSTANCE = iri("testInstance");
    public static final IRI TRUST_STATE_COUNTER = iri("trustStateCounter");
    public static final IRI LAST_TRUST_STATE_UPDATE = iri("lastTrustStateUpdate");
    public static final IRI TRUST_STATE_HASH = iri("trustStateHash");
    public static final IRI COVERAGE_TYPES = iri("coverageTypes");
    public static final IRI COVERAGE_AGENTS = iri("coverageAgents");
    public static final IRI ORIGINAL = iri("original");
    public static final IRI CURRENT = iri("current");
    public static final IRI AGENT = iri("agent");
    public static final IRI DEPTH = iri("depth");
    public static final IRI NAME = iri("name");
    public static final IRI NAME_CREATED_AT = iri("nameCreatedAt");
    public static final IRI INTRO_NANOPUB = iri("introNanopub");
    public static final IRI RATIO = iri("ratio");
    public static final IRI PATH_COUNT = iri("pathCount");
    public static final IRI QUOTA = iri("quota");
    public static final IRI AVG_PATH_COUNT = iri("avgPathCount");
    public static final IRI TOTAL_RATIO = iri("totalRatio");
    public static final IRI CREATED_AT = iri("createdAt");

    /**
     * The type token of the all-types list, as in MongoDB.
     */
    public static final String ALL_TYPES = "$";

    public static IRI iri(String localName) {
        return vf.createIRI(NS + localName);
    }

    public static IRI npNode(String artifactCode) {
        return iri("np/" + artifactCode);
    }

    public static IRI pubkeyNode(String pubkeyHash) {
        return iri("pubkey/" + pubkeyHash);
    }

    public static IRI listNode(String pubkeyHash, String typeHash) {
        return iri("list/" + pubkeyHash + "/" + typeHash);
    }

    public static IRI entryNode(String pubkeyHash, String typeHash, long position) {
        return iri("entry/" + pubkeyHash + "/" + typeHash + "/" + position);
    }

    public static IRI listBatchNode(String pubkeyHash, String typeHash, long index) {
        return iri("batch/list/" + pubkeyHash + "/" + typeHash + "/" + index);
    }

    public static IRI counterBatchNode(long index) {
        return iri("batch/counter/" + index);
    }

    public static IRI idBatchNode(long index) {
        return iri("batch/id/" + index);
    }

    public static IRI accountNode(String pubkeyHash) {
        return iri("account/" + pubkeyHash);
    }

    public static IRI agentNode(String agentIdHash) {
        return iri("agent/" + agentIdHash);
    }

    public static IRI trustStateNode(String hash) {
        return iri("trustState/" + hash);
    }

    public static IRI hashNode(String hash) {
        return iri("hash/" + hash);
    }

    /**
     * The batch a counter (1-based) belongs to.
     */
    public static long counterBatchIndex(long counter, long batchSize) {
        return (counter - 1) / batchSize;
    }

    /**
     * The batch a list position (0-based) belongs to.
     */
    public static long positionBatchIndex(long position, long batchSize) {
        return position / batchSize;
    }

    /**
     * The local name of a predicate, which is also its field name in the served documents.
     */
    public static String localName(IRI predicate) {
        return predicate.getLocalName();
    }

}
