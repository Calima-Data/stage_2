import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Java port of worker_python/test_index_benchmark.py.
 * Compares update cost of the three inverted-index layouts (SQLite,
 * monolithic JSON, folder hierarchy) using the same synthetic workload:
 * a 5000-word pre-existing vocabulary, then one new book contributing
 * 450 existing + 50 brand-new terms, over 5 rounds without resetting
 * state between rounds (round 1 is the real insert, rounds 2-5 mostly
 * hit "already present" paths) - matching the Python benchmark exactly.
 */
public class IndexBenchmark {

    static final Path TEST_DIR = Paths.get("..", "index_benchmark_tmp_java").toAbsolutePath().normalize();
    static final Path DB_PATH = TEST_DIR.resolve("inverted_index.db");
    static final Path JSON_PATH = TEST_DIR.resolve("inverted_index.json");
    static final Path FOLDER_DIR = TEST_DIR.resolve("folders");

    static final int VOCAB_SIZE = 5000;
    static final int TEST_BOOK_ID = 9999;
    static final int ROUNDS = 5;

    static List<String> vocabulary;
    static Set<String> bookWords;

    // ---------- minimal JSON codec for { "term": [ints, ...], ... } ----------

    static String toJson(Map<String, List<Integer>> index) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : index.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":[");
            sb.append(e.getValue().stream().map(String::valueOf).collect(Collectors.joining(",")));
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    static Map<String, List<Integer>> fromJson(String text) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        text = text.strip();
        if (text.length() <= 2) return result;
        text = text.substring(1, text.length() - 1); // strip outer { }
        int i = 0, n = text.length();
        while (i < n) {
            while (i < n && (text.charAt(i) == ',' || Character.isWhitespace(text.charAt(i)))) i++;
            if (i >= n) break;
            int keyStart = text.indexOf('"', i) + 1;
            int keyEnd = text.indexOf('"', keyStart);
            String key = text.substring(keyStart, keyEnd);
            int arrStart = text.indexOf('[', keyEnd) + 1;
            int arrEnd = text.indexOf(']', arrStart);
            String arrBody = text.substring(arrStart, arrEnd).trim();
            List<Integer> vals = new ArrayList<>();
            if (!arrBody.isEmpty()) {
                for (String v : arrBody.split(",")) vals.add(Integer.parseInt(v.trim()));
            }
            result.put(key, vals);
            i = arrEnd + 1;
        }
        return result;
    }

    // ---------- setup ----------

    static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    static void setup() throws Exception {
        deleteRecursive(TEST_DIR);
        Files.createDirectories(TEST_DIR);
        Files.createDirectories(FOLDER_DIR);

        vocabulary = new ArrayList<>();
        for (int i = 0; i < VOCAB_SIZE; i++) vocabulary.add("word" + i);

        List<String> shuffled = new ArrayList<>(vocabulary);
        Collections.shuffle(shuffled);
        bookWords = new LinkedHashSet<>(shuffled.subList(0, 450));
        for (int i = 0; i < 50; i++) bookWords.add("new_word" + i);

        // 1. SQLite: 5000 words -> book_id 1
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE postings (term TEXT, book_id INTEGER, PRIMARY KEY (term, book_id))");
            }
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO postings (term, book_id) VALUES (?, ?)")) {
                for (String w : vocabulary) {
                    ps.setString(1, w);
                    ps.setInt(2, 1);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }

        // 2. JSON: 5000 words -> [1]
        Map<String, List<Integer>> index = new LinkedHashMap<>();
        for (String w : vocabulary) index.put(w, new ArrayList<>(List.of(1)));
        Files.writeString(JSON_PATH, toJson(index), StandardCharsets.UTF_8);

        // 3. Folders: only the words that are both in BOOK_WORDS and in VOCABULARY
        Set<String> vocabSet = new HashSet<>(vocabulary);
        for (String w : bookWords) {
            if (vocabSet.contains(w)) {
                Path letterDir = FOLDER_DIR.resolve(w.substring(0, 1).toUpperCase());
                Files.createDirectories(letterDir);
                Files.writeString(letterDir.resolve(w + ".txt"), "1\n", StandardCharsets.UTF_8);
            }
        }
    }

    // ---------- insert operations under benchmark ----------

    static void insertJson() throws IOException {
        Map<String, List<Integer>> index = fromJson(Files.readString(JSON_PATH, StandardCharsets.UTF_8));
        for (String w : bookWords) {
            List<Integer> postings = index.computeIfAbsent(w, k -> new ArrayList<>());
            if (!postings.contains(TEST_BOOK_ID)) postings.add(TEST_BOOK_ID);
        }
        Files.writeString(JSON_PATH, toJson(index), StandardCharsets.UTF_8);
    }

    static void insertSqlite() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO postings (term, book_id) VALUES (?, ?)")) {
                for (String w : bookWords) {
                    ps.setString(1, w);
                    ps.setInt(2, TEST_BOOK_ID);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    static void insertFolders() throws IOException {
        for (String w : bookWords) {
            Path letterDir = FOLDER_DIR.resolve(w.substring(0, 1).toUpperCase());
            Files.createDirectories(letterDir);
            Path wordFile = letterDir.resolve(w + ".txt");

            Set<Integer> existing = new TreeSet<>();
            if (Files.exists(wordFile)) {
                for (String line : Files.readAllLines(wordFile, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (!line.isEmpty()) existing.add(Integer.parseInt(line));
                }
            }
            if (!existing.contains(TEST_BOOK_ID)) {
                existing.add(TEST_BOOK_ID);
                StringBuilder sb = new StringBuilder();
                for (int id : existing) sb.append(id).append('\n');
                Files.writeString(wordFile, sb.toString(), StandardCharsets.UTF_8);
            }
        }
    }

    // ---------- timing harness ----------

    interface Op { void run() throws Exception; }

    static double[] time(Op op) throws Exception {
        double[] timesMs = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long start = System.nanoTime();
            op.run();
            long end = System.nanoTime();
            timesMs[r] = (end - start) / 1_000_000.0;
        }
        return timesMs;
    }

    static void printStats(String name, double[] times) {
        double min = Arrays.stream(times).min().orElse(0);
        double max = Arrays.stream(times).max().orElse(0);
        double mean = Arrays.stream(times).average().orElse(0);
        double[] sorted = times.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double variance = Arrays.stream(times).map(t -> Math.pow(t - mean, 2)).sum() / (times.length - 1);
        double stddev = Math.sqrt(variance);
        System.out.printf(Locale.US, "%-16s min=%10.4f  max=%10.4f  mean=%10.4f  median=%10.4f  stddev=%10.4f  (ms)%n",
                name, min, max, mean, median, stddev);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Inverted index benchmark (Java) - " + ROUNDS + " rounds, 5000-word vocabulary, +500-word book");

        setup();
        double[] sqliteTimes = time(IndexBenchmark::insertSqlite);

        setup();
        double[] jsonTimes = time(IndexBenchmark::insertJson);

        setup();
        double[] folderTimes = time(IndexBenchmark::insertFolders);

        printStats("index_sqlite", sqliteTimes);
        printStats("index_json", jsonTimes);
        printStats("index_folders", folderTimes);

        deleteRecursive(TEST_DIR);
    }
}
