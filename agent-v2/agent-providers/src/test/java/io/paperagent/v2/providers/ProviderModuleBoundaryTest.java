package io.paperagent.v2.providers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModuleBoundaryTest {
    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            "java.net.",
            "java.net.http",
            "System.getenv",
            "System.getProperty",
            "com.fasterxml.",
            "org.springframework.",
            "reactor.",
            "okhttp",
            "retrofit",
            "openai",
            "anthropic",
            "e2b",
            "paperagent.v1");

    @Test
    void productionSourceHasNoSdkNetworkEnvironmentOrV1Dependency() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot));
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path).toLowerCase();
                for (String marker : FORBIDDEN_SOURCE_MARKERS) {
                    assertFalse(
                            source.contains(marker.toLowerCase()),
                            () -> path + " contains forbidden marker " + marker);
                }
            }
        }
    }
}
