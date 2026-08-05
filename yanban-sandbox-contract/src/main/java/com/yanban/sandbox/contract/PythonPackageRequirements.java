package com.yanban.sandbox.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict, exactly pinned PyPI requirements accepted by the Python runner. */
public final class PythonPackageRequirements {
    public static final int MAX_REQUIREMENTS = 8;
    private static final Pattern NAME = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,78}[A-Za-z0-9])?");
    private static final Pattern VERSION = Pattern.compile(
            "[0-9][A-Za-z0-9.!+_-]{0,63}");

    private PythonPackageRequirements() {}

    public static List<String> normalize(List<String> values) {
        if (values == null || values.size() > MAX_REQUIREMENTS) {
            throw invalid();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.length() > 146
                    || value.indexOf('\0') >= 0
                    || value.contains("\r") || value.contains("\n")) {
                throw invalid();
            }
            String[] parts = value.split("==", -1);
            if (parts.length != 2 || !NAME.matcher(parts[0]).matches()
                    || !VERSION.matcher(parts[1]).matches()) {
                throw invalid();
            }
            String canonicalName = parts[0].toLowerCase(Locale.ROOT)
                    .replaceAll("[-_.]+", "-");
            if (!names.add(canonicalName)) {
                throw invalid();
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Python package requirements are invalid");
    }
}
