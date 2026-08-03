package com.yanban.api.agent.v2.context.assembly;

import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.api.agent.AgentRagExperimentResult;
import java.util.List;

public record V2ContextAssemblyRequest(
        Long userId,
        Long sessionId,
        Long currentTurnId,
        int maxRecentTurns,
        long recentTokenLimit,
        long summaryTokenLimit,
        long memoryTokenLimit,
        long ragTokenLimit,
        long historicalTokenLimit,
        List<V2CanonicalTurnProjection> boundedTurns,
        V2CanonicalTurnProjection coverageBoundary,
        V2SummaryProjection summary,
        AgentLongTermMemoryContext memory,
        AgentRagExperimentResult rag,
        List<V2HistoricalTerminalFact> historicalFacts
) {
    public V2ContextAssemblyRequest {
        if (userId == null || sessionId == null) {
            throw new IllegalArgumentException("assembly authority is required");
        }
        if (maxRecentTurns < 1 || maxRecentTurns > 64) {
            throw new IllegalArgumentException("maxRecentTurns is invalid");
        }
        if (recentTokenLimit < 0 || summaryTokenLimit < 0
                || memoryTokenLimit < 0 || ragTokenLimit < 0
                || historicalTokenLimit < 0) {
            throw new IllegalArgumentException("section limits must not be negative");
        }
        boundedTurns = boundedTurns == null ? List.of() : List.copyOf(boundedTurns);
        if (boundedTurns.size() > 256) {
            throw new IllegalArgumentException("boundedTurns exceeds source contract");
        }
        memory = memory == null ? AgentLongTermMemoryContext.empty() : memory;
        rag = rag == null ? new AgentRagExperimentResult(null, null) : rag;
        historicalFacts = historicalFacts == null
                ? List.of() : List.copyOf(historicalFacts);
        if (historicalFacts.size() > 64) {
            throw new IllegalArgumentException("historicalFacts exceeds source contract");
        }
    }
}
