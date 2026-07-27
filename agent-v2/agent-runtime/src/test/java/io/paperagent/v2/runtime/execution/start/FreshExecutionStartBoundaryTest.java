package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.ExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.FreshExecutionGate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.paperagent.v2.runtime.execution.start.FreshExecutionStartTestFixtures.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshExecutionStartBoundaryTest {
    private static final Set<String> PRODUCTION_FILES = Set.of(
            "DefaultFreshExecutionStarter.java",
            "FreshAtomicExecutionStartRejected.java",
            "FreshExecutionBootstrapRejected.java",
            "FreshExecutionLeaseDisposition.java",
            "FreshExecutionRecoveryRequired.java",
            "FreshExecutionStartAttempt.java",
            "FreshExecutionStartOutcome.java",
            "FreshExecutionStartProtocolCode.java",
            "FreshExecutionStartProtocolException.java",
            "FreshExecutionStartProtocolStage.java",
            "FreshExecutionStartRequest.java",
            "FreshExecutionStartValidationCode.java",
            "FreshExecutionStartValidationException.java",
            "FreshExecutionStarter.java",
            "FreshExecutionStarted.java",
            "FreshExecutionStartValues.java",
            "FreshLeaseAcquisitionRejected.java");
    private static final Set<String> ALLOWED_JDK_IMPORTS = Set.of(
            "import java.time.Instant;",
            "import java.util.Optional;");
    private static final Set<String> ALLOWED_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.ExecutionStartRepository;",
            "import io.paperagent.v2.persistence.ExecutionStartRequest;",
            "import io.paperagent.v2.persistence.LeaseRecord;",
            "import io.paperagent.v2.persistence.LeaseRepository;",
            "import io.paperagent.v2.persistence.PersistedExecutionStart;",
            "import io.paperagent.v2.persistence.PersistedPlanBootstrap;",
            "import io.paperagent.v2.persistence.PersistenceFailure;",
            "import io.paperagent.v2.persistence.PersistenceOutcome;",
            "import io.paperagent.v2.persistence.PersistenceResult;");
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "import static ",
            "import java.*",
            "import io.paperagent.v2.persistence.*",
            "InMemoryPersistence",
            "PlanRepository",
            "CheckpointRepository",
            "EventRepository",
            "PersistenceErrorCode",
            ".success" + "ful(",
            ".find(",
            ".renew(",
            ".release(",
            "Instant.now",
            "Clock.",
            "UUID",
            "random(",
            "Thread.",
            "sleep(",
            "System.getenv",
            "System.getProperty",
            "java.net.",
            "java.io.",
            "java.nio.file.",
            "ProcessBuilder",
            "io.paperagent.v2.workspace",
            "io.paperagent.v2.sandbox",
            "io.paperagent.v2.providers",
            "io.paperagent.v2.runtime.recovery",
            "StepAgent",
            "StepLoop",
            "paperagent.v1",
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "Candidate");

    @Test
    void productionFilesAndImportsAreExactAndFailClosed() throws Exception {
        Path sourceRoot = sourceRoot();
        Set<String> files;
        try (var paths = Files.list(sourceRoot)) {
            files = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
        assertEquals(PRODUCTION_FILES, files);

        for (String name : files) {
            Path sourcePath = sourceRoot.resolve(name);
            String source = Files.readString(sourcePath);
            for (String line : Files.readAllLines(sourcePath)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import java.")) {
                    assertTrue(
                            ALLOWED_JDK_IMPORTS.contains(trimmed),
                            () -> sourcePath
                                    + " has non-allowlisted JDK import "
                                    + trimmed);
                }
                if (trimmed.startsWith(
                        "import io.paperagent.v2.persistence.")) {
                    assertTrue(
                            ALLOWED_PERSISTENCE_IMPORTS.contains(trimmed),
                            () -> sourcePath
                                    + " has non-allowlisted persistence import "
                                    + trimmed);
                }
            }
            for (String marker : FORBIDDEN_MARKERS) {
                assertFalse(
                        source.contains(marker),
                        () -> sourcePath + " contains forbidden marker "
                                + marker);
            }
        }
    }

    @Test
    void publicSurfaceAndSealedOutcomesAreFrozen() throws Exception {
        assertEquals(
                Set.of(
                        FreshExecutionStarted.class,
                        FreshExecutionRecoveryRequired.class,
                        FreshExecutionBootstrapRejected.class,
                        FreshLeaseAcquisitionRejected.class,
                        FreshAtomicExecutionStartRejected.class),
                Set.of(FreshExecutionStartOutcome.class
                        .getPermittedSubclasses()));
        assertEquals(
                List.of(
                        "leaseOwnerId:String",
                        "leaseToken:String",
                        "leaseExpiresAt:Instant",
                        "eventDraft:ExecutionStartEventDraft",
                        "checkpointCreatedAt:Instant"),
                components(FreshExecutionStartAttempt.class));
        assertEquals(
                List.of(
                        "bootstrapResult:PersistenceResult",
                        "attempt:Optional"),
                components(FreshExecutionStartRequest.class));
        assertEquals(
                List.of(
                        "startOutcome:PersistenceOutcome",
                        "persistedStart:PersistedExecutionStart"),
                components(FreshExecutionStarted.class));
        assertEquals(
                List.of("planId:PlanId"),
                components(FreshExecutionRecoveryRequired.class));
        assertEquals(
                List.of("failure:PersistenceFailure"),
                components(FreshExecutionBootstrapRejected.class));
        assertEquals(
                List.of("planId:PlanId", "failure:PersistenceFailure"),
                components(FreshLeaseAcquisitionRejected.class));
        assertEquals(
                List.of(
                        "planId:PlanId",
                        "failure:PersistenceFailure",
                        "leaseDisposition:FreshExecutionLeaseDisposition"),
                components(FreshAtomicExecutionStartRejected.class));

        assertTrue(Modifier.isPublic(
                DefaultFreshExecutionStarter.class
                        .getDeclaredConstructor(
                                FreshExecutionGate.class,
                                ExecutionStartMaterializer.class,
                                LeaseRepository.class,
                                ExecutionStartRepository.class)
                        .getModifiers()));
        assertEquals(
                FreshExecutionStartOutcome.class,
                FreshExecutionStarter.class
                        .getDeclaredMethod(
                                "start",
                                FreshExecutionStartRequest.class)
                        .getReturnType());
        assertFalse(Modifier.isPublic(
                FreshExecutionStartValues.class.getModifiers()));
    }

    @Test
    void exceptionSurfacesAreInspectableButConstructionIsPackageOwned()
            throws Exception {
        var validationConstructor =
                FreshExecutionStartValidationException.class
                        .getDeclaredConstructor(
                                FreshExecutionStartValidationCode.class,
                                String.class,
                                String.class);
        assertFalse(Modifier.isPublic(
                validationConstructor.getModifiers()));
        assertEquals(
                FreshExecutionStartValidationCode.class,
                FreshExecutionStartValidationException.class
                        .getDeclaredMethod("code")
                        .getReturnType());
        assertEquals(
                String.class,
                FreshExecutionStartValidationException.class
                        .getDeclaredMethod("path")
                        .getReturnType());

        var protocolConstructor =
                FreshExecutionStartProtocolException.class
                        .getDeclaredConstructor(
                                PlanId.class,
                                FreshExecutionStartProtocolStage.class,
                                FreshExecutionStartProtocolCode.class,
                                String.class,
                                FreshExecutionLeaseDisposition.class,
                                Throwable.class);
        assertFalse(Modifier.isPublic(
                protocolConstructor.getModifiers()));
        assertEquals(
                PlanId.class,
                FreshExecutionStartProtocolException.class
                        .getDeclaredMethod("planId")
                        .getReturnType());
        assertEquals(
                FreshExecutionStartProtocolStage.class,
                FreshExecutionStartProtocolException.class
                        .getDeclaredMethod("stage")
                        .getReturnType());
        assertEquals(
                FreshExecutionStartProtocolCode.class,
                FreshExecutionStartProtocolException.class
                        .getDeclaredMethod("code")
                        .getReturnType());
        assertEquals(
                String.class,
                FreshExecutionStartProtocolException.class
                        .getDeclaredMethod("path")
                        .getReturnType());
        assertEquals(
                FreshExecutionLeaseDisposition.class,
                FreshExecutionStartProtocolException.class
                        .getDeclaredMethod("leaseDisposition")
                        .getReturnType());
    }

    @Test
    void outputAndExceptionTextNeverContainsLeaseToken() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("boundary");
        FreshExecutionStartAttempt attempt =
                FreshExecutionStartTestFixtures.attempt("boundary");
        var baseDraft = attempt.eventDraft();
        var tokenDraft =
                new io.paperagent.v2.runtime.execution
                        .ExecutionStartEventDraft(
                        baseDraft.id(),
                        baseDraft.occurredAt(),
                        baseDraft.type(),
                        baseDraft.causationId(),
                        baseDraft.correlationId(),
                        new InlineEventPayload(new ObjectValue(Map.of(
                                "sensitive",
                                new TextValue(TOKEN)))));
        FreshExecutionStartAttempt sensitiveAttempt =
                new FreshExecutionStartAttempt(
                        attempt.leaseOwnerId(),
                        attempt.leaseToken(),
                        attempt.leaseExpiresAt(),
                        tokenDraft,
                        attempt.checkpointCreatedAt());
        var materialized = FreshExecutionStartTestFixtures.materialized(
                bootstrap,
                sensitiveAttempt);
        LeaseRecord lease = FreshExecutionStartTestFixtures.lease(
                bootstrap,
                sensitiveAttempt,
                1);
        PersistedExecutionStart persisted =
                FreshExecutionStartTestFixtures.persisted(
                        bootstrap,
                        lease,
                        materialized);
        PersistenceFailure persistenceFailure =
                new PersistenceFailure(
                        io.paperagent.v2.persistence.PersistenceErrorCode
                                .CONFLICTING_REPLAY,
                        TOKEN);
        PlanId sensitivePlanId = new PlanId(TOKEN);
        List<Object> outputs = List.of(
                new FreshExecutionStarted(
                        PersistenceOutcome.APPLIED,
                        persisted),
                new FreshExecutionRecoveryRequired(
                        sensitivePlanId),
                new FreshExecutionBootstrapRejected(persistenceFailure),
                new FreshLeaseAcquisitionRejected(
                        sensitivePlanId,
                        persistenceFailure),
                new FreshAtomicExecutionStartRejected(
                        sensitivePlanId,
                        persistenceFailure,
                        FreshExecutionLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        for (Object output : outputs) {
            assertFalse(output.toString().contains(TOKEN));
            if (output.getClass().isRecord()) {
                for (var component
                        : output.getClass().getRecordComponents()) {
                    assertFalse(Set.of(
                            FreshExecutionStartAttempt.class,
                            LeaseRecord.class,
                            ExecutionStartRequest.class)
                            .contains(component.getType()));
                }
            }
        }
        assertEquals(
                "FreshExecutionStarted[startOutcome=APPLIED, "
                        + "persistedStart=<provided>]",
                outputs.get(0).toString());
        assertEquals(
                "FreshExecutionRecoveryRequired[planId=<provided>]",
                outputs.get(1).toString());
        assertEquals(
                "FreshExecutionBootstrapRejected[failure=<provided>]",
                outputs.get(2).toString());
        assertEquals(
                "FreshLeaseAcquisitionRejected[planId=<provided>, "
                        + "failure=<provided>]",
                outputs.get(3).toString());
        assertEquals(
                "FreshAtomicExecutionStartRejected[planId=<provided>, "
                        + "failure=<provided>, leaseDisposition="
                        + "RETAINED_FOR_RECOVERY]",
                outputs.get(4).toString());
        assertSame(
                persistenceFailure,
                ((FreshExecutionBootstrapRejected) outputs.get(2))
                        .failure());
        assertSame(
                persistenceFailure,
                ((FreshLeaseAcquisitionRejected) outputs.get(3))
                        .failure());
        assertSame(
                persistenceFailure,
                ((FreshAtomicExecutionStartRejected) outputs.get(4))
                        .failure());
        assertEquals(
                sensitivePlanId,
                ((FreshExecutionRecoveryRequired) outputs.get(1))
                        .planId());
        assertTrue(attempt.toString().contains("<redacted>"));
        assertFalse(attempt.toString().contains(TOKEN));

        IllegalArgumentException nested =
                new IllegalArgumentException("nested contains " + TOKEN);
        nested.addSuppressed(
                new IllegalStateException("nested suppressed " + TOKEN));
        IllegalStateException cause =
                new IllegalStateException(
                        "cause contains " + TOKEN,
                        nested);
        cause.addSuppressed(
                new IllegalArgumentException("suppressed contains " + TOKEN));
        FreshExecutionStartProtocolException protocol =
                new FreshExecutionStartProtocolException(
                        bootstrap.plan().id(),
                        FreshExecutionStartProtocolStage.ATOMIC_START,
                        FreshExecutionStartProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "freshExecutionStart.executionStartResult",
                        FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                        cause);
        assertNotSame(cause, protocol.getCause());
        assertThrowableTreeSafe(protocol);
        assertFalse(protocol.getMessage().contains(TOKEN));
        assertFalse(protocol.toString().contains(TOKEN));
        assertNull(protocol.getCause().getCause());
        assertEquals(0, protocol.getCause().getSuppressed().length);
        protocol.getCause().addSuppressed(
                new IllegalStateException("late suppressed " + TOKEN));
        assertEquals(0, protocol.getCause().getSuppressed().length);
        StringWriter trace = new StringWriter();
        protocol.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(TOKEN));

        FreshExecutionStartProtocolException distinctType =
                new FreshExecutionStartProtocolException(
                        bootstrap.plan().id(),
                        FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                        FreshExecutionStartProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "freshExecutionStart.leaseAcquireResult",
                        FreshExecutionLeaseDisposition
                                .ACQUISITION_INDETERMINATE,
                        new IllegalArgumentException(
                                "different type contains " + TOKEN));
        assertNotEquals(
                protocol.getCause().getMessage(),
                distinctType.getCause().getMessage());
        assertTrue(protocol.getCause().getMessage().contains(
                IllegalStateException.class.getName()));
        assertTrue(distinctType.getCause().getMessage().contains(
                IllegalArgumentException.class.getName()));
        assertThrowableTreeSafe(distinctType);

        FreshExecutionStartValidationException validation =
                new FreshExecutionStartValidationException(
                        FreshExecutionStartValidationCode
                                .REQUIRED_VALUE_MISSING,
                        "freshExecutionStart.request",
                        "fresh execution start request is required");
        assertFalse(validation.getMessage().contains(TOKEN));
        assertFalse(validation.toString().contains(TOKEN));
    }

    @Test
    void persistenceAllowlistIsTheDedicatedNineTypes() {
        assertEquals(
                Set.of(
                        "import io.paperagent.v2.persistence"
                                + ".ExecutionStartRepository;",
                        "import io.paperagent.v2.persistence"
                                + ".ExecutionStartRequest;",
                        "import io.paperagent.v2.persistence.LeaseRecord;",
                        "import io.paperagent.v2.persistence.LeaseRepository;",
                        "import io.paperagent.v2.persistence"
                                + ".PersistedExecutionStart;",
                        "import io.paperagent.v2.persistence"
                                + ".PersistedPlanBootstrap;",
                        "import io.paperagent.v2.persistence"
                                + ".PersistenceFailure;",
                        "import io.paperagent.v2.persistence"
                                + ".PersistenceOutcome;",
                        "import io.paperagent.v2.persistence"
                                + ".PersistenceResult;"),
                ALLOWED_PERSISTENCE_IMPORTS);
        assertEquals(Optional.class,
                FreshExecutionStartRequest.class
                        .getRecordComponents()[1].getType());
        assertEquals(Instant.class,
                FreshExecutionStartAttempt.class
                        .getRecordComponents()[2].getType());
    }

    private static List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName()
                        + ":"
                        + component.getType().getSimpleName())
                .toList();
    }

    private static void assertThrowableTreeSafe(Throwable throwable) {
        assertFalse(throwable.toString().contains(TOKEN));
        if (throwable.getMessage() != null) {
            assertFalse(throwable.getMessage().contains(TOKEN));
        }
        if (throwable.getCause() != null) {
            assertThrowableTreeSafe(throwable.getCause());
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            assertThrowableTreeSafe(suppressed);
        }
    }

    private static Path sourceRoot() {
        Path current = Path.of("").toAbsolutePath();
        Path module = Files.isRegularFile(current.resolve("pom.xml"))
                && current.getFileName().toString().equals("agent-runtime")
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
                "start"));
    }
}
