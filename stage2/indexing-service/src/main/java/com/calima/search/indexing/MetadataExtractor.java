package com.calima.search.indexing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the metadata fields of a book from its Project Gutenberg header. */
public final class MetadataExtractor {

    public record Metadata(String title, String author, String language, Integer year) {
    }

    /** Codes for the languages Project Gutenberg uses most; other names are stored as {@code und}. */
    private static final Map<String, String> LANGUAGE_CODES = Map.ofEntries(
            Map.entry("english", "en"), Map.entry("french", "fr"), Map.entry("german", "de"),
            Map.entry("spanish", "es"), Map.entry("italian", "it"), Map.entry("portuguese", "pt"),
            Map.entry("dutch", "nl"), Map.entry("finnish", "fi"), Map.entry("swedish", "sv"),
            Map.entry("danish", "da"), Map.entry("norwegian", "no"), Map.entry("latin", "la"),
            Map.entry("greek", "el"), Map.entry("esperanto", "eo"), Map.entry("russian", "ru"),
            Map.entry("chinese", "zh"), Map.entry("japanese", "ja"), Map.entry("polish", "pl"),
            Map.entry("hungarian", "hu"), Map.entry("catalan", "ca"));

    static final String UNDETERMINED_LANGUAGE = "und";

    private static final Pattern TITLE = field("Title");
    private static final Pattern AUTHOR = field("Author");
    private static final Pattern LANGUAGE = field("Language");
    private static final Pattern ORIGINAL_PUBLICATION = Pattern.compile(
            "^Original publication:.*?\\b(\\d{4})\\b", Pattern.MULTILINE);

    private final Map<Integer, Integer> yearOverrides;

    public MetadataExtractor(Map<Integer, Integer> yearOverrides) {
        this.yearOverrides = Map.copyOf(yearOverrides);
    }

    private static Pattern field(String name) {
        // A value may continue on indented lines, which long titles do.
        return Pattern.compile("^" + name + ":[ \\t]*(.*(?:\\r?\\n[ \\t]+\\S.*)*)", Pattern.MULTILINE);
    }

    public Metadata extract(int bookId, String header) {
        return new Metadata(text(TITLE, header), text(AUTHOR, header), languageCode(text(LANGUAGE, header)),
                publicationYear(bookId, header));
    }

    /**
     * The year of the work, from the header's "Original publication" line or else the curated list.
     * The header's "Release date" is deliberately not used: it would date Pride and Prejudice 1998.
     */
    private Integer publicationYear(int bookId, String header) {
        Matcher matcher = ORIGINAL_PUBLICATION.matcher(header);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : yearOverrides.get(bookId);
    }

    private static String text(Pattern pattern, String header) {
        Matcher matcher = pattern.matcher(header);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).replaceAll("\\s*\\r?\\n\\s*", " ").strip();
        return value.isEmpty() ? null : value;
    }

    private static String languageCode(String name) {
        if (name == null) {
            return UNDETERMINED_LANGUAGE;
        }
        // Books in several languages list them separated by commas or "and"; the first one is kept.
        String first = name.split(",| and ")[0].strip().toLowerCase(Locale.ROOT);
        return LANGUAGE_CODES.getOrDefault(first, UNDETERMINED_LANGUAGE);
    }

    /** Reads {@code book_id,year} lines from a file, or from the bundled list when the path is empty. */
    public static Map<Integer, Integer> loadYearOverrides(String path) {
        try (InputStream in = path == null || path.isBlank()
                ? MetadataExtractor.class.getResourceAsStream("/publication_years.csv")
                : Files.newInputStream(Path.of(path));
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Map<Integer, Integer> years = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    String[] parts = line.split(",");
                    years.put(Integer.valueOf(parts[0].strip()), Integer.valueOf(parts[1].strip()));
                }
            }
            return years;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
