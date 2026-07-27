package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.TaskFrame;

/**
 * The unchanged product run identity bound to its canonical V2 TaskFrame.
 */
public record ProductTaskFrameBinding(
        AgentRunIdentity identity,
        TaskFrame taskFrame) {
}
