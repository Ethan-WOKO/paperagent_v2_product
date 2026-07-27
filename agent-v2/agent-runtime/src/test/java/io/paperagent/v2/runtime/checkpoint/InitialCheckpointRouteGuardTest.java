package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialCheckpointRouteGuardTest {
    @Test
    void directRouteWinsBeforeFactAndTaskFramePlanCanonicalFailures() {
        TaskFrame planTaskFrame =
                InitialCheckpointTestFixtures.taskFrame(
                        "task-checkpoint-direct-plan");
        TaskFrame mismatchedRequestTaskFrame =
                InitialCheckpointTestFixtures.taskFrame(
                        "task-checkpoint-direct-request");
        Plan plan =
                InitialCheckpointTestFixtures.planWithLatestCompletedFact(
                        planTaskFrame);
        InitialCheckpointFreezeRequest request =
                InitialCheckpointTestFixtures.request(
                        InitialCheckpointTestFixtures.directDecision(
                                "routing-checkpoint-direct"),
                        mismatchedRequestTaskFrame,
                        plan,
                        InitialCheckpointTestFixtures.CREATED_AT);

        InitialCheckpointFreezeValidationException exception = assertThrows(
                InitialCheckpointFreezeValidationException.class,
                () -> new DeterministicInitialCheckpointFreezer()
                        .freeze(request));

        assertEquals(
                InitialCheckpointFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                exception.code());
        assertEquals(
                "initialCheckpointFreezeRequest.routingDecision.route",
                exception.path());
    }
}
