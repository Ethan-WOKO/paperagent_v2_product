package com.yanban.api.agent.reactplan;

/** Tool intent after the product has assigned identity and computed its digest. */
public record ReactPlanToolRequested(
        String toolCallId,
        String toolName,
        String requestDigest) implements ReactPlanFact {

    public ReactPlanToolRequested {
        toolCallId = ReactPlanValues.text(toolCallId, "toolCallId");
        toolName = ReactPlanValues.text(toolName, "toolName");
        requestDigest = ReactPlanValues.text(requestDigest, "requestDigest");
    }
}
