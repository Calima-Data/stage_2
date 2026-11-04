package com.calima.search.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void countsLowerCasedOccurrences() {
        assertEquals(Map.of("the", 2, "whale", 1), Tokenizer.termFrequencies("The whale, the."));
    }

    @Test
    void keepsDigitsAndSplitsOnPunctuation() {
        assertEquals(List.of("chapter", "12", "don", "t", "stop"), Tokenizer.queryTerms("Chapter-12: don't _stop_"));
    }

    @Test
    void stripsDiacriticsSoQueriesMatchWithOrWithoutThem() {
        assertEquals(List.of("espanol", "cafe"), Tokenizer.queryTerms("Español CAFÉ"));
        assertEquals(List.of("cafe"), Tokenizer.queryTerms("cafe"));
    }

    @Test
    void treatsDecomposedAndComposedTextAlike() {
        String decomposed = Normalizer.normalize("café", Normalizer.Form.NFD);
        assertEquals(List.of("cafe"), Tokenizer.queryTerms(decomposed));
    }

    @Test
    void queryTermsAreDistinctAndKeepTheirOrder() {
        assertEquals(List.of("sea", "ship"), Tokenizer.queryTerms("Sea ship SEA"));
    }

    @Test
    void textWithoutWordsHasNoTerms() {
        assertTrue(Tokenizer.queryTerms("!!! ... ---").isEmpty());
        assertTrue(Tokenizer.termFrequencies("").isEmpty());
    }

    @Test
    void dropsOverlongRuns() {
        assertTrue(Tokenizer.queryTerms("x".repeat(Tokenizer.MAX_TERM_LENGTH + 1)).isEmpty());
        assertEquals(1, Tokenizer.queryTerms("x".repeat(Tokenizer.MAX_TERM_LENGTH)).size());
    }

    @Test
    void normalizesAuthorNames() {
        assertEquals("garcia marquez", Tokenizer.normalize("García MÁRQUEZ"));
    }
}
