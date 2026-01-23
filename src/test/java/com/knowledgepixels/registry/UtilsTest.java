package com.knowledgepixels.registry;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import com.mongodb.client.ClientSession;
import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.setting.NanopubSetting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

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
    void tearDown() throws IOException {
        fakeEnv.reset();
        FileUtils.deleteDirectory(new File("data"));
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
    void getSettingWithSettingFile() throws IOException, MalformedNanopubException {
        Path dataDir = Path.of("data");
        Files.createDirectory(dataDir);
        Files.copy(Path.of("setting.trig"), dataDir.resolve("setting.trig"));

        fakeEnv.addVariable("REGISTRY_SETTING_FILE", "./data/setting.trig").build();
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
        fakeEnv.addVariable("REGISTRY_PEER_URLS", "https://registry.nanodash.net/;https://registry.knowledgepixels.com/")
                .addVariable("REGISTRY_SERVICE_URL", "https://registry.petapico.org/")
                .build();

        List<String> expectedPeerUrls = List.of("https://registry.nanodash.net/", "https://registry.knowledgepixels.com/");
        assertEquals(expectedPeerUrls, Utils.getPeerUrls());
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

    @Test
    void getInvalidatedNanopubIds() throws MalformedNanopubException, IOException {
        File nanopubExample = new File(this.getClass().getClassLoader().getResource("testsuite/valid/signed/example5.trig").getFile());
        Nanopub nanopub = new NanopubImpl(nanopubExample);
        Set<IRI> invalidatedIds = Utils.getInvalidatedNanopubIds(nanopub);
        assertEquals(1, invalidatedIds.size());
        assertTrue(invalidatedIds.contains(Values.iri("http://purl.org/np/RARv1-bZWsdvQs88TDH2trcwNoGF1g5AawE2sPKeh5K_0")));
    }

}