package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentRuntimeResult;
import java.util.List;

/** Internal return value from the trusted child runtime boundary. */
record ControlledWorkerTaskRun(AgentRuntimeResult runtimeResult,
                               List<ControlledWorkerToolExecution> executions,
                               String scopeRejection) {
    ControlledWorkerTaskRun {
        if (runtimeResult == null) throw new IllegalArgumentException("worker runtime result is required");
        executions = executions == null ? List.of() : List.copyOf(executions);
    }
}
