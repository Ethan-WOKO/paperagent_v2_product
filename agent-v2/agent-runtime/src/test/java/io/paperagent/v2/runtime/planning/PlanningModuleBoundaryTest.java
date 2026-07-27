package io.paperagent.v2.runtime.planning;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningModuleBoundaryTest {
    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            "io.paperagent.v2.persistence",
            "io.paperagent.v2.workspace",
            "io.paperagent.v2.sandbox",
            "io.paperagent.v2.providers",
            "io.paperagent.v2.app",
            "org.springframework.",
            "com.fasterxml.",
            "okhttp",
            "retrofit",
            "openai",
            "anthropic",
            "e2b",
            "paperagent.v1",
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "Candidate",
            "java.net.",
            "java.net.http",
            "java.io.",
            "java.nio.file.",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "System.getenv",
            "System.getProperty",
            "SecretRef",
            "\".env",
            "System.currentTimeMillis",
            "Instant.now",
            "Clock.",
            "UUID.randomUUID",
            "Thread.sleep");

    @Test
    void runtimeProductionDependenciesRemainFrozen()
            throws Exception {
        Path module = moduleDirectory();
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(module.resolve("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        List<String> productionDependencies = new ArrayList<>();
        List<String> testDependencies = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String coordinate =
                    text(dependency, "groupId") + ":" + text(dependency, "artifactId");
            if ("test".equals(text(dependency, "scope"))) {
                testDependencies.add(coordinate);
            } else {
                productionDependencies.add(coordinate);
            }
        }
        assertEquals(
                List.of(
                        "io.paperagent.v2:agent-contracts",
                        "io.paperagent.v2:agent-persistence",
                        "io.paperagent.v2:agent-workspace"),
                productionDependencies);
        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), testDependencies);
    }

    @Test
    void planningProductionImportsOnlyContractsRuntimeAndJdk() throws Exception {
        Path sourceRoot = planningSourceRoot();
        assertTrue(Files.isDirectory(sourceRoot));
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                for (String line : Files.readAllLines(sourcePath)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(
                                trimmed.startsWith("import java.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.contracts.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.runtime."),
                                () -> sourcePath + " crosses production boundary: " + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void planningProductionHasNoForbiddenSideEffectsOrV1Markers() throws Exception {
        try (var paths = Files.walk(planningSourceRoot())) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(sourcePath).toLowerCase();
                for (String marker : FORBIDDEN_SOURCE_MARKERS) {
                    assertFalse(
                            source.contains(marker.toLowerCase()),
                            () -> sourcePath + " contains forbidden marker " + marker);
                }
            }
        }
    }

    private static Path planningSourceRoot() {
        return moduleDirectory()
                .resolve("src/main/java/io/paperagent/v2/runtime/planning");
    }

    private static Path moduleDirectory() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(current.resolve("pom.xml"))
                && current.getFileName().toString().equals("agent-runtime")) {
            return current;
        }
        return current.resolve("agent-runtime");
    }

    private static String text(Element parent, String name) {
        var elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().trim();
    }
}
