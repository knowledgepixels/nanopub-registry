package com.knowledgepixels.registry;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.nanopub.Nanopub;
import org.nanopub.extra.setting.IntroNanopub;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-logic tests for {@link Utils#extractIntroName(IntroNanopub)}. Exercises the
 * deterministic-tiebreaker policy ({@code MIN(?name)} alphabetically) when an
 * intro asserts multiple {@code foaf:name} literals on the same agent, and
 * the {@code null}-fallback when the assertion has no name.
 */
class TaskExtractIntroNameTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI AGENT = VF.createIRI("https://orcid.org/0000-0000-0000-0001");
    private static final IRI OTHER_AGENT = VF.createIRI("https://orcid.org/0000-0000-0000-0002");

    @Test
    void returnsNameLiteralWhenAssertionDeclaresOne() {
        IntroNanopub intro = introWith(
                VF.createStatement(AGENT, FOAF.NAME, VF.createLiteral("Alice")));
        assertEquals("Alice", Utils.extractIntroName(intro));
    }

    @Test
    void returnsLexicographicallyMinimumWhenMultipleNames() {
        // Determinism across rebuilds requires a stable tiebreaker for multi-name intros.
        // MIN(?name) keeps the same chosen literal regardless of triple iteration order.
        IntroNanopub intro = introWith(
                VF.createStatement(AGENT, FOAF.NAME, VF.createLiteral("Charlie")),
                VF.createStatement(AGENT, FOAF.NAME, VF.createLiteral("Alice")),
                VF.createStatement(AGENT, FOAF.NAME, VF.createLiteral("Bob")));
        assertEquals("Alice", Utils.extractIntroName(intro));
    }

    @Test
    void returnsNullWhenAssertionHasNoFoafName() {
        IntroNanopub intro = introWith(
                VF.createStatement(AGENT, RDF.TYPE, VF.createIRI("http://example.org/Thing")));
        assertNull(Utils.extractIntroName(intro));
    }

    @Test
    void ignoresFoafNameAssertedOnDifferentSubject() {
        // An intro might mention other people's names in passing; only the user's
        // own foaf:name should be considered.
        IntroNanopub intro = introWith(
                VF.createStatement(OTHER_AGENT, FOAF.NAME, VF.createLiteral("Mallory")));
        assertNull(Utils.extractIntroName(intro));
    }

    @Test
    void ignoresNonLiteralObject() {
        // Defensive: the object of foaf:name is normally a literal, but a malformed
        // intro could put an IRI there. Such triples must not be picked up as names.
        IntroNanopub intro = introWith(
                VF.createStatement(AGENT, FOAF.NAME, VF.createIRI("http://example.org/not-a-name")));
        assertNull(Utils.extractIntroName(intro));
    }

    @Test
    void returnsNullWhenIntroHasNoUser() {
        IntroNanopub intro = mock(IntroNanopub.class);
        when(intro.getUser()).thenReturn(null);
        assertNull(Utils.extractIntroName(intro));
    }

    private static IntroNanopub introWith(Statement... assertion) {
        Nanopub np = mock(Nanopub.class);
        Set<Statement> assertionSet = new LinkedHashSet<>();
        for (Statement st : assertion) assertionSet.add(st);
        when(np.getAssertion()).thenReturn(assertionSet);
        IntroNanopub intro = mock(IntroNanopub.class);
        when(intro.getUser()).thenReturn(AGENT);
        when(intro.getNanopub()).thenReturn(np);
        return intro;
    }
}
