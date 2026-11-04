package com.calima.search.query;

import com.calima.search.common.ApiException;
import com.calima.search.common.Tokenizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A validated {@code GET /search} request. Validation rules are part of the published contract. */
public record SearchRequest(String query, List<String> terms, String author, String language, Integer year,
                            int offset, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_OFFSET = 1_000_000;
    public static final int MAX_QUERY_LENGTH = 200;
    public static final int MAX_QUERY_TERMS = 10;
    public static final int MAX_AUTHOR_LENGTH = 100;

    private static final Set<String> KNOWN_PARAMETERS = Set.of("q", "author", "language", "year", "offset", "limit");

    /** @throws ApiException 400 with a specific error code when a parameter is invalid */
    public static SearchRequest parse(Map<String, List<String>> parameters) {
        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            if (!KNOWN_PARAMETERS.contains(parameter.getKey())) {
                // Ignoring a misspelled filter would silently return unfiltered results.
                throw invalid("unknown_parameter", "Unknown parameter: " + parameter.getKey());
            }
            if (parameter.getValue().size() > 1) {
                throw invalid("repeated_parameter", "Parameter may appear only once: " + parameter.getKey());
            }
        }

        String query = single(parameters, "q");
        if (query == null || query.isBlank()) {
            throw invalid("missing_query", "Parameter q is required");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw invalid("invalid_query", "q must have at most " + MAX_QUERY_LENGTH + " characters");
        }
        List<String> terms = Tokenizer.queryTerms(query);
        if (terms.isEmpty()) {
            throw invalid("invalid_query", "q must contain at least one letter or digit");
        }
        if (terms.size() > MAX_QUERY_TERMS) {
            throw invalid("invalid_query", "q must have at most " + MAX_QUERY_TERMS + " distinct terms");
        }

        String author = single(parameters, "author");
        if (author != null && (author.isBlank() || author.length() > MAX_AUTHOR_LENGTH)) {
            throw invalid("invalid_author", "author must have between 1 and " + MAX_AUTHOR_LENGTH + " characters");
        }

        String language = single(parameters, "language");
        if (language != null) {
            language = language.toLowerCase(Locale.ROOT);
            if (!language.matches("[a-z]{2}|und")) {
                throw invalid("invalid_language", "language must be a two-letter ISO 639-1 code or und, such as en");
            }
        }

        Integer year = null;
        String rawYear = single(parameters, "year");
        if (rawYear != null) {
            if (!rawYear.matches("\\d{4}")) {
                throw invalid("invalid_year", "year must have four digits, such as 1813");
            }
            year = Integer.valueOf(rawYear);
        }

        return new SearchRequest(query, terms, author, language, year,
                integer(parameters, "offset", 0, 0, MAX_OFFSET, "invalid_offset"),
                integer(parameters, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT, "invalid_limit"));
    }

    /** The filters that were actually requested, echoed in the response. */
    public Map<String, Object> filters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (author != null) {
            filters.put("author", author);
        }
        if (language != null) {
            filters.put("language", language);
        }
        if (year != null) {
            filters.put("year", year);
        }
        return filters;
    }

    private static String single(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static int integer(Map<String, List<String>> parameters, String name, int fallback, int min, int max,
                               String errorCode) {
        String raw = single(parameters, name);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw invalid(errorCode, name + " must be an integer between " + min + " and " + max);
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(400, code, message);
    }
}
