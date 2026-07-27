package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrame;

/**
 * Freezes caller-owned authority and a structured draft into the canonical
 * TaskFrame contract without reading ambient state.
 */
public final class DeterministicTaskFrameFreezer implements TaskFrameFreezer {
    @Override
    public TaskFrame freeze(TaskFrameFreezeRequest request) {
        TaskFrameFreezeValues.required(request, "taskFrameFreezeRequest");
        if (request.routingDecision().route() != Route.PERSISTENT_PLAN_EXECUTE) {
            TaskFrameFreezeValues.fail(
                    TaskFrameFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                    "taskFrameFreezeRequest.routingDecision.route",
                    "TaskFrame freezing requires a persistent route");
        }

        TaskFrameDraft draft = request.draft();
        return new TaskFrame(
                request.taskFrameId(),
                draft.objective(),
                draft.targets(),
                draft.deliverables(),
                draft.constraints(),
                request.sourceProjectVersion(),
                request.executionProfile(),
                request.createdAt());
    }
}
