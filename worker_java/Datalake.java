import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Java port of worker_python/datalake.py.
 * Downloads books from Project Gutenberg and saves them under the same
 * three datalake layouts (time-based, book-based, range-based) so results
 * are comparable against the Python implementation.
 */
public class Datalake {

    static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    static final Path BASE_DIR = Paths.get("..").toAbsolutePath().normalize();
    static final Path DATALAKE_DIR = BASE_DIR.resolve("datalake");
    static final Path CONTROL_DIR = BASE_DIR.resolve("control");
    static final Path DOWNLOADED_FILE = CONTROL_DIR.resolve("downloaded_books_java.txt");

    static Set<Integer> getDownloadedBooks() throws IOException {
        Set<Integer> ids = new HashSet<>();
        if (!Files.exists(DOWNLOADED_FILE)) return ids;
        for (String line : Files.readAllLines(DOWNLOADED_FILE, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (!line.isEmpty()) ids.add(Integer.parseInt(line));
        }
        return ids;
    }

    static void markAsDownloaded(int bookId) throws IOException {
        Files.createDirectories(CONTROL_DIR);
        Files.writeString(DOWNLOADED_FILE, bookId + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    record HeaderBody(String header, String body) {}

    static HeaderBody fetchAndSplitBook(int bookId) {
        String url = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt".formatted(bookId, bookId);
        System.out.println("Descargando libro " + bookId + "...");
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                System.out.println("Error al descargar libro " + bookId + ": HTTP " + response.statusCode());
                return null;
            }
            String text = response.body();
            if (!text.contains(START_MARKER) || !text.contains(END_MARKER)) {
                System.out.println("Error: Marcadores no encontrados en el libro " + bookId);
                return null;
            }
            int startIdx = text.indexOf(START_MARKER) + START_MARKER.length();
            int endIdx = text.indexOf(END_MARKER);
            String header = text.substring(0, text.indexOf(START_MARKER)).strip();
            String body = text.substring(startIdx, endIdx).strip();
            return new HeaderBody(header, body);
        } catch (Exception e) {
            System.out.println("Error al descargar libro " + bookId + ": " + e.getMessage());
            return null;
        }
    }

    static void saveTimeBased(int bookId, String header, String body) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path outputPath = DATALAKE_DIR.resolve("time_based_java")
                .resolve(now.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .resolve(now.format(DateTimeFormatter.ofPattern("HH")));
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(bookId + ".header.txt"), header, StandardCharsets.UTF_8);
        Files.writeString(outputPath.resolve(bookId + ".body.txt"), body, StandardCharsets.UTF_8);
    }

    static void saveBookBased(int bookId, String header, String body) throws IOException {
        Path outputPath = DATALAKE_DIR.resolve("book_based_java").resolve("books").resolve(String.valueOf(bookId));
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve("header.txt"), header, StandardCharsets.UTF_8);
        Files.writeString(outputPath.resolve("body.txt"), body, StandardCharsets.UTF_8);
    }

    static void saveRangeBased(int bookId, String header, String body, int rangeSize) throws IOException {
        int lower = ((bookId - 1) / rangeSize) * rangeSize + 1;
        int upper = lower + rangeSize - 1;
        Path outputPath = DATALAKE_DIR.resolve("range_based_java").resolve("batch_%d_%d".formatted(lower, upper));
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(bookId + ".header.txt"), header, StandardCharsets.UTF_8);
        Files.writeString(outputPath.resolve(bookId + ".body.txt"), body, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        int[] librosPrueba = {1342, 84, 11, 16};
        Set<Integer> descargados = getDownloadedBooks();

        for (int libro : librosPrueba) {
            if (descargados.contains(libro)) {
                System.out.println("Saltando libro " + libro + ": Ya se encuentra en el Datalake.");
                continue;
            }
            HeaderBody hb = fetchAndSplitBook(libro);
            if (hb != null) {
                saveTimeBased(libro, hb.header(), hb.body());
                saveBookBased(libro, hb.header(), hb.body());
                saveRangeBased(libro, hb.header(), hb.body(), 1000);
                markAsDownloaded(libro);
                System.out.println("Exito: Libro " + libro + " guardado y registrado en control.");
            }
        }
    }
}
