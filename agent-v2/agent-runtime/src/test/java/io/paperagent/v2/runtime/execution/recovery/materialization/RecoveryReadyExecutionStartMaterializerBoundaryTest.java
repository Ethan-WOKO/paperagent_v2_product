package io.paperagent.v2.runtime.execution.recovery.materialization;

import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryReadyExecutionStartMaterializerBoundaryTest {
    private static final Set<String> PRODUCTION_FILES = Set.of(
            "RecoveryReadyExecutionStartMaterializationRequest.java",
            "RecoveryReadyExecutionStartMaterializer.java",
            "DeterministicRecoveryReadyExecutionStartMaterializer.java",
            "RecoveryReadyExecutionStartMaterializationValidationCode.java",
            "RecoveryReadyExecutionStartMaterializationValidationException.java",
            "RecoveryReadyExecutionStartMaterializationValues.java");
    private static final Set<String> ALLOWED_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence"
                    + ".PersistedExecutionStartReady;");
    private static final Set<String> ALLOWED_RUNTIME_IMPORTS = Set.of(
            "import io.paperagent.v2.runtime.execution"
                    + ".ExecutionStartEventDraft;",
            "import io.paperagent.v2.runtime.execution"
                    + ".MaterializedExecutionStart;");
    private static final Set<String> ALLOWED_JDK_IMPORTS = Set.of(
            "import java.time.Instant;",
            "import java.util.LinkedHashMap;",
            "import java.util.List;",
            "import java.util.Map;");
    private static final List<String> FORBIDDEN_TYPE_TOKENS = List.of(
            "DeterministicExecutionStartMaterializer",
            "ExecutionStartMaterializer");
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "new PersistedPlanBootstrap",
            "ExecutionStartRecoveryRepository",
            "ExecutionStartRepository",
            "ExecutionStartRequest",
            "FreshExecutionStartAttempt",
            "LeaseRecord",
            "LeaseRepository",
            "PersistenceResult",
            "PersistenceOutcome",
            "PersistenceFailure",
            "PersistedExecutionStartCommitted",
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
        assertTrue(Modifier.isPublic(
                RecoveryReadyExecutionStartMaterializationRequest.class
                        .getModifiers()));
        assertRecord(
                RecoveryReadyExecutionStartMaterializationRequest.class,
                List.of("ready", "eventDraft", "checkpointCreatedAt"),
                List.of(
                        PersistedExecutionStartReady.class,
                        ExecutionStartEventDraft.class,
                        Instant.class));
        assertEquals(
                List.of(
                        RecoveryReadyExecutionStartMaterializationValidationCode
                                .REQUIRED_VALUE_MISSING,
                        RecoveryReadyExecutionStartMaterializationValidationCode
                                .NON_CANONICAL_READY_SNAPSHOT),
                List.of(
                        RecoveryReadyExecutionStartMaterializationValidationCode
                                .values()));
        assertTrue(Modifier.isPublic(
                RecoveryReadyExecutionStartMaterializationValidationCode.class
                        .getModifiers()));

        Class<?> exception =
                RecoveryReadyExecutionStartMaterializationValidationException
                        .class;
        assertTrue(Modifier.isPublic(exception.getModifiers()));
        assertTrue(Modifier.isFinal(exception.getModifiers()));
        assertEquals(IllegalArgumentException.class, exception.getSuperclass());
        assertEquals(0, exception.getFields().length);
        assertEquals(
                Set.of("code", "path"),
                Arrays.stream(exception.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .map(method -> method.getName())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, exception.getConstructors().length);
        assertTrue(Modifier.isPublic(
                exception.getDeclaredMethod("code").getModifiers()));
        assertEquals(
                RecoveryReadyExecutionStartMaterializationValidationCode.class,
                exception.getDeclaredMethod("code").getReturnType());
        assertTrue(Modifier.isPublic(
                exception.getDeclaredMethod("path").getModifiers()));
        assertEquals(
                String.class,
                exception.getDeclaredMethod("path").getReturnType());

        Class<?> values =
                RecoveryReadyExecutionStartMaterializationValues.class;
        assertFalse(Modifier.isPublic(values.getModifiers()));
        assertFalse(Modifier.isProtected(values.getModifiers()));
        assertTrue(Modifier.isFinal(values.getModifiers()));

        Class<?> materializer = RecoveryReadyExecutionStartMaterializer.class;
        assertTrue(Modifier.isPublic(materializer.getModifiers()));
        assertTrue(materializer.isInterface());
        assertTrue(materializer.isAnnotationPresent(FunctionalInterface.class));
        var methods = Arrays.stream(materializer.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();
        assertEquals(1, methods.size());
        assertEquals("materialize", methods.get(0).getName());
        assertEquals(
                MaterializedExecutionStart.class,
                methods.get(0).getReturnType());
        assertEquals(
                List.of(
                        RecoveryReadyExecutionStartMaterializationRequest.class),
                List.of(methods.get(0).getParameterTypes()));

        Class<?> implementation =
                DeterministicRecoveryReadyExecutionStartMaterializer.class;
        assertTrue(Modifier.isPublic(implementation.getModifiers()));
        assertTrue(Modifier.isFinal(implementation.getModifiers()));
        assertEquals(
                List.of(RecoveryReadyExecutionStartMaterializer.class),
                List.of(implementation.getInterfaces()));
        assertEquals(0, implementation.getDeclaredFields().length);
        var constructors = implementation.getConstructors();
        assertEquals(1, constructors.length);
        assertEquals(0, constructors[0].getParameterCount());
    }

    @Test
    void productionImportsOnlyContractsRuntimeValuesSnapshotAndMinimalJdk()
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
                                || ALLOWED_RUNTIME_IMPORTS.contains(trimmed)
                                || ALLOWED_PERSISTENCE_IMPORTS
                                        .contains(trimmed),
                        () -> sourcePath
                                + " crosses recovery materialization boundary: "
                                + trimmed);
            }
        }
        assertFalse(ALLOWED_RUNTIME_IMPORTS.contains(
                "import io.paperagent.v2.runtime.execution.start"
                        + ".FreshExecutionStartAttempt;"));
        assertTrue(containsForbiddenTypeToken(
                "io.paperagent.v2.runtime.execution"
                        + ".DeterministicExecutionStartMaterializer"
                        + "::materialize"));
        assertTrue(containsForbiddenTypeToken(
                "ExecutionStartMaterializer materializer"));
        assertFalse(containsForbiddenTypeToken(
                "RecoveryReadyExecutionStartMaterializer materializer"));
    }

    @Test
    void productionHasNoAuthoritySideEffectsSyntheticSourceOrV1Markers()
            throws Exception {
        for (Path sourcePath : productionSources()) {
            String source = Files.readString(sourcePath);
            for (String marker : FORBIDDEN_MARKERS) {
                assertFalse(
                        source.toLowerCase().contains(marker.toLowerCase()),
                        () -> sourcePath + " contains forbidden marker "
                                + marker);
            }
            assertFalse(
                    containsForbiddenTypeToken(source),
                    () -> sourcePath
                            + " references the frozen fresh materializer");
            for (String line : Files.readAllLines(sourcePath)) {
                String trimmed = line.trim();
                if (trimmed.contains("io.paperagent.v2.persistence")) {
                    assertTrue(
                            ALLOWED_PERSISTENCE_IMPORTS.contains(trimmed),
                            () -> sourcePath
                                    + " contains non-allowlisted persistence "
                                    + "reference: " + trimmed);
                }
            }
        }
    }

    @Test
    void deterministicMaterializerIsTheOnlyImplementation()
            throws Exception {
        int implementationCount = 0;
        assertEquals(
                1,
                implementationCount(
                        "final class Example implements\n"
                                + "    RecoveryReadyExecutionStartMaterializer"
                                + " {}"));
        assertEquals(
                2,
                implementationCount(
                        "class First implements "
                                + "RecoveryReadyExecutionStartMaterializer {} "
                                + "class Second implements\t"
                                + "RecoveryReadyExecutionStartMaterializer {}"));
        assertEquals(
                1,
                implementationCount(
                        "class Multiple implements Cloneable,\n"
                                + "    RecoveryReadyExecutionStartMaterializer"
                                + " {}"));
        assertEquals(
                1,
                implementationCount(
                        "class Qualified implements Cloneable, "
                                + "io.paperagent.v2.runtime.execution.recovery"
                                + ".materialization"
                                + ".RecoveryReadyExecutionStartMaterializer"
                                + " {}"));
        for (Path sourcePath : productionSources()) {
            String source = Files.readString(sourcePath);
            int implementations = implementationCount(source);
            if (implementations > 0) {
                implementationCount += implementations;
                assertEquals(
                        "DeterministicRecoveryReadyExecutionStartMaterializer"
                                + ".java",
                        sourcePath.getFileName().toString());
            }
        }
        assertEquals(1, implementationCount);
    }

    private static int implementationCount(String source) {
        var clauses = java.util.regex.Pattern.compile(
                        "\\bimplements\\b([^\\{]+)\\{")
                .matcher(source);
        int count = 0;
        while (clauses.find()) {
            var implementations = java.util.regex.Pattern.compile(
                            "(?<![\\w$])"
                                    + "(?:[A-Za-z_$][\\w$]*\\.)*"
                                    + "RecoveryReadyExecutionStartMaterializer"
                                    + "\\b")
                    .matcher(clauses.group(1));
            while (implementations.find()) {
                count++;
            }
        }
        return count;
    }

    private static void assertRecord(
            Class<?> recordType,
            List<String> names,
            List<Class<?>> types) {
        assertTrue(recordType.isRecord());
        RecordComponent[] components = recordType.getRecordComponents();
        assertEquals(
                names,
                Arrays.stream(components)
                        .map(RecordComponent::getName)
                        .toList());
        assertEquals(
                types,
                Arrays.stream(components)
                        .map(RecordComponent::getType)
                        .toList());
    }

    private static boolean containsForbiddenTypeToken(String source) {
        return FORBIDDEN_TYPE_TOKENS.stream().anyMatch(token ->
                java.util.regex.Pattern.compile(
                                "\\b"
                                        + java.util.regex.Pattern.quote(token)
                                        + "\\b")
                        .matcher(source)
                        .find());
    }

    private static List<Path> productionSources() throws Exception {
        Path root = moduleDirectory().resolve(Path.of(
                "src",
                "main",
                "java",
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "materialization"));
        try (var paths = Files.list(root)) {
            List<Path> sources = paths
                    .filter(path -> path.toString().endsWith(".java"))
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
