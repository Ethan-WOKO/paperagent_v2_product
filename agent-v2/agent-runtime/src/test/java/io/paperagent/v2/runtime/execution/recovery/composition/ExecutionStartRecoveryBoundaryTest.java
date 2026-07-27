package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializationValidationException;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.NULL;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.attempt;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.bootstrap;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.lease;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.materialized;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.persisted;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.ready;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRecoveryBoundaryTest {
    private static final Set<String> EXECUTION_START_PRODUCTION_FILES = Set.of(
            "DefaultExecutionStartRecoverer.java",
            "ExecutionStartRecoverer.java",
            "ExecutionStartRecoveryAdvancedUnsupported.java",
            "ExecutionStartRecoveryLeaseDisposition.java",
            "ExecutionStartRecoveryOutcome.java",
            "ExecutionStartRecoveryProtocolCode.java",
            "ExecutionStartRecoveryProtocolException.java",
            "ExecutionStartRecoveryRejected.java",
            "ExecutionStartRecoveryRequest.java",
            "ExecutionStartRecoveryResolution.java",
            "ExecutionStartRecoveryRetryRequired.java",
            "ExecutionStartRecoveryStage.java",
            "ExecutionStartRecoveryValidationCode.java",
            "ExecutionStartRecoveryValidationException.java",
            "ExecutionStartRecoveryValues.java",
            "RecoveredExecutionStart.java");
    private static final Set<String> STEP_RECOVERY_PRODUCTION_FILES = Set.of(
            "DefaultStepRecoverer.java",
            "RecoveredActiveStep.java",
            "StepRecoverer.java",
            "StepRecoveryCompositionOutcome.java",
            "StepRecoveryCompositionValues.java",
            "StepRecoveryLeaseAttempt.java",
            "StepRecoveryLeaseDisposition.java",
            "StepRecoveryLeaseRejected.java",
            "StepRecoveryPersistenceRejected.java",
            "StepRecoveryProtocolCode.java",
            "StepRecoveryProtocolException.java",
            "StepRecoveryRequest.java",
            "StepRecoveryStage.java",
            "StepRecoveryValidationCode.java",
            "StepRecoveryValidationException.java");
    private static final Set<String> PRODUCTION_FILES = union(
            EXECUTION_START_PRODUCTION_FILES,
            STEP_RECOVERY_PRODUCTION_FILES);
    private static final Set<String> CONTRACT_IMPORTS = Set.of(
            "import io.paperagent.v2.contracts.Checkpoint;",
            "import io.paperagent.v2.contracts.EventEnvelope;",
            "import io.paperagent.v2.contracts.PlanExecutionState;",
            "import io.paperagent.v2.contracts.PlanId;",
            "import io.paperagent.v2.contracts.PlanRevision;",
            "import io.paperagent.v2.contracts.StepExecutionState;");
    private static final Set<String> PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence"
                    + ".ExecutionStartRecoveryRepository;",
            "import io.paperagent.v2.persistence"
                    + ".ExecutionStartRecoverySnapshot;",
            "import io.paperagent.v2.persistence.ExecutionStartRepository;",
            "import io.paperagent.v2.persistence.ExecutionStartRequest;",
            "import io.paperagent.v2.persistence.LeaseRecord;",
            "import io.paperagent.v2.persistence.LeaseRepository;",
            "import io.paperagent.v2.persistence.PersistedExecutionStart;",
            "import io.paperagent.v2.persistence"
                    + ".PersistedExecutionStartCommitted;",
            "import io.paperagent.v2.persistence"
                    + ".PersistedExecutionStartReady;",
            "import io.paperagent.v2.persistence.PersistenceErrorCode;",
            "import io.paperagent.v2.persistence.PersistenceFailure;",
            "import io.paperagent.v2.persistence.PersistenceOutcome;",
            "import io.paperagent.v2.persistence.PersistenceResult;");
    private static final Set<String> RUNTIME_IMPORTS = Set.of(
            "import io.paperagent.v2.runtime.execution"
                    + ".MaterializedExecutionStart;",
            "import io.paperagent.v2.runtime.execution.recovery"
                    + ".materialization"
                    + ".RecoveryReadyExecutionStartMaterializationRequest;",
            "import io.paperagent.v2.runtime.execution.recovery"
                    + ".materialization"
                    + ".RecoveryReadyExecutionStartMaterializer;",
            "import io.paperagent.v2.runtime.execution.start"
                    + ".FreshExecutionStartAttempt;");
    private static final Set<String> JDK_IMPORTS = Set.of(
            "import java.util.Optional;");
    private static final String RECOVERER_SIMPLE_NAME =
            "ExecutionStart" + "Recoverer";

    @Test
    void publicSurfaceAndImplementationFieldsRemainExactlyFrozen()
            throws Exception {
        assertPublicFinalRecord(
                ExecutionStartRecoveryRequest.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "attempt:" + Optional.class.getName()
                                + "<"
                                + FreshExecutionStartAttempt.class.getName()
                                + ">"),
                Set.of(),
                Set.of(
                        "public java.lang.String toString()",
                        "public final int hashCode()",
                        "public final boolean equals(java.lang.Object)",
                        "public " + PlanId.class.getName() + " planId()",
                        "public " + Optional.class.getName()
                                + "<"
                                + FreshExecutionStartAttempt.class.getName()
                                + "> attempt()"),
                "public " + ExecutionStartRecoveryRequest.class.getName()
                        + "(" + PlanId.class.getName()
                        + "," + Optional.class.getName()
                        + "<"
                        + FreshExecutionStartAttempt.class.getName()
                        + ">)");

        assertPublicInterface(ExecutionStartRecoverer.class, false);
        assertTrue(ExecutionStartRecoverer.class
                .isAnnotationPresent(FunctionalInterface.class));
        assertDeclaredPublicMethods(
                ExecutionStartRecoverer.class,
                Set.of(
                        "public abstract "
                                + ExecutionStartRecoveryOutcome.class.getName()
                                + " recover("
                                + ExecutionStartRecoveryRequest.class.getName()
                                + ")"));
        assertEquals(0,
                ExecutionStartRecoverer.class
                        .getDeclaredConstructors().length);

        assertTrue(Modifier.isPublic(
                DefaultExecutionStartRecoverer.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                DefaultExecutionStartRecoverer.class.getModifiers()));
        assertFalse(DefaultExecutionStartRecoverer.class.isInterface());
        assertFalse(DefaultExecutionStartRecoverer.class.isRecord());
        assertFalse(DefaultExecutionStartRecoverer.class.isSealed());
        assertEquals(Object.class,
                DefaultExecutionStartRecoverer.class.getSuperclass());
        assertEquals(
                List.of(ExecutionStartRecoverer.class),
                List.of(DefaultExecutionStartRecoverer.class.getInterfaces()));
        assertDeclaredConstructors(
                DefaultExecutionStartRecoverer.class,
                Set.of(
                        "public "
                                + DefaultExecutionStartRecoverer.class.getName()
                                + "("
                                + ExecutionStartRecoveryRepository.class
                                        .getName()
                                + ","
                                + io.paperagent.v2.runtime.execution.recovery
                                        .materialization
                                        .RecoveryReadyExecutionStartMaterializer
                                        .class.getName()
                                + "," + LeaseRepository.class.getName()
                                + "," + ExecutionStartRepository.class.getName()
                                + ")"));
        assertDeclaredPublicMethods(
                DefaultExecutionStartRecoverer.class,
                Set.of(
                        "public "
                                + ExecutionStartRecoveryOutcome.class.getName()
                                + " recover("
                                + ExecutionStartRecoveryRequest.class.getName()
                                + ")"));
        assertExactFields(
                DefaultExecutionStartRecoverer.class,
                Map.of(
                        "recoveryRepository",
                        ExecutionStartRecoveryRepository.class,
                        "materializer",
                        io.paperagent.v2.runtime.execution.recovery
                                .materialization
                                .RecoveryReadyExecutionStartMaterializer.class,
                        "leaseRepository",
                        LeaseRepository.class,
                        "executionStartRepository",
                        ExecutionStartRepository.class),
                Modifier.PRIVATE | Modifier.FINAL);

        assertPublicInterface(ExecutionStartRecoveryOutcome.class, true);
        assertEquals(
                Set.of(
                        RecoveredExecutionStart.class,
                        ExecutionStartRecoveryRejected.class,
                        ExecutionStartRecoveryAdvancedUnsupported.class,
                        ExecutionStartRecoveryRetryRequired.class),
                Set.of(ExecutionStartRecoveryOutcome.class
                        .getPermittedSubclasses()));
        assertDeclaredPublicMethods(
                ExecutionStartRecoveryOutcome.class,
                Set.of(
                        "public abstract " + PlanId.class.getName()
                                + " planId()",
                        "public abstract "
                                + ExecutionStartRecoveryLeaseDisposition.class
                                        .getName()
                                + " leaseDisposition()"));
        assertEquals(0,
                ExecutionStartRecoveryOutcome.class
                        .getDeclaredConstructors().length);

        assertPublicFinalRecord(
                RecoveredExecutionStart.class,
                List.of(
                        "resolution:"
                                + ExecutionStartRecoveryResolution.class
                                        .getName(),
                        "persistedStart:"
                                + PersistedExecutionStart.class.getName(),
                        "leaseDisposition:"
                                + ExecutionStartRecoveryLeaseDisposition.class
                                        .getName()),
                Set.of(ExecutionStartRecoveryOutcome.class),
                recordMethods(
                        Map.of(
                                "resolution",
                                ExecutionStartRecoveryResolution.class,
                                "persistedStart",
                                PersistedExecutionStart.class,
                                "leaseDisposition",
                                ExecutionStartRecoveryLeaseDisposition.class),
                        Map.of("planId", PlanId.class)),
                recordConstructor(
                        RecoveredExecutionStart.class,
                        ExecutionStartRecoveryResolution.class,
                        PersistedExecutionStart.class,
                        ExecutionStartRecoveryLeaseDisposition.class));
        assertPublicFinalRecord(
                ExecutionStartRecoveryRejected.class,
                outcomeComponents(),
                Set.of(ExecutionStartRecoveryOutcome.class),
                recordMethods(
                        outcomeAccessors(),
                        Map.of()),
                recordConstructor(
                        ExecutionStartRecoveryRejected.class,
                        PlanId.class,
                        ExecutionStartRecoveryStage.class,
                        PersistenceFailure.class,
                        ExecutionStartRecoveryLeaseDisposition.class));
        assertPublicFinalRecord(
                ExecutionStartRecoveryAdvancedUnsupported.class,
                outcomeComponents(),
                Set.of(ExecutionStartRecoveryOutcome.class),
                recordMethods(
                        outcomeAccessors(),
                        Map.of()),
                recordConstructor(
                        ExecutionStartRecoveryAdvancedUnsupported.class,
                        PlanId.class,
                        ExecutionStartRecoveryStage.class,
                        PersistenceFailure.class,
                        ExecutionStartRecoveryLeaseDisposition.class));
        assertPublicFinalRecord(
                ExecutionStartRecoveryRetryRequired.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "leaseDisposition:"
                                + ExecutionStartRecoveryLeaseDisposition.class
                                        .getName()),
                Set.of(ExecutionStartRecoveryOutcome.class),
                recordMethods(
                        Map.of(
                                "planId", PlanId.class,
                                "leaseDisposition",
                                ExecutionStartRecoveryLeaseDisposition.class),
                        Map.of()),
                recordConstructor(
                        ExecutionStartRecoveryRetryRequired.class,
                        PlanId.class,
                        ExecutionStartRecoveryLeaseDisposition.class));
        for (Class<?> outcome : List.of(
                RecoveredExecutionStart.class,
                ExecutionStartRecoveryRejected.class,
                ExecutionStartRecoveryAdvancedUnsupported.class,
                ExecutionStartRecoveryRetryRequired.class)) {
            Set<Class<?>> fieldTypes = Arrays.stream(
                            outcome.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(
                            field.getModifiers()))
                    .map(field -> field.getType())
                    .collect(Collectors.toSet());
            assertFalse(fieldTypes.contains(FreshExecutionStartAttempt.class));
            assertFalse(fieldTypes.contains(
                    io.paperagent.v2.persistence.LeaseRecord.class));
            assertFalse(fieldTypes.contains(
                    io.paperagent.v2.runtime.execution
                            .MaterializedExecutionStart.class));
            assertFalse(fieldTypes.contains(
                    io.paperagent.v2.persistence.ExecutionStartRequest.class));
        }

        for (Class<?> enumType : List.of(
                ExecutionStartRecoveryResolution.class,
                ExecutionStartRecoveryLeaseDisposition.class,
                ExecutionStartRecoveryStage.class,
                ExecutionStartRecoveryValidationCode.class,
                ExecutionStartRecoveryProtocolCode.class)) {
            assertTrue(Modifier.isPublic(enumType.getModifiers()));
            assertTrue(Modifier.isFinal(enumType.getModifiers()));
            assertTrue(enumType.isEnum());
            assertEquals(Enum.class, enumType.getSuperclass());
            assertEquals(0, enumType.getInterfaces().length);
        }
        assertEquals(
                List.of(
                        "OBSERVED_COMMITTED",
                        "ATOMIC_START_APPLIED",
                        "ATOMIC_START_REPLAYED",
                        "RECONCILED_AFTER_RESPONSE_LOSS"),
                enumNames(ExecutionStartRecoveryResolution.class));
        assertEquals(
                List.of(
                        "NO_LEASE_ACTION",
                        "NOT_ACQUIRED",
                        "ACQUISITION_INDETERMINATE",
                        "RETAINED_FOR_RECOVERY"),
                enumNames(ExecutionStartRecoveryLeaseDisposition.class));
        assertEquals(
                List.of(
                        "INITIAL_INSPECT",
                        "MATERIALIZE",
                        "LEASE_ACQUIRE",
                        "POST_LEASE_INSPECT",
                        "POST_LEASE_MATERIALIZE",
                        "ATOMIC_START",
                        "POST_START_INSPECT"),
                enumNames(ExecutionStartRecoveryStage.class));
        assertEquals(
                List.of(
                        "REQUIRED_VALUE_MISSING",
                        "INVALID_OUTCOME_STATE",
                        "INCONSISTENT_MATERIALIZATION_AUTHORITY"),
                enumNames(ExecutionStartRecoveryValidationCode.class));
        assertEquals(
                List.of(
                        "NULL_COLLABORATOR_RESULT",
                        "UNEXPECTED_PERSISTENCE_OUTCOME",
                        "COLLABORATOR_EXCEPTION",
                        "INCONSISTENT_INSPECTION_RESULT",
                        "INCONSISTENT_MATERIALIZATION_AUTHORITY",
                        "INCONSISTENT_LEASE_AUTHORITY",
                        "INCONSISTENT_EXECUTION_START_RESULT"),
                enumNames(ExecutionStartRecoveryProtocolCode.class));

        assertPublicFinalException(
                ExecutionStartRecoveryValidationException.class,
                IllegalArgumentException.class,
                Set.of(
                        "public "
                                + ExecutionStartRecoveryValidationCode.class
                                        .getName()
                                + " code()",
                        "public java.lang.String path()"),
                Set.of(
                        ExecutionStartRecoveryValidationException.class
                                .getName()
                                + "("
                                + ExecutionStartRecoveryValidationCode.class
                                        .getName()
                                + ",java.lang.String,java.lang.String)"),
                Map.of(
                        "code", ExecutionStartRecoveryValidationCode.class,
                        "path", String.class));
        assertPublicFinalException(
                ExecutionStartRecoveryProtocolException.class,
                IllegalStateException.class,
                Set.of(
                        "public " + PlanId.class.getName() + " planId()",
                        "public "
                                + ExecutionStartRecoveryStage.class.getName()
                                + " stage()",
                        "public "
                                + ExecutionStartRecoveryProtocolCode.class
                                        .getName()
                                + " code()",
                        "public java.lang.String path()",
                        "public "
                                + ExecutionStartRecoveryLeaseDisposition.class
                                        .getName()
                                + " leaseDisposition()"),
                Set.of(
                        ExecutionStartRecoveryProtocolException.class.getName()
                                + "(" + PlanId.class.getName()
                                + ","
                                + ExecutionStartRecoveryStage.class.getName()
                                + ","
                                + ExecutionStartRecoveryProtocolCode.class
                                        .getName()
                                + ",java.lang.String,"
                                + ExecutionStartRecoveryLeaseDisposition.class
                                        .getName()
                                + ",java.lang.Throwable)"),
                Map.of(
                        "planId", PlanId.class,
                        "stage", ExecutionStartRecoveryStage.class,
                        "code", ExecutionStartRecoveryProtocolCode.class,
                        "path", String.class,
                        "leaseDisposition",
                        ExecutionStartRecoveryLeaseDisposition.class));

        assertFalse(Modifier.isPublic(
                ExecutionStartRecoveryValues.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                ExecutionStartRecoveryValues.class.getModifiers()));
        assertEquals(Object.class,
                ExecutionStartRecoveryValues.class.getSuperclass());
        assertEquals(0,
                ExecutionStartRecoveryValues.class.getInterfaces().length);
        assertDeclaredConstructors(
                ExecutionStartRecoveryValues.class,
                Set.of(
                        "private "
                                + ExecutionStartRecoveryValues.class.getName()
                                + "()"));
        assertExactFields(
                ExecutionStartRecoveryValues.class,
                Map.of(
                        "RECOVERY_PATH", String.class,
                        "PLAN_ID_PATH", String.class),
                Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL);
        for (Class<?> safeCarrier : List.of(
                ExecutionStartRecoveryValidationException.class,
                ExecutionStartRecoveryProtocolException.class,
                ExecutionStartRecoveryValues.class)) {
            for (var field : safeCarrier.getDeclaredFields()) {
                String fieldName = field.getName().toLowerCase();
                assertFalse(Throwable.class.isAssignableFrom(field.getType()));
                assertFalse(field.getType().equals(Object.class));
                assertFalse(field.getType().equals(FreshExecutionStartAttempt.class));
                assertFalse(field.getType().equals(
                        io.paperagent.v2.persistence.LeaseRecord.class));
                assertFalse(field.getType().equals(
                        io.paperagent.v2.runtime.execution
                                .MaterializedExecutionStart.class));
                assertFalse(field.getType().equals(
                        io.paperagent.v2.persistence.ExecutionStartRequest.class));
                assertFalse(fieldName.contains("original"));
                assertFalse(fieldName.contains("attempt"));
                assertFalse(fieldName.contains("token"));
            }
        }
    }

    @Test
    void productionFilesImportsAndSourceCallsAreExact() throws Exception {
        Path source = productionDirectory();
        List<Path> sources = productionJavaSources();
        Set<String> files = sources.stream()
                .map(source::relativize)
                .map(Path::toString)
                .map(relative -> relative.replace('\\', '/'))
                .collect(Collectors.toSet());
        assertEquals(PRODUCTION_FILES, files);

        List<Path> executionStartSources = executionStartProductionJavaSources();
        Set<String> imports = new java.util.LinkedHashSet<>();
        StringBuilder allSource = new StringBuilder();
        for (Path path : executionStartSources) {
            String text = Files.readString(path);
            allSource.append(text).append('\n');
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    imports.add(trimmed);
                }
            }
        }
        Set<String> expectedImports = new java.util.LinkedHashSet<>();
        expectedImports.addAll(CONTRACT_IMPORTS);
        expectedImports.addAll(PERSISTENCE_IMPORTS);
        expectedImports.addAll(RUNTIME_IMPORTS);
        expectedImports.addAll(JDK_IMPORTS);
        assertEquals(expectedImports, imports);

        String text = allSource.toString();
        for (String forbidden : List.of(
                ".success" + "ful(",
                "Instant.now",
                "Clock.",
                "System.getenv",
                "System.getProperty",
                "UUID.randomUUID",
                "java.io.",
                "java.nio.file.",
                "java.net.",
                "ProcessBuilder",
                "Thread.",
                "InMemoryPersistence",
                "PlanAgentService",
                "PlanningAgentPlanner",
                "CompletionVerifier",
                "paperagent.v1",
                "\".env")) {
            assertFalse(text.contains(forbidden),
                    () -> "forbidden production source marker " + forbidden);
        }
        Pattern forbiddenLeaseOperation = Pattern.compile(
                "\\bleaseRepository\\s*(?:\\.\\s*(?:find|renew|release)"
                        + "\\s*\\(|::\\s*(?:find|renew|release)\\b)");
        assertFalse(forbiddenLeaseOperation.matcher(text).find());
        assertFalse(Pattern.compile(
                        "\\.\\s*success" + "ful\\s*\\(")
                .matcher(text)
                .find());
        assertEquals(
                1,
                memberCallCount(text, "leaseRepository", "acquire"));
        assertEquals(
                1,
                memberCallCount(
                        text,
                        "executionStartRepository",
                        "start"));
        assertEquals(
                1,
                memberCallCount(text, "recoveryRepository", "inspect"));
        assertEquals(
                1,
                memberCallCount(text, "materializer", "materialize"));
    }

    @Test
    void defaultRecovererIsTheOnlyImplementation() throws Exception {
        assertEquals(
                1,
                implementationCount(
                        "final class Example implements\n    "
                                + RECOVERER_SIMPLE_NAME + " {}"));
        assertEquals(
                2,
                implementationCount(
                        "class First implements " + RECOVERER_SIMPLE_NAME
                                + " {} class Second implements\t"
                                + RECOVERER_SIMPLE_NAME + " {}"));
        assertEquals(
                1,
                implementationCount(
                        "class Multiple implements Cloneable,\n    "
                                + RECOVERER_SIMPLE_NAME + " {}"));
        assertEquals(
                1,
                implementationCount(
                        "class Qualified implements Cloneable, "
                                + "io.paperagent.v2.runtime.execution"
                                + ".recovery.composition."
                                + RECOVERER_SIMPLE_NAME + " {}"));

        int sourceImplementations = 0;
        for (Path path : executionStartProductionJavaSources()) {
            int inFile = implementationCount(Files.readString(path));
            if (inFile > 0) {
                sourceImplementations += inFile;
                assertEquals(
                        "DefaultExecutionStartRecoverer.java",
                        productionDirectory()
                                .relativize(path)
                                .toString()
                                .replace('\\', '/'));
            }
        }
        assertEquals(1, sourceImplementations);

        Set<Class<?>> compiledImplementations =
                new java.util.LinkedHashSet<>();
        Path runtimeClasses = runtimeClassesDirectory();
        try (var paths = Files.walk(runtimeClasses)) {
            for (Path path : paths
                    .filter(classFile ->
                            classFile.toString().endsWith(".class"))
                    .toList()) {
                String relative = runtimeClasses
                        .relativize(path)
                        .toString()
                        .replace('\\', '.')
                        .replace('/', '.');
                String className = "io.paperagent.v2.runtime."
                        + relative.substring(
                                0,
                                relative.length()
                                        - ".class".length());
                Class<?> type = Class.forName(
                        className,
                        false,
                        ExecutionStartRecoveryBoundaryTest.class
                                .getClassLoader());
                if (type != ExecutionStartRecoverer.class
                        && ExecutionStartRecoverer.class
                                .isAssignableFrom(type)) {
                    compiledImplementations.add(type);
                }
            }
        }
        assertEquals(
                Set.of(DefaultExecutionStartRecoverer.class),
                compiledImplementations);
    }

    @Test
    void opaqueTextAndSanitizedThrowableTreeDoNotLeakSecrets() {
        String secret = "SECRET-boundary";
        PersistedExecutionStartReady ready = ready(secret);
        FreshExecutionStartAttempt attempt =
                attempt(secret, secret, secret);
        var proposal = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        PersistedExecutionStart persisted =
                persisted(ready, lease, proposal);
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_HELD,
                secret);

        var request = new ExecutionStartRecoveryRequest(
                new PlanId("plan-" + secret),
                Optional.of(attempt));
        List<Object> values = List.of(
                request,
                new RecoveredExecutionStart(
                        ExecutionStartRecoveryResolution
                                .OBSERVED_COMMITTED,
                        persisted,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION),
                new ExecutionStartRecoveryRejected(
                        ready.planId(),
                        ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                        failure,
                        ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED),
                new ExecutionStartRecoveryAdvancedUnsupported(
                        ready.planId(),
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_ADVANCED_STATE,
                                "executionRecovery"),
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION),
                new ExecutionStartRecoveryRetryRequired(
                        ready.planId(),
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        for (Object value : values) {
            assertFalse(value.toString().contains(secret));
        }

        RuntimeException original =
                new IllegalStateException(secret);
        original.initCause(new IllegalArgumentException(secret));
        original.addSuppressed(new IllegalStateException(secret));
        original.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(secret, secret, secret, 1)
        });
        var protocol = ExecutionStartRecoveryValues.protocolFailure(
                new PlanId("plan-" + secret),
                ExecutionStartRecoveryStage.MATERIALIZE,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.materializedStart",
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                original);
        String printed = printed(protocol);
        assertFalse(protocol.getMessage().contains(secret));
        assertFalse(printed.contains(secret));
        assertNotSame(original, protocol.getCause());
        assertEquals(0, protocol.getSuppressed().length);
        assertEquals(null, protocol.getCause().getCause());
        assertEquals(0, protocol.getCause().getSuppressed().length);
        assertEquals(0, protocol.getCause().getStackTrace().length);
    }

    @Test
    void pollutedRealMaterializerValidationIsNeverRethrown() {
        String secret = "SECRET-polluted-validation";
        PersistedPlanBootstrap canonical = bootstrap("polluted");
        Checkpoint source = canonical.initialCheckpoint().checkpoint();
        Checkpoint nonCanonical = new Checkpoint(
                source.taskFrameId(),
                source.planId(),
                source.revisionId(),
                source.revisionNumber(),
                7,
                source.planState(),
                source.stepStates(),
                source.receiptReferences(),
                source.createdAt());
        PersistedPlanBootstrap badBootstrap = new PersistedPlanBootstrap(
                canonical.taskFrame(),
                canonical.plan(),
                new VersionedCheckpoint(1, nonCanonical));
        PersistedExecutionStartReady badReady =
                new PersistedExecutionStartReady(
                        badBootstrap,
                        badBootstrap.plan());
        var draft = attempt("polluted").eventDraft();
        var original = assertThrows(
                RecoveryReadyExecutionStartMaterializationValidationException
                        .class,
                () -> new DeterministicRecoveryReadyExecutionStartMaterializer()
                        .materialize(
                                new RecoveryReadyExecutionStartMaterializationRequest(
                                        badReady,
                                        draft,
                                        T0.plusSeconds(4))));
        original.initCause(new IllegalStateException(secret));
        original.addSuppressed(new IllegalStateException(secret));
        original.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(secret, secret, secret, 1)
        });

        PersistedExecutionStartReady canonicalReady = ready("polluted");
        var trace = new java.util.ArrayList<String>();
        var inspector = new ExecutionStartRecoveryTestFixtures
                .ScriptedRecoveryRepository(
                        List.of(PersistenceResult.found(canonicalReady)),
                        trace);
        var materializer = new ExecutionStartRecoveryTestFixtures
                .ScriptedMaterializer(List.of(original), trace);
        var leases = new ExecutionStartRecoveryTestFixtures
                .ScriptedLeaseRepository(NULL, trace);
        var starts = new ExecutionStartRecoveryTestFixtures
                .ScriptedStartRepository(NULL, trace);

        var protocol = assertThrows(
                ExecutionStartRecoveryProtocolException.class,
                () -> new DefaultExecutionStartRecoverer(
                        inspector, materializer, leases, starts)
                        .recover(new ExecutionStartRecoveryRequest(
                                canonicalReady.planId(),
                                Optional.of(attempt("polluted")))));
        assertEquals(ExecutionStartRecoveryStage.MATERIALIZE,
                protocol.stage());
        assertEquals(
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                protocol.code());
        assertNotSame(original, protocol);
        assertFalse(printed(protocol).contains(secret));
        assertEquals(0, protocol.getSuppressed().length);
        assertNotSame(original, protocol.getCause());
        assertEquals(null, protocol.getCause().getCause());
        assertEquals(0, protocol.getCause().getSuppressed().length);
        assertEquals(0, protocol.getCause().getStackTrace().length);
        assertEquals(List.of("I1", "P1"), trace);
        assertEquals(0, leases.acquireCalls.get());
        assertEquals(0, starts.calls.get());
    }

    private static void assertPublicFinalRecord(
            Class<?> type,
            List<String> components,
            Set<Class<?>> interfaces,
            Set<String> methods,
            String constructor) {
        assertTrue(type.isRecord());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertFalse(type.isSealed());
        assertEquals(Record.class, type.getSuperclass());
        assertEquals(interfaces, Set.of(type.getInterfaces()));
        assertEquals(
                components,
                Arrays.stream(type.getRecordComponents())
                        .map(component -> component.getName()
                                + ":"
                                + component.getGenericType().getTypeName())
                        .toList());
        assertDeclaredPublicMethods(type, methods);
        assertDeclaredConstructors(type, Set.of(constructor));
    }

    private static void assertPublicInterface(
            Class<?> type,
            boolean sealed) {
        assertTrue(type.isInterface());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isAbstract(type.getModifiers()));
        assertFalse(Modifier.isFinal(type.getModifiers()));
        assertEquals(sealed, type.isSealed());
        assertEquals(null, type.getSuperclass());
        assertEquals(Set.of(), Set.of(type.getInterfaces()));
    }

    private static void assertPublicFinalException(
            Class<?> type,
            Class<?> superclass,
            Set<String> methods,
            Set<String> constructors,
            Map<String, Class<?>> fields) {
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertFalse(type.isInterface());
        assertFalse(type.isRecord());
        assertFalse(type.isSealed());
        assertEquals(superclass, type.getSuperclass());
        assertEquals(0, type.getInterfaces().length);
        assertDeclaredPublicMethods(type, methods);
        assertDeclaredConstructors(type, constructors);
        assertExactFields(
                type,
                fields,
                Modifier.PRIVATE | Modifier.FINAL);
    }

    private static void assertDeclaredPublicMethods(
            Class<?> type,
            Set<String> expected) {
        assertEquals(
                expected,
                Arrays.stream(type.getDeclaredMethods())
                        .filter(method ->
                                Modifier.isPublic(method.getModifiers()))
                        .map(ExecutionStartRecoveryBoundaryTest
                                ::methodSignature)
                        .collect(Collectors.toSet()));
    }

    private static void assertDeclaredConstructors(
            Class<?> type,
            Set<String> expected) {
        assertEquals(
                expected,
                Arrays.stream(type.getDeclaredConstructors())
                        .map(ExecutionStartRecoveryBoundaryTest
                                ::constructorSignature)
                        .collect(Collectors.toSet()));
    }

    private static void assertExactFields(
            Class<?> type,
            Map<String, Class<?>> expected,
            int expectedModifiers) {
        assertEquals(
                expected,
                Arrays.stream(type.getDeclaredFields())
                        .collect(Collectors.toMap(
                                field -> field.getName(),
                                field -> {
                                    assertEquals(
                                            expectedModifiers,
                                            field.getModifiers());
                                    return field.getType();
                                })));
    }

    private static String methodSignature(
            java.lang.reflect.Method method) {
        String modifiers = Modifier.toString(method.getModifiers());
        String exceptions = Arrays.stream(
                        method.getGenericExceptionTypes())
                .map(java.lang.reflect.Type::getTypeName)
                .collect(Collectors.joining(","));
        return modifiers + " "
                + method.getGenericReturnType().getTypeName()
                + " " + method.getName() + "("
                + Arrays.stream(method.getGenericParameterTypes())
                        .map(java.lang.reflect.Type::getTypeName)
                        .collect(Collectors.joining(","))
                + ")"
                + (exceptions.isEmpty()
                        ? ""
                        : " throws " + exceptions);
    }

    private static String constructorSignature(
            java.lang.reflect.Constructor<?> constructor) {
        String modifiers = Modifier.toString(constructor.getModifiers());
        String prefix = modifiers.isEmpty() ? "" : modifiers + " ";
        String exceptions = Arrays.stream(
                        constructor.getGenericExceptionTypes())
                .map(java.lang.reflect.Type::getTypeName)
                .collect(Collectors.joining(","));
        return prefix + constructor.getDeclaringClass().getName()
                + "("
                + Arrays.stream(constructor.getGenericParameterTypes())
                        .map(java.lang.reflect.Type::getTypeName)
                        .collect(Collectors.joining(","))
                + ")"
                + (exceptions.isEmpty()
                        ? ""
                        : " throws " + exceptions);
    }

    private static Set<String> recordMethods(
            Map<String, Class<?>> components,
            Map<String, Class<?>> additional) {
        Set<String> methods = new java.util.LinkedHashSet<>(Set.of(
                "public java.lang.String toString()",
                "public final int hashCode()",
                "public final boolean equals(java.lang.Object)"));
        components.forEach((name, type) ->
                methods.add("public " + type.getName() + " " + name + "()"));
        additional.forEach((name, type) ->
                methods.add("public " + type.getName() + " " + name + "()"));
        return Set.copyOf(methods);
    }

    private static String recordConstructor(
            Class<?> type,
            Class<?>... componentTypes) {
        return "public " + type.getName() + "("
                + Arrays.stream(componentTypes)
                        .map(Class::getName)
                        .collect(Collectors.joining(","))
                + ")";
    }

    private static List<String> outcomeComponents() {
        return List.of(
                "planId:" + PlanId.class.getName(),
                "stage:" + ExecutionStartRecoveryStage.class.getName(),
                "failure:" + PersistenceFailure.class.getName(),
                "leaseDisposition:"
                        + ExecutionStartRecoveryLeaseDisposition.class
                                .getName());
    }

    private static Map<String, Class<?>> outcomeAccessors() {
        return Map.of(
                "planId", PlanId.class,
                "stage", ExecutionStartRecoveryStage.class,
                "failure", PersistenceFailure.class,
                "leaseDisposition",
                ExecutionStartRecoveryLeaseDisposition.class);
    }

    private static <E extends Enum<E>> List<String> enumNames(
            Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .toList();
    }

    private static String printed(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static int memberCallCount(
            String source,
            String receiver,
            String method) {
        var matcher = Pattern.compile(
                        "\\b" + Pattern.quote(receiver)
                                + "\\s*\\.\\s*"
                                + Pattern.quote(method)
                                + "\\s*\\(")
                .matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int implementationCount(String source) {
        var clauses = Pattern.compile("\\bimplements\\b([^\\{]+)\\{")
                .matcher(source);
        int count = 0;
        while (clauses.find()) {
            var implementations = Pattern.compile(
                            "(?<![\\w$])"
                                    + "(?:[A-Za-z_$][\\w$]*\\.)*"
                                    + Pattern.quote(RECOVERER_SIMPLE_NAME)
                                    + "\\b")
                    .matcher(clauses.group(1));
            while (implementations.find()) {
                count++;
            }
        }
        return count;
    }

    private static List<Path> productionJavaSources()
            throws java.io.IOException {
        try (var paths = Files.walk(productionDirectory())) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static List<Path> executionStartProductionJavaSources()
            throws java.io.IOException {
        return productionJavaSources().stream()
                .filter(path -> EXECUTION_START_PRODUCTION_FILES.contains(
                        path.getFileName().toString()))
                .toList();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        var values = new java.util.LinkedHashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private static Path moduleDirectory() {
        Path current = Path.of("").toAbsolutePath();
        return current.getFileName().toString()
                        .equals("agent-runtime")
                ? current
                : current.resolve("agent-runtime");
    }

    private static Path productionDirectory() {
        return moduleDirectory().resolve(Path.of(
                "src",
                "main",
                "java",
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "composition"));
    }

    private static Path runtimeClassesDirectory() {
        return moduleDirectory().resolve(Path.of(
                "target",
                "classes",
                "io",
                "paperagent",
                "v2",
                "runtime"));
    }
}
