package com.calima.search.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Configuration lookup: environment variable, then system property, then application.properties, then default. */
public final class Config {

    private final Properties defaults = new Properties();

    private Config() {
    }

    /** Loads {@code application.properties} from the classpath of the running service. */
    public static Config load() {
        Config config = new Config();
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                config.defaults.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return config;
    }

    public String get(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            value = defaults.getProperty(name);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public int getInt(String name, int fallback) {
        String value = get(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration " + name + " must be an integer, got: " + value);
        }
    }

    /** A comma-separated value, used for the endpoint lists that identify service replicas. */
    public List<String> getList(String name, String fallback) {
        List<String> values = new ArrayList<>();
        for (String part : get(name, fallback).split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }
}
