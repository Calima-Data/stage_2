package org.calima;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Java implementation of the datalake ingestion layer (see worker_python/datalake.py).
 * Downloads books from Project Gutenberg, splits header from body, and stores them
 * under the three datalake layouts being compared. Save methods take their base
 * directory as a parameter so the JMH benchmarks can point them at a temp directory.
 */
public class Datalake {

    static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");

    /** Header and body of one downloaded book. */
    public static class Book {
        public final String header;
        public final String body;

        public Book(String header, String body) {
            this.header = header;
            this.body = body;
        }
    }

    // ---------- control layer ----------

    public static Set<Integer> getDownloadedBooks(Path controlFile) throws IOException {
        Set<Integer> ids = new HashSet<>();
        if (!Files.exists(controlFile)) {
            return ids;
        }
        for (String line : Files.readAllLines(controlFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                ids.add(Integer.parseInt(trimmed));
            }
        }
        return ids;
    }

    public static void markAsDownloaded(Path controlFile, int bookId) throws IOException {
        Files.createDirectories(controlFile.getParent());
        Files.write(controlFile, (bookId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---------- download ----------

    public static Book fetchAndSplitBook(int bookId) {
        String url = String.format("https://www.gutenberg.org/cache/epub/%d/pg%d.txt", bookId, bookId);
        System.out.println("Descargando libro " + bookId + "...");
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                System.out.println("Error al descargar libro " + bookId + ": HTTP " + response.statusCode());
                return null;
            }
            String text = response.body();
            int startIdx = text.indexOf(START_MARKER);
            int endIdx = text.indexOf(END_MARKER);
            if (startIdx < 0 || endIdx < 0) {
                System.out.println("Error: Marcadores no encontrados en el libro " + bookId);
                return null;
            }
            String header = text.substring(0, startIdx).trim();
            String body = text.substring(startIdx + START_MARKER.length(), endIdx).trim();
            return new Book(header, body);
        } catch (IOException | InterruptedException e) {
            System.out.println("Error al descargar libro " + bookId + ": " + e.getMessage());
            return null;
        }
    }

    // ---------- the three datalake layouts ----------

    public static void saveTimeBased(Path baseDir, int bookId, String header, String body) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path out = baseDir.resolve(now.format(DAY)).resolve(now.format(HOUR));
        Files.createDirectories(out);
        write(out.resolve(bookId + ".header.txt"), header);
        write(out.resolve(bookId + ".body.txt"), body);
    }

    public static void saveBookBased(Path baseDir, int bookId, String header, String body) throws IOException {
        Path out = baseDir.resolve("books").resolve(String.valueOf(bookId));
        Files.createDirectories(out);
        write(out.resolve("header.txt"), header);
        write(out.resolve("body.txt"), body);
    }

    public static void saveRangeBased(Path baseDir, int bookId, String header, String body, int rangeSize)
            throws IOException {
        int lower = ((bookId - 1) / rangeSize) * rangeSize + 1;
        int upper = lower + rangeSize - 1;
        Path out = baseDir.resolve(String.format("batch_%d_%d", lower, upper));
        Files.createDirectories(out);
        write(out.resolve(bookId + ".header.txt"), header);
        write(out.resolve(bookId + ".body.txt"), body);
    }

    private static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- pipeline entry point ----------

    /** IDs from the command line or, when there are none, from the shared config file. */
    static List<Integer> loadBookIds(String[] args, Path configFile) throws IOException {
        List<String> values = args.length > 0 ? List.of(args) : Files.readAllLines(configFile);
        List<Integer> ids = new ArrayList<>();
        for (String value : values) {
            String id = value.split("#", 2)[0].trim();
            if (!id.isEmpty()) {
                ids.add(Integer.parseInt(id));
            }
        }
        return ids;
    }

    public static void main(String[] args) throws IOException {
        Path baseDir = Paths.get("..").toAbsolutePath().normalize();
        Path datalakeDir = baseDir.resolve("datalake");
        Path controlFile = baseDir.resolve("control").resolve("downloaded_books_java.txt");

        Set<Integer> descargados = getDownloadedBooks(controlFile);

        for (int libro : loadBookIds(args, baseDir.resolve("config").resolve("book_ids.txt"))) {
            if (descargados.contains(libro)) {
                System.out.println("Saltando libro " + libro + ": Ya se encuentra en el Datalake.");
                continue;
            }
            Book book = fetchAndSplitBook(libro);
            if (book != null) {
                saveTimeBased(datalakeDir.resolve("time_based_java"), libro, book.header, book.body);
                saveBookBased(datalakeDir.resolve("book_based_java"), libro, book.header, book.body);
                saveRangeBased(datalakeDir.resolve("range_based_java"), libro, book.header, book.body, 1000);
                markAsDownloaded(controlFile, libro);
                System.out.println("Exito: Libro " + libro + " guardado y registrado en control.");
            }
        }
    }
}
