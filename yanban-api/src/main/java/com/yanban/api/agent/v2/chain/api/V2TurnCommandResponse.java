package com.yanban.api.agent.v2.chain.api;

public record V2TurnCommandResponse(
        String rootClientRequestId,
        String commandClientRequestId,
        String instructionId,
        String pendingItemStatus,
        String taskOutcomeStatus,
        boolean replayed) {
}
