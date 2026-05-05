package com.dynatrace.openfeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class TestFixtures {

    public static final String MINIMAL_FLAGS = load("minimal-flags.json");
    public static final String FLAG_CONFIG = load("flag-config.json");

    private TestFixtures() {}

    static String load(String resourceName) {
        try {
            var url = TestFixtures.class.getClassLoader().getResource(resourceName);
            if (url == null) {
                throw new IllegalArgumentException("test resource not found: " + resourceName);
            }
            return Files.readString(Paths.get(url.toURI()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
