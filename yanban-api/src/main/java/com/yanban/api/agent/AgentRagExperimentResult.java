package com.yanban.api.agent;

import java.util.List;

public record AgentRagExperimentResult(
        String ragContext,
        List<AgentRetrievedChunkDebug> retrievedChunks,
        List<AgentRagSelectionRef> selectedRefs
) {
    public AgentRagExperimentResult {
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        selectedRefs = selectedRefs == null ? List.of() : List.copyOf(selectedRefs);
    }

    public AgentRagExperimentResult(
            String ragContext,
            List<AgentRetrievedChunkDebug> retrievedChunks) {
        this(ragContext, retrievedChunks, List.of());
    }
}
