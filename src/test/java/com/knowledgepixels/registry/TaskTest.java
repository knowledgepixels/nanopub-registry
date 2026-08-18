package com.knowledgepixels.registry;

import com.knowledgepixels.registry.db.IndexInitializer;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.testsuite.NanopubTestSuite;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Exercises the {@link Task} state machine against a real MongoDB.
 * <p>
 * Every task is driven directly through {@link Task#runTask}, with the database seeded
 * into the state that task expects, so each one can be checked in isolation: what it
 * writes, and which task it hands over to. Peer URLs are pinned to an empty list, which
 * makes {@code retrieveNanopubsFromPeers} return an empty stream — the tasks therefore
 * run their full logic without any network access.
 * <p>
 * Branches gated on {@code System.getenv} directly (rather than the injectable
 * {@code Utils.getEnv}) are not reachable from here: {@code REGISTRY_ENABLE_TRUST_CALCULATION},
 * {@code REGISTRY_ENABLE_OPTIONAL_LOAD}, {@code REGISTRY_PERFORM_FULL_LOAD} and
 * {@code REGISTRY_PRIORITIZE_ALL_PUBKEYS}.
 */
@Testcontainers
class TaskTest {

    /**
     * Shared across the class; each test gets a clean database because {@link #tearDown}
     * drops it and {@link #setUp} re-initialises the schema.
     */
    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.0");

    /**
     * Stands in for the artifact code of the setting nanopub in fixtures.
     */
    private static final String SETTING_ID = "RAsettingArtifactCode";

    /**
     * An agent introduction from the nanopub test suite: one declared RSA key, a
     * {@code foaf:name} and a creation timestamp — everything LOAD_DECLARATIONS reads.
     */
    private static final String INTRO_AC = "RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE";

    /**
     * An index nanopub from the test suite, used as the agent intro collection that
     * {@code test-setting.trig} points at.
     */
    private static final String AGENT_INDEX_AC = "RApww43dy8UvCoEc8QKOaXhojCTgao3ZXX_d6V_jVBo6s";

    private FakeEnv fakeEnv;
    private ClientSession session;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Set up fake environment - note that this must be done before RegistryDB.init() is called
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.setupDBEnv(mongoDBContainer, "nanopubRegistry");
        TestUtils.clearStaticFields(RegistryDB.class, "mongoClient", "mongoDB");

        // Initialize RegistryDB
        RegistryDB.init();

        // Clear static fields in Task class - this must always be run after the RegistryDB is initialized
        TestUtils.clearStaticFields(Task.class, new HashMap<>() {{
            put("tasksCollection", collection(Collection.TASKS.toString()));
        }});

        // No peers: every peer fetch yields an empty stream, keeping these tests off the network.
        TestUtils.clearStaticFields(Utils.class, Map.of("peerUrls", List.of()));
        // The setting is cached statically; drop any copy another test class loaded.
        TestUtils.clearStaticFields(Utils.class, "settingNp");
        CoverageFilter.init();
        AgentFilter.init();

        session = RegistryDB.getClient().startSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        RegistryDB.getDB().drop();
        RegistryDB.getClient().close();
        TestUtils.clearStaticFields(AgentFilter.class, Map.of("enforceQuota", false));
        TestUtils.clearStaticFields(Utils.class, "settingNp", "peerUrls");
        TestUtils.cleanupDataDir();
        fakeEnv.reset();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs INIT_DB and LOAD_CONFIG, leaving the registry in the {@code launching} state
     * with a setupId and a trust state counter of 0.
     */
    private void bootstrap() throws Exception {
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());
    }

    /**
     * Makes the registry's setting file available and points the environment at it.
     */
    private void useSettingFile() throws Exception {
        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();
    }

    /**
     * Points the registry at the test fixture setting, whose agent intro collection is an
     * index nanopub from the test suite rather than the live one.
     */
    private void useTestSuiteSettingFile() throws Exception {
        Path settingFile = TestUtils.copyClasspathResourceToDataDir("test-setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", settingFile.toString()).build();
    }

    /**
     * Stores a nanopub from the nanopub test suite in the local nanopub store, so the
     * loader resolves it locally instead of asking a peer for it.
     */
    private Nanopub seedTestSuiteNanopub(String artifactCode) throws Exception {
        Nanopub np = new NanopubImpl(
                NanopubTestSuite.getLatest().getByArtifactCode(artifactCode).getFirst().toFile());
        assertTrue(RegistryDB.loadNanopub(session, np),
                "the test suite nanopub is signed and accepted by the store");
        return np;
    }

    private void setStatus(ServerStatus status) {
        RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "status", status.toString());
    }

    private void seed(String collectionName, Document doc) {
        RegistryDB.insert(session, collectionName, doc);
    }

    private List<Document> all(String collectionName) {
        return collection(collectionName).find(session).into(new ArrayList<>());
    }

    private Document one(String collectionName, Document filter) {
        return collection(collectionName).find(session, filter).first();
    }

    /**
     * Empties the task queue so a test only sees what the task under test scheduled.
     */
    private void clearQueue() {
        collection(Collection.TASKS.toString()).deleteMany(session, new Document());
    }

    private List<String> queuedActions() {
        return all(Collection.TASKS.toString()).stream().map(d -> d.getString("action")).toList();
    }

    private Document queuedTask(Task task) {
        return one(Collection.TASKS.toString(), new Document("action", task.name()));
    }

    /**
     * Runs the task {@code predecessor} just queued, and removes it from the queue so that
     * {@link #queuedActions()} still reports only what the chain scheduled last.
     * <p>
     * Issue #128 split every task that cannot be transactional away from the writes it used
     * to make: the DDL and network half stays in the original task, the derived writes move
     * to a successor it schedules. Tests that assert on those writes have to run both halves.
     */
    private void runQueuedSuccessor(Task predecessor, Task successor) throws Exception {
        Document taskDoc = queuedTask(successor);
        assertNotNull(taskDoc, predecessor.name() + " schedules " + successor.name());
        collection(Collection.TASKS.toString()).deleteMany(session, new Document("action", successor.name()));
        Task.runTask(successor, taskDoc);
    }

    /**
     * Puts the registry in a state where a task operating on the {@code *_loading}
     * collections can run: indexes created, setting id available, queue empty.
     */
    private void prepareLoadingCollections() throws Exception {
        bootstrap();
        IndexInitializer.initLoadingCollections(session);
        RegistryDB.setValue(session, Collection.SETTING.toString(), "current", SETTING_ID);
        clearQueue();
    }

    // ------------------------------------------------------- INIT_DB / config

    @Test
    void initDB() throws Exception {
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());

        assertEquals(ServerStatus.launching.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        assertNotNull(RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "setupId"));
        assertNotNull(RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "testInstance"));
        assertEquals(1, RegistryDB.collection(Collection.TASKS.toString()).countDocuments(session));
        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(session).first().getString("action"), Task.LOAD_CONFIG.asDocument().getString("action"));
    }

    @Test
    void initDBRefusesToReinitialiseAnExistingDatabase() throws Exception {
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());

        // A second INIT_DB would mint a new setupId and invalidate every peer's tracking state.
        assertThrows(RuntimeException.class, () -> Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument()));
    }

    @Test
    void loadConfig() throws Exception {
        Task.runTask(Task.INIT_DB, Task.INIT_DB.asDocument());
        Task.runTask(Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument());

        assertNull(RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "coverageTypes"));
        assertNull(RegistryDB.getValue(session, Collection.SERVER_INFO.toString(), "coverageAgents"));

        assertEquals(RegistryDB.collection(Collection.TASKS.toString()).find(session).sort(Sorts.descending("not-before")).first().getString("action"), Task.LOAD_SETTING.asDocument().getString("action"));
    }

    @Test
    void loadConfigRequiresTheLaunchingStatus() throws Exception {
        bootstrap();
        setStatus(ServerStatus.ready);

        assertThrows(IllegalTaskStatusException.class,
                () -> Task.runTask(Task.LOAD_CONFIG, Task.LOAD_CONFIG.asDocument()));
    }

    @Test
    void loadSetting() throws Exception {
        bootstrap();
        useSettingFile();

        Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        assertNotNull(RegistryDB.getValue(session, Collection.SETTING.toString(), "original"));
        assertNotNull(RegistryDB.getValue(session, Collection.SETTING.toString(), "current"));

        assertNotNull(RegistryDB.getValue(session, Collection.SETTING.toString(), "bootstrap-services"));

        assertEquals(ServerStatus.coreLoading.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        // LOAD_SETTING schedules both LOAD_FULL and INIT_COLLECTIONS with no delay;
        // relative order between them is not significant — LOAD_FULL's status guard
        // handles either execution order.
        List<String> actions = queuedActions();
        assertTrue(actions.contains(Task.LOAD_FULL.name()));
        assertTrue(actions.contains(Task.INIT_COLLECTIONS.name()));
    }

    @Test
    void loadSettingSkipsTheFullLoadWhenItIsDisabled() throws Exception {
        bootstrap();
        useSettingFile();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        clearQueue();

        Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());

        // A core-only registry serves the trust state without mirroring everyone's nanopubs.
        assertEquals(List.of(Task.INIT_COLLECTIONS.name()), queuedActions());
        assertEquals(ServerStatus.coreLoading.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
    }

    @Test
    void loadSettingRequiresTheLaunchingStatus() throws Exception {
        bootstrap();
        useSettingFile();
        setStatus(ServerStatus.updating);

        assertThrows(IllegalTaskStatusException.class,
                () -> Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument()));
    }

    // ------------------------------------------------------- INIT_COLLECTIONS

    @Test
    void initCollectionsRequiresALoadingStatus() throws Exception {
        bootstrap();
        setStatus(ServerStatus.ready);

        // INIT_COLLECTIONS rebuilds the *_loading collections from scratch; running it while
        // the registry is serving would discard a live trust state.
        assertThrows(IllegalTaskStatusException.class,
                () -> Task.runTask(Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument()));
    }

    @Test
    void initCollectionsSeedsExplicitPubkeysWhenTrustCalculationIsDisabled() throws Exception {
        bootstrap();
        fakeEnv.addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false")
                .addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting PK1:500 PK2:900").build();
        AgentFilter.init();
        setStatus(ServerStatus.coreLoading);
        clearQueue();

        Task.runTask(Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument());
        runQueuedSuccessor(Task.INIT_COLLECTIONS, Task.SEED_TRUST_STATE);

        // With no trust network to walk there is nothing to expand: the explicitly
        // configured pubkeys become accounts directly and the cycle jumps to the end.
        assertEquals(2, all("accounts_loading").size());
        Document pk1 = one("accounts_loading", new Document("pubkey", "PK1"));
        assertEquals(EntryStatus.toLoad.getValue(), pk1.getString("status"));
        assertEquals(500, pk1.getInteger("quota"));
        assertEquals(0, pk1.getInteger("depth"));
        assertEquals(900, one("accounts_loading", new Document("pubkey", "PK2")).getInteger("quota"));
        assertTrue(all("trustPaths_loading").isEmpty(), "no root trust path is built");
        assertTrue(all("endorsements_loading").isEmpty(), "no endorsements are fetched");
        assertEquals(List.of(Task.FINALIZE_TRUST_STATE.name()), queuedActions());
    }

    @Test
    void initCollectionsDoesNotDuplicateExplicitPubkeysOnRerun() throws Exception {
        bootstrap();
        fakeEnv.addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false")
                .addVariable("REGISTRY_COVERAGE_AGENTS", "PK1:500").build();
        AgentFilter.init();
        setStatus(ServerStatus.coreLoading);

        // INIT_COLLECTIONS runs once per update cycle, so seeding has to be idempotent.
        Task.runTask(Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument());
        runQueuedSuccessor(Task.INIT_COLLECTIONS, Task.SEED_TRUST_STATE);
        Task.runTask(Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument());
        runQueuedSuccessor(Task.INIT_COLLECTIONS, Task.SEED_TRUST_STATE);

        assertEquals(1, all("accounts_loading").size());
    }

    @Test
    void initCollectionsSeedsTheRootFromTheAgentIntroCollection() throws Exception {
        bootstrap();
        useTestSuiteSettingFile();
        RegistryDB.setValue(session, Collection.SETTING.toString(), "current", SETTING_ID);
        Nanopub agentIndex = seedTestSuiteNanopub(AGENT_INDEX_AC);
        setStatus(ServerStatus.coreLoading);
        clearQueue();

        Task.runTask(Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument());
        runQueuedSuccessor(Task.INIT_COLLECTIONS, Task.SEED_TRUST_STATE);

        // The root of the trust network: the base agent, at depth 0, holding the full ratio.
        Document root = one("trustPaths_loading", new Document("_id", "$"));
        assertNotNull(root);
        assertEquals(1.0, root.getDouble("ratio"), 1e-12);
        assertEquals("extended", root.getString("type"));
        assertEquals(EntryStatus.visited.getValue(),
                one("accounts_loading", new Document("agent", "$")).getString("status"));

        // One endorsement per element of the intro collection, all waiting to be fetched.
        int elementCount = IndexUtils.castToIndex(agentIndex).getElements().size();
        assertTrue(elementCount > 0, "the test suite index has elements to endorse");
        List<Document> endorsements = all("endorsements_loading");
        assertEquals(elementCount, endorsements.size());
        for (Document e : endorsements) {
            assertEquals("$", e.getString("agent"));
            assertEquals(EntryStatus.toRetrieve.getValue(), e.getString("status"));
            assertEquals(SETTING_ID, e.getString("source"), "the endorsement is traced back to the setting");
            assertNotNull(e.getString("endorsedNanopub"));
        }

        Document next = queuedTask(Task.LOAD_DECLARATIONS);
        assertNotNull(next);
        assertEquals(1, next.getInteger("depth"), "the iteration starts one level below the root");
    }

    // ------------------------------------------------------ LOAD_DECLARATIONS

    /**
     * Seeds an endorsement of the test suite's intro nanopub, as INIT_COLLECTIONS would.
     */
    private void seedIntroEndorsement(String endorsedArtifactCode, String source) {
        seed("endorsements_loading", new Document("agent", "$").append("pubkey", "$")
                .append("endorsedNanopub", endorsedArtifactCode).append("source", source)
                .append("status", EntryStatus.toRetrieve.getValue()));
    }

    @Test
    void loadDeclarationsTurnsAnIntroIntoATrustEdgeAndAccount() throws Exception {
        prepareLoadingCollections();
        Nanopub introNp = seedTestSuiteNanopub(INTRO_AC);
        seedIntroEndorsement(INTRO_AC, SETTING_ID);

        Task.runTask(Task.LOAD_DECLARATIONS, Task.LOAD_DECLARATIONS.asDocument().append("depth", 1));

        // Expectations are derived from the intro itself, so the test survives test suite
        // updates while still proving the task read the real declaration.
        IntroNanopub intro = new IntroNanopub(introNp);
        String expectedAgent = intro.getUser().stringValue();
        KeyDeclaration key = intro.getKeyDeclarations().iterator().next();
        String expectedPubkeyHash = Utils.getHash(key.getPublicKeyString());
        String expectedName = Utils.extractIntroName(intro);
        assertNotNull(expectedName, "the test suite intro declares a foaf:name");

        List<Document> edges = all("trustEdges");
        assertEquals(1, edges.size(), "one declared key means one trust edge");
        Document edge = edges.getFirst();
        assertEquals("$", edge.getString("fromAgent"));
        assertEquals("$", edge.getString("fromPubkey"));
        assertEquals(expectedAgent, edge.getString("toAgent"));
        assertEquals(expectedPubkeyHash, edge.getString("toPubkey"));
        assertEquals(SETTING_ID, edge.getString("source"));
        assertFalse(edge.getBoolean("invalidated"), "nothing has retracted this declaration");

        Document account = one("accounts_loading", new Document("agent", expectedAgent));
        assertNotNull(account);
        assertEquals(expectedPubkeyHash, account.getString("pubkey"));
        assertEquals(EntryStatus.seen.getValue(), account.getString("status"));
        assertEquals(1, account.getInteger("depth"));
        assertEquals(expectedName, account.getString("name"));
        assertEquals(introNp.getUri().stringValue(), account.getString("introNanopub"),
                "the authorizing intro is recorded alongside the name");
        assertNotNull(account.getDate("nameCreatedAt"), "the intro's creation time is kept for the name policy");

        assertEquals(EntryStatus.retrieved.getValue(),
                one("endorsements_loading", new Document("endorsedNanopub", INTRO_AC)).getString("status"));
        assertNotNull(queuedTask(Task.EXPAND_TRUST_PATHS));
    }

    @Test
    void loadDeclarationsKeepsOneAccountWhenTheSameIntroIsEndorsedTwice() throws Exception {
        prepareLoadingCollections();
        Nanopub introNp = seedTestSuiteNanopub(INTRO_AC);
        // Two endorsers vouch for the same intro; each is its own trust edge, but the
        // (agent, pubkey) account exists only once.
        seedIntroEndorsement(INTRO_AC, SETTING_ID);
        seedIntroEndorsement(INTRO_AC, "RAotherEndorsingNanopub");

        Task.runTask(Task.LOAD_DECLARATIONS, Task.LOAD_DECLARATIONS.asDocument().append("depth", 1));

        String agent = new IntroNanopub(introNp).getUser().stringValue();
        assertEquals(2, all("trustEdges").size());
        assertEquals(1, all("accounts_loading").stream()
                .filter(d -> agent.equals(d.getString("agent"))).count());
        // The second pass sees the same creation time, which is not strictly newer, so the
        // recorded name is left as it was.
        assertEquals(Utils.extractIntroName(new IntroNanopub(introNp)),
                one("accounts_loading", new Document("agent", agent)).getString("name"));
    }

    @Test
    void loadDeclarationsDiscardsEndorsementsOfNonIntroNanopubs() throws Exception {
        prepareLoadingCollections();
        // An index nanopub is a perfectly valid nanopub, but it declares no user, so it
        // cannot authorize anyone.
        seedTestSuiteNanopub(AGENT_INDEX_AC);
        seedIntroEndorsement(AGENT_INDEX_AC, SETTING_ID);

        Task.runTask(Task.LOAD_DECLARATIONS, Task.LOAD_DECLARATIONS.asDocument().append("depth", 1));

        assertEquals(EntryStatus.discarded.getValue(),
                one("endorsements_loading", new Document("endorsedNanopub", AGENT_INDEX_AC)).getString("status"));
        assertTrue(all("trustEdges").isEmpty());
        assertTrue(all("accounts_loading").isEmpty());
    }

    @Test
    void loadDeclarationsWithNothingToRetrieveMovesOnToExpansion() throws Exception {
        prepareLoadingCollections();

        Task.runTask(Task.LOAD_DECLARATIONS, Task.LOAD_DECLARATIONS.asDocument().append("depth", 3));

        Document next = queuedTask(Task.EXPAND_TRUST_PATHS);
        assertNotNull(next, "expansion is scheduled even when no declarations were pending");
        assertEquals(3, next.getInteger("depth"), "the depth is carried over unchanged");
    }

    // ----------------------------------------------------- EXPAND_TRUST_PATHS

    /**
     * Seeds the root account and trust path that INIT_COLLECTIONS would create.
     */
    private void seedRootTrustPath(double ratio) {
        seed("accounts_loading", new Document("agent", "$").append("pubkey", "$")
                .append("status", EntryStatus.visited.getValue()).append("depth", 0));
        seed("trustPaths_loading", new Document("_id", "$").append("sorthash", "")
                .append("agent", "$").append("pubkey", "$").append("depth", 0)
                .append("ratio", ratio).append("type", "extended"));
    }

    private void seedTrustEdge(String toAgent, String toPubkey, boolean invalidated) {
        seed("trustEdges", new Document("fromAgent", "$").append("fromPubkey", "$")
                .append("toAgent", toAgent).append("toPubkey", toPubkey)
                .append("source", "RAsourceNanopub").append("invalidated", invalidated));
    }

    @Test
    void expandTrustPathsSplitsRatioPerAgentThenPerKey() throws Exception {
        prepareLoadingCollections();
        seedRootTrustPath(1.0);
        // Agent B holds two keys, agent C one. The ratio is divided by agents first, then
        // by that agent's keys, so a multi-key agent gains no extra weight.
        seedTrustEdge("B", "Q1", false);
        seedTrustEdge("B", "Q2", false);
        seedTrustEdge("C", "R", false);

        Task.runTask(Task.EXPAND_TRUST_PATHS, Task.EXPAND_TRUST_PATHS.asDocument().append("depth", 1));

        assertEquals(0.225, one("trustPaths_loading", new Document("_id", "$ B|Q1")).getDouble("ratio"), 1e-12);
        assertEquals(0.225, one("trustPaths_loading", new Document("_id", "$ B|Q2")).getDouble("ratio"), 1e-12);
        assertEquals(0.45, one("trustPaths_loading", new Document("_id", "$ C|R")).getDouble("ratio"), 1e-12);

        // 90% was handed to the children; the parent keeps the remaining 10% and is no
        // longer extendable.
        Document root = one("trustPaths_loading", new Document("_id", "$"));
        assertEquals(0.1, root.getDouble("ratio"), 1e-12);
        assertEquals("primary", root.getString("type"));

        assertEquals(EntryStatus.expanded.getValue(),
                one("accounts_loading", new Document("agent", "$")).getString("status"));

        Document next = queuedTask(Task.LOAD_CORE);
        assertNotNull(next);
        assertEquals(1, next.getInteger("depth"));
        assertEquals(0, next.getInteger("load-count"));
    }

    @Test
    void expandTrustPathsIgnoresInvalidatedEdges() throws Exception {
        prepareLoadingCollections();
        seedRootTrustPath(1.0);
        seedTrustEdge("B", "Q", false);
        seedTrustEdge("C", "R", true);

        Task.runTask(Task.EXPAND_TRUST_PATHS, Task.EXPAND_TRUST_PATHS.asDocument().append("depth", 1));

        assertNotNull(one("trustPaths_loading", new Document("_id", "$ B|Q")));
        assertNull(one("trustPaths_loading", new Document("_id", "$ C|R")),
                "a retracted key declaration must not extend the trust network");
        // The invalidated edge is not counted when dividing, so B gets the full 90%.
        assertEquals(0.9, one("trustPaths_loading", new Document("_id", "$ B|Q")).getDouble("ratio"), 1e-12);
    }

    @Test
    void expandTrustPathsDefersAccountsWithoutATrustPathYet() throws Exception {
        prepareLoadingCollections();
        // Visited at depth 0 but no matching trust path: the account is pushed to the next
        // depth so a later iteration can pick it up, rather than being dropped.
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.visited.getValue()).append("depth", 0));

        Task.runTask(Task.EXPAND_TRUST_PATHS, Task.EXPAND_TRUST_PATHS.asDocument().append("depth", 1));

        Document account = one("accounts_loading", new Document("agent", "A"));
        assertEquals(EntryStatus.visited.getValue(), account.getString("status"));
        assertEquals(1, account.getInteger("depth"));
    }

    // ------------------------------------------------------------- LOAD_CORE

    @Test
    void loadCoreFinishesTheIterationWhenNoAccountsRemain() throws Exception {
        prepareLoadingCollections();

        Task.runTask(Task.LOAD_CORE, Task.LOAD_CORE.asDocument().append("depth", 2).append("load-count", 7));

        Document next = queuedTask(Task.FINISH_ITERATION);
        assertNotNull(next);
        assertEquals(2, next.getInteger("depth"));
        assertEquals(7, next.getInteger("load-count"), "the load count is carried into the decision");
    }

    @Test
    void loadCoreSkipsAccountsWithoutATrustPath() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.seen.getValue()).append("depth", 1));

        Task.runTask(Task.LOAD_CORE, Task.LOAD_CORE.asDocument().append("depth", 1).append("load-count", 0));

        assertEquals(EntryStatus.skipped.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        // A skipped account is not a load, so the count must not advance.
        assertEquals(0, queuedTask(Task.LOAD_CORE).getInteger("load-count"));
    }

    @Test
    void loadCoreSkipsNegligibleTrustButStillRecordsThePubkey() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.seen.getValue()).append("depth", 1));
        seed("trustPaths_loading", new Document("_id", "$ A|P").append("sorthash", "x")
                .append("agent", "A").append("pubkey", "P").append("depth", 1)
                .append("ratio", 1e-12).append("type", "extended"));

        Task.runTask(Task.LOAD_CORE, Task.LOAD_CORE.asDocument().append("depth", 1).append("load-count", 0));

        assertEquals(EntryStatus.skipped.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        // Below the ratio floor the core is not fetched, but the pubkey is remembered so
        // RUN_OPTIONAL_LOAD can still pick it up later.
        Document list = one("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH));
        assertNotNull(list);
        assertEquals(EntryStatus.encountered.getValue(), list.getString("status"));
        assertEquals(1, queuedTask(Task.LOAD_CORE).getInteger("load-count"));
    }

    @Test
    void loadCoreMarksAnAccountVisitedOnceItsCoreIsLoaded() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.seen.getValue()).append("depth", 1));
        seed("trustPaths_loading", new Document("_id", "$ A|P").append("sorthash", "x")
                .append("agent", "A").append("pubkey", "P").append("depth", 1)
                .append("ratio", 0.5).append("type", "extended"));

        // With no peers the intro and endorsement streams are empty, so this exercises the
        // bookkeeping around the fetch rather than the fetch itself.
        Task.runTask(Task.LOAD_CORE, Task.LOAD_CORE.asDocument().append("depth", 1).append("load-count", 0));

        assertEquals(EntryStatus.visited.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)).getString("status"));
        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", NanopubLoader.ENDORSE_TYPE_HASH)).getString("status"));
        // The full list is only marked as encountered here; LOAD_FULL loads it later.
        assertEquals(EntryStatus.encountered.getValue(),
                one("lists", new Document("pubkey", "P").append("type", "$")).getString("status"));
        assertEquals(1, queuedTask(Task.LOAD_CORE).getInteger("load-count"));
    }

    // -------------------------------------------------------- FINISH_ITERATION

    @Test
    void finishIterationStopsWhenNothingWasLoaded() throws Exception {
        prepareLoadingCollections();

        Task.runTask(Task.FINISH_ITERATION, Task.FINISH_ITERATION.asDocument().append("depth", 3).append("load-count", 0));

        assertEquals(List.of(Task.CALCULATE_TRUST_SCORES.name()), queuedActions());
    }

    @Test
    void finishIterationStopsAtTheMaximumDepth() throws Exception {
        prepareLoadingCollections();

        // MAX_TRUST_PATH_DEPTH is 10; going deeper would keep the trust network expanding
        // indefinitely.
        Task.runTask(Task.FINISH_ITERATION, Task.FINISH_ITERATION.asDocument().append("depth", 10).append("load-count", 5));

        assertEquals(List.of(Task.CALCULATE_TRUST_SCORES.name()), queuedActions());
    }

    @Test
    void finishIterationProgressesToTheNextDepth() throws Exception {
        prepareLoadingCollections();

        Task.runTask(Task.FINISH_ITERATION, Task.FINISH_ITERATION.asDocument().append("depth", 2).append("load-count", 5));

        Document next = queuedTask(Task.LOAD_DECLARATIONS);
        assertNotNull(next);
        assertEquals(3, next.getInteger("depth"));
    }

    // ------------------------------------------------- CALCULATE_TRUST_SCORES

    private void seedExpandedAccount() {
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.expanded.getValue()).append("depth", 1));
    }

    private void seedTrustPath(String id, int depth, double ratio) {
        seed("trustPaths_loading", new Document("_id", id).append("sorthash", id)
                .append("agent", "A").append("pubkey", "P").append("depth", depth)
                .append("ratio", ratio).append("type", "primary"));
    }

    @Test
    void calculateTrustScoresSumsRatiosAndCountsIndependentPaths() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        seedTrustPath("$ X|1 A|P", 2, 0.25);
        seedTrustPath("$ Y|1 A|P", 2, 0.5);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", "A"));
        assertEquals(EntryStatus.processed.getValue(), account.getString("status"));
        assertEquals(0.75, account.getDouble("ratio"), 1e-12);
        assertEquals(2, account.getInteger("pathCount"), "paths through different agents count separately");
        assertEquals(List.of(Task.AGGREGATE_AGENTS.name()), queuedActions());
    }

    @Test
    void calculateTrustScoresCountsOverlappingPathsOnce() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        // Both paths run through X, so they are not independent endorsements.
        seedTrustPath("$ X|1 A|P", 2, 0.25);
        seedTrustPath("$ X|1 Y|2 A|P", 3, 0.25);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", "A"));
        assertEquals(0.5, account.getDouble("ratio"), 1e-12, "the ratio still accumulates over both paths");
        assertEquals(1, account.getInteger("pathCount"));
    }

    @Test
    void calculateTrustScoresDerivesQuotaFromTheRatio() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        // 2^-15 of the global quota of 1_000_000_000 lands between the per-user bounds.
        seedTrustPath("$ X|1 A|P", 2, 0.000030517578125);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        assertEquals(30517, one("accounts_loading", new Document("agent", "A")).getInteger("quota"));
    }

    @Test
    void calculateTrustScoresClampsQuotaToTheMinimum() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        seedTrustPath("$ X|1 A|P", 2, 1e-9);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        // Even a barely-trusted account is allowed a usable allowance.
        assertEquals(1000, one("accounts_loading", new Document("agent", "A")).getInteger("quota"));
    }

    @Test
    void calculateTrustScoresClampsQuotaToTheMaximum() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        seedTrustPath("$ X|1 A|P", 2, 0.5);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        // No single account may claim an unbounded share of the registry.
        assertEquals(100000, one("accounts_loading", new Document("agent", "A")).getInteger("quota"));
    }

    // ------------------------------------------------------- AGGREGATE_AGENTS

    private void seedProcessedAccount(String agent, String pubkey, int pathCount, double ratio, String name) {
        seed("accounts_loading", new Document("agent", agent).append("pubkey", pubkey)
                .append("status", EntryStatus.processed.getValue())
                .append("pathCount", pathCount).append("ratio", ratio).append("name", name));
    }

    @Test
    void aggregateAgentsFoldsAllKeysOfAnAgentIntoOneRow() throws Exception {
        prepareLoadingCollections();
        seedProcessedAccount("A", "P1", 2, 0.25, "Alice");
        seedProcessedAccount("A", "P2", 4, 0.5, "Alice");

        Task.runTask(Task.AGGREGATE_AGENTS, Task.AGGREGATE_AGENTS.asDocument());

        Document agent = one("agents_loading", new Document("agent", "A"));
        assertNotNull(agent);
        assertEquals(2, agent.getInteger("accountCount"));
        assertEquals(3.0, agent.getDouble("avgPathCount"), 1e-12);
        assertEquals(0.75, agent.getDouble("totalRatio"), 1e-12);
        assertEquals(2, all("accounts_loading").stream()
                .filter(d -> EntryStatus.aggregated.getValue().equals(d.getString("status"))).count());
        assertEquals(List.of(Task.ASSIGN_PUBKEYS.name()), queuedActions());
    }

    @Test
    void aggregateAgentsTakesTheNameFromTheMostTrustedKey() throws Exception {
        prepareLoadingCollections();
        seedProcessedAccount("A", "P1", 1, 0.25, "Old Name");
        seedProcessedAccount("A", "P2", 1, 0.5, "Current Name");

        Task.runTask(Task.AGGREGATE_AGENTS, Task.AGGREGATE_AGENTS.asDocument());

        assertEquals("Current Name", one("agents_loading", new Document("agent", "A")).getString("name"));
    }

    @Test
    void aggregateAgentsBreaksNameTiesDeterministically() throws Exception {
        prepareLoadingCollections();
        // Equal trust: the lexicographic minimum keeps the choice stable across rebuilds.
        seedProcessedAccount("A", "P1", 1, 0.25, "Bob");
        seedProcessedAccount("A", "P2", 1, 0.25, "Alice");

        Task.runTask(Task.AGGREGATE_AGENTS, Task.AGGREGATE_AGENTS.asDocument());

        assertEquals("Alice", one("agents_loading", new Document("agent", "A")).getString("name"));
    }

    // --------------------------------------------------------- ASSIGN_PUBKEYS

    @Test
    void assignPubkeysApprovesUniquelyClaimedKeys() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.aggregated.getValue()));

        Task.runTask(Task.ASSIGN_PUBKEYS, Task.ASSIGN_PUBKEYS.asDocument());

        assertEquals(EntryStatus.approved.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        assertEquals(List.of(Task.DETERMINE_UPDATES.name()), queuedActions());
    }

    @Test
    void assignPubkeysContestsKeysClaimedByMoreThanOneAgent() throws Exception {
        prepareLoadingCollections();
        // The same key declared by two agents cannot be attributed to either of them.
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.aggregated.getValue()));
        seed("accounts_loading", new Document("agent", "B").append("pubkey", "P")
                .append("status", EntryStatus.aggregated.getValue()));

        Task.runTask(Task.ASSIGN_PUBKEYS, Task.ASSIGN_PUBKEYS.asDocument());

        for (Document d : all("accounts_loading")) {
            assertEquals(EntryStatus.contested.getValue(), d.getString("status"));
        }
    }

    // ------------------------------------------------------- DETERMINE_UPDATES

    @Test
    void determineUpdatesQueuesUnknownAccountsForLoading() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()));

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertEquals(EntryStatus.toLoad.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        assertEquals(List.of(Task.FINALIZE_TRUST_STATE.name()), queuedActions());
    }

    @Test
    void determineUpdatesLeavesAlreadyLoadedAccountsAlone() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()));
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.loaded.getValue()));

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        // Its nanopubs are already in the registry, so there is nothing to fetch.
        assertEquals(EntryStatus.loaded.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
    }

    // --------------------------------------------------- FINALIZE_TRUST_STATE

    @Test
    void finalizeTrustStateHandsTheComputedHashToReleaseData() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()));
        seedTrustPath("$ A|P", 1, 0.5);

        Task.runTask(Task.FINALIZE_TRUST_STATE, Task.FINALIZE_TRUST_STATE.asDocument());

        assertNotNull(getValue(session, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate"));
        Document next = queuedTask(Task.RELEASE_DATA);
        assertNotNull(next);
        assertEquals(RegistryDB.calculateTrustStateHash(session), next.getString("newTrustStateHash"));
        assertNull(next.getString("previousTrustStateHash"), "there is no previous state on the first cycle");
    }

    // ------------------------------------------------------------ RELEASE_DATA

    /**
     * Seeds a complete loading state ready to be published.
     */
    private void seedReleasableState() {
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.loaded.getValue()).append("depth", 1)
                .append("name", "Alice").append("pathCount", 2).append("ratio", 0.5).append("quota", 1000));
        // The synthetic root and staging accounts are internal and must stay out of snapshots.
        seed("accounts_loading", new Document("agent", "$").append("pubkey", "$")
                .append("status", EntryStatus.approved.getValue()).append("depth", 0));
        seed("accounts_loading", new Document("agent", "B").append("pubkey", "Q")
                .append("status", EntryStatus.toLoad.getValue()).append("depth", 1));
        seedTrustPath("$ A|P", 1, 0.5);
        seed("agents_loading", new Document("agent", "A").append("accountCount", 1)
                .append("avgPathCount", 2.0).append("totalRatio", 0.5));
        seed("endorsements_loading", new Document("agent", "A").append("pubkey", "P")
                .append("endorsedNanopub", "RAxyz").append("status", EntryStatus.retrieved.getValue()));
    }

    @Test
    void releaseDataPublishesTheLoadingCollectionsAndSnapshotsTheState() throws Exception {
        prepareLoadingCollections();
        // Without a full load there is no backlog to wait for, so the first cycle publishes
        // straight away rather than being held back as a bootstrap state (see the test below).
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        setStatus(ServerStatus.coreLoading);
        seedReleasableState();

        Task.runTask(Task.RELEASE_DATA,
                Task.RELEASE_DATA.asDocument().append("newTrustStateHash", "hash1"));
        runQueuedSuccessor(Task.RELEASE_DATA, Task.PUBLISH_TRUST_STATE);

        // The *_loading collections become the live ones.
        assertEquals(3, all(Collection.ACCOUNTS.toString()).size());
        assertEquals(1, all(Collection.AGENTS.toString()).size());
        assertEquals(1, all("trustPaths").size());
        assertEquals(1, all("endorsements").size());
        assertFalse(RegistryDB.hasCollection("accounts_loading"));

        assertEquals("hash1", getValue(session, Collection.SERVER_INFO.toString(), "trustStateHash"));
        assertEquals(1L, getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter"));
        assertEquals(1, all("debug_trustPaths").size());

        Document snapshot = one(Collection.TRUST_STATE_SNAPSHOTS.toString(), new Document("_id", "hash1"));
        assertNotNull(snapshot);
        @SuppressWarnings("unchecked")
        List<Document> snapshotAccounts = (List<Document>) snapshot.get("accounts");
        assertEquals(1, snapshotAccounts.size(), "only servable accounts are published");
        assertEquals("P", snapshotAccounts.getFirst().getString("pubkey"));
        assertEquals("Alice", snapshotAccounts.getFirst().getString("name"));

        // Core loading is finished, but the full backlog has not been fetched yet.
        assertEquals(ServerStatus.coreReady.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        assertNotNull(queuedTask(Task.UPDATE));
    }

    @Test
    void releaseDataSkipsTheSnapshotWhenTheTrustStateIsUnchanged() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.updating);
        seedReleasableState();
        RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "trustStateHash", "hash1");

        Task.runTask(Task.RELEASE_DATA, Task.RELEASE_DATA.asDocument()
                .append("newTrustStateHash", "hash1").append("previousTrustStateHash", "hash1"));
        runQueuedSuccessor(Task.RELEASE_DATA, Task.PUBLISH_TRUST_STATE);

        // Consumers poll the hash; re-emitting an identical state would churn their caches.
        assertTrue(all(Collection.TRUST_STATE_SNAPSHOTS.toString()).isEmpty());
        assertEquals(0L, getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter"));
        // The collections are still published, and an updating registry returns to ready.
        assertEquals(3, all(Collection.ACCOUNTS.toString()).size());
        assertEquals(ServerStatus.ready.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
    }

    @Test
    void releaseDataHoldsBackTheBootstrapTrustState() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.coreLoading);
        seedReleasableState();

        Task.runTask(Task.RELEASE_DATA,
                Task.RELEASE_DATA.asDocument().append("newTrustStateHash", "hash1"));
        runQueuedSuccessor(Task.RELEASE_DATA, Task.PUBLISH_TRUST_STATE);

        // Nothing has ever been published and an account is still waiting for the initial full
        // load, so the computed state is the near-empty bootstrap one: publishing it would hand
        // consumers a trust state containing essentially nobody (issue #119). The collections are
        // still promoted; only the hash, counter and snapshot are held back until a later cycle
        // finds the load complete.
        assertEquals(3, all(Collection.ACCOUNTS.toString()).size());
        assertNull(getValue(session, Collection.SERVER_INFO.toString(), "trustStateHash"));
        assertEquals(0L, getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter"));
        assertTrue(all(Collection.TRUST_STATE_SNAPSHOTS.toString()).isEmpty());
    }

    // ------------------------------------------------------------------ UPDATE

    @Test
    void updateStartsANewCycleWhenTheRegistryIsReady() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.ready);

        Task.runTask(Task.UPDATE, Task.UPDATE.asDocument());

        assertEquals(ServerStatus.updating.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        assertEquals(List.of(Task.INIT_COLLECTIONS.name()), queuedActions());
    }

    @Test
    void updatePostponesItselfWhileTheRegistryIsStillLoading() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.coreLoading);
        long before = System.currentTimeMillis();

        Task.runTask(Task.UPDATE, Task.UPDATE.asDocument());

        assertEquals(ServerStatus.coreLoading.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        Document next = queuedTask(Task.UPDATE);
        assertNotNull(next);
        assertTrue(next.getLong("not-before") >= before + 10 * 60 * 1000,
                "the retry is deferred by the update interval");
    }

    // -------------------------------------------------------- RUN_OPTIONAL_LOAD

    @Test
    void runOptionalLoadYieldsToCheckNewWhenThereIsNothingEncountered() throws Exception {
        prepareLoadingCollections();

        Task.runTask(Task.RUN_OPTIONAL_LOAD, Task.RUN_OPTIONAL_LOAD.asDocument());

        assertEquals(List.of(Task.CHECK_NEW.name()), queuedActions());
    }

    @Test
    void runOptionalLoadPromotesAnEncounteredPubkeyToLoaded() throws Exception {
        prepareLoadingCollections();
        seed("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)
                .append("status", EntryStatus.encountered.getValue()));

        Task.runTask(Task.RUN_OPTIONAL_LOAD, Task.RUN_OPTIONAL_LOAD.asDocument());

        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)).getString("status"));
        // Loading a core also creates the endorsement list and flags the full list for later.
        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", NanopubLoader.ENDORSE_TYPE_HASH)).getString("status"));
        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", "$")).getString("status"));
        assertEquals(List.of(Task.CHECK_NEW.name()), queuedActions());
    }

    @Test
    void runOptionalLoadLoadsNothingWhenItIsDisabled() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_ENABLE_OPTIONAL_LOAD", "false").build();
        seed("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)
                .append("status", EntryStatus.encountered.getValue()));

        Task.runTask(Task.RUN_OPTIONAL_LOAD, Task.RUN_OPTIONAL_LOAD.asDocument());

        // Encountered pubkeys stay untouched: only explicitly approved ones get loaded.
        assertEquals(EntryStatus.encountered.getValue(),
                one("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)).getString("status"));
        assertNull(one("lists", new Document("pubkey", "P").append("type", "$")),
                "no follow-up lists are created");
        assertEquals(List.of(Task.CHECK_NEW.name()), queuedActions());
    }

    @Test
    void runOptionalLoadChecksForRemainingWorkWhenPrioritisingAllPubkeys() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PRIORITIZE_ALL_PUBKEYS", "true").build();
        seed("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)
                .append("status", EntryStatus.encountered.getValue()));

        Task.runTask(Task.RUN_OPTIONAL_LOAD, Task.RUN_OPTIONAL_LOAD.asDocument());

        // Prioritising re-queues itself only while encountered lists remain. With no peers
        // nothing is downloaded, so the batch limit is never hit, both phases drain
        // everything they were given, and this run hands over to CHECK_NEW like the
        // throttled path does. The immediate re-queue needs a batch cut-off to be observed.
        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", NanopubLoader.INTRO_TYPE_HASH)).getString("status"));
        assertEquals(List.of(Task.CHECK_NEW.name()), queuedActions());
    }

    // --------------------------------------------------------------- CHECK_NEW

    @Test
    void checkNewHandsBackToLoadFull() throws Exception {
        prepareLoadingCollections();

        // No peers are configured, and the legacy source is stubbed out so the task stays local.
        try (MockedStatic<LegacyConnector> legacy = mockStatic(LegacyConnector.class)) {
            Task.runTask(Task.CHECK_NEW, Task.CHECK_NEW.asDocument());
            legacy.verify(() -> LegacyConnector.checkForNewNanopubs(org.mockito.ArgumentMatchers.any()));
        }

        assertEquals(List.of(Task.LOAD_FULL.name()), queuedActions());
    }

    // --------------------------------------------------------------- LOAD_FULL

    @Test
    void loadFull() throws Exception {
        bootstrap();
        useSettingFile();

        Task.runTask(Task.LOAD_SETTING, Task.LOAD_SETTING.asDocument());
        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        List<Document> retrievedTasks = RegistryDB.collection(Collection.TASKS.toString())
                .find(session)
                .sort(Sorts.descending("not-before"))
                .into(new ArrayList<>());

        // LOAD_FULL ran while status was still launching/coreLoading, so it self-rescheduled
        // with a 1s retry delay; that's the only task with a non-zero not-before, so it
        // sorts first. The queue also still contains the LOAD_FULL scheduled earlier by
        // LOAD_SETTING and INIT_COLLECTIONS (both at near-zero delay).
        assertEquals(Task.LOAD_FULL.name(), retrievedTasks.getFirst().getString("action"));
        List<String> actions = retrievedTasks.stream().map(d -> d.getString("action")).toList();
        assertTrue(actions.contains(Task.INIT_COLLECTIONS.name()));
        long loadFullCount = actions.stream().filter(a -> a.equals(Task.LOAD_FULL.name())).count();
        assertTrue(loadFullCount >= 2);
    }

    @Test
    void loadFullDoesNothingWhenFullLoadIsDisabled() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        setStatus(ServerStatus.ready);
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.toLoad.getValue()));

        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        // The guard returns before the status check, so the account is left alone and the
        // chain stops here rather than rescheduling itself.
        assertEquals(EntryStatus.toLoad.getValue(),
                one(Collection.ACCOUNTS.toString(), new Document("agent", "A")).getString("status"));
        assertTrue(queuedActions().isEmpty());
    }

    @Test
    void loadFullCompletesTheCoreLoadingPhase() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.coreReady);

        // Nothing left with status toLoad: the backlog is done.
        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        assertEquals(ServerStatus.ready.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        assertEquals(List.of(Task.RUN_OPTIONAL_LOAD.name()), queuedActions());
    }

    @Test
    void loadFullMarksAnAccountLoadedOncePeersAreExhausted() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.ready);
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.toLoad.getValue()));
        seed("lists", new Document("pubkey", "P").append("type", "$")
                .append("status", EntryStatus.encountered.getValue()));

        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        assertEquals(EntryStatus.loaded.getValue(),
                one(Collection.ACCOUNTS.toString(), new Document("agent", "A")).getString("status"));
        assertEquals(EntryStatus.loaded.getValue(),
                one("lists", new Document("pubkey", "P").append("type", "$")).getString("status"));
        assertEquals(List.of(Task.LOAD_FULL.name()), queuedActions());
    }

    @Test
    void loadFullSkipsPubkeysTheAgentFilterRejects() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.ready);
        // Quota enforcement is read straight from System.getenv, so it is set directly here.
        TestUtils.clearStaticFields(AgentFilter.class, Map.of("enforceQuota", true));
        // The account carries no quota, so the filter grants it none.
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.toLoad.getValue()));

        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        assertEquals(EntryStatus.skipped.getValue(),
                one(Collection.ACCOUNTS.toString(), new Document("agent", "A")).getString("status"));
        assertEquals(List.of(Task.LOAD_FULL.name()), queuedActions());
    }

    @Test
    void loadFullCapsAnAccountThatHasReachedItsQuota() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.ready);
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting P:0").build();
        AgentFilter.init();
        TestUtils.clearStaticFields(AgentFilter.class, Map.of("enforceQuota", true));
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.toLoad.getValue()));

        Task.runTask(Task.LOAD_FULL, Task.LOAD_FULL.asDocument());

        Document account = one(Collection.ACCOUNTS.toString(), new Document("agent", "A"));
        // 'capped' distinguishes "allowed but full" from "not allowed at all".
        assertEquals(EntryStatus.capped.getValue(), account.getString("status"));
        assertEquals(0, account.getInteger("quota"), "the effective quota is recorded on the account");
    }

}
