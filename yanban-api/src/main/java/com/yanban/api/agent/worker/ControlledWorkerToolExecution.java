package com.yanban.api.agent.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.core.research.ProjectRelativePath;
import java.util.List;

/** Server-observed facts for one invocation that crossed the governed tool boundary. */
record ControlledWorkerToolExecution(
        String toolName,
        List<ProjectRelativePath> requestedPaths,
        boolean success,
        JsonNode output,
        String errorCode,
        String errorMessage,
        boolean retryable
) {
    ControlledWorkerToolExecution {
        requestedPaths = requestedPaths == null ? List.of() : List.copyOf(requestedPaths);
        output = output == null ? null : output.deepCopy();
    }
}
