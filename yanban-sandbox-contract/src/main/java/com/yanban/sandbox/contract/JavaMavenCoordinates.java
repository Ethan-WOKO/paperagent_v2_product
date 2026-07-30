package com.yanban.sandbox.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Strict Maven Central coordinates accepted from a bounded Java repair proposal. */
public final class JavaMavenCoordinates {
    public static final int MAX_COORDINATES = 8;
    private static final Pattern GROUP =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9_-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9_-]{0,62}[A-Za-z0-9])?)*");
    private static final Pattern ARTIFACT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");
    private static final Pattern VERSION = Pattern.compile("[0-9][A-Za-z0-9_.+\\-]{0,63}");

    private JavaMavenCoordinates() {}

    public static List<String> normalize(List<String> values) {
        if (values == null || values.size() > MAX_COORDINATES) throw invalid();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.length() > 226 || value.indexOf('\0') >= 0
                    || value.contains("\r") || value.contains("\n")) throw invalid();
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !GROUP.matcher(parts[0]).matches()
                    || !ARTIFACT.matcher(parts[1]).matches()
                    || !VERSION.matcher(parts[2]).matches()
                    || !unique.add(value)) throw invalid();
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Java Maven coordinates are invalid");
    }
}
