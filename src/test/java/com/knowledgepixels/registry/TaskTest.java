package com.knowledgepixels.registry;

import com.knowledgepixels.registry.db.IndexInitializer;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import net.trustyuri.TrustyUriUtils;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.SimpleTimestampPattern;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.security.SignNanopub;
import org.nanopub.extra.security.SignatureAlgorithm;
import org.nanopub.extra.security.TransformContext;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.testsuite.NanopubTestSuite;
import org.nanopub.vocabulary.NPX;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
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
import static org.mockito.ArgumentMatchers.any;
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

        // The queue holds the LOAD_CONFIG that INIT_DB scheduled and the LOAD_SETTING that
        // LOAD_CONFIG scheduled. Both can carry the same not-before millisecond, so the queue
        // is checked by what is in it, not by its order.
        assertEquals(List.of(Task.LOAD_CONFIG.name(), Task.LOAD_SETTING.name()), queuedActions().stream().sorted().toList());
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
        // The second pass meets the very same introduction, which does not take precedence
        // over itself, so the recorded name is left as it was.
        assertEquals(Utils.extractIntroName(new IntroNanopub(introNp)),
                one("accounts_loading", new Document("agent", agent)).getString("name"));
    }

    private static final IRI INTRODUCED = SimpleValueFactory.getInstance().createIRI("http://example.org/introduced");

    private static KeyPair introducedKey;

    /**
     * Stores an introduction of {@link #INTRODUCED} that declares one and the same key each
     * time, with the given name (or none) and creation time.
     */
    private Nanopub storeIntro(String name, long createdAt) throws Exception {
        return storeIntro(name, creator -> creator.addTimestamp(new Date(createdAt)));
    }

    /**
     * Adds the creation time to an introduction that {@link #storeIntro(String, Timestamping)}
     * makes.
     */
    private interface Timestamping {
        void addTo(NanopubCreator creator) throws Exception;
    }

    /**
     * Stores an introduction of {@link #INTRODUCED} that declares one and the same key each
     * time, with the given name (or none), and its creation time written as given.
     */
    private Nanopub storeIntro(String name, Timestamping timestamping) throws Exception {
        if (introducedKey == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            introducedKey = generator.generateKeyPair();
        }
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        NanopubCreator creator = new NanopubCreator("http://example.org/intro" + System.nanoTime() + "/");
        IRI keyDeclaration = vf.createIRI(creator.getNanopubUri() + "key");
        creator.addAssertionStatement(keyDeclaration, NPX.DECLARED_BY, INTRODUCED);
        creator.addAssertionStatement(keyDeclaration, NPX.HAS_ALGORITHM, vf.createLiteral("RSA"));
        creator.addAssertionStatement(keyDeclaration, NPX.HAS_PUBLIC_KEY,
                vf.createLiteral(Base64.getEncoder().encodeToString(introducedKey.getPublic().getEncoded())));
        if (name != null) {
            creator.addAssertionStatement(INTRODUCED, vf.createIRI("http://xmlns.com/foaf/0.1/name"), vf.createLiteral(name));
        }
        creator.addProvenanceStatement(vf.createIRI("http://www.w3.org/ns/prov#wasAttributedTo"), INTRODUCED);
        creator.addPubinfoStatement(vf.createIRI("http://purl.org/dc/terms/creator"), INTRODUCED);
        timestamping.addTo(creator);
        Nanopub intro = SignNanopub.signAndTransform(creator.finalizeNanopub(),
                new TransformContext(SignatureAlgorithm.RSA, introducedKey, INTRODUCED, false, false, false));
        assertTrue(RegistryDB.loadNanopub(session, intro));
        return intro;
    }

    /**
     * Runs LOAD_DECLARATIONS over the given introductions, endorsed in the given order, and
     * returns the account they declare.
     */
    private Document accountDeclaredBy(Nanopub... introsInOrder) throws Exception {
        for (String c : List.of("accounts_loading", "endorsements_loading", "trustEdges")) {
            collection(c).deleteMany(session, new Document());
        }
        clearQueue();
        for (Nanopub intro : introsInOrder) {
            seedIntroEndorsement(TrustyUriUtils.getArtifactCode(intro.getUri().stringValue()), SETTING_ID);
        }
        Task.runTask(Task.LOAD_DECLARATIONS, Task.LOAD_DECLARATIONS.asDocument().append("depth", 1));
        Document account = one("accounts_loading", new Document("agent", INTRODUCED.stringValue()));
        assertNotNull(account, "the introductions declare an account");
        return account;
    }

    private static void assertTakenFrom(Nanopub intro, String name, Document account) {
        assertEquals(intro.getUri().stringValue(), account.getString("introNanopub"));
        assertEquals(name, account.getString("name"));
        assertEquals(SimpleTimestampPattern.getCreationTime(intro).getTime(), account.getDate("nameCreatedAt"),
                "name, creation time and introduction come from the same one introduction");
    }

    /**
     * An account's name, name timestamp and introduction are published, so which of several
     * introductions they are taken from must not depend on the order the endorsements happen
     * to be processed in: that order is not fixed from one cycle to the next, and a choice
     * that flipped with it would publish a new trust state each time (issue #223).
     */
    @Test
    void loadDeclarationsTakesTheLaterIntroductionWhateverTheOrder() throws Exception {
        prepareLoadingCollections();
        Nanopub earlier = storeIntro("Earlier Name", 1_600_000_000_000L);
        Nanopub later = storeIntro("Later Name", 1_700_000_000_000L);

        assertTakenFrom(later, "Later Name", accountDeclaredBy(earlier, later));
        assertTakenFrom(later, "Later Name", accountDeclaredBy(later, earlier));
    }

    /**
     * The registry stores an introduction whose creation time is typed {@code xsd:dateTime}
     * but does not parse. Reading that time throws, and an endorsement of such an
     * introduction must not stop the trust calculation (issue #244): the introduction counts
     * as undated instead.
     */
    @Test
    void loadDeclarationsTakesAnIntroductionWithAMalformedTimestampAsUndated() throws Exception {
        prepareLoadingCollections();
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        Nanopub malformed;
        // nanopub-java refuses to sign a nanopublication with an ill-typed literal, but ones
        // signed before it did, or signed by other tools, exist, and the registry stores them.
        // Signing here is made to skip that check to get one.
        try (MockedStatic<NanopubUtils> utils = mockStatic(NanopubUtils.class, Mockito.CALLS_REAL_METHODS)) {
            utils.when(() -> NanopubUtils.getIllTypedLiteralStatements(any())).thenReturn(List.of());
            malformed = storeIntro("Malformed Name", creator -> creator.addPubinfoStatement(
                    vf.createIRI("http://purl.org/dc/terms/created"),
                    vf.createLiteral("the day before yesterday", vf.createIRI("http://www.w3.org/2001/XMLSchema#dateTime"))));
        }
        assertThrows(IllegalArgumentException.class, () -> SimpleTimestampPattern.getCreationTime(malformed),
                "the timestamp does not parse");

        Document account = accountDeclaredBy(malformed);
        assertEquals(malformed.getUri().stringValue(), account.getString("introNanopub"));
        assertEquals("Malformed Name", account.getString("name"));
        assertNull(account.get("nameCreatedAt"), "the introduction counts as undated");
        assertEquals(EntryStatus.retrieved.getValue(),
                one("endorsements_loading", new Document()).getString("status"),
                "the endorsement is processed like any other");

        // As undated, it ranks behind a dated introduction, whatever the order.
        Nanopub dated = storeIntro("Dated Name", 1_600_000_000_000L);
        assertTakenFrom(dated, "Dated Name", accountDeclaredBy(malformed, dated));
        assertTakenFrom(dated, "Dated Name", accountDeclaredBy(dated, malformed));
    }

    @Test
    void loadDeclarationsBreaksATieBetweenIntroductionsWhateverTheOrder() throws Exception {
        prepareLoadingCollections();
        // Created at the same time: the one with the smaller URI is taken.
        Nanopub one = storeIntro("One Name", 1_700_000_000_000L);
        Nanopub other = storeIntro("Other Name", 1_700_000_000_000L);
        boolean oneFirst = one.getUri().stringValue().compareTo(other.getUri().stringValue()) < 0;
        Nanopub expected = oneFirst ? one : other;
        String expectedName = oneFirst ? "One Name" : "Other Name";

        assertTakenFrom(expected, expectedName, accountDeclaredBy(one, other));
        assertTakenFrom(expected, expectedName, accountDeclaredBy(other, one));
    }

    @Test
    void loadDeclarationsPrefersAnIntroductionWithANameWhateverTheOrder() throws Exception {
        prepareLoadingCollections();
        Nanopub named = storeIntro("A Name", 1_600_000_000_000L);
        // Later, but it gives no name.
        Nanopub unnamed = storeIntro(null, 1_700_000_000_000L);

        assertTakenFrom(named, "A Name", accountDeclaredBy(named, unnamed));
        assertTakenFrom(named, "A Name", accountDeclaredBy(unnamed, named));
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
        seedTrustPath(id, depth, ratio, "primary");
    }

    private void seedTrustPath(String id, int depth, double ratio, String type) {
        seed("trustPaths_loading", new Document("_id", id).append("sorthash", id)
                .append("agent", "A").append("pubkey", "P").append("depth", depth)
                .append("ratio", ratio).append("type", type));
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

    /**
     * A primary path has already been reduced to the self share when it was expanded; an
     * extended path never is, so the self share has to be applied when it is scored (issue #196).
     */
    @Test
    void calculateTrustScoresAppliesTheSelfShareToExtendedPaths() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        seedTrustPath("$ X|1 A|P", 2, 0.05, "primary");
        seedTrustPath("$ Y|1 A|P", 2, 0.5, "extended");

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", "A"));
        assertEquals(0.05 + 0.5 * Task.SELF_SHARE, account.getDouble("ratio"), 1e-12,
                "the extended path counts only its self share, the primary one its stored ratio");
        assertEquals(2, account.getInteger("pathCount"));
    }

    @Test
    void calculateTrustScoresCountsIndependentPathsInOrderOfEffectiveRatio() throws Exception {
        prepareLoadingCollections();
        seedExpandedAccount();
        // Effective ratios: primary 0.01, then 0.005 and 0.004 for the extended paths. Taken in
        // that order the primary path claims X and Y, and both extended paths overlap with it.
        // Ordered by stored ratio instead, the extended paths would come first and two of the
        // three would count as independent.
        seedTrustPath("$ X|1 Y|2 A|P", 3, 0.01, "primary");
        seedTrustPath("$ Z|1 Y|2 A|P", 3, 0.05, "extended");
        seedTrustPath("$ X|1 W|2 A|P", 3, 0.04, "extended");

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", "A"));
        assertEquals(0.019, account.getDouble("ratio"), 1e-12);
        assertEquals(1, account.getInteger("pathCount"), "the primary path leads its depth group");
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

    /**
     * An explicit quota from REGISTRY_COVERAGE_AGENTS overrides the computed one in
     * AgentFilter, so it has to override it here too — otherwise the registry would publish
     * and display a quota other than the one it enforces.
     */
    @Test
    void calculateTrustScoresPrefersAnExplicitlyConfiguredQuota() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting P:250000").build();
        AgentFilter.init();
        seedExpandedAccount();
        seedTrustPath("$ X|1 A|P", 2, 1e-9);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        assertEquals(250000, one("accounts_loading", new Document("agent", "A")).getInteger("quota"),
                "the configured quota wins over the ratio-derived one, bounds and all");
    }

    // ----------------------------------------------------- quota grants (#220)

    private static final IRI SOMEONE = SimpleValueFactory.getInstance().createIRI("http://example.org/someone");

    private static KeyPair authorityKey;

    /**
     * Publishes a grant of the given quota for the given account, signed with a key that is
     * then configured as this registry's one quota authority, and stores it the way the
     * loaders do: on the "$" list and the type lists of its pubkey.
     *
     * @return the pubkey hash of the authority
     */
    private String storeGrant(String agent, String pubkeyHash, int quota) throws Exception {
        if (authorityKey == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            authorityKey = generator.generateKeyPair();
        }
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        NanopubCreator creator = new NanopubCreator("http://example.org/grant" + System.nanoTime() + "/");
        IRI subject = vf.createIRI(creator.getNanopubUri() + "grant");
        creator.addAssertionStatement(subject, QuotaGrant.FOR_AGENT, vf.createIRI(agent));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_PUBLIC_KEY_HASH, vf.createLiteral(pubkeyHash));
        creator.addAssertionStatement(subject, QuotaGrant.HAS_QUOTA, vf.createLiteral(quota));
        creator.addProvenanceStatement(vf.createIRI("http://www.w3.org/ns/prov#wasAttributedTo"), SOMEONE);
        creator.addPubinfoStatement(vf.createIRI("http://purl.org/dc/terms/creator"), SOMEONE);
        creator.addPubinfoStatement(NPX.HAS_NANOPUB_TYPE, QuotaGrant.QUOTA_GRANT);
        creator.addTimestampNow();
        Nanopub grant = SignNanopub.signAndTransform(creator.finalizeNanopub(),
                new TransformContext(SignatureAlgorithm.RSA, authorityKey, SOMEONE, false, false, false));
        String authority = Utils.getHash(RegistryDB.getPubkey(grant));
        assertTrue(RegistryDB.loadNanopub(session, grant, authority, "$"));
        fakeEnv.addVariable("REGISTRY_QUOTA_AUTHORITIES", authority).build();
        AgentFilter.init();
        return authority;
    }

    /** The account the grant tests are about: a grant names an agent IRI and a pubkey hash. */
    private static final String GRANTEE_AGENT = "http://example.org/grantee";
    private static final String GRANTEE = "403d674304853131343af2fd56c48a808c8754d0a7aa32ffb35aa0960cdbd97b";

    /** An expanded account of the grantee with a negligible trust path. */
    private void seedExpandedGrantee() {
        seed("accounts_loading", new Document("agent", GRANTEE_AGENT).append("pubkey", GRANTEE)
                .append("status", EntryStatus.expanded.getValue()).append("depth", 1));
        seed("trustPaths_loading", new Document("_id", "$ X|1 " + GRANTEE_AGENT + "|" + GRANTEE).append("sorthash", "x")
                .append("agent", GRANTEE_AGENT).append("pubkey", GRANTEE).append("depth", 2)
                .append("ratio", 1e-9).append("type", "primary"));
    }

    @Test
    void calculateTrustScoresAppliesAGrantedQuota() throws Exception {
        prepareLoadingCollections();
        storeGrant(GRANTEE_AGENT, GRANTEE, 20000);
        seedExpandedGrantee();

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", GRANTEE_AGENT));
        assertEquals(20000, account.getInteger("quota"), "the granted quota replaces the ratio-derived one");
        assertEquals("grant", account.getString("quotaSource"), "and is marked, for the trust state hash to cover it");
    }

    @Test
    void calculateTrustScoresPrefersAnExplicitEntryOverAGrant() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting " + GRANTEE + ":250000").build();
        storeGrant(GRANTEE_AGENT, GRANTEE, 20000);
        seedExpandedGrantee();

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", GRANTEE_AGENT));
        assertEquals(250000, account.getInteger("quota"),
                "the operator's own configuration has the final say on their instance");
        assertNull(account.getString("quotaSource"));
    }

    /**
     * A grant is for one account. Another agent declaring the same key does not come by the
     * quota that way.
     */
    @Test
    void calculateTrustScoresLeavesAGrantForAnotherAgentOfTheSameKeyAside() throws Exception {
        prepareLoadingCollections();
        storeGrant("http://example.org/someone-else", GRANTEE, 20000);
        seedExpandedGrantee();

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());

        Document account = one("accounts_loading", new Document("agent", GRANTEE_AGENT));
        assertEquals(1000, account.getInteger("quota"), "the ratio-derived quota, here the minimum, stays");
        assertNull(account.getString("quotaSource"));
    }

    /**
     * A grant sets a quota and approves nothing: an account the trust network does not reach
     * is not created by it, and neither is one for the authority that signed it.
     */
    @Test
    void aGrantCreatesNoAccount() throws Exception {
        prepareLoadingCollections();
        String authority = storeGrant(GRANTEE_AGENT, GRANTEE, 20000);

        Task.runTask(Task.CALCULATE_TRUST_SCORES, Task.CALCULATE_TRUST_SCORES.asDocument());
        clearQueue();
        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertNull(one("accounts_loading", new Document("pubkey", GRANTEE)));
        assertNull(one("accounts_loading", new Document("pubkey", authority)));
    }

    /**
     * Nor does a grant lift an account the trust network leaves contested or skipped: those
     * carry no quota, and a grant does not decide whose key it is.
     */
    @Test
    void aGrantLeavesAContestedOrSkippedAccountAsItIs() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", GRANTEE_AGENT).append("pubkey", GRANTEE)
                .append("status", EntryStatus.contested.getValue()));
        seed("accounts_loading", new Document("agent", "B").append("pubkey", GRANTEE)
                .append("status", EntryStatus.skipped.getValue()));
        storeGrant(GRANTEE_AGENT, GRANTEE, 20000);

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        List<Document> accounts = collection("accounts_loading").find(session, new Document("pubkey", GRANTEE)).into(new ArrayList<>());
        assertEquals(2, accounts.size(), "no account is added next to them");
        assertEquals(EntryStatus.contested.getValue(), one("accounts_loading", new Document("agent", GRANTEE_AGENT)).getString("status"));
        assertEquals(EntryStatus.skipped.getValue(), one("accounts_loading", new Document("agent", "B")).getString("status"));
    }

    /**
     * An explicit entry is no different with the trust network on: it sets the quota of an
     * account the network reaches, and is honoured for publishing by {@link AgentFilter},
     * but it is not turned into an account without an agent.
     */
    @Test
    void determineUpdatesCreatesNoAccountForAnExplicitPubkey() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_COVERAGE_AGENTS", "viaSetting PK1:500").build();
        AgentFilter.init();

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertNull(one("accounts_loading", new Document("pubkey", "PK1")));
    }

    @Test
    void seedTrustStateSeedsNoGrantedAccountWhenTrustCalculationIsDisabled() throws Exception {
        bootstrap();
        String authority = storeGrant(GRANTEE_AGENT, GRANTEE, 20000);
        fakeEnv.addVariable("REGISTRY_ENABLE_TRUST_CALCULATION", "false").build();
        setStatus(ServerStatus.coreLoading);
        clearQueue();

        Task.runTask(Task.INIT_COLLECTIONS, Task.INIT_COLLECTIONS.asDocument());
        runQueuedSuccessor(Task.INIT_COLLECTIONS, Task.SEED_TRUST_STATE);

        // Without a trust network there is no approved account for a grant to apply to.
        assertNull(one("accounts_loading", new Document("pubkey", GRANTEE)));
        assertNull(one("accounts_loading", new Document("pubkey", authority)));
        assertEquals(List.of(Task.FINALIZE_TRUST_STATE.name()), queuedActions());
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
        assertEquals(1, newlyToLoad(), "the account is handed to the loader in this cycle");
        assertEquals(List.of(Task.FINALIZE_TRUST_STATE.name()), queuedActions());
    }

    private Object newlyToLoad() {
        return getValue(session, Collection.SERVER_INFO.toString(), "accountsNewlyToLoad");
    }

    /**
     * An account holds publication back only in the cycle that hands it to the loader. One
     * that was waiting before has had its cycle, whether it takes long or cannot be loaded.
     */
    @Test
    void determineUpdatesDoesNotCountAnAccountThatWasWaitingAlready() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()));
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.toLoad.getValue()));

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertEquals(EntryStatus.toLoad.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        assertEquals(0, newlyToLoad());
    }

    /**
     * Under quota enforcement the loader passes over an account it does not cover, cycle after
     * cycle. Waiting for it would hold every other trust state back for nothing.
     */
    @Test
    void determineUpdatesDoesNotCountAnAccountTheLoaderPassesOver() throws Exception {
        prepareLoadingCollections();
        TestUtils.clearStaticFields(AgentFilter.class, Map.of("enforceQuota", true, "viaSetting", false));
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()));
        // What LOAD_FULL made of it after the previous cycle.
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.skipped.getValue()));

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertEquals(EntryStatus.toLoad.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
        assertEquals(0, newlyToLoad());
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
        assertEquals(0, newlyToLoad());
    }

    /**
     * A capped account is loaded as far as its quota allows, which makes it servable. Staging
     * it as 'toLoad' would drop it out of the published trust state, since that status marks
     * an account that cannot be served yet.
     */
    @Test
    void determineUpdatesKeepsAStillFullAccountCapped() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()).append("quota", 10));
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.capped.getValue()).append("quota", 10));
        // Ten nanopubs held against a quota of ten: no room.
        seed("lists", new Document("pubkey", "P").append("type", "$").append("maxPosition", 9L));

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertEquals(EntryStatus.capped.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"));
    }

    @Test
    void determineUpdatesLetsACappedAccountResumeOnceItsQuotaGrows() throws Exception {
        prepareLoadingCollections();
        // This cycle computed a larger quota than the one the cap was applied under.
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()).append("quota", 50));
        seed(Collection.ACCOUNTS.toString(), new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.capped.getValue()).append("quota", 10));
        seed("lists", new Document("pubkey", "P").append("type", "$").append("maxPosition", 9L));

        Task.runTask(Task.DETERMINE_UPDATES, Task.DETERMINE_UPDATES.asDocument());

        assertEquals(EntryStatus.toLoad.getValue(),
                one("accounts_loading", new Document("agent", "A")).getString("status"),
                "there is room again, so LOAD_FULL has to pick it up and load the rest");
        assertEquals(1, newlyToLoad(), "which makes it an account handed to the loader in this cycle");
    }

    // --------------------------------------------------- FINALIZE_TRUST_STATE

    @Test
    void finalizeTrustStateRecordsTheTimeAndHandsOverToReleaseData() throws Exception {
        prepareLoadingCollections();
        seed("accounts_loading", new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.approved.getValue()));
        seedTrustPath("$ A|P", 1, 0.5);

        Task.runTask(Task.FINALIZE_TRUST_STATE, Task.FINALIZE_TRUST_STATE.asDocument());

        assertNotNull(getValue(session, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate"));
        Document next = queuedTask(Task.RELEASE_DATA);
        assertNotNull(next);
        // The hash is taken where the snapshot is written, from the data that is stored (#223).
        assertFalse(next.containsKey("newTrustStateHash"), "no hash is computed ahead of the release");
    }

    // ------------------------------------- RELEASE_DATA / PUBLISH_TRUST_STATE

    private static Document accountA() {
        return new Document("agent", "A").append("pubkey", "P")
                .append("status", EntryStatus.loaded.getValue()).append("depth", 1)
                .append("name", "Alice").append("pathCount", 2).append("ratio", 0.5).append("quota", 1000);
    }

    private static Document accountB(EntryStatus status) {
        return new Document("agent", "B").append("pubkey", "Q")
                .append("status", status.getValue()).append("depth", 1);
    }

    /**
     * Seeds a complete loading state ready to be published.
     */
    private void seedReleasableState() {
        seedReleasableState(accountA(), accountB(EntryStatus.toLoad));
    }

    /**
     * Seeds a complete loading state with the given accounts, as a trust cycle leaves it
     * behind. Can be called again after a release, for the cycle that follows.
     */
    private void seedReleasableState(Document... accounts) {
        IndexInitializer.initLoadingCollections(session);
        for (Document account : accounts) {
            seed("accounts_loading", account);
        }
        // The synthetic root is internal and must stay out of snapshots.
        seed("accounts_loading", new Document("agent", "$").append("pubkey", "$")
                .append("status", EntryStatus.approved.getValue()).append("depth", 0));
        seedTrustPath("$ A|P", 1, 0.5);
        seed("agents_loading", new Document("agent", "A").append("accountCount", 1)
                .append("avgPathCount", 2.0).append("totalRatio", 0.5));
        seed("endorsements_loading", new Document("agent", "A").append("pubkey", "P")
                .append("endorsedNanopub", "RAxyz").append("status", EntryStatus.retrieved.getValue()));
    }

    /**
     * Runs the end of a trust cycle on what is in the loading collections.
     */
    private void releaseAndPublish() throws Exception {
        Task.runTask(Task.RELEASE_DATA, Task.RELEASE_DATA.asDocument());
        runQueuedSuccessor(Task.RELEASE_DATA, Task.PUBLISH_TRUST_STATE);
    }

    private String publishedHash() {
        return (String) getValue(session, Collection.SERVER_INFO.toString(), "trustStateHash");
    }

    private Object publishedCounter() {
        return getValue(session, Collection.SERVER_INFO.toString(), "trustStateCounter");
    }

    @SuppressWarnings("unchecked")
    private List<Document> snapshotAccounts(String hash) {
        Document snapshot = one(Collection.TRUST_STATE_SNAPSHOTS.toString(), new Document("_id", hash));
        assertNotNull(snapshot, "there is a snapshot for the published hash");
        return (List<Document>) snapshot.get("accounts");
    }

    @Test
    void releaseDataPublishesTheLoadingCollectionsAndSnapshotsTheState() throws Exception {
        prepareLoadingCollections();
        // Without a full load there is no backlog to wait for, so the first cycle publishes
        // straight away rather than being held back as a bootstrap state (see the test below).
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        setStatus(ServerStatus.coreLoading);
        seedReleasableState();

        releaseAndPublish();

        // The *_loading collections become the live ones.
        assertEquals(3, all(Collection.ACCOUNTS.toString()).size());
        assertEquals(1, all(Collection.AGENTS.toString()).size());
        assertEquals(1, all("trustPaths").size());
        assertEquals(1, all("endorsements").size());
        assertFalse(RegistryDB.hasCollection("accounts_loading"));

        String hash = publishedHash();
        assertNotNull(hash);
        assertEquals(1L, publishedCounter());
        assertEquals(1, all("debug_trustPaths").size());

        Document snapshot = one(Collection.TRUST_STATE_SNAPSHOTS.toString(), new Document("_id", hash));
        assertNotNull(snapshot);
        @SuppressWarnings("unchecked")
        List<Document> snapshotAccounts = (List<Document>) snapshot.get("accounts");
        @SuppressWarnings("unchecked")
        List<Document> snapshotEdges = (List<Document>) snapshot.get("edges");
        assertEquals(1, snapshotAccounts.size(), "only servable accounts are published");
        assertEquals("P", snapshotAccounts.getFirst().getString("pubkey"));
        assertEquals("Alice", snapshotAccounts.getFirst().getString("name"));
        // The hash is that of the content stored under it, which is what makes it cover
        // everything that is published (#223).
        assertEquals(hash, RegistryDB.calculateTrustStateHash(snapshotAccounts, snapshotEdges));

        // The network hash is the trust paths alone, and travels with the snapshot.
        String networkHash = Utils.getHash("$ A|P (primary)\n");
        assertEquals(networkHash, getValue(session, Collection.SERVER_INFO.toString(), "trustNetworkHash"));
        assertEquals(networkHash, snapshot.getString("trustNetworkHash"));

        // Core loading is finished, but the full backlog has not been fetched yet.
        assertEquals(ServerStatus.coreReady.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
        assertNotNull(queuedTask(Task.UPDATE));
    }

    @Test
    void releaseDataSkipsTheSnapshotWhenTheTrustStateIsUnchanged() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        setStatus(ServerStatus.updating);
        seedReleasableState();
        releaseAndPublish();
        String hash = publishedHash();

        // The next cycle computes the very same state.
        setStatus(ServerStatus.updating);
        seedReleasableState();
        releaseAndPublish();

        // Consumers poll the hash; re-emitting an identical state would churn their caches.
        assertEquals(hash, publishedHash());
        assertEquals(1L, publishedCounter());
        assertEquals(1, all(Collection.TRUST_STATE_SNAPSHOTS.toString()).size());
        // The collections are still published, and an updating registry returns to ready.
        assertEquals(3, all(Collection.ACCOUNTS.toString()).size());
        assertEquals(ServerStatus.ready.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"));
    }

    @Test
    void releaseDataHoldsBackTheBootstrapTrustState() throws Exception {
        prepareLoadingCollections();
        setStatus(ServerStatus.coreLoading);
        seedReleasableState();

        releaseAndPublish();

        // Nothing has ever been published and an account is still waiting for the initial full
        // load, so the computed state is the near-empty bootstrap one: publishing it would hand
        // consumers a trust state containing essentially nobody (issue #119). The collections are
        // still promoted; only the hash, counter and snapshot are held back until a later cycle
        // finds the load complete.
        assertEquals(3, all(Collection.ACCOUNTS.toString()).size());
        assertNull(publishedHash());
        assertEquals(0L, publishedCounter());
        assertTrue(all(Collection.TRUST_STATE_SNAPSHOTS.toString()).isEmpty());
        // The network is known all the same, and comparable with other registries.
        assertEquals(Utils.getHash("$ A|P (primary)\n"), getValue(session, Collection.SERVER_INFO.toString(), "trustNetworkHash"));
    }

    /**
     * A change to what is published without any change to the trust paths: a quota set by a
     * grant or by the operator, or ratios moved by a change to the scoring. The latter left a
     * registry serving a snapshot that disagreed with its own accounts in 80 ratios and 53
     * quotas, because the hash was computed from the paths and had not moved (issue #223).
     */
    @Test
    void aChangedQuotaOrRatioIsPublishedThoughNoTrustPathChanged() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        seedReleasableState();
        releaseAndPublish();
        String before = publishedHash();
        Object networkBefore = getValue(session, Collection.SERVER_INFO.toString(), "trustNetworkHash");

        seedReleasableState(accountA().append("quota", 20000), accountB(EntryStatus.toLoad));
        releaseAndPublish();
        String withQuota = publishedHash();
        assertFalse(before.equals(withQuota), "a changed quota is a new trust state");
        assertEquals(2L, publishedCounter());
        assertEquals(20000, snapshotAccounts(withQuota).getFirst().getInteger("quota"));

        seedReleasableState(accountA().append("quota", 20000).append("ratio", 0.4), accountB(EntryStatus.toLoad));
        releaseAndPublish();
        assertFalse(withQuota.equals(publishedHash()), "a changed ratio is a new trust state");
        assertEquals(3L, publishedCounter());

        assertEquals(networkBefore, getValue(session, Collection.SERVER_INFO.toString(), "trustNetworkHash"),
                "the network is the same network throughout");
    }

    /**
     * An account enters the published state when it becomes loaded, and that has to be a
     * change of the state, or it would be left out of it indefinitely (issue #119).
     */
    @Test
    void anAccountBecomingLoadedIsPublished() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        seedReleasableState(accountA(), accountB(EntryStatus.toLoad));
        releaseAndPublish();
        String before = publishedHash();
        assertEquals(1, snapshotAccounts(before).size());

        seedReleasableState(accountA(), accountB(EntryStatus.loaded));
        releaseAndPublish();

        assertFalse(before.equals(publishedHash()));
        assertEquals(2, snapshotAccounts(publishedHash()).size());
    }

    /**
     * Reaching a quota changes an account's published status without touching a trust path.
     */
    @Test
    void anAccountReachingItsQuotaIsPublished() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        seedReleasableState();
        releaseAndPublish();
        String before = publishedHash();

        seedReleasableState(accountA().append("status", EntryStatus.capped.getValue()), accountB(EntryStatus.toLoad));
        releaseAndPublish();

        assertFalse(before.equals(publishedHash()));
        assertEquals(EntryStatus.capped.getValue(), snapshotAccounts(publishedHash()).getFirst().getString("status"));
    }

    /**
     * RELEASE_DATA is not transactional, and an interrupted run of it queues its successor a
     * second time. The second run finds the state it computes already recorded.
     */
    @Test
    void publishingTheSameStateTwiceCountsItOnce() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        seedReleasableState();
        releaseAndPublish();
        String hash = publishedHash();

        Task.runTask(Task.PUBLISH_TRUST_STATE, Task.PUBLISH_TRUST_STATE.asDocument());

        assertEquals(hash, publishedHash());
        assertEquals(1L, publishedCounter());
        assertEquals(1, all(Collection.TRUST_STATE_SNAPSHOTS.toString()).size());
        assertEquals(1, all("debug_trustPaths").size());
    }

    /**
     * The hash is recorded before its snapshot is written. If the task stops in between,
     * consumers are pointed at a snapshot that does not exist, so the next run has to write
     * it although the state itself is not new.
     */
    @Test
    void aRecordedStateWithoutASnapshotGetsItsSnapshot() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        seedReleasableState();
        releaseAndPublish();
        String hash = publishedHash();
        collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).deleteMany(session, new Document());

        Task.runTask(Task.PUBLISH_TRUST_STATE, Task.PUBLISH_TRUST_STATE.asDocument());

        assertEquals(1, snapshotAccounts(hash).size());
        assertEquals(hash, publishedHash());
        assertEquals(1L, publishedCounter(), "the state is not a new one");
    }

    private static Document accountA(double ratio) {
        return accountA().append("ratio", ratio);
    }

    /**
     * Seeds what the earlier tasks of a cycle leave behind for its end: the loading
     * collections, what identifies the cycle, and how many accounts it hands to the loader.
     */
    private void seedCycle(String cycle, int newlyToLoad, Document... accounts) {
        seedReleasableState(accounts);
        RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "lastTrustStateUpdate", cycle);
        RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "accountsNewlyToLoad", newlyToLoad);
    }

    /**
     * Publishes a first trust state with account A alone, on a registry that loads accounts.
     */
    private String publishFirstState() throws Exception {
        prepareLoadingCollections();
        seedCycle("cycle 1", 0, accountA(0.5));
        releaseAndPublish();
        assertEquals(1L, publishedCounter());
        return publishedHash();
    }

    /**
     * A newly approved account stays out of what is published until it is loaded, but it
     * moves the scores of the accounts next to it in the very cycle that approves it.
     * Published as they come, that makes two trust states of one event, and every consumer
     * rebuilds twice. Seen on the live registries: one new account, three changed ratios.
     */
    @Test
    void aNewAccountAndTheScoresItMovesArePublishedAsOneState() throws Exception {
        String first = publishFirstState();

        // The cycle that approves B: A's ratio has moved, B is waiting to be loaded.
        seedCycle("cycle 2", 1, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();

        assertEquals(first, publishedHash(), "nothing is published in this cycle");
        assertEquals(1L, publishedCounter());
        assertEquals(1, all(Collection.TRUST_STATE_SNAPSHOTS.toString()).size());
        assertEquals(ServerStatus.ready.toString(), getValue(session, Collection.SERVER_INFO.toString(), "status"),
                "the cycle ends as usual");
        assertNotNull(queuedTask(Task.UPDATE));

        // The next one finds B loaded.
        clearQueue();
        seedCycle("cycle 3", 0, accountA(0.25), accountB(EntryStatus.loaded));
        releaseAndPublish();

        assertFalse(first.equals(publishedHash()));
        assertEquals(2L, publishedCounter(), "one new state for the one event");
        List<Document> accounts = snapshotAccounts(publishedHash());
        assertEquals(2, accounts.size());
        assertEquals(0.25, accounts.getFirst().getDouble("ratio"), 1e-12);
        assertEquals(2L, one(Collection.TRUST_STATE_SNAPSHOTS.toString(), new Document("_id", publishedHash())).getLong("trustStateCounter"),
                "the snapshot carries the counter it is recorded under");
        assertNull(getValue(session, Collection.SERVER_INFO.toString(), "trustStateHeldBackAt"));
    }

    private static Document account(String agent, String pubkey, EntryStatus status) {
        return new Document("agent", agent).append("pubkey", pubkey)
                .append("status", status.getValue()).append("depth", 1);
    }

    /**
     * During a run of approvals, each cycle hands a new account to the loader. An account
     * that has been loaded by then must not be kept waiting for that: people watch for their
     * approval, and it is to come in the cycle after their own, as it does without holding
     * anything back.
     */
    @Test
    void anAccountThatHasBeenLoadedIsNotKeptWaitingByTheNextOne() throws Exception {
        publishFirstState();
        // B has been waiting for a while, so this state is published without it.
        seedCycle("cycle 2", 0, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();
        assertEquals(2L, publishedCounter());

        // B is loaded, and C has just been approved.
        seedCycle("cycle 3", 1, accountA(0.2), accountB(EntryStatus.loaded), account("C", "R", EntryStatus.toLoad));
        releaseAndPublish();

        assertEquals(3L, publishedCounter(), "published at once, as B comes with it");
        assertEquals(2, snapshotAccounts(publishedHash()).size());
    }

    /**
     * Accounts can keep arriving while none of them gets loaded. Holding back every time
     * would then publish nothing for as long as that goes on.
     */
    @Test
    void aTrustStateIsNotHeldBackForTwoCyclesInARow() throws Exception {
        String first = publishFirstState();
        seedCycle("cycle 2", 1, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();
        assertEquals(first, publishedHash());

        // B is still not loaded, and the next account has arrived.
        seedCycle("cycle 3", 1, accountA(0.2), accountB(EntryStatus.toLoad), account("C", "R", EntryStatus.toLoad));
        releaseAndPublish();

        assertEquals(2L, publishedCounter(), "published, with what there is by now");
        assertEquals(1, snapshotAccounts(publishedHash()).size());

        // Whereas the cycle after that may hold back again.
        String second = publishedHash();
        seedCycle("cycle 4", 1, accountA(0.1), accountB(EntryStatus.toLoad), account("C", "R", EntryStatus.toLoad),
                account("D", "S", EntryStatus.toLoad));
        releaseAndPublish();

        assertEquals(second, publishedHash());
        assertEquals(2L, publishedCounter());
    }

    /**
     * Whether publishing would add an account is read off the recorded snapshot. Without it
     * that cannot be told, and the state is published.
     */
    @Test
    void nothingIsHeldBackWhenTheRecordedSnapshotIsGone() throws Exception {
        String first = publishFirstState();
        collection(Collection.TRUST_STATE_SNAPSHOTS.toString()).deleteMany(session, new Document());

        seedCycle("cycle 2", 1, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();

        assertFalse(first.equals(publishedHash()));
        assertEquals(2L, publishedCounter());
    }

    /**
     * An interrupted RELEASE_DATA queues the publishing task a second time in the same cycle.
     * The second run has to decide as the first did, not take the first for the cycle before.
     */
    @Test
    void aSecondRunInTheSameCycleHoldsBackAsTheFirstDid() throws Exception {
        String first = publishFirstState();
        seedCycle("cycle 2", 1, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();

        Task.runTask(Task.PUBLISH_TRUST_STATE, Task.PUBLISH_TRUST_STATE.asDocument());

        assertEquals(first, publishedHash());
        assertEquals(1L, publishedCounter());
    }

    /**
     * An account that was waiting already, because it takes long to load or cannot be loaded,
     * holds nothing back: other changes are published without it, as they always were.
     */
    @Test
    void anAccountThatWasWaitingAlreadyHoldsNothingBack() throws Exception {
        String first = publishFirstState();

        seedCycle("cycle 2", 0, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();

        assertFalse(first.equals(publishedHash()));
        assertEquals(2L, publishedCounter());
        assertEquals(1, snapshotAccounts(publishedHash()).size());
    }

    @Test
    void nothingIsHeldBackOnARegistryThatDoesNotLoadAccounts() throws Exception {
        String first = publishFirstState();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();

        seedCycle("cycle 2", 1, accountA(0.25), accountB(EntryStatus.toLoad));
        releaseAndPublish();

        assertFalse(first.equals(publishedHash()), "waiting would not get the account loaded");
        assertEquals(2L, publishedCounter());
    }

    /**
     * The count comes from DETERMINE_UPDATES, which a registry without trust calculation does
     * not run, so an old one can be left behind. It must not hold back a state when no
     * account is waiting.
     */
    @Test
    void aLeftoverCountHoldsNothingBackWhenNoAccountIsWaiting() throws Exception {
        String first = publishFirstState();

        seedCycle("cycle 2", 1, accountA(0.25));
        releaseAndPublish();

        assertFalse(first.equals(publishedHash()));
        assertEquals(2L, publishedCounter());
    }

    /**
     * The runner picks the queued task with the smallest 'not-before'. A LOAD_FULL that got in
     * between would move accounts on before they are published, and what is published would
     * depend on that timing.
     */
    @Test
    void releaseDataQueuesPublishingAheadOfEverythingElse() throws Exception {
        prepareLoadingCollections();
        seedReleasableState();
        long now = System.currentTimeMillis();
        Document releaseData = Task.RELEASE_DATA.asDocument().append("not-before", now - 5000);
        // Became due after RELEASE_DATA, and well before it finished.
        seed(Collection.TASKS.toString(), Task.LOAD_FULL.asDocument().append("not-before", now - 4999));

        Task.runTask(Task.RELEASE_DATA, releaseData);

        Document next = collection(Collection.TASKS.toString()).find(session).sort(Sorts.ascending("not-before")).first();
        assertEquals(Task.PUBLISH_TRUST_STATE.name(), next.getString("action"));
    }

    /**
     * A registry that computes no trust network has no network hash, and must not go on
     * showing the one from a time when it did.
     */
    @Test
    void theTrustNetworkHashIsRemovedWhenThereAreNoTrustPaths() throws Exception {
        prepareLoadingCollections();
        fakeEnv.addVariable("REGISTRY_PERFORM_FULL_LOAD", "false").build();
        RegistryDB.setValue(session, Collection.SERVER_INFO.toString(), "trustNetworkHash", "from-an-earlier-configuration");
        IndexInitializer.initLoadingCollections(session);
        seed("accounts_loading", new Document("agent", "").append("pubkey", "PK1")
                .append("status", EntryStatus.loaded.getValue()).append("depth", 0).append("quota", 500));

        releaseAndPublish();

        assertNull(getValue(session, Collection.SERVER_INFO.toString(), "trustNetworkHash"));
        assertNotNull(publishedHash(), "the accounts are published all the same");
    }

    // ------------------------------------------------- choice of introduction

    private static Document accountWithIntro(String name, java.util.Date createdAt, String nanopub) {
        return new Document("agent", "A").append("pubkey", "P")
                .append("name", name).append("nameCreatedAt", createdAt).append("introNanopub", nanopub);
    }

    /**
     * Folds introductions onto an account the way LOAD_DECLARATIONS does, in the given order.
     */
    private static Document applyIntros(Document... intros) {
        Document account = intros[0];
        for (int i = 1; i < intros.length; i++) {
            Document intro = intros[i];
            if (Task.introTakesPrecedence(intro.getString("name"), intro.getDate("nameCreatedAt"),
                    intro.getString("introNanopub"), account)) {
                account = intro;
            }
        }
        return account;
    }

    /**
     * Name and introduction are published in the trust state. Introductions are met in an
     * order that depends on how peers list them, so a choice that depended on it could flip
     * from one cycle to the next and publish a new state each time.
     */
    @Test
    void theChoiceOfIntroductionDoesNotDependOnTheOrderTheyAreMetIn() {
        java.util.Date earlier = new java.util.Date(1700000000000L);
        java.util.Date later = new java.util.Date(1700000001000L);
        List<Document> intros = List.of(
                accountWithIntro("Named, later", later, "https://w3id.org/np/RAb"),
                accountWithIntro("Named, later too", later, "https://w3id.org/np/RAa"),
                accountWithIntro("Named, earlier", earlier, "https://w3id.org/np/RAc"),
                accountWithIntro("Named, undated", null, "https://w3id.org/np/RAd"),
                accountWithIntro(null, new java.util.Date(1800000000000L), "https://w3id.org/np/RAe"),
                accountWithIntro(null, null, "https://w3id.org/np/RAf"));

        // A named introduction before an unnamed one, then the later one, then the smaller URI.
        String expected = "https://w3id.org/np/RAa";
        for (int rotation = 0; rotation < intros.size(); rotation++) {
            List<Document> order = new ArrayList<>(intros);
            java.util.Collections.rotate(order, rotation);
            assertEquals(expected, applyIntros(order.toArray(new Document[0])).getString("introNanopub"));
            java.util.Collections.reverse(order);
            assertEquals(expected, applyIntros(order.toArray(new Document[0])).getString("introNanopub"));
        }

        // Without any named introduction: the latest, and an undated one last.
        assertEquals("https://w3id.org/np/RAe", applyIntros(intros.get(5), intros.get(4)).getString("introNanopub"));
        assertEquals("https://w3id.org/np/RAe", applyIntros(intros.get(4), intros.get(5)).getString("introNanopub"));
        // An introduction does not replace itself.
        assertFalse(Task.introTakesPrecedence("Named, later", later, "https://w3id.org/np/RAb", intros.get(0)));
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
