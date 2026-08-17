package com.yanban.api.agent.reactplan;

/** UI-safe progress derived from facts, never from model narration. */
public record ReactPlanProgress(
        ReactPlanProgressPhase phase,
        int requestedToolCalls,
        int terminalReceipts) {
}
