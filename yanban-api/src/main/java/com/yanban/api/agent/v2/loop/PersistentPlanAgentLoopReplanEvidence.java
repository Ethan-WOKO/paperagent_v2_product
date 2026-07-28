package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;

import java.util.Objects;

/** Opaque identifiers and monotonic versions for one committed replan. */
public record PersistentPlanAgentLoopReplanEvidence(
        PlanStepId supersededStepId,
        EventId supersessionEventId,
        EventId replanEventId,
        PlanRevisionId replannedRevisionId,
        long supersededCheckpointVersion,
        long replannedCheckpointVersion) {

    public PersistentPlanAgentLoopReplanEvidence {
        Objects.requireNonNull(supersededStepId, "supersededStepId");
        Objects.requireNonNull(
                supersessionEventId, "supersessionEventId");
        Objects.requireNonNull(replanEventId, "replanEventId");
        Objects.requireNonNull(
                replannedRevisionId, "replannedRevisionId");
        if (supersededCheckpointVersion < 0
                || replannedCheckpointVersion
                        <= supersededCheckpointVersion) {
            throw new IllegalArgumentException(
                    "replan checkpoint versions are invalid");
        }
    }
}
