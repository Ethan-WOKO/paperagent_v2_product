package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRepository;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveStepCompletionCompositionBoundaryTest {
    @Test
    void publicComposerConsumesOnlyTheFrozenMaterializationRequest() {
        assertEquals(
                ActiveStepCompletionCompositionOutcome.class,
                ActiveStepCompletionComposer.class
                        .getDeclaredMethods()[0].getReturnType());
        assertEquals(
                ActiveStepCompletionMaterializationRequest.class,
                ActiveStepCompletionComposer.class
                        .getDeclaredMethods()[0].getParameterTypes()[0]);
    }

    @Test
    void implementationHasExactlyTheTwoFrozenCollaborators() {
        assertTrue(Modifier.isFinal(
                DefaultActiveStepCompletionComposer.class.getModifiers()));
        Set<Class<?>> fieldTypes = Arrays.stream(
                        DefaultActiveStepCompletionComposer.class
                                .getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                Set.of(
                        ActiveStepCompletionMaterializer.class,
                        StepCompletionRepository.class),
                fieldTypes);
    }

    @Test
    void outcomeSurfaceIsClosedAndAlwaysRetainsRecoveryLease() {
        assertTrue(ActiveStepCompletionCompositionOutcome.class.isSealed());
        assertEquals(
                Set.of(
                        ActiveStepCompletionCommitted.class,
                        ActiveStepCompletionPersistenceRejected.class),
                Set.of(ActiveStepCompletionCompositionOutcome.class
                        .getPermittedSubclasses()));
        assertEquals(
                Set.of(ActiveStepCompletionLeaseDisposition
                        .RETAINED_FOR_RECOVERY),
                Set.of(ActiveStepCompletionLeaseDisposition.values()));
    }

    @Test
    void compositionSurfaceHasNoProductOrSideEffectType() {
        Set<String> forbidden = Set.of(
                "com." + "yanban",
                "org.springframework",
                "jakarta.persistence",
                "java.io",
                "java.net",
                "io.paperagent.v2.workspace",
                "io.paperagent.v2.providers",
                "io.paperagent.v2.sandbox");
        Class<?>[] types = {
            ActiveStepCompletionComposer.class,
            DefaultActiveStepCompletionComposer.class,
            ActiveStepCompletionCompositionOutcome.class,
            ActiveStepCompletionCommitted.class,
            ActiveStepCompletionPersistenceRejected.class,
            ActiveStepCompletionCompositionProtocolException.class
        };
        for (Class<?> type : types) {
            for (var field : type.getDeclaredFields()) {
                assertFalse(forbidden.stream().anyMatch(
                        prefix -> field.getType().getName()
                                .startsWith(prefix)));
            }
        }
    }

    @Test
    void stablePersistenceResultRejectsMissingOrContradictoryFields() {
        assertFalse(PersistenceResult.rejected(
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion").value().isPresent());
        assertTrue(PersistenceResult.rejected(
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion").failure().isPresent());
        assertEquals(
                3,
                java.util.List.<Runnable>of(
                        () -> new PersistenceResult<>(
                                PersistenceOutcome.APPLIED,
                                Optional.empty(),
                                Optional.empty()),
                        () -> new PersistenceResult<>(
                                PersistenceOutcome.APPLIED,
                                Optional.of("value"),
                                Optional.of(new PersistenceFailure(
                                        PersistenceErrorCode.STALE_VERSION,
                                        "request.expectedCheckpointVersion"))),
                        () -> new PersistenceResult<>(
                                PersistenceOutcome.REJECTED,
                                Optional.of("value"),
                                Optional.empty()))
                        .stream()
                        .filter(action -> {
                            try {
                                action.run();
                                return false;
                            } catch (IllegalArgumentException expected) {
                                return true;
                            }
                        })
                        .count());
    }
}
