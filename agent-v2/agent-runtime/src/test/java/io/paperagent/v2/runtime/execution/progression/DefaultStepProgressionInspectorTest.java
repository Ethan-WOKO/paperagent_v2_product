package io.paperagent.v2.runtime.execution.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStepProgressionInspectorTest {
    @Test
    void preservesTypedRepositoryRejectionWithoutMutation() {
        PersistenceResult<StepRecoverySnapshot> expected =
                PersistenceResult.rejected(
                PersistenceErrorCode.NOT_FOUND, "planId");
        StepProgressionInspector inspector =
                new DefaultStepProgressionInspector(planId -> expected);

        assertEquals(expected, inspector.inspect(new PlanId("plan-a")));
    }

    @Test
    void sanitizesCollaboratorFailureWithoutRetainingItsCauseOrToken() {
        StepProgressionInspector inspector =
                new DefaultStepProgressionInspector(planId -> {
                    throw new IllegalStateException("secret-token");
                });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> inspector.inspect(new PlanId("plan-a")));
        assertEquals("progression inspection failed", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains("secret-token"));
    }

    @Test
    void runtimeProgressionSurfaceDependsOnlyOnInwardStableTypes() {
        assertTrue(Modifier.isFinal(
                DefaultStepProgressionInspector.class.getModifiers()));
        assertEquals(
                Set.of(StepRecoveryRepository.class),
                Arrays.stream(DefaultStepProgressionInspector.class
                                .getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(
                                field.getModifiers()))
                        .map(field -> field.getType())
                        .collect(java.util.stream.Collectors.toSet()));
        Set<String> forbidden = Set.of(
                "com." + "yanban",
                "org.springframework",
                "jakarta.",
                "java.io",
                "java.net",
                "io.paperagent.v1",
                "io.paperagent.v2.providers",
                "io.paperagent.v2.sandbox",
                "io.paperagent.v2.workspace");
        for (Class<?> type : Set.of(
                StepProgressionInspector.class,
                DefaultStepProgressionInspector.class)) {
            for (var field : type.getDeclaredFields()) {
                assertFalse(forbidden.stream().anyMatch(
                        prefix -> field.getType().getName()
                                .startsWith(prefix)));
            }
            for (var method : type.getDeclaredMethods()) {
                assertFalse(forbidden.stream().anyMatch(
                        prefix -> method.getReturnType().getName()
                                .startsWith(prefix)));
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(forbidden.stream().anyMatch(
                            prefix -> parameter.getName()
                                    .startsWith(prefix)));
                }
            }
        }
    }
}
