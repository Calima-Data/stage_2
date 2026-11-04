package com.calima.search.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GutenbergParserTest {

    private static String text(String startWord, String endWord) {
        return "Title: Alpha\n*** START OF " + startWord + " PROJECT GUTENBERG EBOOK ALPHA ***\n"
                + "the body\n*** END OF " + endWord + " PROJECT GUTENBERG EBOOK ALPHA ***\nlicense text";
    }

    @Test
    void splitsHeaderAndBodyWithoutMarkersOrFooter() throws Exception {
        GutenbergParser.Book book = GutenbergParser.split(text("THE", "THE"));
        assertEquals("Title: Alpha", book.header());
        assertEquals("the body", book.body());
    }

    @Test
    void acceptsTheOlderThisMarkers() throws Exception {
        assertEquals("the body", GutenbergParser.split(text("THIS", "THIS")).body());
    }

    @Test
    void acceptsWindowsLineEndingsAndAByteOrderMark() throws Exception {
        String raw = "﻿" + text("THE", "THE").replace("\n", "\r\n");
        GutenbergParser.Book book = GutenbergParser.split(raw);
        assertEquals("Title: Alpha", book.header());
        assertEquals("the body", book.body());
    }

    @Test
    void rejectsTextsWithoutMarkersOrWithAnEmptyBody() {
        assertThrows(GutenbergParser.InvalidBookException.class, () -> GutenbergParser.split("no markers at all"));
        assertThrows(GutenbergParser.InvalidBookException.class, () -> GutenbergParser.split(
                "Title: A\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\nbody without end"));
        assertThrows(GutenbergParser.InvalidBookException.class, () -> GutenbergParser.split(
                "Title: A\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\n\n*** END OF THE PROJECT GUTENBERG EBOOK A ***"));
    }
}
