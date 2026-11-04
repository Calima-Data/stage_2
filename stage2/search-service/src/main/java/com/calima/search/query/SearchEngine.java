package com.calima.search.query;

import com.calima.search.common.ApiException;
import com.calima.search.common.Sqlite;
import com.calima.search.common.Tokenizer;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Answers queries from the published datamart. Every statement of a request runs in one read
 * transaction, so totals, scores and metadata all describe the same index version.
 *
 * <p>Matching: a book must contain every query term (AND). Ranking: BM25, then {@code book_id}, so
 * that pages are stable between requests.
 */
public final class SearchEngine {

    static final double BM25_K1 = 1.2;
    static final double BM25_B = 0.75;
    private static final int MAX_FILTER_VALUES = 200;

    /** A search response and the index version it was computed from. */
    public record Answer(long version, long total, List<Map<String, Object>> results) {
    }

    private record Scored(int bookId, double score) {
    }

    private static final class Candidate {
        int matchedTerms;
        double score;
    }

    private final Sqlite db;
    private final ResultCache cache;

    public SearchEngine(Sqlite db, ResultCache cache) {
        this.db = db;
        this.cache = cache;
    }

    public Answer search(SearchRequest request) throws SQLException {
        return readPublished(c -> {
            long version = version(c);
            Answer cached = cache.get(request, version);
            if (cached != null) {
                return cached;
            }
            List<Scored> ranked = rank(c, request);
            int from = Math.min(request.offset(), ranked.size());
            int to = Math.min(from + request.limit(), ranked.size());
            List<Scored> page = ranked.subList(from, to);
            Answer answer = new Answer(version, ranked.size(), metadataOf(c, page));
            cache.put(request, answer);
            return answer;
        });
    }

    /** Book details, or null if the book is not published. */
    public Map<String, Object> book(int bookId) throws SQLException {
        return readPublished(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT book_id, title, author, language, year, word_count, "
                    + "unique_terms, indexed_at FROM books WHERE book_id = ?")) {
                ps.setInt(1, bookId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> book = baseFields(rs);
                    book.put("word_count", rs.getLong("word_count"));
                    book.put("unique_terms", rs.getLong("unique_terms"));
                    book.put("indexed_at", java.time.Instant.ofEpochMilli(rs.getLong("indexed_at")).toString());
                    return book;
                }
            }
        });
    }

    /** The values a front-end can offer as filters, with how many books each one would match. */
    public Map<String, Object> filters() throws SQLException {
        return readPublished(c -> {
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("languages", counted(c, "language", "value ASC"));
            filters.put("years", counted(c, "year", "value ASC"));
            filters.put("authors", counted(c, "author", "n DESC, value ASC"));
            return filters;
        });
    }

    private static List<Map<String, Object>> counted(Connection c, String column, String order) throws SQLException {
        List<Map<String, Object>> values = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " AS value, COUNT(*) AS n FROM books WHERE "
                + column + " IS NOT NULL GROUP BY " + column + " ORDER BY " + order + " LIMIT " + MAX_FILTER_VALUES);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("value", rs.getObject("value"));
                value.put("books", rs.getLong("n"));
                values.add(value);
            }
        }
        return values;
    }

    private <T> T readPublished(Sqlite.Work<T> work) throws SQLException {
        if (!Files.exists(db.file())) {
            throw new ApiException(503, "index_unavailable", "No index has been published yet", 5);
        }
        return db.read(work);
    }

    private static long version(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT version FROM index_state WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private List<Scored> rank(Connection c, SearchRequest request) throws SQLException {
        double bookCount;
        double averageLength;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), AVG(word_count) FROM books");
             ResultSet rs = ps.executeQuery()) {
            bookCount = rs.getDouble(1);
            averageLength = rs.getDouble(2);
        }
        if (bookCount == 0) {
            return List.of();
        }

        Map<String, Double> idf = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM postings WHERE term = ?")) {
            for (String term : request.terms()) {
                ps.setString(1, term);
                try (ResultSet rs = ps.executeQuery()) {
                    idf.put(term, Bm25.idf(bookCount, rs.getLong(1)));
                }
            }
        }

        Map<Integer, Candidate> candidates = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT p.book_id, p.term, p.tf, b.word_count FROM postings p "
                + "JOIN books b ON b.book_id = p.book_id WHERE p.term IN (")
                .append(placeholders(request.terms().size())).append(")");
        List<Object> arguments = new ArrayList<>(request.terms());
        if (request.author() != null) {
            sql.append(" AND b.author_norm LIKE ? ESCAPE '\\'");
            arguments.add("%" + escapeLike(Tokenizer.normalize(request.author())) + "%");
        }
        if (request.language() != null) {
            sql.append(" AND b.language = ?");
            arguments.add(request.language());
        }
        if (request.year() != null) {
            sql.append(" AND b.year = ?");
            arguments.add(request.year());
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < arguments.size(); i++) {
                ps.setObject(i + 1, arguments.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Candidate candidate = candidates.computeIfAbsent(rs.getInt(1), id -> new Candidate());
                    candidate.matchedTerms++;
                    candidate.score += Bm25.score(idf.get(rs.getString(2)), rs.getInt(3), rs.getInt(4), averageLength);
                }
            }
        }

        List<Scored> ranked = new ArrayList<>();
        int required = request.terms().size();
        candidates.forEach((bookId, candidate) -> {
            if (candidate.matchedTerms == required) {
                ranked.add(new Scored(bookId, candidate.score));
            }
        });
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::bookId));
        return ranked;
    }

    private List<Map<String, Object>> metadataOf(Connection c, List<Scored> page) throws SQLException {
        if (page.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Integer, Map<String, Object>> byId = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT book_id, title, author, language, year FROM books "
                + "WHERE book_id IN (" + placeholders(page.size()) + ")")) {
            for (int i = 0; i < page.size(); i++) {
                ps.setInt(i + 1, page.get(i).bookId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byId.put(rs.getInt("book_id"), baseFields(rs));
                }
            }
        }
        List<Map<String, Object>> results = new ArrayList<>(page.size());
        for (Scored scored : page) {
            Map<String, Object> result = byId.get(scored.bookId());
            result.put("score", Math.round(scored.score() * 10_000) / 10_000.0);
            results.add(result);
        }
        return results;
    }

    private static Map<String, Object> baseFields(ResultSet rs) throws SQLException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("book_id", rs.getInt("book_id"));
        fields.put("title", rs.getString("title"));
        fields.put("author", rs.getString("author"));
        fields.put("language", rs.getString("language"));
        int year = rs.getInt("year");
        fields.put("year", rs.wasNull() ? null : year);
        return fields;
    }

    private static String placeholders(int count) {
        StringJoiner joiner = new StringJoiner(",");
        for (int i = 0; i < count; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
