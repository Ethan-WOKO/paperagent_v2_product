package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialCheckpointValidationTest {
    @Test
    void validationCodesContainExactlyTheFrozenValues() {
        assertEquals(
                Set.of(
                        InitialCheckpointFreezeValidationCode
                                .REQUIRED_VALUE_MISSING,
                        InitialCheckpointFreezeValidationCode
                                .ROUTE_NOT_PERSISTENT),
                Set.of(InitialCheckpointFreezeValidationCode.values()));
    }

    @Test
    void requestRejectsEveryMissingFieldWithStablePaths() {
        RoutingDecision routingDecision =
                InitialCheckpointTestFixtures.declaredRequirementDecision(
                        "routing-checkpoint-validation");
        TaskFrame taskFrame =
                InitialCheckpointTestFixtures.taskFrame(
                        "task-checkpoint-validation");
        Plan plan = InitialCheckpointTestFixtures.standardPlan(taskFrame);
        Instant createdAt = InitialCheckpointTestFixtures.CREATED_AT;

        assertViolation(
                "initialCheckpointFreezeRequest.routingDecision",
                () -> new InitialCheckpointFreezeRequest(
                        null,
                        taskFrame,
                        plan,
                        createdAt));
        assertViolation(
                "initialCheckpointFreezeRequest.taskFrame",
                () -> new InitialCheckpointFreezeRequest(
                        routingDecision,
                        null,
                        plan,
                        createdAt));
        assertViolation(
                "initialCheckpointFreezeRequest.plan",
                () -> new InitialCheckpointFreezeRequest(
                        routingDecision,
                        taskFrame,
                        null,
                        createdAt));
        assertViolation(
                "initialCheckpointFreezeRequest.createdAt",
                () -> new InitialCheckpointFreezeRequest(
                        routingDecision,
                        taskFrame,
                        plan,
                        null));
    }

    @Test
    void freezerRejectsMissingTopLevelRequestWithStablePath() {
        assertViolation(
                "initialCheckpointFreezeRequest",
                () -> new DeterministicInitialCheckpointFreezer().freeze(null));
    }

    private static void assertViolation(String expectedPath, Runnable action) {
        InitialCheckpointFreezeValidationException exception = assertThrows(
                InitialCheckpointFreezeValidationException.class,
                action::run);
        assertEquals(
                InitialCheckpointFreezeValidationCode.REQUIRED_VALUE_MISSING,
                exception.code());
        assertEquals(expectedPath, exception.path());
    }
}
