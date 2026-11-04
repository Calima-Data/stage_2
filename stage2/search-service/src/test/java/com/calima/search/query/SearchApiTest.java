package com.calima.search.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.calima.search.common.Config;
import com.calima.search.common.ServiceClient;
import com.calima.search.common.Sqlite;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the service over real HTTP against a datamart with the schema the Indexing Service publishes. */
class SearchApiTest {

    @TempDir
    Path dir;

    private Sqlite writer;
    private Javalin app;
    private ServiceClient client;

    @BeforeEach
    void start() throws Exception {
        Path file = dir.resolve("search.db");
        writer = Sqlite.writer(file, 2, 2000, "NORMAL");
        writer.write(c -> {
            Sqlite.execute(c, "CREATE TABLE books (book_id INTEGER PRIMARY KEY, title TEXT, author TEXT, author_norm TEXT, "
                    + "language TEXT NOT NULL, year INTEGER, word_count INTEGER NOT NULL, unique_terms INTEGER NOT NULL, "
                    + "content_sha256 TEXT NOT NULL, indexed_at INTEGER NOT NULL)");
            Sqlite.execute(c, "CREATE TABLE postings (term TEXT NOT NULL, book_id INTEGER NOT NULL, tf INTEGER NOT NULL, "
                    + "PRIMARY KEY (term, book_id)) WITHOUT ROWID");
            Sqlite.execute(c, "CREATE TABLE index_state (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, last_update INTEGER)");
            Sqlite.execute(c, "INSERT INTO index_state VALUES (1, 1, 0)");
            return null;
        });
        book(1, "Moby Dick", "Herman Melville", "herman melville", "en", 1851, 1000, "whale", 50, "sea", 20);
        book(2, "Whale Notes", "Herman Melville", "herman melville", "en", 1860, 100, "whale", 30);
        book(3, "La Ballena", "José Martí", "jose marti", "es", 1851, 1000, "whale", 1, "sea", 1);
        book(4, "Sea Stories", null, null, "en", null, 1000, "sea", 5);
        System.setProperty("DATAMART_PATH", file.toString());
        System.setProperty("SEARCH_CACHE_SIZE", "100");
        app = App.create(Config.load()).start(0);
        client = new ServiceClient(List.of("http://127.0.0.1:" + app.port()), Duration.ofSeconds(10));
    }

    @AfterEach
    void stop() {
        app.stop();
        writer.close();
    }

