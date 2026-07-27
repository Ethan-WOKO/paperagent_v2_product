package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeRequest;

/**
 * Verified product identity paired with the deterministic V2 freeze request.
 */
public record ProductTaskFramePreparation(
        AgentRunIdentity identity,
        TaskFrameFreezeRequest freezeRequest) {
}
