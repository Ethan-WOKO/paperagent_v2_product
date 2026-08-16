package com.yanban.api.agent.reactplan;

import java.util.Set;

/** Product-bound final delivery. Receipt IDs are never copied from model text. */
public record ReactPlanDelivery(String conclusion, Set<String> receiptIds) {
    public ReactPlanDelivery {
        conclusion = ReactPlanValues.text(conclusion, "conclusion");
        receiptIds = Set.copyOf(receiptIds);
        if (receiptIds.isEmpty()) {
            throw new IllegalArgumentException("receiptIds must not be empty");
        }
    }
}
