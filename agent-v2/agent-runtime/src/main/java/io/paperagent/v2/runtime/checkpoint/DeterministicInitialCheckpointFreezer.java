package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes caller authority into a canonical no-execution Checkpoint without
 * reading ambient state.
 */
public final class DeterministicInitialCheckpointFreezer
        implements InitialCheckpointFreezer {
    @Override
    public Checkpoint freeze(InitialCheckpointFreezeRequest request) {
        InitialCheckpointFreezeValues.required(
                request,
                "initialCheckpointFreezeRequest");
        if (request.routingDecision().route() != Route.PERSISTENT_PLAN_EXECUTE) {
            InitialCheckpointFreezeValues.fail(
                    InitialCheckpointFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                    "initialCheckpointFreezeRequest.routingDecision.route",
                    "initial Checkpoint freezing requires a persistent route");
        }

        PlanRevision latestRevision = request.plan().latestRevision();
        Map<PlanStepId, StepExecutionState> stepStates = new LinkedHashMap<>();
        for (PlanStep step : latestRevision.steps()) {
            stepStates.put(step.id(), StepExecutionState.NOT_STARTED);
        }
        Checkpoint checkpoint = new Checkpoint(
                request.taskFrame().id(),
                request.plan().id(),
                latestRevision.id(),
                latestRevision.number(),
                0,
                PlanExecutionState.NOT_STARTED,
                stepStates,
                List.of(),
                request.createdAt());
        CheckpointValidators.requireValid(
                checkpoint,
                request.taskFrame(),
                request.plan(),
                null);
        return checkpoint;
    }
}