    private void book(int id, String title, String author, String authorNorm, String language, Integer year, int words,
                      Object... termsAndCounts) throws Exception {
        writer.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO books VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'sha', 0)")) {
                ps.setInt(1, id);
                ps.setString(2, title);
                ps.setString(3, author);
                ps.setString(4, authorNorm);
                ps.setString(5, language);
                ps.setObject(6, year);
                ps.setInt(7, words);
                ps.setInt(8, termsAndCounts.length / 2);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO postings VALUES (?, ?, ?)")) {
                for (int i = 0; i < termsAndCounts.length; i += 2) {
                    ps.setString(1, (String) termsAndCounts[i]);
                    ps.setInt(2, id);
                    ps.setInt(3, (Integer) termsAndCounts[i + 1]);
                    ps.executeUpdate();
                }
            }
            Sqlite.execute(c, "UPDATE index_state SET version = version + 1");
            return null;
        });
    }

    private JsonObject ok(String query) throws Exception {
        ServiceClient.Response response = client.get("/search?" + query);
        assertEquals(200, response.status(), String.valueOf(response.json()));
        return response.json();
    }

    private static List<Integer> ids(JsonObject answer) {
        List<Integer> ids = new ArrayList<>();
        JsonArray results = answer.getAsJsonArray("results");
        results.forEach(r -> ids.add(r.getAsJsonObject().get("book_id").getAsInt()));
        return ids;
    }

    private void assertInvalid(String query, String code) throws Exception {
        ServiceClient.Response response = client.get("/search?" + query);
        assertEquals(400, response.status(), query);
        assertEquals(code, response.errorCode(), query);
    }

    @Test
    void keywordSearchFollowsTheContract() throws Exception {
        JsonObject answer = ok("q=whale");
        assertEquals("whale", answer.get("query").getAsString());
        assertEquals(0, answer.getAsJsonObject("filters").size());
        assertEquals(3, answer.get("total").getAsInt());
        assertEquals(0, answer.get("offset").getAsInt());
        assertEquals(20, answer.get("limit").getAsInt());
        JsonObject first = answer.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("Whale Notes", first.get("title").getAsString());
        assertEquals("Herman Melville", first.get("author").getAsString());
        assertEquals("en", first.get("language").getAsString());
        assertEquals(1860, first.get("year").getAsInt());
        assertTrue(first.get("score").getAsDouble() > 0);
    }

    @Test
    void resultsAreRankedByRelevance() throws Exception {
        // A short book with many occurrences beats a long one, and a single occurrence comes last.
        assertEquals(List.of(2, 1, 3), ids(ok("q=whale")));
    }

    @Test
    void severalTermsMustAllMatch() throws Exception {
        assertEquals(List.of(1, 3), ids(ok("q=whale%20sea")));
        assertEquals(List.of(), ids(ok("q=whale%20unicorn")));
    }

    @Test
    void queriesAreNormalizedLikeTheIndex() throws Exception {
        assertEquals(ids(ok("q=whale")), ids(ok("q=WHALE!")));
    }

    @Test
    void filtersWorkAloneAndCombined() throws Exception {
        assertEquals(List.of(3), ids(ok("q=whale&language=es")));
        assertEquals(List.of(1, 3), ids(ok("q=whale&year=1851")));
        assertEquals(List.of(2, 1), ids(ok("q=whale&author=melville")));
        assertEquals(List.of(1), ids(ok("q=whale&author=melville&language=en&year=1851")));

        JsonObject filters = ok("q=whale&author=melville&language=EN&year=1851").getAsJsonObject("filters");
        assertEquals("melville", filters.get("author").getAsString());
        assertEquals("en", filters.get("language").getAsString());
        assertEquals(1851, filters.get("year").getAsInt());
    }

    @Test
    void authorFilterIgnoresCaseAndDiacriticsAndTreatsWildcardsLiterally() throws Exception {
        String accented = URLEncoder.encode("MARTÍ", StandardCharsets.UTF_8);
        assertEquals(List.of(3), ids(ok("q=whale&author=" + accented)));
        assertEquals(List.of(), ids(ok("q=whale&author=%25")));
        assertEquals(List.of(), ids(ok("q=whale&author=_")));
    }

    @Test
    void booksWithoutAYearAreNotMatchedByTheYearFilter() throws Exception {
        assertEquals(List.of(1, 3), ids(ok("q=sea&year=1851")));
        JsonObject withoutYear = ok("q=sea&language=en&author=zzz");
        assertEquals(0, withoutYear.get("total").getAsInt());
        JsonObject book = ok("q=stories%20sea".replace("stories%20", "")).getAsJsonArray("results").get(0).getAsJsonObject();
        assertTrue(book.has("year"));
    }

    @Test
    void noMatchesIsAnEmptySuccess() throws Exception {
        JsonObject answer = ok("q=unicorn");
        assertEquals(0, answer.get("total").getAsInt());
        assertEquals(0, answer.getAsJsonArray("results").size());
        assertEquals(0, ok("q=whale&language=fr").get("total").getAsInt());
    }

    @Test
    void paginationKeepsTheTotalAndAStableOrder() throws Exception {
        List<Integer> all = ids(ok("q=whale"));
        JsonObject first = ok("q=whale&limit=2");
        JsonObject second = ok("q=whale&limit=2&offset=2");
        assertEquals(3, first.get("total").getAsInt());
        assertEquals(3, second.get("total").getAsInt());
        assertEquals(all.subList(0, 2), ids(first));
        assertEquals(all.subList(2, 3), ids(second));
        assertEquals(List.of(), ids(ok("q=whale&offset=50")));
        assertEquals(50, ok("q=whale&offset=50").get("offset").getAsInt());
    }

    @Test
    void invalidParametersReturnDocumentedErrors() throws Exception {
        assertEquals("missing_query", client.get("/search").errorCode());
        assertInvalid("q=", "missing_query");
        assertInvalid("q=%20%20", "missing_query");
        assertInvalid("q=!!!", "invalid_query");
        assertInvalid("q=" + "a".repeat(201), "invalid_query");
        assertInvalid("q=a%20b%20c%20d%20e%20f%20g%20h%20i%20j%20k", "invalid_query");
        assertInvalid("q=x&limit=0", "invalid_limit");
        assertInvalid("q=x&limit=101", "invalid_limit");
        assertInvalid("q=x&limit=abc", "invalid_limit");
        assertInvalid("q=x&offset=-1", "invalid_offset");
        assertInvalid("q=x&year=99", "invalid_year");
        assertInvalid("q=x&year=abcd", "invalid_year");
        assertInvalid("q=x&language=english", "invalid_language");
        assertInvalid("q=x&author=%20", "invalid_author");
        assertInvalid("q=x&lang=en", "unknown_parameter");
        assertInvalid("q=x&q=y", "repeated_parameter");
    }

    @Test
    void bookDetailsAndNotFound() throws Exception {
        JsonObject book = client.get("/books/1").json();
        assertEquals("Moby Dick", book.get("title").getAsString());
        assertEquals(1000, book.get("word_count").getAsInt());
        assertTrue(client.get("/books/4").json().get("year").isJsonNull());

        ServiceClient.Response missing = client.get("/books/99");
        assertEquals(404, missing.status());
        assertEquals("book_not_found", missing.errorCode());
        assertEquals(400, client.get("/books/abc").status());
    }

    @Test
    void filterValuesAreListedWithTheirCounts() throws Exception {
        JsonObject filters = client.get("/search/filters").json();
        JsonArray languages = filters.getAsJsonArray("languages");
        assertEquals("en", languages.get(0).getAsJsonObject().get("value").getAsString());
        assertEquals(3, languages.get(0).getAsJsonObject().get("books").getAsInt());
        assertEquals(2, filters.getAsJsonArray("years").size());
        assertEquals("Herman Melville", filters.getAsJsonArray("authors").get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void aNewlyPublishedBookIsVisibleDespiteTheCache() throws Exception {
        assertEquals(3, ok("q=whale").get("total").getAsInt());
        assertEquals(3, ok("q=whale").get("total").getAsInt());
        book(5, "New Whale", "N", "n", "en", 2000, 500, "whale", 9);
        assertEquals(4, ok("q=whale").get("total").getAsInt());
    }

    @Test
    void anUnpublishedDatamartIsADocumentedError() throws Exception {
        System.setProperty("DATAMART_PATH", dir.resolve("missing.db").toString());
        Javalin empty = App.create(Config.load()).start(0);
        try {
            ServiceClient emptyClient = new ServiceClient(List.of("http://127.0.0.1:" + empty.port()), Duration.ofSeconds(5));
            ServiceClient.Response response = emptyClient.get("/search?q=x");
            assertEquals(503, response.status());
            assertEquals("index_unavailable", response.errorCode());
            assertFalse(java.nio.file.Files.exists(dir.resolve("missing.db")), "search must never create the datamart");
        } finally {
            empty.stop();
        }
    }

    @Test
    void theBm25FormulaFavoursRareTermsAndShortBooks() {
        assertTrue(Bm25.idf(100, 1) > Bm25.idf(100, 50));
        double idf = Bm25.idf(100, 10);
        assertTrue(Bm25.score(idf, 5, 100, 1000) > Bm25.score(idf, 5, 5000, 1000));
        assertTrue(Bm25.score(idf, 50, 1000, 1000) > Bm25.score(idf, 5, 1000, 1000));
    }
}
