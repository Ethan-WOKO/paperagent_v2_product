package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartMaterializerBoundaryTest {
    private static final Set<String> PRODUCTION_FILES = Set.of(
            "ExecutionStartEventDraft.java",
            "ExecutionStartMaterializationRequest.java",
            "MaterializedExecutionStart.java",
            "ExecutionStartMaterializer.java",
            "DeterministicExecutionStartMaterializer.java",
            "ExecutionStartMaterializationValidationCode.java",
            "ExecutionStartMaterializationValidationException.java",
            "ExecutionStartMaterializationValues.java");
    private static final Set<String> ALLOWED_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.PersistedPlanBootstrap;");
    private static final Set<String> ALLOWED_JDK_IMPORTS = Set.of(
            "import java.time.Instant;",
            "import java.util.LinkedHashMap;",
            "import java.util.List;",
            "import java.util.Map;",
            "import java.util.Optional;");
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "ExecutionStartRepository",
            "FreshExecutionGate",
            "PersistenceResult",
            "PersistenceOutcome",
            "PersistenceFailure",
            "PlanBootstrapRepository",
            "InMemoryPersistence",
            "io.paperagent.v2.workspace",
            "io.paperagent.v2.sandbox",
            "io.paperagent.v2.providers",
            "io.paperagent.v2.app",
            "paperagent.v1",
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "Candidate",
            "java.net.",
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
            "java.time.Clock",
            "Clock.",
            "Clock::",
            "UUID.randomUUID",
            "java.util.UUID",
            "Math.random",
            "java.util.Random",
            "java.security.SecureRandom",
            "java.util.concurrent.ThreadLocalRandom",
            "Thread.sleep");

    @Test
    void publicSurfaceRemainsExactlyFrozen() throws Exception {
        assertRecord(
                ExecutionStartEventDraft.class,
                List.of("id", "occurredAt", "type", "causationId",
                        "correlationId", "payload"),
                List.of(EventId.class, Instant.class, EventType.class,
                        Optional.class, String.class, EventPayload.class));
        assertRecord(
                ExecutionStartMaterializationRequest.class,
                List.of("bootstrap", "eventDraft", "checkpointCreatedAt"),
                List.of(PersistedPlanBootstrap.class,
                        ExecutionStartEventDraft.class, Instant.class));
        assertRecord(
                MaterializedExecutionStart.class,
                List.of("startEvent", "startedCheckpoint"),
                List.of(EventEnvelope.class, Checkpoint.class));
        assertEquals(
                List.of(
                        ExecutionStartMaterializationValidationCode
                                .REQUIRED_VALUE_MISSING,
                        ExecutionStartMaterializationValidationCode
                                .INVALID_IDENTIFIER,
                        ExecutionStartMaterializationValidationCode
                                .NON_CANONICAL_BOOTSTRAP),
                List.of(ExecutionStartMaterializationValidationCode.values()));
        assertTrue(Modifier.isPublic(
                ExecutionStartMaterializationValidationException.class
                        .getModifiers()));
        assertTrue(Modifier.isFinal(
                ExecutionStartMaterializationValidationException.class
                        .getModifiers()));
        assertEquals(
                IllegalArgumentException.class,
                ExecutionStartMaterializationValidationException.class
                        .getSuperclass());
        assertEquals(
                Set.of("code", "path"),
                Arrays.stream(
                                ExecutionStartMaterializationValidationException
                                        .class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .map(method -> method.getName())
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(Modifier.isPublic(
                ExecutionStartMaterializationValidationException.class
                        .getDeclaredMethod("code").getModifiers()));
        assertEquals(
                ExecutionStartMaterializationValidationCode.class,
                ExecutionStartMaterializationValidationException.class
                        .getDeclaredMethod("code").getReturnType());
        assertTrue(Modifier.isPublic(
                ExecutionStartMaterializationValidationException.class
                        .getDeclaredMethod("path").getModifiers()));
        assertEquals(
                String.class,
                ExecutionStartMaterializationValidationException.class
                        .getDeclaredMethod("path").getReturnType());
        assertEquals(
                0,
                ExecutionStartMaterializationValidationException.class
                        .getConstructors().length);

        assertFalse(Modifier.isPublic(
                ExecutionStartMaterializationValues.class.getModifiers()));
        assertFalse(Modifier.isProtected(
                ExecutionStartMaterializationValues.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                ExecutionStartMaterializationValues.class.getModifiers()));

        assertTrue(ExecutionStartMaterializer.class.isInterface());
        assertTrue(ExecutionStartMaterializer.class.isAnnotationPresent(
                FunctionalInterface.class));
        var methods = Arrays.stream(
                        ExecutionStartMaterializer.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();
        assertEquals(1, methods.size());
        assertEquals("materialize", methods.get(0).getName());
        assertEquals(
                MaterializedExecutionStart.class,
                methods.get(0).getReturnType());
        assertEquals(
                List.of(ExecutionStartMaterializationRequest.class),
                List.of(methods.get(0).getParameterTypes()));

        assertTrue(Modifier.isPublic(
                DeterministicExecutionStartMaterializer.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                DeterministicExecutionStartMaterializer.class.getModifiers()));
        assertEquals(
                List.of(ExecutionStartMaterializer.class),
                List.of(
                        DeterministicExecutionStartMaterializer.class
                                .getInterfaces()));
        var constructors =
                DeterministicExecutionStartMaterializer.class.getConstructors();
        assertEquals(1, constructors.length);
        assertEquals(0, constructors[0].getParameterCount());
    }

    @Test
    void newProductionImportsOnlyContractsSnapshotCarrierAndJdk()
            throws Exception {
        for (Path sourcePath : productionSources()) {
            for (String line : Files.readAllLines(sourcePath)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                assertTrue(
                        ALLOWED_JDK_IMPORTS.contains(trimmed)
                                || trimmed.startsWith(
                                        "import io.paperagent.v2.contracts.")
                                || ALLOWED_PERSISTENCE_IMPORTS
                                        .contains(trimmed),
                        () -> sourcePath
                                + " crosses execution-start boundary: "
                                + trimmed);
            }
        }
    }

    @Test
    void newProductionHasNoAuthoritySideEffectsOrV1Markers()
            throws Exception {
        for (Path sourcePath : productionSources()) {
            String source = Files.readString(sourcePath);
            for (String marker : FORBIDDEN_MARKERS) {
                assertFalse(
                        source.toLowerCase().contains(marker.toLowerCase()),
                        () -> sourcePath + " contains forbidden marker "
                                + marker);
            }
        }
    }

    @Test
    void deterministicMaterializerIsTheOnlyImplementation()
            throws Exception {
        int implementationCount = 0;
        for (Path sourcePath : productionSources()) {
            String source = Files.readString(sourcePath);
            if (source.contains("implements ExecutionStartMaterializer")) {
                implementationCount++;
                assertEquals(
                        "DeterministicExecutionStartMaterializer.java",
                        sourcePath.getFileName().toString());
            }
        }
        assertEquals(1, implementationCount);
    }

    private static void assertRecord(
            Class<?> recordType,
            List<String> names,
            List<Class<?>> types) {
        assertTrue(recordType.isRecord());
        RecordComponent[] components = recordType.getRecordComponents();
        assertEquals(names, Arrays.stream(components)
                .map(RecordComponent::getName)
                .toList());
        assertEquals(types, Arrays.stream(components)
                .map(RecordComponent::getType)
                .toList());
    }

    private static List<Path> productionSources() throws Exception {
        Path executionRoot = moduleDirectory().resolve(Path.of(
                "src",
                "main",
                "java",
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution"));
        try (var paths = Files.list(executionRoot)) {
            List<Path> sources = paths
                    .filter(path -> PRODUCTION_FILES.contains(
                            path.getFileName().toString()))
                    .sorted()
                    .toList();
            assertEquals(
                    PRODUCTION_FILES,
                    sources.stream()
                            .map(path -> path.getFileName().toString())
                            .collect(java.util.stream.Collectors.toSet()));
            return sources;
        }
    }

    private static Path moduleDirectory() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(current.resolve("pom.xml"))
                && current.getFileName().toString().equals("agent-runtime")) {
            return current;
        }
        return current.resolve("agent-runtime");
    }
}
