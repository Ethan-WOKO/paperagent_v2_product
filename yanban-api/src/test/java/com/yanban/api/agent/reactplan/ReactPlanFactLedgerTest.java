package com.yanban.api.agent.reactplan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactPlanFactLedgerTest {

    @Test
    void exactReplayIsNoOpAndRestartRebuildsTheSameTerminalState() {
        ReactPlanToolRequested request = request();
        ReactPlanReceiptRecorded receipt = receipt(ReactPlanReceiptKind.TASK_SUCCEEDED, "compile-ok");
        ReactPlanFactLedger ledger = ReactPlanFactLedger.empty().append(request).append(receipt);

        assertSame(ledger, ledger.append(request));
        assertSame(ledger, ledger.append(receipt));
        ReactPlanFactLedger rebuilt = ReactPlanFactLedger.rebuild(ledger.facts());
        assertEquals(ledger.requests(), rebuilt.requests());
        assertEquals(ledger.receipts(), rebuilt.receipts());
        assertEquals(false, rebuilt.hasPendingEffects());
    }

    @Test
    void identityReuseWithDifferentDigestOrReceiptIsAConflict() {
        ReactPlanFactLedger ledger = ReactPlanFactLedger.empty().append(request());

        assertThrows(ReactPlanFactConflictException.class, () -> ledger.append(
                new ReactPlanToolRequested("call-1", "sandbox.compile", "sha256:different")));
        assertThrows(ReactPlanFactConflictException.class, () -> ledger.append(
                new ReactPlanReceiptRecorded(
                        "call-1", "receipt-1", "sha256:different",
                        ReactPlanReceiptKind.TASK_SUCCEEDED, "compile-ok")));
    }

    @Test
    void receiptWithoutPriorIntentIsRejectedDuringRecovery() {
        assertThrows(ReactPlanFactConflictException.class, () ->
                ReactPlanFactLedger.rebuild(List.of(
                        receipt(ReactPlanReceiptKind.TASK_SUCCEEDED, "compile-ok"))));
    }

    static ReactPlanToolRequested request() {
        return new ReactPlanToolRequested("call-1", "sandbox.compile", "sha256:stable");
    }

    static ReactPlanReceiptRecorded receipt(ReactPlanReceiptKind kind, String resultCode) {
        return new ReactPlanReceiptRecorded(
                "call-1", "receipt-1", "sha256:stable", kind, resultCode);
    }
}
