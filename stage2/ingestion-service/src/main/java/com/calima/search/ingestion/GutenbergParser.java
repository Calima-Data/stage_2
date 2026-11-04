package com.calima.search.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a raw Project Gutenberg text into the header and the body stored in the datalake. */
public final class GutenbergParser {

    /** Thrown when a text lacks the Gutenberg markers; retrying the download would not help. */
    public static final class InvalidBookException extends Exception {
        public InvalidBookException(String message) {
            super(message);
        }
    }

    public record Book(String header, String body) {
    }

    // Older releases write "THIS" instead of "THE".
    private static final Pattern START = Pattern.compile(
            "^\\s*\\*\\*\\*\\s*START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK[^\\n]*$", Pattern.MULTILINE);
    private static final Pattern END = Pattern.compile(
            "^\\s*\\*\\*\\*\\s*END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK[^\\n]*$", Pattern.MULTILINE);

    private GutenbergParser() {
    }

    public static Book split(String raw) throws InvalidBookException {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        Matcher start = START.matcher(text);
        if (!start.find()) {
            throw new InvalidBookException("Start marker not found");
        }
        Matcher end = END.matcher(text);
        if (!end.find(start.end())) {
            throw new InvalidBookException("End marker not found after the start marker");
        }
        String header = text.substring(0, start.start()).strip();
        String body = text.substring(start.end(), end.start()).strip();
        if (header.isEmpty() || body.isEmpty()) {
            throw new InvalidBookException("Empty header or body");
        }
        return new Book(header, body);
    }
}
