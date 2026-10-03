package org.calima;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The three inverted-index layouts being compared (see worker_python/datamart_index*.py):
 * a monolithic JSON file, one file per term in a letter-bucketed folder tree, and a
 * normalized SQLite postings table. Paths are parameters so the JMH benchmarks can
 * point each structure at a temp directory.
 */
public class InvertedIndex {

    // ---------- monolithic JSON ----------

    public static void insertJson(Path jsonPath, int bookId, Set<String> terms) throws IOException {
        Map<String, List<Integer>> index = readJson(jsonPath);
        for (String term : terms) {
            List<Integer> postings = index.computeIfAbsent(term, k -> new ArrayList<>());
            if (!postings.contains(bookId)) {
                postings.add(bookId);
            }
        }
        writeJson(jsonPath, index);
    }

    public static Map<String, List<Integer>> readJson(Path jsonPath) throws IOException {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        if (!Files.exists(jsonPath)) {
            return result;
        }
        String text = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8).trim();
        if (text.length() <= 2) {
            return result;
        }
        String body = text.substring(1, text.length() - 1);
        int i = 0;
        while (i < body.length()) {
            int keyStart = body.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = body.indexOf('"', keyStart + 1);
            String key = body.substring(keyStart + 1, keyEnd);
            int arrStart = body.indexOf('[', keyEnd);
            int arrEnd = body.indexOf(']', arrStart);
            String arr = body.substring(arrStart + 1, arrEnd).trim();
            List<Integer> values = new ArrayList<>();
            if (!arr.isEmpty()) {
                for (String part : arr.split(",")) {
                    values.add(Integer.parseInt(part.trim()));
                }
            }
            result.put(key, values);
            i = arrEnd + 1;
        }
        return result;
    }

    public static void writeJson(Path jsonPath, Map<String, List<Integer>> index) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<Integer>> entry : index.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":[");
            List<Integer> postings = entry.getValue();
            for (int j = 0; j < postings.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(postings.get(j));
            }
            sb.append(']');
        }
        sb.append('}');
        Files.write(jsonPath, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- folder hierarchy, one file per term ----------

    public static void insertFolders(Path indexDir, int bookId, Set<String> terms) throws IOException {
        for (String term : terms) {
            Path letterDir = indexDir.resolve(term.substring(0, 1).toUpperCase());
            Files.createDirectories(letterDir);
            Path termFile = letterDir.resolve(term + ".txt");

            Set<Integer> existing = new TreeSet<>();
            if (Files.exists(termFile)) {
                for (String line : Files.readAllLines(termFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        existing.add(Integer.parseInt(trimmed));
                    }
                }
            }
            if (!existing.contains(bookId)) {
                existing.add(bookId);
                StringBuilder sb = new StringBuilder();
                for (int id : existing) {
                    sb.append(id).append('\n');
                }
                Files.write(termFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // ---------- normalized SQLite postings table ----------

    public static void createSqliteSchema(Path dbPath) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS postings ("
                    + "term TEXT, book_id INTEGER, PRIMARY KEY (term, book_id))");
        }
    }

    public static void insertSqlite(Path dbPath, int bookId, Set<String> terms) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO postings (term, book_id) VALUES (?, ?)")) {
                for (String term : terms) {
                    ps.setString(1, term);
                    ps.setInt(2, bookId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }
}
