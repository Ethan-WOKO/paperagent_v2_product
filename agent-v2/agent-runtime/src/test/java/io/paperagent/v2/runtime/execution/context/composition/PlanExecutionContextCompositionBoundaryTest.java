package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspacePort;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.FINGERPRINT;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.PLAN_ID;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.SECRET;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.persistedContext;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.spec;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.verifiedWorkspace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionContextCompositionBoundaryTest {
    private static final Set<String> PRODUCTION_FILES = Set.of(
            "DefaultPlanExecutionContextComposer.java",
            "PlanExecutionContextAdvancedUnsupported.java",
            "PlanExecutionContextComposer.java",
            "PlanExecutionContextCompositionOutcome.java",
            "PlanExecutionContextCompositionProtocolCode.java",
            "PlanExecutionContextCompositionProtocolException.java",
            "PlanExecutionContextCompositionRequest.java",
            "PlanExecutionContextCompositionResolution.java",
            "PlanExecutionContextCompositionStage.java",
            "PlanExecutionContextCompositionValidationCode.java",
            "PlanExecutionContextCompositionValidationException.java",
            "PlanExecutionContextCompositionValues.java",
            "PlanExecutionContextLeaseAttempt.java",
            "PlanExecutionContextLeaseDisposition.java",
            "PlanExecutionContextNotRequired.java",
            "PlanExecutionContextPersistenceRejected.java",
            "PlanExecutionContextReady.java",
            "PlanExecutionContextRetryReason.java",
            "PlanExecutionContextRetryRequired.java",
            "PlanExecutionContextWorkspaceRejected.java");

    private static final Set<String> ALLOWED_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence"
                    + ".ExecutionStartRecoveryRepository;",
            "import io.paperagent.v2.persistence"
                    + ".ExecutionStartRecoverySnapshot;",
            "import io.paperagent.v2.persistence.LeaseRecord;",
            "import io.paperagent.v2.persistence.LeaseRepository;",
            "import io.paperagent.v2.persistence"
                    + ".PersistedExecutionStartCommitted;",
            "import io.paperagent.v2.persistence"
                    + ".PersistedExecutionStartReady;",
            "import io.paperagent.v2.persistence"
                    + ".PersistedPlanExecutionContextConfirmed;",
            "import io.paperagent.v2.persistence"
                    + ".PersistedPlanExecutionContextReserved;",
            "import io.paperagent.v2.persistence.PersistenceErrorCode;",
            "import io.paperagent.v2.persistence.PersistenceFailure;",
            "import io.paperagent.v2.persistence.PersistenceOutcome;",
            "import io.paperagent.v2.persistence.PersistenceResult;",
            "import io.paperagent.v2.persistence"
                    + ".PlanExecutionContextConfirmationRequest;",
            "import io.paperagent.v2.persistence"
                    + ".PlanExecutionContextRepository;",
            "import io.paperagent.v2.persistence"
                    + ".PlanExecutionContextReservationRequest;",
            "import io.paperagent.v2.persistence"
                    + ".PlanExecutionContextSnapshot;");

    private static final Set<String> ALLOWED_WORKSPACE_IMPORTS = Set.of(
            "import io.paperagent.v2.workspace"
                    + ".VerifiedWorkspaceMaterialization;",
            "import io.paperagent.v2.workspace.WorkspaceErrorCode;",
            "import io.paperagent.v2.workspace.WorkspaceException;",
            "import io.paperagent.v2.workspace.WorkspacePort;");

    @Test
    void publicSurfaceAndSealedOutcomesAreExactlyFrozen()
            throws Exception {
        assertTrue(PlanExecutionContextComposer.class.isInterface());
        assertTrue(Modifier.isPublic(
                PlanExecutionContextComposer.class.getModifiers()));
        assertTrue(PlanExecutionContextComposer.class.isAnnotationPresent(
                FunctionalInterface.class));
        assertEquals(
                PlanExecutionContextCompositionOutcome.class,
                PlanExecutionContextComposer.class
                        .getMethod(
                                "compose",
                                PlanExecutionContextCompositionRequest.class)
                        .getReturnType());

        assertTrue(PlanExecutionContextCompositionOutcome.class.isSealed());
        assertEquals(
                Set.of(
                        PlanExecutionContextReady.class,
                        PlanExecutionContextNotRequired.class,
                        PlanExecutionContextAdvancedUnsupported.class,
                        PlanExecutionContextPersistenceRejected.class,
                        PlanExecutionContextWorkspaceRejected.class,
                        PlanExecutionContextRetryRequired.class),
                Set.of(PlanExecutionContextCompositionOutcome.class
                        .getPermittedSubclasses()));
        assertEquals(
                PlanId.class,
                PlanExecutionContextCompositionOutcome.class
                        .getMethod("planId")
                        .getReturnType());
        assertEquals(
                PlanExecutionContextLeaseDisposition.class,
                PlanExecutionContextCompositionOutcome.class
                        .getMethod("leaseDisposition")
                        .getReturnType());

        assertRecord(
                PlanExecutionContextCompositionRequest.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "proposedMaterializationSpec:"
                                + Optional.class.getName()
                                + "<"
                                + WorkspaceMaterializationSpec.class.getName()
                                + ">",
                        "leaseAttempt:"
                                + Optional.class.getName()
                                + "<"
                                + PlanExecutionContextLeaseAttempt.class
                                        .getName()
                                + ">"),
                List.of(
                        PlanId.class,
                        Optional.class,
                        Optional.class),
                Set.of());
        assertRecord(
                PlanExecutionContextLeaseAttempt.class,
                List.of(
                        "leaseOwnerId:java.lang.String",
                        "leaseToken:java.lang.String",
                        "leaseExpiresAt:" + Instant.class.getName()),
                List.of(String.class, String.class, Instant.class),
                Set.of());
        assertRecord(
                PlanExecutionContextReady.class,
                List.of(
                        "resolution:"
                                + PlanExecutionContextCompositionResolution
                                        .class.getName(),
                        "persistedContext:"
                                + PersistedPlanExecutionContextConfirmed.class
                                        .getName(),
                        "verifiedWorkspace:"
                                + VerifiedWorkspaceMaterialization.class
                                        .getName(),
                        "leaseDisposition:"
                                + PlanExecutionContextLeaseDisposition.class
                                        .getName()),
                List.of(
                        PlanExecutionContextCompositionResolution.class,
                        PersistedPlanExecutionContextConfirmed.class,
                        VerifiedWorkspaceMaterialization.class,
                        PlanExecutionContextLeaseDisposition.class),
                Set.of(methodSignature("planId", PlanId.class)));
        assertRecord(
                PlanExecutionContextNotRequired.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "leaseDisposition:"
                                + PlanExecutionContextLeaseDisposition.class
                                        .getName()),
                List.of(
                        PlanId.class,
                        PlanExecutionContextLeaseDisposition.class),
                Set.of());
        for (Class<?> type : List.of(
                PlanExecutionContextAdvancedUnsupported.class,
                PlanExecutionContextPersistenceRejected.class)) {
            assertRecord(
                    type,
                    List.of(
                            "planId:" + PlanId.class.getName(),
                            "stage:"
                                    + PlanExecutionContextCompositionStage
                                            .class.getName(),
                            "failure:"
                                    + PersistenceFailure.class.getName(),
                            "leaseDisposition:"
                                    + PlanExecutionContextLeaseDisposition
                                            .class.getName()),
                    List.of(
                            PlanId.class,
                            PlanExecutionContextCompositionStage.class,
                            PersistenceFailure.class,
                            PlanExecutionContextLeaseDisposition.class),
                    Set.of());
        }
        assertRecord(
                PlanExecutionContextWorkspaceRejected.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "stage:"
                                + PlanExecutionContextCompositionStage.class
                                        .getName(),
                        "workspaceErrorCode:"
                                + WorkspaceErrorCode.class.getName(),
                        "leaseDisposition:"
                                + PlanExecutionContextLeaseDisposition.class
                                        .getName()),
                List.of(
                        PlanId.class,
                        PlanExecutionContextCompositionStage.class,
                        WorkspaceErrorCode.class,
                        PlanExecutionContextLeaseDisposition.class),
                Set.of());
        assertRecord(
                PlanExecutionContextRetryRequired.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "stage:"
                                + PlanExecutionContextCompositionStage.class
                                        .getName(),
                        "retryReason:"
                                + PlanExecutionContextRetryReason.class
                                        .getName(),
                        "leaseDisposition:"
                                + PlanExecutionContextLeaseDisposition.class
                                        .getName()),
                List.of(
                        PlanId.class,
                        PlanExecutionContextCompositionStage.class,
                        PlanExecutionContextRetryReason.class,
                        PlanExecutionContextLeaseDisposition.class),
                Set.of());

        assertTrue(Modifier.isPublic(
                DefaultPlanExecutionContextComposer.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                DefaultPlanExecutionContextComposer.class.getModifiers()));
        assertEquals(
                Set.of(PlanExecutionContextComposer.class),
                Set.of(DefaultPlanExecutionContextComposer.class
                        .getInterfaces()));
        assertEquals(
                List.of(
                        "executionStartRecoveryRepository:"
                                + ExecutionStartRecoveryRepository.class
                                        .getName(),
                        "planExecutionContextRepository:"
                                + PlanExecutionContextRepository.class
                                        .getName(),
                        "leaseRepository:"
                                + LeaseRepository.class.getName(),
                        "workspacePort:" + WorkspacePort.class.getName()),
                Arrays.stream(
                                DefaultPlanExecutionContextComposer.class
                                        .getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(
                                field.getModifiers()))
                        .map(field -> field.getName()
                                + ":"
                                + field.getType().getName())
                        .toList());
        for (Field field
                : DefaultPlanExecutionContextComposer.class
                        .getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue(Modifier.isPrivate(field.getModifiers()));
                assertTrue(Modifier.isFinal(field.getModifiers()));
            }
        }
        assertExactConstructor(
                DefaultPlanExecutionContextComposer.class,
                true,
                ExecutionStartRecoveryRepository.class,
                PlanExecutionContextRepository.class,
                LeaseRepository.class,
                WorkspacePort.class);
        assertEquals(
                Set.of(methodSignature(
                        "compose",
                        PlanExecutionContextCompositionOutcome.class,
                        PlanExecutionContextCompositionRequest.class)),
                declaredPublicMethods(
                        DefaultPlanExecutionContextComposer.class));
        assertNoPublicFields(DefaultPlanExecutionContextComposer.class);
    }

    @Test
    void publicEnumsAreExact() {
        assertEnum(
                PlanExecutionContextCompositionResolution.class,
                "OBSERVED_CONFIRMED",
                "CONFIRM_APPLIED",
                "CONFIRM_REPLAYED",
                "RECONCILED_AFTER_RESPONSE_LOSS",
                "OBSERVED_CONCURRENT_CONFIRMATION");
        assertEnum(
                PlanExecutionContextCompositionStage.class,
                "INITIAL_EXECUTION_START_INSPECT",
                "INITIAL_CONTEXT_INSPECT",
                "LEASE_ACQUIRE",
                "POST_LEASE_EXECUTION_START_INSPECT",
                "POST_LEASE_CONTEXT_INSPECT",
                "RESERVE",
                "POST_RESERVE_CONTEXT_INSPECT",
                "WORKSPACE_INSPECT",
                "WORKSPACE_MATERIALIZE",
                "POST_MATERIALIZE_WORKSPACE_INSPECT",
                "CONFIRM",
                "POST_CONFIRM_CONTEXT_INSPECT");
        assertEnum(
                PlanExecutionContextLeaseDisposition.class,
                "NO_LEASE_ACTION",
                "NOT_ACQUIRED",
                "ACQUISITION_INDETERMINATE",
                "RETAINED_FOR_RECOVERY");
        assertEnum(
                PlanExecutionContextRetryReason.class,
                "RESERVATION_INDETERMINATE",
                "MATERIALIZATION_INDETERMINATE",
                "CONFIRMATION_INDETERMINATE",
                "EXECUTION_START_NOT_COMMITTED");
        assertEnum(
                PlanExecutionContextCompositionValidationCode.class,
                "REQUIRED_VALUE_MISSING",
                "INVALID_IDENTIFIER",
                "INCONSISTENT_REQUEST_AUTHORITY",
                "INVALID_OUTCOME_STATE");
        assertEnum(
                PlanExecutionContextCompositionProtocolCode.class,
                "NULL_COLLABORATOR_RESULT",
                "UNEXPECTED_PERSISTENCE_OUTCOME",
                "COLLABORATOR_EXCEPTION",
                "INCONSISTENT_EXECUTION_START_AUTHORITY",
                "INCONSISTENT_LEASE_AUTHORITY",
                "INCONSISTENT_CONTEXT_AUTHORITY",
                "INCONSISTENT_WORKSPACE_AUTHORITY",
                "INCONSISTENT_RECONCILIATION_AUTHORITY");
    }

    @Test
    void exceptionAndInternalClassifierSurfacesAreFailClosed()
            throws Exception {
        assertTrue(Modifier.isPublic(
                PlanExecutionContextCompositionValidationException.class
                        .getModifiers()));
        assertTrue(Modifier.isFinal(
                PlanExecutionContextCompositionValidationException.class
                        .getModifiers()));
        assertEquals(
                IllegalArgumentException.class,
                PlanExecutionContextCompositionValidationException.class
                        .getSuperclass());
        assertFalse(Modifier.isPublic(
                PlanExecutionContextCompositionValidationException.class
                        .getDeclaredConstructor(
                                PlanExecutionContextCompositionValidationCode
                                        .class,
                                String.class,
                                String.class)
                        .getModifiers()));
        assertExactFields(
                PlanExecutionContextCompositionValidationException.class,
                List.of(
                        "code:"
                                + PlanExecutionContextCompositionValidationCode
                                        .class.getName(),
                        "path:" + String.class.getName()));
        assertExactConstructor(
                PlanExecutionContextCompositionValidationException.class,
                false,
                PlanExecutionContextCompositionValidationCode.class,
                String.class,
                String.class);
        assertEquals(
                Set.of(
                        methodSignature(
                                "code",
                                PlanExecutionContextCompositionValidationCode
                                        .class),
                        methodSignature("path", String.class)),
                declaredPublicMethods(
                        PlanExecutionContextCompositionValidationException
                                .class));
        assertNoPublicFields(
                PlanExecutionContextCompositionValidationException.class);

        assertTrue(Modifier.isPublic(
                PlanExecutionContextCompositionProtocolException.class
                        .getModifiers()));
        assertTrue(Modifier.isFinal(
                PlanExecutionContextCompositionProtocolException.class
                        .getModifiers()));
        assertEquals(
                IllegalStateException.class,
                PlanExecutionContextCompositionProtocolException.class
                        .getSuperclass());
        assertFalse(Modifier.isPublic(
                PlanExecutionContextCompositionProtocolException.class
                        .getDeclaredConstructor(
                                PlanId.class,
                                PlanExecutionContextCompositionStage.class,
                                PlanExecutionContextCompositionProtocolCode
                                        .class,
                                String.class,
                                PlanExecutionContextLeaseDisposition.class,
                                Throwable.class)
                        .getModifiers()));
        assertExactFields(
                PlanExecutionContextCompositionProtocolException.class,
                List.of(
                        "planId:" + PlanId.class.getName(),
                        "stage:"
                                + PlanExecutionContextCompositionStage.class
                                        .getName(),
                        "code:"
                                + PlanExecutionContextCompositionProtocolCode
                                        .class.getName(),
                        "path:" + String.class.getName(),
                        "leaseDisposition:"
                                + PlanExecutionContextLeaseDisposition.class
                                        .getName()));
        assertExactConstructor(
                PlanExecutionContextCompositionProtocolException.class,
                false,
                PlanId.class,
                PlanExecutionContextCompositionStage.class,
                PlanExecutionContextCompositionProtocolCode.class,
                String.class,
                PlanExecutionContextLeaseDisposition.class,
                Throwable.class);
        assertEquals(
                Set.of(
                        methodSignature("planId", PlanId.class),
                        methodSignature(
                                "stage",
                                PlanExecutionContextCompositionStage.class),
                        methodSignature(
                                "code",
                                PlanExecutionContextCompositionProtocolCode
                                        .class),
                        methodSignature("path", String.class),
                        methodSignature(
                                "leaseDisposition",
                                PlanExecutionContextLeaseDisposition.class)),
                declaredPublicMethods(
                        PlanExecutionContextCompositionProtocolException
                                .class));
        assertNoPublicFields(
                PlanExecutionContextCompositionProtocolException.class);

        assertFalse(Modifier.isPublic(
                PlanExecutionContextCompositionValues.class.getModifiers()));
        for (Class<?> nested
                : DefaultPlanExecutionContextComposer.class
                        .getDeclaredClasses()) {
            assertFalse(
                    Modifier.isPublic(nested.getModifiers()),
                    nested::getName);
        }
    }

    @Test
    void constructorPathsAndComposeNullAreExact() {
        ExecutionStartRecoveryRepository recovery =
                proxy(ExecutionStartRecoveryRepository.class);
        PlanExecutionContextRepository contexts =
                proxy(PlanExecutionContextRepository.class);
        LeaseRepository leases = proxy(LeaseRepository.class);
        WorkspacePort workspace = proxy(WorkspacePort.class);

        assertValidation(
                () -> new DefaultPlanExecutionContextComposer(
                        null,
                        contexts,
                        leases,
                        workspace),
                "planExecutionContextComposition"
                        + ".executionStartRecoveryRepository");
        assertValidation(
                () -> new DefaultPlanExecutionContextComposer(
                        recovery,
                        null,
                        leases,
                        workspace),
                "planExecutionContextComposition"
                        + ".planExecutionContextRepository");
        assertValidation(
                () -> new DefaultPlanExecutionContextComposer(
                        recovery,
                        contexts,
                        null,
                        workspace),
                "planExecutionContextComposition.leaseRepository");
        assertValidation(
                () -> new DefaultPlanExecutionContextComposer(
                        recovery,
                        contexts,
                        leases,
                        null),
                "planExecutionContextComposition.workspacePort");
        DefaultPlanExecutionContextComposer composer =
                new DefaultPlanExecutionContextComposer(
                        recovery,
                        contexts,
                        leases,
                        workspace);
        assertValidation(
                () -> composer.compose(null),
                "planExecutionContextComposition.request");
    }

    @Test
    void publicTextAndSanitizedProtocolCauseAreOpaque() {
        var spec = spec("opaque");
        var persisted = persistedContext(spec, FINGERPRINT);
        var verified = verifiedWorkspace(spec, FINGERPRINT);
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
        List<Object> values = List.of(
                new PlanExecutionContextCompositionRequest(
                        PLAN_ID,
                        Optional.of(spec),
                        Optional.of(new PlanExecutionContextLeaseAttempt(
                                "owner-" + SECRET,
                                "token-" + SECRET,
                                Instant.parse("2026-07-25T00:00:00Z")))),
                new PlanExecutionContextLeaseAttempt(
                        "owner-" + SECRET,
                        "token-" + SECRET,
                        Instant.parse("2026-07-25T00:00:00Z")),
                new PlanExecutionContextReady(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        persisted,
                        verified,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                new PlanExecutionContextNotRequired(
                        PLAN_ID,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                new PlanExecutionContextAdvancedUnsupported(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_ADVANCED_STATE,
                                "executionRecovery"),
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                new PlanExecutionContextPersistenceRejected(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        failure,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                new PlanExecutionContextWorkspaceRejected(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .WORKSPACE_INSPECT,
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                new PlanExecutionContextRetryRequired(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        PlanExecutionContextRetryReason
                                .EXECUTION_START_NOT_COMMITTED,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION));
        for (Object value : values) {
            assertFalse(value.toString().contains(SECRET), value::toString);
            assertFalse(
                    value.toString().contains(FINGERPRINT.value()),
                    value::toString);
        }

        IllegalArgumentException nested =
                new IllegalArgumentException("nested-" + SECRET);
        IllegalStateException original =
                new IllegalStateException("message-" + SECRET, nested);
        original.addSuppressed(
                new RuntimeException("suppressed-" + SECRET));
        PlanExecutionContextCompositionProtocolException protocol =
                PlanExecutionContextCompositionValues.protocolFailure(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                        PlanExecutionContextCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "planExecutionContextComposition"
                                + ".leaseAcquireResult",
                        PlanExecutionContextLeaseDisposition
                                .ACQUISITION_INDETERMINATE,
                        original);
        assertNotSame(original, protocol.getCause());
        assertNull(protocol.getCause().getCause());
        assertEquals(0, protocol.getCause().getSuppressed().length);
        assertEquals(0, protocol.getCause().getStackTrace().length);
        protocol.getCause().addSuppressed(
                new IllegalStateException("late-" + SECRET));
        assertEquals(0, protocol.getCause().getSuppressed().length);
        StringWriter trace = new StringWriter();
        protocol.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET));
        assertFalse(protocol.getMessage().contains(SECRET));
        assertFalse(protocol.toString().contains(SECRET));

        assertThrows(
                IllegalArgumentException.class,
                () -> PlanExecutionContextCompositionValues.protocolFailure(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                        PlanExecutionContextCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "adapter." + SECRET,
                        PlanExecutionContextLeaseDisposition
                                .ACQUISITION_INDETERMINATE,
                        original));

        PlanExecutionContextCompositionValidationException validation =
                new PlanExecutionContextCompositionValidationException(
                        PlanExecutionContextCompositionValidationCode
                                .REQUIRED_VALUE_MISSING,
                        "planExecutionContextComposition.request",
                        "caller-message-" + SECRET);
        assertNull(validation.getCause());
        StringWriter validationTrace = new StringWriter();
        validation.printStackTrace(new PrintWriter(validationTrace));
        for (String publicText : List.of(
                validation.getMessage(),
                validation.toString(),
                validationTrace.toString())) {
            assertFalse(publicText.contains(SECRET), publicText);
            assertTrue(
                    publicText.contains(
                            "planExecutionContextComposition.request"),
                    publicText);
        }
    }

    @Test
    void productionFilesAndImportsAreExactlyBounded() throws Exception {
        Path sourceRoot = sourceRoot();
        Set<String> files;
        try (var paths = Files.list(sourceRoot)) {
            files = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
        assertEquals(PRODUCTION_FILES, files);

        for (String file : PRODUCTION_FILES) {
            Path source = sourceRoot.resolve(file);
            for (String line : Files.readAllLines(source)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                assertTrue(
                        trimmed.startsWith("import java.")
                                || trimmed.startsWith(
                                        "import io.paperagent.v2.contracts.")
                                || ALLOWED_PERSISTENCE_IMPORTS.contains(trimmed)
                                || ALLOWED_WORKSPACE_IMPORTS.contains(trimmed),
                        () -> file + " has forbidden import: " + trimmed);
            }
            String text = Files.readString(source);
            for (String forbidden : List.of(
                    "InMemoryPersistence",
                    "InMemoryPlanExecutionContext",
                    "LocalWorkspaceProvider",
                    ".cleanup(",
                    ".release(",
                    ".renew(",
                    ".find(",
                    "Thread.sleep",
                    "System.getenv",
                    "\".env",
                    "PlanAgentService",
                    "Candidate")) {
                assertFalse(
                        text.contains(forbidden),
                        () -> file + " contains forbidden marker: "
                                + forbidden);
            }
        }
    }

    private static void assertRecord(
            Class<?> type,
            List<String> expectedComponents,
            List<Class<?>> canonicalParameterTypes,
            Set<String> extraPublicMethods) {
        assertTrue(type.isRecord(), type::getName);
        assertTrue(Modifier.isPublic(type.getModifiers()), type::getName);
        assertTrue(Modifier.isFinal(type.getModifiers()), type::getName);
        assertEquals(
                expectedComponents,
                Arrays.stream(type.getRecordComponents())
                        .map(component -> component.getName()
                                + ":"
                                + component.getGenericType().getTypeName())
                        .toList());
        assertExactConstructor(
                type,
                true,
                canonicalParameterTypes.toArray(Class<?>[]::new));
        Set<String> expectedMethods = Arrays.stream(type.getRecordComponents())
                .map(component -> methodSignature(
                        component.getName(),
                        component.getType()))
                .collect(Collectors.toSet());
        expectedMethods.add(methodSignature(
                "equals",
                boolean.class,
                Object.class));
        expectedMethods.add(methodSignature("hashCode", int.class));
        expectedMethods.add(methodSignature("toString", String.class));
        expectedMethods.addAll(extraPublicMethods);
        assertEquals(expectedMethods, declaredPublicMethods(type));
        assertNoPublicFields(type);
    }

    private static void assertExactConstructor(
            Class<?> type,
            boolean expectedPublic,
            Class<?>... parameterTypes) {
        List<Constructor<?>> constructors = Arrays.stream(
                        type.getDeclaredConstructors())
                .toList();
        assertEquals(1, constructors.size(), type::getName);
        Constructor<?> constructor = constructors.get(0);
        assertEquals(
                List.of(parameterTypes),
                List.of(constructor.getParameterTypes()),
                type::getName);
        assertEquals(
                expectedPublic,
                Modifier.isPublic(constructor.getModifiers()),
                type::getName);
    }

    private static void assertExactFields(
            Class<?> type,
            List<String> expectedFields) {
        List<Field> fields = Arrays.stream(type.getDeclaredFields())
                .toList();
        assertEquals(
                expectedFields,
                fields.stream()
                        .map(field -> field.getName()
                                + ":"
                                + field.getType().getName())
                        .toList());
        for (Field field : fields) {
            assertTrue(Modifier.isPrivate(field.getModifiers()), type::getName);
            assertTrue(Modifier.isFinal(field.getModifiers()), type::getName);
        }
    }

    private static void assertNoPublicFields(Class<?> type) {
        assertEquals(
                Set.of(),
                Arrays.stream(type.getDeclaredFields())
                        .filter(field -> Modifier.isPublic(
                                field.getModifiers()))
                        .map(Field::getName)
                        .collect(Collectors.toSet()),
                type::getName);
    }

    private static Set<String> declaredPublicMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(PlanExecutionContextCompositionBoundaryTest
                        ::methodSignature)
                .collect(Collectors.toSet());
    }

    private static String methodSignature(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        return name
                + "("
                + Arrays.stream(parameterTypes)
                        .map(Class::getName)
                        .collect(Collectors.joining(","))
                + "):"
                + returnType.getName();
    }

    private static String methodSignature(Method method) {
        return methodSignature(
                method.getName(),
                method.getReturnType(),
                method.getParameterTypes());
    }

    private static void assertEnum(
            Class<? extends Enum<?>> type,
            String... expected) {
        assertTrue(Modifier.isPublic(type.getModifiers()), type::getName);
        assertEquals(
                List.of(expected),
                Arrays.stream(type.getEnumConstants())
                        .map(Enum::name)
                        .toList());
    }

    private static void assertValidation(
            org.junit.jupiter.api.function.Executable executable,
            String path) {
        PlanExecutionContextCompositionValidationException failure =
                assertThrows(
                        PlanExecutionContextCompositionValidationException
                                .class,
                        executable);
        assertEquals(
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                failure.code());
        assertEquals(path, failure.path());
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (ignoredProxy, ignoredMethod, ignoredArguments) -> null));
    }

    private static Path sourceRoot() {
        Path current = Path.of("").toAbsolutePath();
        Path module = Files.isRegularFile(current.resolve("pom.xml"))
                        && current.getFileName().toString()
                                .equals("agent-runtime")
                ? current
                : current.resolve("agent-runtime");
        return module.resolve(Path.of(
                "src",
                "main",
                "java",
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "context",
                "composition"));
    }
}
