package com.knowledgepixels.registry;

import com.knowledgepixels.registry.utils.FakeEnv;
import com.knowledgepixels.registry.utils.TestUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverageFilterTest {

    private FakeEnv fakeEnv;

    @BeforeEach
    void setUp() {
        fakeEnv = TestUtils.setupFakeEnv();
    }

    @AfterEach
    void tearDown() {
        fakeEnv.reset();
    }

    @Test
    void coversAllTypes_whenNoEnvVar() {
        fakeEnv.build();
        CoverageFilter.init();
        assertTrue(CoverageFilter.coversAllTypes());
        assertNull(CoverageFilter.getCoveredTypeHashes());
        assertNull(CoverageFilter.getCoveredTypeHashesAsString());
    }

    @Test
    void coversAllTypes_whenEmptyEnvVar() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "").build();
        CoverageFilter.init();
        assertTrue(CoverageFilter.coversAllTypes());
    }

    @Test
    void restrictedCoverage_includesCoreTypes() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/MyType").build();
        CoverageFilter.init();
        assertFalse(CoverageFilter.coversAllTypes());

        Set<String> covered = CoverageFilter.getCoveredTypeHashes();
        assertNotNull(covered);
        // Should include the configured type + intro + endorsement core types
        assertTrue(covered.contains(Utils.getHash("http://example.org/MyType")));
        assertTrue(covered.contains(NanopubLoader.INTRO_TYPE_HASH));
        assertTrue(covered.contains(NanopubLoader.ENDORSE_TYPE_HASH));
        assertEquals(3, covered.size());
    }

    @Test
    void restrictedCoverage_multipleTypes() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/TypeA,http://example.org/TypeB").build();
        CoverageFilter.init();

        Set<String> covered = CoverageFilter.getCoveredTypeHashes();
        // 2 configured + 2 core = 4
        assertEquals(4, covered.size());
        assertTrue(covered.contains(Utils.getHash("http://example.org/TypeA")));
        assertTrue(covered.contains(Utils.getHash("http://example.org/TypeB")));
    }

    @Test
    void isCoveredType_dollarAlwaysCovered() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/MyType").build();
        CoverageFilter.init();
        assertTrue(CoverageFilter.isCoveredType("$"));
    }

    @Test
    void isCoveredType_uncoveredTypeRejected() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/MyType").build();
        CoverageFilter.init();
        assertFalse(CoverageFilter.isCoveredType(Utils.getHash("http://example.org/OtherType")));
    }

    @Test
    void isCoveredType_allCoveredWhenNoRestriction() {
        fakeEnv.build();
        CoverageFilter.init();
        assertTrue(CoverageFilter.isCoveredType("anyhash"));
        assertTrue(CoverageFilter.isCoveredType(Utils.getHash("http://example.org/Anything")));
    }

    @Test
    void isCovered_nanopubWithCoveredType() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/MyType").build();
        CoverageFilter.init();

        Nanopub np = mock(Nanopub.class);
        IRI typeIri = SimpleValueFactory.getInstance().createIRI("http://example.org/MyType");
        try (var mocked = mockStatic(NanopubUtils.class)) {
            mocked.when(() -> NanopubUtils.getTypes(np)).thenReturn(Set.of(typeIri));
            assertTrue(CoverageFilter.isCovered(np));
        }
    }

    @Test
    void isCovered_nanopubWithUncoveredType() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/MyType").build();
        CoverageFilter.init();

        Nanopub np = mock(Nanopub.class);
        IRI typeIri = SimpleValueFactory.getInstance().createIRI("http://example.org/OtherType");
        try (var mocked = mockStatic(NanopubUtils.class)) {
            mocked.when(() -> NanopubUtils.getTypes(np)).thenReturn(Set.of(typeIri));
            assertFalse(CoverageFilter.isCovered(np));
        }
    }

    @Test
    void isCovered_nanopubWithNoTypes_accepted() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/MyType").build();
        CoverageFilter.init();

        Nanopub np = mock(Nanopub.class);
        try (var mocked = mockStatic(NanopubUtils.class)) {
            mocked.when(() -> NanopubUtils.getTypes(np)).thenReturn(Set.of());
            assertTrue(CoverageFilter.isCovered(np), "Nanopubs with no types should be accepted");
        }
    }

    @Test
    void isCovered_allAcceptedWhenNoRestriction() {
        fakeEnv.build();
        CoverageFilter.init();

        Nanopub np = mock(Nanopub.class);
        IRI typeIri = SimpleValueFactory.getInstance().createIRI("http://example.org/Anything");
        try (var mocked = mockStatic(NanopubUtils.class)) {
            mocked.when(() -> NanopubUtils.getTypes(np)).thenReturn(Set.of(typeIri));
            assertTrue(CoverageFilter.isCovered(np));
        }
    }

    @Test
    void getCoveredTypeHashesAsString_returnsCommaSeparated() {
        fakeEnv.addVariable("REGISTRY_COVERAGE_TYPES", "http://example.org/TypeA").build();
        CoverageFilter.init();

        String result = CoverageFilter.getCoveredTypeHashesAsString();
        assertNotNull(result);
        // Should contain 3 hashes (TypeA + intro + endorsement) comma-separated
        assertEquals(3, result.split(",").length);
    }
}
