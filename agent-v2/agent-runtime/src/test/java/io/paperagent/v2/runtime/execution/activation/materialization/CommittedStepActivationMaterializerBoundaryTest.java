package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommittedStepActivationMaterializerBoundaryTest {
    private static final Set<String> PRODUCTION_FILES = Set.of(
            "StepActivationEventDraft.java",
            "CommittedStepActivationMaterializationRequest.java",
            "MaterializedStepActivation.java",
            "CommittedStepActivationMaterializer.java",
            "DeterministicCommittedStepActivationMaterializer.java",
            "CommittedStepActivationMaterializationValidationCode.java",
            "CommittedStepActivationMaterializationValidationException.java",
            "CommittedStepActivationMaterializationValues.java");
    private static final Set<String> ALLOWED_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence"
                    + ".PersistedExecutionStartCommitted;");
    private static final Set<String> ALLOWED_JDK_IMPORTS = Set.of(
            "import java.time.Instant;",
            "import java.util.Optional;");
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "StepActivationRequest",
            "StepActivationRepository",
            "PersistedStepActivation",
            "ExecutionStartRecoveryRepository",
            "LeaseRecord",
            "LeaseRepository",
            "PersistenceResult",
            "PersistenceOutcome",
            "PersistenceFailure",
            "InMemoryPersistence",
            "PlanExecutionContext",
            "io.paperagent.v2.workspace",
            "io.paperagent.v2.sandbox",
            "io.paperagent.v2.providers",
            "io.paperagent.v2.app",
            "paperagent.v1",
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "Candidate",
            "initialCheckpoint",
            "java.net.",
            "java.io.",
            "java.nio.file.",
            "java.lang.reflect",
            "Class.forName",
            "getDeclared",
            "MethodHandles",
            "Unsafe",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "System.getenv",
            "System.getProperty",
            "SecretRef",
            "\".env",
            "System.currentTimeMillis",
            "System.nanoTime",
            "Instant.now",
            "LocalDate.now",
            "java.time.Clock",
            "Clock.",
            "Clock::",
            "UUID.randomUUID",
            "java.util.UUID",
            "Math.random",
            "java.util.Random",
            "SplittableRandom",
            "java.security.SecureRandom",
            "java.util.concurrent",
            "Thread.sleep",
            "new Thread",
            "java.lang.Thread",
            "Thread.ofVirtual",
            "Thread.ofPlatform",
            "Thread.currentThread",
            "ofVirtual(",
            "ofPlatform(",
            "currentThread(",
            ".start(");

    @Test
    void publicSurfaceRemainsExactlyFrozen() throws Exception {
        assertRecord(
                StepActivationEventDraft.class,
                List.of(
                        "id",
                        "occurredAt",
                        "type",
                        "causationId",
                        "correlationId",
                        "payload"),
                List.of(
                        EventId.class,
                        Instant.class,
                        EventType.class,
                        Optional.class,
                        String.class,
                        EventPayload.class),
                Set.of(
                        "id():io.paperagent.v2.contracts.EventId",
                        "occurredAt():java.time.Instant",
                        "type():io.paperagent.v2.contracts.EventType",
                        "causationId():java.util.Optional",
                        "correlationId():java.lang.String",
                        "payload():io.paperagent.v2.contracts.EventPayload",
                        "equals(java.lang.Object):boolean",
                        "hashCode():int",
                        "toString():java.lang.String"));
        assertRecord(
                CommittedStepActivationMaterializationRequest.class,
                List.of(
                        "committedStart",
                        "stepId",
                        "eventDraft",
                        "checkpointCreatedAt"),
                List.of(
                        PersistedExecutionStartCommitted.class,
                        PlanStepId.class,
                        StepActivationEventDraft.class,
                        Instant.class),
                Set.of(
                        "committedStart():io.paperagent.v2.persistence"
                                + ".PersistedExecutionStartCommitted",
                        "stepId():io.paperagent.v2.contracts.PlanStepId",
                        "eventDraft():io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".StepActivationEventDraft",
                        "checkpointCreatedAt():java.time.Instant",
                        "equals(java.lang.Object):boolean",
                        "hashCode():int",
                        "toString():java.lang.String"));
        assertRecord(
                MaterializedStepActivation.class,
                List.of("activationEvent", "activatedCheckpoint"),
                List.of(EventEnvelope.class, Checkpoint.class),
                Set.of(
                        "activationEvent():io.paperagent.v2.contracts"
                                + ".EventEnvelope",
                        "activatedCheckpoint():io.paperagent.v2.contracts"
                                + ".Checkpoint",
                        "equals(java.lang.Object):boolean",
                        "hashCode():int",
                        "toString():java.lang.String"));
        assertEquals(
                List.of(
                        CommittedStepActivationMaterializationValidationCode
                                .REQUIRED_VALUE_MISSING,
                        CommittedStepActivationMaterializationValidationCode
                                .INVALID_IDENTIFIER,
                        CommittedStepActivationMaterializationValidationCode
                                .STEP_NOT_ELIGIBLE),
                List.of(
                        CommittedStepActivationMaterializationValidationCode
                                .values()));
        Class<?> validationCode =
                CommittedStepActivationMaterializationValidationCode.class;
        assertEquals(
                Set.of(
                        "values():[Lio.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode;",
                        "valueOf(java.lang.String):io.paperagent.v2.runtime"
                                + ".execution.activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode"),
                declaredPublicMethodSignatures(validationCode));
        assertEquals(
                Set.of(
                        "REQUIRED_VALUE_MISSING:io.paperagent.v2.runtime"
                                + ".execution.activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode",
                        "INVALID_IDENTIFIER:io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode",
                        "STEP_NOT_ELIGIBLE:io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode"),
                Arrays.stream(validationCode.getFields())
                        .map(field -> field.getName()
                                + ":"
                                + field.getType().getName())
                        .collect(Collectors.toSet()));
        Arrays.stream(validationCode.getFields()).forEach(field -> {
            assertTrue(field.isEnumConstant());
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isPublic(modifiers));
            assertTrue(Modifier.isStatic(modifiers));
            assertTrue(Modifier.isFinal(modifiers));
        });
        assertEquals(0, validationCode.getDeclaredClasses().length);

        Class<?> exception =
                CommittedStepActivationMaterializationValidationException
                        .class;
        assertTrue(Modifier.isPublic(exception.getModifiers()));
        assertTrue(Modifier.isFinal(exception.getModifiers()));
        assertEquals(IllegalArgumentException.class, exception.getSuperclass());
        assertEquals(0, exception.getFields().length);
        assertEquals(
                Set.of(
                        "code:io.paperagent.v2.runtime.execution.activation"
                                + ".materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode",
                        "path:java.lang.String"),
                declaredFieldSignatures(exception));
        Arrays.stream(exception.getDeclaredFields()).forEach(field -> {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isPrivate(modifiers));
            assertTrue(Modifier.isFinal(modifiers));
        });
        assertEquals(
                Set.of(
                        "code():io.paperagent.v2.runtime.execution.activation"
                                + ".materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "ValidationCode",
                        "path():java.lang.String"),
                declaredPublicMethodSignatures(exception));
        assertEquals(2, exception.getDeclaredMethods().length);
        assertEquals(0, exception.getConstructors().length);
        assertEquals(1, exception.getDeclaredConstructors().length);
        var exceptionConstructor = exception.getDeclaredConstructors()[0];
        int exceptionConstructorModifiers =
                exceptionConstructor.getModifiers();
        assertFalse(
                Modifier.isPublic(exceptionConstructorModifiers)
                        || Modifier.isProtected(exceptionConstructorModifiers)
                        || Modifier.isPrivate(exceptionConstructorModifiers));
        assertEquals(
                List.of(
                        CommittedStepActivationMaterializationValidationCode
                                .class,
                        String.class,
                        String.class),
                List.of(exceptionConstructor.getParameterTypes()));
        assertEquals(0, exception.getDeclaredClasses().length);
        assertTrue(Modifier.isPublic(
                exception.getDeclaredMethod("code").getModifiers()));
        assertEquals(
                CommittedStepActivationMaterializationValidationCode.class,
                exception.getDeclaredMethod("code").getReturnType());
        assertTrue(Modifier.isPublic(
                exception.getDeclaredMethod("path").getModifiers()));
        assertEquals(
                String.class,
                exception.getDeclaredMethod("path").getReturnType());

        Class<?> values =
                CommittedStepActivationMaterializationValues.class;
        assertFalse(Modifier.isPublic(values.getModifiers()));
        assertFalse(Modifier.isProtected(values.getModifiers()));
        assertTrue(Modifier.isFinal(values.getModifiers()));
        assertEquals(1, values.getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(
                values.getDeclaredConstructors()[0].getModifiers()));
        assertEquals(0, values.getDeclaredClasses().length);
        Arrays.stream(values.getDeclaredMethods()).forEach(method -> {
            int modifiers = method.getModifiers();
            assertFalse(
                    Modifier.isPublic(modifiers)
                            || Modifier.isProtected(modifiers)
                            || Modifier.isPrivate(modifiers),
                    () -> method + " must remain package-private");
        });

        Class<?> materializer = CommittedStepActivationMaterializer.class;
        assertTrue(Modifier.isPublic(materializer.getModifiers()));
        assertTrue(materializer.isInterface());
        assertTrue(materializer.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(0, materializer.getDeclaredFields().length);
        var methods = Arrays.stream(materializer.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();
        assertEquals(1, methods.size());
        assertEquals(1, materializer.getDeclaredMethods().length);
        assertEquals("materialize", methods.get(0).getName());
        assertEquals(
                MaterializedStepActivation.class,
                methods.get(0).getReturnType());
        assertEquals(
                List.of(
                        CommittedStepActivationMaterializationRequest.class),
                List.of(methods.get(0).getParameterTypes()));
        assertEquals(
                Set.of(
                        "materialize(io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "Request):io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".MaterializedStepActivation"),
                declaredPublicMethodSignatures(materializer));
        assertEquals(0, materializer.getDeclaredClasses().length);

        Class<?> implementation =
                DeterministicCommittedStepActivationMaterializer.class;
        assertTrue(Modifier.isPublic(implementation.getModifiers()));
        assertTrue(Modifier.isFinal(implementation.getModifiers()));
        assertEquals(
                List.of(CommittedStepActivationMaterializer.class),
                List.of(implementation.getInterfaces()));
        assertEquals(0, implementation.getDeclaredFields().length);
        var constructors = implementation.getConstructors();
        assertEquals(1, constructors.length);
        assertEquals(0, constructors[0].getParameterCount());
        assertEquals(1, implementation.getDeclaredConstructors().length);
        assertTrue(Modifier.isPublic(
                implementation.getDeclaredConstructors()[0].getModifiers()));
        assertEquals(
                Set.of(
                        "materialize(io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".CommittedStepActivationMaterialization"
                                + "Request):io.paperagent.v2.runtime.execution"
                                + ".activation.materialization"
                                + ".MaterializedStepActivation"),
                declaredPublicMethodSignatures(implementation));
        assertEquals(1, implementation.getDeclaredMethods().length);
        assertEquals(0, implementation.getDeclaredClasses().length);
    }

    @Test
    void productionImportsOnlyContractsCommittedSnapshotAndMinimalJdk()
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
                                + " crosses committed activation"
                                + " materialization boundary: "
                                + trimmed);
            }
        }
    }

    @Test
    void productionHasNoAuthoritySideEffectsOrForbiddenMarkers()
            throws Exception {
        for (String marker : FORBIDDEN_MARKERS) {
            assertTrue(
                    containsForbiddenMarker("prefix " + marker + " suffix"),
                    () -> "forbidden-marker mechanism does not reject "
                            + marker);
        }
        assertTrue(containsForbiddenMarker(
                "return JAVA.LANG.CLASS.FORNAME(name);"));
        assertFalse(containsForbiddenMarker(
                "return \"pure committed activation materialization\";"));

        for (Path sourcePath : productionSources()) {
            String source = Files.readString(sourcePath);
            assertFalse(
                    containsForbiddenMarker(source),
                    () -> sourcePath + " contains forbidden marker");
            for (String line : Files.readAllLines(sourcePath)) {
                String trimmed = line.trim();
                if (trimmed.contains("io.paperagent.v2.persistence")) {
                    assertTrue(
                            ALLOWED_PERSISTENCE_IMPORTS.contains(trimmed),
                            () -> sourcePath
                                    + " contains non-allowlisted persistence "
                                    + "reference: "
                                    + trimmed);
                }
            }
        }
    }

    @Test
    void deterministicMaterializerIsTheOnlyImplementation()
            throws Exception {
        int implementationCount = 0;
        for (Path sourcePath : productionSources()) {
            String source = Files.readString(sourcePath);
            int implementations = implementationCount(source);
            if (implementations > 0) {
                implementationCount += implementations;
                assertEquals(
                        "DeterministicCommittedStepActivationMaterializer.java",
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
                                    + "CommittedStepActivationMaterializer"
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
            List<Class<?>> types,
            Set<String> publicMethodSignatures) {
        assertTrue(Modifier.isPublic(recordType.getModifiers()));
        assertTrue(recordType.isRecord());
        assertEquals(0, recordType.getDeclaredClasses().length);
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
        assertEquals(1, recordType.getDeclaredConstructors().length);
        var canonicalConstructor = recordType.getDeclaredConstructors()[0];
        assertTrue(Modifier.isPublic(canonicalConstructor.getModifiers()));
        assertEquals(
                types,
                List.of(canonicalConstructor.getParameterTypes()));
        assertEquals(
                publicMethodSignatures,
                declaredPublicMethodSignatures(recordType));
        assertEquals(
                publicMethodSignatures.size(),
                recordType.getDeclaredMethods().length);
        assertEquals(
                names.stream()
                        .map(name -> {
                            int index = names.indexOf(name);
                            return name + ":" + types.get(index).getName();
                        })
                        .collect(Collectors.toSet()),
                declaredFieldSignatures(recordType));
        Arrays.stream(recordType.getDeclaredFields()).forEach(field -> {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isPrivate(modifiers));
            assertTrue(Modifier.isFinal(modifiers));
        });
    }

    private static Set<String> declaredFieldSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(field -> field.getName()
                        + ":"
                        + field.getType().getName())
                .collect(Collectors.toSet());
    }

    private static Set<String> declaredPublicMethodSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName()
                        + "("
                        + Arrays.stream(method.getParameterTypes())
                                .map(Class::getName)
                                .collect(Collectors.joining(","))
                        + "):"
                        + method.getReturnType().getName())
                .collect(Collectors.toSet());
    }

    private static boolean containsForbiddenMarker(String source) {
        String normalized = source.toLowerCase();
        return FORBIDDEN_MARKERS.stream()
                .map(String::toLowerCase)
                .anyMatch(normalized::contains);
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
                "activation",
                "materialization"));
        try (var paths = Files.walk(root)) {
            List<Path> sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertEquals(
                    PRODUCTION_FILES,
                    sources.stream()
                            .map(root::relativize)
                            .map(Path::toString)
                            .map(path -> path.replace('\\', '/'))
                            .collect(Collectors.toSet()));
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
