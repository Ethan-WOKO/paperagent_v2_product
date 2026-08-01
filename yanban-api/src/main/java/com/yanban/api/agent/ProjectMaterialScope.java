package com.yanban.api.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-owned extraction and comparison of explicitly named Project file materials. */
public final class ProjectMaterialScope {

    static final String MISSING_TARGET_PREFIX = "TARGET_PROJECT_FILE_MISSING:";

    private static final Pattern RELATIVE_FILE = Pattern.compile(
            "(?i)([A-Za-z0-9_.()\\-]+(?:[/\\\\][A-Za-z0-9_.()\\-]+)*\\."
                    + "(?:tex|bib|py|java|kt|kts|js|jsx|ts|tsx|json|ya?ml|toml|xml|csv|tsv|"
                    + "md|rst|ipynb|mat|m|c|cc|cpp|cxx|h|hpp|sh|ps1))(?![A-Za-z0-9])");

    private ProjectMaterialScope() {
    }

    public static Set<String> explicitRelativePaths(String... values) {
        return explicitRelativePaths(true, values);
    }

    static Set<String> explicitRelativePathsPreservingCase(String... values) {
        return explicitRelativePaths(false, values);
    }

    private static Set<String> explicitRelativePaths(boolean lowerCase, String... values) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (values == null) return Set.of();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            Matcher matcher = RELATIVE_FILE.matcher(value);
            while (matcher.find()) {
                String path = matcher.group(1).trim().replace('\\', '/');
                paths.add(lowerCase ? path.toLowerCase(Locale.ROOT) : path);
            }
        }
        return Set.copyOf(paths);
    }

    static boolean contains(Set<String> scope, String path) {
        return scope == null || scope.isEmpty() || scope.contains(normalize(path));
    }

    /**
     * Resolves a basename only when the current server-owned manifest contains exactly one match.
     * Exact Project-relative paths retain precedence; ambiguous basenames remain fail-closed.
     */
    static MaterialPathResolution resolveAgainstManifest(Set<String> requestedPaths,
                                                         List<String> manifestPaths) {
        if (requestedPaths == null || requestedPaths.isEmpty()) {
            return new MaterialPathResolution(Set.of(), Map.of());
        }
        List<String> currentPaths = manifestPaths == null ? List.of() : manifestPaths.stream()
                .filter(java.util.Objects::nonNull)
                .filter(path -> !path.isBlank())
                .toList();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        Map<String, List<String>> ambiguities = new LinkedHashMap<>();
        for (String requestedValue : requestedPaths) {
            String requested = normalize(requestedValue);
            if (requested.isBlank()) continue;
            List<String> exact = currentPaths.stream()
                    .filter(path -> normalize(path).equals(requested))
                    .toList();
            if (exact.size() == 1) {
                resolved.add(normalize(exact.get(0)));
                continue;
            }
            if (exact.size() > 1) {
                ambiguities.put(requested, sortedPaths(exact));
                continue;
            }
            if (requested.contains("/")) {
                resolved.add(requested);
                continue;
            }
            List<String> basenameMatches = currentPaths.stream()
                    .filter(path -> basename(normalize(path)).equals(requested))
                    .toList();
            if (basenameMatches.size() == 1) {
                resolved.add(normalize(basenameMatches.get(0)));
            } else if (basenameMatches.size() > 1) {
                ambiguities.put(requested, sortedPaths(basenameMatches));
            } else {
                resolved.add(requested);
            }
        }
        return new MaterialPathResolution(Set.copyOf(resolved), Map.copyOf(ambiguities));
    }

    /**
     * Resolves sandbox aliases to the exact server-owned path spelling.
     * Missing or ambiguous inputs remain explicit so callers can fail before dispatch.
     */
    public static CanonicalPathResolution resolveCanonicalPaths(Set<String> requestedPaths,
                                                                List<String> knownPaths) {
        if (requestedPaths == null || requestedPaths.isEmpty()) {
            return new CanonicalPathResolution(Set.of(), Set.of(), Map.of(), Map.of());
        }
        LinkedHashSet<String> currentPaths = new LinkedHashSet<>();
        if (knownPaths != null) {
            knownPaths.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(path -> !path.isBlank())
                    .forEach(currentPaths::add);
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, List<String>> ambiguities = new LinkedHashMap<>();
        requestedPaths.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .sorted(Comparator.comparing(ProjectMaterialScope::normalize))
                .forEach(requested -> {
                    String normalized = normalize(requested);
                    List<String> matches = currentPaths.stream()
                            .filter(path -> normalize(path).equals(normalized))
                            .toList();
                    if (matches.isEmpty() && !normalized.contains("/")) {
                        matches = currentPaths.stream()
                                .filter(path -> basename(normalize(path)).equals(normalized))
                                .toList();
                    }
                    if (matches.size() == 1) {
                        String canonical = matches.get(0);
                        resolved.add(canonical);
                        aliases.put(normalized, canonical);
                    } else if (matches.size() > 1) {
                        ambiguities.put(requested, sortedPaths(matches));
                    } else {
                        missing.add(requested);
                    }
                });
        return new CanonicalPathResolution(
                Set.copyOf(resolved), Set.copyOf(missing), Map.copyOf(aliases), Map.copyOf(ambiguities));
    }

    static boolean hasDeterministicMissingTarget(AgentRuntimeResult result) {
        if (result == null || result.fallbacks() == null) return false;
        return result.fallbacks().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .anyMatch(value -> value.startsWith(MISSING_TARGET_PREFIX));
    }

    public static String normalize(String path) {
        if (path == null) return "";
        return path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String basename(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static List<String> sortedPaths(List<String> paths) {
        ArrayList<String> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.comparing(ProjectMaterialScope::normalize));
        return List.copyOf(sorted);
    }

    record MaterialPathResolution(Set<String> paths, Map<String, List<String>> ambiguities) {
        MaterialPathResolution {
            paths = paths == null ? Set.of() : Set.copyOf(paths);
            ambiguities = ambiguities == null ? Map.of() : Map.copyOf(ambiguities);
        }

        boolean ambiguous() {
            return !ambiguities.isEmpty();
        }
    }

    public record CanonicalPathResolution(Set<String> paths,
                                          Set<String> missingPaths,
                                          Map<String, String> aliases,
                                          Map<String, List<String>> ambiguities) {
        public CanonicalPathResolution {
            paths = paths == null ? Set.of() : Set.copyOf(paths);
            missingPaths = missingPaths == null ? Set.of() : Set.copyOf(missingPaths);
            aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
            ambiguities = ambiguities == null ? Map.of() : Map.copyOf(ambiguities);
        }

        public boolean valid() {
            return missingPaths.isEmpty() && ambiguities.isEmpty();
        }

        public String canonicalAlias(String path) {
            if (path == null) return null;
            return aliases.getOrDefault(normalize(path), path);
        }
    }
}
