package com.yanban.api.agent.reactplan;

import org.springframework.stereotype.Component;

import java.util.Objects;

/** Produces UI progress exclusively from the append-only fact fold. */
@Component
public class ReactPlanProgressProjector {
    private final ReactPlanCompletionGate completionGate;

    public ReactPlanProgressProjector(ReactPlanCompletionGate completionGate) {
        this.completionGate = completionGate;
    }

    public ReactPlanProgress project(ReactPlanFactLedger ledger, ReactPlanDelivery delivery) {
        Objects.requireNonNull(ledger, "ledger");
        ReactPlanProgressPhase phase;
        if (ledger.requests().isEmpty()) {
            phase = ReactPlanProgressPhase.READY_TO_EXECUTE;
        } else {
            phase = switch (completionGate.evaluate(ledger, delivery)) {
                case WAITING_FOR_RECEIPT -> ReactPlanProgressPhase.EXECUTING;
                case WAITING_FOR_DELIVERY, DELIVERY_BINDING_MISMATCH ->
                        ReactPlanProgressPhase.READY_TO_DELIVER;
                case READY -> ReactPlanProgressPhase.COMPLETED;
                case SYSTEM_FAILURE -> ReactPlanProgressPhase.SYSTEM_FAILURE;
                case CANCELLED -> ReactPlanProgressPhase.CANCELLED;
                case TIMED_OUT -> ReactPlanProgressPhase.TIMED_OUT;
            };
        }
        return new ReactPlanProgress(
                phase,
                ledger.requests().size(),
                ledger.receipts().size());
    }
}
