package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.persistence.StepCompletionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveStepCompletionMaterializerBoundaryTest {
    @Test
    void publicBoundaryReturnsExactlyOneStableCompletionRequest() {
        assertEquals(
                StepCompletionRequest.class,
                ActiveStepCompletionMaterializer.class
                        .getDeclaredMethods()[0]
                        .getReturnType());
        assertTrue(Modifier.isFinal(
                DeterministicActiveStepCompletionMaterializer.class
                        .getModifiers()));
    }

    @Test
    void inputCannotSupplyRecoveredAuthorityOrDerivedFields() {
        Set<String> components = Arrays.stream(
                        ActiveStepCompletionMaterializationRequest.class
                                .getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        "recoveredActiveStep",
                        "completionFactDraft",
                        "eventDraft",
                        "revisionDraft",
                        "checkpointCreatedAt"),
                components);
        for (String forbidden : Set.of(
                "planId",
                "taskFrame",
                "stepId",
                "leaseToken",
                "fencingToken",
                "revisionNumber",
                "parentRevisionId",
                "checkpointVersion",
                "eventSequence",
                "planState",
                "stepStates")) {
            assertFalse(components.contains(forbidden));
        }
    }

    @Test
    void callerDraftsExposeOnlyAttemptOwnedData() {
        assertEquals(
                Set.of("outcomeHash", "completedAt", "receiptReferences"),
                componentNames(ActiveStepCompletionFactDraft.class));
        assertEquals(
                Set.of(
                        "id",
                        "occurredAt",
                        "type",
                        "causationId",
                        "correlationId",
                        "payload"),
                componentNames(ActiveStepCompletionEventDraft.class));
        assertEquals(
                Set.of("id", "reason", "createdAt"),
                componentNames(ActiveStepCompletionRevisionDraft.class));
    }

    @Test
    void deterministicImplementationHasNoCollaboratorOrSideEffectPort() {
        assertEquals(
                0,
                DeterministicActiveStepCompletionMaterializer.class
                        .getDeclaredFields()
                        .length);
        assertEquals(
                1,
                DeterministicActiveStepCompletionMaterializer.class
                        .getConstructors()
                        .length);
        assertEquals(
                0,
                DeterministicActiveStepCompletionMaterializer.class
                        .getConstructors()[0]
                        .getParameterCount());
    }

    @Test
    void packageSurfaceHasNoProductRepositoryFileNetworkOrClockTypes() {
        Set<String> forbiddenPrefixes = Set.of(
                "com." + "yanban",
                "java.io",
                "java.net",
                "java.nio.file",
                "org.springframework",
                "jakarta.persistence");
        Class<?>[] surface = {
            ActiveStepCompletionMaterializer.class,
            DeterministicActiveStepCompletionMaterializer.class,
            ActiveStepCompletionMaterializationRequest.class,
            ActiveStepCompletionFactDraft.class,
            ActiveStepCompletionEventDraft.class,
            ActiveStepCompletionRevisionDraft.class
        };
        for (Class<?> type : surface) {
            for (var field : type.getDeclaredFields()) {
                assertTrue(forbiddenPrefixes.stream().noneMatch(
                        prefix -> field.getType().getName()
                                .startsWith(prefix)));
            }
            for (var method : type.getDeclaredMethods()) {
                assertTrue(forbiddenPrefixes.stream().noneMatch(
                        prefix -> method.getReturnType().getName()
                                .startsWith(prefix)));
                assertTrue(Arrays.stream(method.getParameterTypes())
                        .noneMatch(parameter -> forbiddenPrefixes.stream()
                                .anyMatch(prefix -> parameter.getName()
                                        .startsWith(prefix))));
            }
        }
    }

    private static Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
    }
}
