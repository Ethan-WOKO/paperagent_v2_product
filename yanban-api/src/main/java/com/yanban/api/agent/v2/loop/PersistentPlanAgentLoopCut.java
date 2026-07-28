package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded authority metadata for the last inspected cut.
 *
 * <p>No TaskFrame content, Step text, event payload, lease material, or
 * provider/tool output crosses this boundary.</p>
 */
public record PersistentPlanAgentLoopCut(
        PersistentPlanAgentLoopCutKind kind,
        Optional<PlanStepId> stepId,
        Optional<PlanRevisionId> revisionId,
        Optional<Long> revisionNumber,
        Optional<Long> checkpointVersion,
        Optional<Long> eventSequence) {

    public PersistentPlanAgentLoopCut {
        Objects.requireNonNull(kind, "kind");
        stepId = Objects.requireNonNull(stepId, "stepId");
        revisionId = Objects.requireNonNull(revisionId, "revisionId");
        revisionNumber = Objects.requireNonNull(
                revisionNumber, "revisionNumber");
        checkpointVersion = Objects.requireNonNull(
                checkpointVersion, "checkpointVersion");
        eventSequence = Objects.requireNonNull(
                eventSequence, "eventSequence");
    }
}
