package com.yanban.api.agent.reactplan;

import org.springframework.stereotype.Component;

import java.util.Objects;

/** Adds authoritative Receipt bindings to model-authored conclusion text. */
@Component
public class ReactPlanDeliveryBinder {

    public ReactPlanDelivery bind(ReactPlanFactLedger ledger, String modelConclusion) {
        Objects.requireNonNull(ledger, "ledger");
        if (ledger.hasPendingEffects() || ledger.receipts().isEmpty()) {
            throw new IllegalStateException("cannot bind a delivery before all Receipts are terminal");
        }
        boolean nonDeliverable = ledger.receipts().stream().anyMatch(receipt -> switch (receipt.kind()) {
            case SYSTEM_FAILED, CANCELLED, TIMED_OUT -> true;
            case TASK_SUCCEEDED, TASK_FAILED -> false;
        });
        if (nonDeliverable) {
            throw new IllegalStateException("system, cancellation, and timeout outcomes are not task deliveries");
        }
        return new ReactPlanDelivery(modelConclusion, ReactPlanCompletionGate.receiptIds(ledger));
    }
}
