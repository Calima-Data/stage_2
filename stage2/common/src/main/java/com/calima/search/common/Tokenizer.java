package com.calima.search.common;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The text normalization rule, shared by indexing and search so that a query always uses the same
 * terms as the index: runs of letters and digits, lower-cased, without diacritics.
 *
 * <p>Stage 1 used {@code [a-z0-9]+}, which breaks non-English words ("español" became "espa" and
 * "ol"). The corpus includes Spanish and French books, so this rule is Unicode-aware.
 */
public final class Tokenizer {

    /** Longer runs are noise such as ASCII art or encoded data, not searchable words. */
    public static final int MAX_TERM_LENGTH = 64;

    private Tokenizer() {
    }

    /** Word occurrences in a document, by term. */
    public static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> frequencies = new HashMap<>();
        scan(text, term -> frequencies.merge(term, 1, Integer::sum));
        return frequencies;
    }

    /** The distinct terms of a query, in the order they first appear. */
    public static List<String> queryTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        scan(query, terms::add);
        return new ArrayList<>(terms);
    }

    /** Lower-cases and strips diacritics; also used to compare author names. */
    public static String normalize(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) > 127) {
                return stripMarks(lower);
            }
        }
        return lower;
    }

    private static void scan(String text, java.util.function.Consumer<String> consumer) {
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean inWord = i < text.length() && isWordChar(text.charAt(i));
            if (inWord && start < 0) {
                start = i;
            } else if (!inWord && start >= 0) {
                String term = stripMarks(text.substring(start, i).toLowerCase(java.util.Locale.ROOT));
                if (!term.isEmpty() && term.length() <= MAX_TERM_LENGTH) {
                    consumer.accept(term);
                }
                start = -1;
            }
        }
    }

    private static boolean isWordChar(char c) {
        if (c < 128) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        }
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    private static String stripMarks(String term) {
        boolean ascii = true;
        for (int i = 0; i < term.length() && ascii; i++) {
            ascii = term.charAt(i) < 128;
        }
        if (ascii) {
            return term;
        }
        String decomposed = Normalizer.normalize(term, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
