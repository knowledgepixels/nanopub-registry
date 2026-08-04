package com.knowledgepixels.registry;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.nanopub.vocabulary.NPX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.setting.NanopubSetting;
import org.nanopub.testsuite.NanopubTestSuite;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class UtilsTest {

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        fakeEnv = TestUtils.setupFakeEnv();
        TestUtils.clearStaticFields(Utils.class, "settingNp", "peerUrls");
        TestUtils.clearStaticFields(Utils.class, new HashMap<>() {{
            put("ENV_READER", new ReadsEnvironment(System::getenv));
        }});
    }

    @AfterEach
    void tearDown() throws Exception {
        fakeEnv.reset();
        TestUtils.cleanupDataDir();
    }

    @Test
    void getTypeWithNullExtension() {
        assertNull(Utils.getType(null));
    }

    @Test
    void getTypeWithValidExtension() {
        assertEquals(Utils.TYPE_TRIG, Utils.getType("trig"));
        assertEquals(Utils.TYPE_JELLY, Utils.getType("jelly"));
        assertEquals(Utils.TYPE_JSONLD, Utils.getType("jsonld"));
        assertEquals(Utils.TYPE_NQUADS, Utils.getType("nq"));
        assertEquals(Utils.TYPE_TRIX, Utils.getType("xml"));
        assertEquals(Utils.TYPE_HTML, Utils.getType("html"));
        assertEquals(Utils.TYPE_JSON, Utils.getType("json"));
    }

    @Test
    void getTypeWithInvalidExtension() {
        assertNull(Utils.getType("invalidExtension"));
    }

    @Test
    void getHash() {
        String resourceToHash = "https://example.com/resource";
        String expectedHash = Hashing.sha256().hashString(resourceToHash, Charsets.UTF_8).toString();
        String actualHash = Utils.getHash(resourceToHash);
        assertEquals(expectedHash, actualHash);
    }

    @Test
    void getAgentLabelReplacesOrcidPrefix() {
        String agentId = "https://orcid.org/0000-0002-1825-0097";
        String expectedLabel = "orcid:0000-0002-1825-0097";
        assertEquals(expectedLabel, Utils.getAgentLabel(agentId));
    }

    @Test
    void getAgentLabelThrowsException() {
        String agentId1 = "";
        assertThrows(IllegalArgumentException.class, () -> Utils.getAgentLabel(agentId1));

        String agentId2 = null;
        assertThrows(IllegalArgumentException.class, () -> Utils.getAgentLabel(agentId2));

        String agentId3 = " ";
        assertThrows(IllegalArgumentException.class, () -> Utils.getAgentLabel(agentId3));
    }

    @Test
    void getAgentLabelWhenLong() {
        // This ORCID doesn't make sense but serves to test the truncation
        String agentId = "https://orcid.org/0000-0002-1825-009712345678901234567890123456789012345678901234567890";
        String expectedLabel = "orcid:0000-0002-1825-00971234567890123456789012345...";
        assertEquals(expectedLabel, Utils.getAgentLabel(agentId));

        String agentIdNoOrcid = "https://example.com/averylongagentidthatneedstobetruncatedbecauseitexceedsthefiftyfivecharacterlimit";
        String expectedLabelNoOrcid = "https://example.com/averylongagentidthatneedstobet...";
        assertEquals(expectedLabelNoOrcid, Utils.getAgentLabel(agentIdNoOrcid));
    }

    @Test
    void isUnloadedStatus() {
        assertTrue(Utils.isUnloadedStatus(EntryStatus.seen.getValue()));
        assertTrue(Utils.isUnloadedStatus(EntryStatus.skipped.getValue()));
        assertFalse(Utils.isUnloadedStatus("anotherStatus"));
    }

    @Test
    void isCoreLoadedStatus() {
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.visited.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.expanded.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.processed.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.aggregated.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.approved.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.contested.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.toLoad.getValue()));
        assertTrue(Utils.isCoreLoadedStatus(EntryStatus.loaded.getValue()));
        assertFalse(Utils.isCoreLoadedStatus("anotherStatus"));
    }

    @Test
    void isFullyLoadedStatus() {
        assertTrue(Utils.isFullyLoadedStatus(EntryStatus.loaded.getValue()));
        assertFalse(Utils.isFullyLoadedStatus("anotherStatus"));
    }

    @Test
    void urlEncodeWithNullURL() {
        assertEquals("", Utils.urlEncode(null));
    }

    @Test
    void urlEncodeWithValidURL() {
        String input = "https://example.com/resource with spaces";
        String expectedOutput = "https%3A%2F%2Fexample.com%2Fresource+with+spaces";
        assertEquals(expectedOutput, Utils.urlEncode(input));
    }

    @Test
    void getTypeHash() {
        ClientSession mockSession = mock(ClientSession.class);
        try (MockedStatic<RegistryDB> mockedRegistryDB = mockStatic(RegistryDB.class)) {
            mockedRegistryDB.when(() -> RegistryDB.recordHash(any(), anyString())).thenAnswer((Answer<Void>) invocation -> null);

            String type = "exampleType";
            String expectedHash = Utils.getHash(type);

            String actualHash = Utils.getTypeHash(mockSession, type);
            assertEquals(expectedHash, actualHash);

            String dollarType = "$";
            String dollarHash = Utils.getTypeHash(mockSession, dollarType);
            assertEquals(dollarType, dollarHash);
        }
    }

    @Test
    void getRandom() {
        Random random = Utils.getRandom();
        assertNotNull(random);
        assertInstanceOf(Random.class, random);

        Random anotherRandom = Utils.getRandom();
        assertSame(random, anotherRandom);
    }

    @Test
    void getSettingWithoutSettingsFile() {
        assertThrows(FileNotFoundException.class, Utils::getSetting);
    }

    @Test
    void getSettingWithSettingFile() throws Exception {
        TestUtils.copyResourceToDataDir("setting.trig");
        fakeEnv.addVariable("REGISTRY_SETTING_FILE", TestUtils.getDataDir().resolve("setting.trig").toString()).build();

        NanopubSetting settingValue = Utils.getSetting();
        assertNotNull(settingValue);

        assertSame(settingValue, Utils.getSetting());
    }

    @Test
    void getPeerUrlsWithoutSettingFile() {
        assertThrows(RuntimeException.class, Utils::getPeerUrls);
    }

    @Test
    void getPeerUrlsWithSettingFile() {
        fakeEnv
                .addVariable("REGISTRY_PEER_URLS", "")
                .addVariable("REGISTRY_SERVICE_URL", "")
                .addVariable("REGISTRY_SETTING_FILE", "setting.trig")
                .build();

        List<String> expectedPeerUrls = List.of("https://registry.petapico.org/", "https://registry.nanodash.net/", "https://registry.knowledgepixels.com/");
        assertEquals(expectedPeerUrls, Utils.getPeerUrls());

        // 2nd call to verify caching
        assertEquals(expectedPeerUrls, Utils.getPeerUrls());
    }

    @Test
    void getPeerUrlsWithNotEmptyPeerUrlsVariable() {
        fakeEnv.addVariable("REGISTRY_PEER_URLS", "https://registry.nanodash.net/ https://registry.knowledgepixels.com/")
                .addVariable("REGISTRY_SERVICE_URL", "https://registry.petapico.org/")
                .build();

        List<String> expectedPeerUrls = List.of("https://registry.nanodash.net/", "https://registry.knowledgepixels.com/");
        assertEquals(expectedPeerUrls, Utils.getPeerUrls());
    }

    @Test
    void getPeerUrlsExcludesThisRegistryFromTheEnvList() {
        fakeEnv.addVariable("REGISTRY_PEER_URLS",
                        "https://registry.nanodash.net/ https://registry.petapico.org/ https://registry.knowledgepixels.com/")
                .addVariable("REGISTRY_SERVICE_URL", "https://registry.petapico.org/")
                .build();

        // A registry syncing from itself would loop; its own URL is dropped from the list.
        assertEquals(List.of("https://registry.nanodash.net/", "https://registry.knowledgepixels.com/"),
                Utils.getPeerUrls());
    }

    @Test
    void getPeerUrlsExcludesThisRegistryFromTheBootstrapServices() {
        fakeEnv.addVariable("REGISTRY_PEER_URLS", "")
                .addVariable("REGISTRY_SERVICE_URL", "https://registry.petapico.org/")
                .addVariable("REGISTRY_SETTING_FILE", "setting.trig")
                .build();

        // Same exclusion applies when the peers come from the setting's bootstrap services.
        List<String> peerUrls = Utils.getPeerUrls();
        assertFalse(peerUrls.contains("https://registry.petapico.org/"));
        assertTrue(peerUrls.contains("https://registry.nanodash.net/"));
        assertTrue(peerUrls.contains("https://registry.knowledgepixels.com/"));
    }

    @Test
    void getRandomPeerWithoutAnyPeers() throws Exception {
        TestUtils.clearStaticFields(Utils.class, new HashMap<>() {{
            put("peerUrls", List.of());
        }});

        // There is no sensible peer to return, so the caller gets an exception rather than
        // a silent null.
        assertThrows(IllegalArgumentException.class, Utils::getRandomPeer);
    }

    @Test
    void getRandomPeer() {
        fakeEnv
                .addVariable("REGISTRY_PEER_URLS", "")
                .addVariable("REGISTRY_SERVICE_URL", "")
                .addVariable("REGISTRY_SETTING_FILE", "setting.trig")
                .build();

        List<String> peerUrls = Utils.getPeerUrls();
        String randomPeer = Utils.getRandomPeer();
        assertTrue(peerUrls.contains(randomPeer));
    }

    private static final IRI TRUSTY_TARGET = Values.iri("http://purl.org/np/RARv1-bZWsdvQs88TDH2trcwNoGF1g5AawE2sPKeh5K_0");
    private static final IRI OTHER_TRUSTY = Values.iri("http://purl.org/np/RAoxvBHmzM1yEEcQNGdiLBBS0UBMQBNzr4l1Qs8HzS_Yc");
    private static final IRI INVALIDATING_NP = Values.iri("http://purl.org/np/RAhCPQGL1JvNi_1jvJgL5cxBu9ITpEhSPYFxRrSNzhoBQ");

    /**
     * Runs getInvalidatedNanopubIds over exactly the given statements.
     */
    private static Set<IRI> invalidatedBy(Statement... statements) {
        Nanopub np = mock(Nanopub.class);
        when(np.getUri()).thenReturn(INVALIDATING_NP);
        try (MockedStatic<org.nanopub.NanopubUtils> utils = mockStatic(org.nanopub.NanopubUtils.class)) {
            utils.when(() -> org.nanopub.NanopubUtils.getStatements(np)).thenReturn(List.of(statements));
            return Utils.getInvalidatedNanopubIds(np);
        }
    }

    @Test
    void retractionsAndInvalidationsCountRegardlessOfSubject() {
        // Anyone can retract or invalidate; the subject is not constrained.
        assertEquals(Set.of(TRUSTY_TARGET), invalidatedBy(
                Values.getValueFactory().createStatement(OTHER_TRUSTY, NPX.RETRACTS, TRUSTY_TARGET)));
        assertEquals(Set.of(TRUSTY_TARGET), invalidatedBy(
                Values.getValueFactory().createStatement(OTHER_TRUSTY, NPX.INVALIDATES, TRUSTY_TARGET)));
    }

    @Test
    void supersedingOnlyCountsWhenTheNanopubSupersedesItself() {
        // "supersedes" is only meaningful when asserted by the superseding nanopub itself.
        assertEquals(Set.of(TRUSTY_TARGET), invalidatedBy(
                Values.getValueFactory().createStatement(INVALIDATING_NP, NPX.SUPERSEDES, TRUSTY_TARGET)));
        assertTrue(invalidatedBy(
                Values.getValueFactory().createStatement(OTHER_TRUSTY, NPX.SUPERSEDES, TRUSTY_TARGET)).isEmpty(),
                "a third party cannot declare someone else's nanopub superseded");
    }

    @Test
    void nonTrustyAndNonIriTargetsAreIgnored() {
        IRI notTrusty = Values.iri("http://example.org/not-a-trusty-uri");
        assertTrue(invalidatedBy(
                Values.getValueFactory().createStatement(OTHER_TRUSTY, NPX.RETRACTS, notTrusty)).isEmpty(),
                "only trusty URIs identify a nanopub to invalidate");
        assertTrue(invalidatedBy(
                Values.getValueFactory().createStatement(OTHER_TRUSTY, NPX.RETRACTS, Values.literal("RAsomething"))).isEmpty(),
                "a literal object is not a nanopub reference");
    }

    @Test
    void unrelatedPredicatesDoNotInvalidateAnything() {
        assertTrue(invalidatedBy(
                Values.getValueFactory().createStatement(OTHER_TRUSTY, FOAF.KNOWS, TRUSTY_TARGET)).isEmpty());
    }

    @Test
    void getInvalidatedNanopubIds() throws MalformedNanopubException, IOException {
        File nanopubExample = NanopubTestSuite.getLatest().getByArtifactCode("RAjPRftIBK8ZbR2LausQpdsMbI39_eRe07AZwfHTsm2dY").getFirst().toFile();
        Nanopub nanopub = new NanopubImpl(nanopubExample);
        Set<IRI> invalidatedIds = Utils.getInvalidatedNanopubIds(nanopub);
        assertEquals(1, invalidatedIds.size());
        assertTrue(invalidatedIds.contains(Values.iri("http://purl.org/np/RARv1-bZWsdvQs88TDH2trcwNoGF1g5AawE2sPKeh5K_0")));
    }

}