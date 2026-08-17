package com.yanban.api.agent.reactplan;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Program-owned completion authority; the model cannot declare a Step done. */
@Component
public class ReactPlanCompletionGate {

    public ReactPlanCompletionDecision evaluate(
            ReactPlanFactLedger ledger,
        ReactPlanDelivery delivery) {
        Objects.requireNonNull(ledger, "ledger");
        if (contains(ledger, ReactPlanReceiptKind.SYSTEM_FAILED)) {
            return ReactPlanCompletionDecision.SYSTEM_FAILURE;
        }
        if (contains(ledger, ReactPlanReceiptKind.CANCELLED)) {
            return ReactPlanCompletionDecision.CANCELLED;
        }
        if (contains(ledger, ReactPlanReceiptKind.TIMED_OUT)) {
            return ReactPlanCompletionDecision.TIMED_OUT;
        }
        if (ledger.hasPendingEffects() || ledger.receipts().isEmpty()) {
            return ReactPlanCompletionDecision.WAITING_FOR_RECEIPT;
        }
        if (delivery == null) {
            return ReactPlanCompletionDecision.WAITING_FOR_DELIVERY;
        }
        return receiptIds(ledger).equals(delivery.receiptIds())
                ? ReactPlanCompletionDecision.READY
                : ReactPlanCompletionDecision.DELIVERY_BINDING_MISMATCH;
    }

    private static boolean contains(ReactPlanFactLedger ledger, ReactPlanReceiptKind kind) {
        return ledger.receipts().stream().anyMatch(receipt -> receipt.kind() == kind);
    }

    static Set<String> receiptIds(ReactPlanFactLedger ledger) {
        return ledger.receipts().stream()
                .map(ReactPlanReceiptRecorded::receiptId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
