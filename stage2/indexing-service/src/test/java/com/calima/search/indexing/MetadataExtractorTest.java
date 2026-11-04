package com.calima.search.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataExtractorTest {

    private final MetadataExtractor extractor = new MetadataExtractor(Map.of(1342, 1813));

    @Test
    void readsTheFieldsAndMapsTheLanguageToItsCode() {
        MetadataExtractor.Metadata m = extractor.extract(9, "Title: Don Quijote\n\nAuthor: Miguel de Cervantes\n\nLanguage: Spanish\n");
        assertEquals("Don Quijote", m.title());
        assertEquals("Miguel de Cervantes", m.author());
        assertEquals("es", m.language());
    }

    @Test
    void joinsATitleContinuedOnIndentedLines() {
        MetadataExtractor.Metadata m = extractor.extract(9, "Title: A very long title\n       that continues here\n\nAuthor: A\r\n");
        assertEquals("A very long title that continues here", m.title());
        assertEquals("A", m.author());
    }

    @Test
    void missingFieldsAreNullAndAnUnknownLanguageIsUndetermined() {
        MetadataExtractor.Metadata m = extractor.extract(9, "Language: Klingon\n");
        assertNull(m.title());
        assertNull(m.author());
        assertEquals(MetadataExtractor.UNDETERMINED_LANGUAGE, m.language());
        assertEquals(MetadataExtractor.UNDETERMINED_LANGUAGE, extractor.extract(9, "Title: x").language());
    }

    @Test
    void keepsTheFirstOfSeveralLanguages() {
        assertEquals("fr", extractor.extract(9, "Language: French, English").language());
        assertEquals("de", extractor.extract(9, "Language: German and Latin").language());
    }

    @Test
    void theReleaseDateIsNeverThePublicationYear() {
        String header = "Title: T\nRelease date: June 1, 1998 [eBook #9]\n";
        assertNull(extractor.extract(9, header).year());
    }

    @Test
    void publicationYearComesFromTheHeaderThenFromTheCuratedList() {
        assertEquals(1925, extractor.extract(1342, "Original publication: New York: Scribner, 1925\n").year());
        assertEquals(1813, extractor.extract(1342, "Release date: June 1, 1998\n").year());
    }

    @Test
    void theBundledYearListLoads() {
        assertEquals(1813, MetadataExtractor.loadYearOverrides(null).get(1342));
    }
}
