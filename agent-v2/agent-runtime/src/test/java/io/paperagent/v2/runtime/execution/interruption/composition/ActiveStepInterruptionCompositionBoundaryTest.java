package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveStepInterruptionCompositionBoundaryTest {
    @Test
    void publicComposerConsumesOnlyTheFrozenMaterializationRequest() {
        assertEquals(
                ActiveStepInterruptionCompositionOutcome.class,
                ActiveStepInterruptionComposer.class
                        .getDeclaredMethods()[0].getReturnType());
        assertEquals(
                ActiveStepInterruptionMaterializationRequest.class,
                ActiveStepInterruptionComposer.class
                        .getDeclaredMethods()[0].getParameterTypes()[0]);
    }

    @Test
    void implementationHasExactlyTheTwoFrozenCollaborators() {
        assertTrue(Modifier.isFinal(
                DefaultActiveStepInterruptionComposer.class.getModifiers()));
        Set<Class<?>> fieldTypes = Arrays.stream(
                        DefaultActiveStepInterruptionComposer.class
                                .getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                Set.of(
                        ActiveStepInterruptionMaterializer.class,
                        StepInterruptionRepository.class),
                fieldTypes);
    }

    @Test
    void outcomeSurfaceIsClosedAndAlwaysCarriesLeaseDisposition() {
        assertTrue(ActiveStepInterruptionCompositionOutcome.class.isSealed());
        assertEquals(
                Set.of(
                        ActiveStepInterruptionCommitted.class,
                        ActiveStepInterruptionPersistenceRejected.class),
                Set.of(ActiveStepInterruptionCompositionOutcome.class
                        .getPermittedSubclasses()));
        assertEquals(
                Set.of(ActiveStepInterruptionLeaseDisposition
                        .RETAINED_FOR_RECOVERY),
                Set.of(ActiveStepInterruptionLeaseDisposition.values()));
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
            ActiveStepInterruptionComposer.class,
            DefaultActiveStepInterruptionComposer.class,
            ActiveStepInterruptionCompositionOutcome.class,
            ActiveStepInterruptionCommitted.class,
            ActiveStepInterruptionPersistenceRejected.class,
            ActiveStepInterruptionCompositionProtocolException.class
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
