package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepPauseRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveStepInterruptionMaterializerBoundaryTest {
    @Test
    void publicSurfaceExposesExactlyThreeStableTypedVariants() {
        assertTrue(MaterializedActiveStepInterruption.class.isSealed());
        assertEquals(
                Set.of(
                        MaterializedStepPause.class,
                        MaterializedStepFailure.class,
                        MaterializedStepCancellation.class),
                Set.of(MaterializedActiveStepInterruption.class
                        .getPermittedSubclasses()));
        assertEquals(
                StepPauseRequest.class,
                MaterializedStepPause.class.getRecordComponents()[0].getType());
        assertEquals(
                StepFailRequest.class,
                MaterializedStepFailure.class.getRecordComponents()[0].getType());
        assertEquals(
                StepCancelRequest.class,
                MaterializedStepCancellation.class
                        .getRecordComponents()[0]
                        .getType());
    }

    @Test
    void requestCannotSupplyAnyRecoveredAuthorityComponent() {
        Set<String> components = Arrays.stream(
                        ActiveStepInterruptionMaterializationRequest.class
                                .getRecordComponents())
                .map(component -> component.getName())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                Set.of(
                        "recoveredActiveStep",
                        "kind",
                        "eventDraft",
                        "checkpointCreatedAt"),
                components);
        assertFalse(components.contains("planId"));
        assertFalse(components.contains("stepId"));
        assertFalse(components.contains("leaseToken"));
        assertFalse(components.contains("fencingToken"));
        assertFalse(components.contains("checkpointVersion"));
        assertFalse(components.contains("eventSequence"));
    }

    @Test
    void materializerHasNoCollaboratorOrSideEffectPort() {
        assertTrue(Modifier.isFinal(
                DeterministicActiveStepInterruptionMaterializer.class
                        .getModifiers()));
        assertEquals(
                3,
                DeterministicActiveStepInterruptionMaterializer.class
                        .getDeclaredFields()
                        .length);
        assertTrue(Arrays.stream(
                        DeterministicActiveStepInterruptionMaterializer.class
                                .getDeclaredFields())
                .allMatch(field -> Modifier.isStatic(field.getModifiers())
                        && field.getType() == long.class));
    }

    @Test
    void packageDependsOnlyOnV2ContractsPersistenceAndRecoveryTypes() {
        Set<String> forbiddenPrefixes = Set.of(
                "com.yanban",
                "java.io",
                "java.net",
                "org.springframework",
                "jakarta.persistence");
        Class<?>[] surface = {
            ActiveStepInterruptionMaterializer.class,
            DeterministicActiveStepInterruptionMaterializer.class,
            ActiveStepInterruptionMaterializationRequest.class,
            ActiveStepInterruptionEventDraft.class,
            MaterializedActiveStepInterruption.class,
            MaterializedStepPause.class,
            MaterializedStepFailure.class,
            MaterializedStepCancellation.class
        };
        for (Class<?> type : surface) {
            for (var field : type.getDeclaredFields()) {
                assertTrue(forbiddenPrefixes.stream().noneMatch(
                        prefix -> field.getType().getName().startsWith(prefix)));
            }
            for (var method : type.getDeclaredMethods()) {
                assertTrue(forbiddenPrefixes.stream().noneMatch(
                        prefix -> method.getReturnType().getName()
                                .startsWith(prefix)));
            }
        }
    }
}
