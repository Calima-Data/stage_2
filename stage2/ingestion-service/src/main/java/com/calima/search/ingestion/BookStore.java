package com.calima.search.ingestion;

import com.calima.search.common.Faults;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Writes books into the time-based datalake chosen in Stage 1:
 * {@code <root>/YYYYMMDD/HH/<id>.header.txt} and {@code <id>.body.txt}.
 *
 * <p>Locations are reported as the logical path {@code datalake/YYYYMMDD/HH/<id>}, so no caller
 * needs to know where this host keeps the datalake.
 */
public final class BookStore {

    public record Stored(long headerBytes, long bodyBytes, String bodySha256) {
    }

    private static final DateTimeFormatter HOUR_FOLDER =
            DateTimeFormatter.ofPattern("yyyyMMdd/HH").withZone(ZoneOffset.UTC);

    private final Path root;

    public BookStore(Path root) {
        this.root = root;
    }

    /** The logical path of a book first ingested at this instant. */
    public static String logicalPath(int bookId, Instant now) {
        return "datalake/" + HOUR_FOLDER.format(now) + "/" + bookId;
    }

    /**
     * Writes both files through a temporary name, flushed to disk and then renamed. Readiness is
     * recorded later in the ledger, so a crash here never exposes a partial book. Repeating the
     * call overwrites the same two files.
     */
    public Stored write(String logicalPath, String requestId, GutenbergParser.Book book) throws IOException {
        Path base = resolve(logicalPath);
        Files.createDirectories(base.getParent());
        removeAbandonedTemporaries(base);

        byte[] header = book.header().getBytes(StandardCharsets.UTF_8);
        byte[] body = book.body().getBytes(StandardCharsets.UTF_8);
        writeAtomically(headerFile(base), header, requestId);
        Faults.point("ingest_after_header");
        writeAtomically(bodyFile(base), body, requestId);
        return new Stored(header.length, body.length, sha256(body));
    }

    public Path resolve(String logicalPath) {
        return root.resolve(logicalPath.substring("datalake/".length()));
    }

    public static Path headerFile(Path base) {
        return base.resolveSibling(base.getFileName() + ".header.txt");
    }

    public static Path bodyFile(Path base) {
        return base.resolveSibling(base.getFileName() + ".body.txt");
    }

    private static void writeAtomically(Path target, byte[] content, String requestId) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + requestId);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void removeAbandonedTemporaries(Path base) throws IOException {
        try (DirectoryStream<Path> leftovers =
                     Files.newDirectoryStream(base.getParent(), base.getFileName() + ".*.tmp-*")) {
            for (Path leftover : leftovers) {
                Files.deleteIfExists(leftover);
            }
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
