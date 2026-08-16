package com.yanban.api.agent.reactplan;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactPlanCompletionGateTest {
    private final ReactPlanCompletionGate gate = new ReactPlanCompletionGate();
    private final ReactPlanDeliveryBinder binder = new ReactPlanDeliveryBinder();

    @Test
    void successfulCompileAndTrustworthyCompileFailureAreBothDeliverable() {
        assertReady(ReactPlanReceiptKind.TASK_SUCCEEDED, "compile-ok");
        assertReady(ReactPlanReceiptKind.TASK_FAILED, "javac-error");
    }

    @Test
    void systemFailureIsNotMisreportedAsTaskCompletion() {
        ReactPlanFactLedger ledger = terminal(
                ReactPlanReceiptKind.SYSTEM_FAILED, "broker-unavailable");

        assertEquals(ReactPlanCompletionDecision.SYSTEM_FAILURE, gate.evaluate(ledger, null));
        assertThrows(IllegalStateException.class, () -> binder.bind(ledger, "could not compile"));
    }

    @Test
    void aRecordedSystemFailureIsNotHiddenByAnotherPendingEffect() {
        ReactPlanFactLedger ledger = terminal(
                ReactPlanReceiptKind.SYSTEM_FAILED, "broker-unavailable")
                .append(new ReactPlanToolRequested(
                        "call-2", "sandbox.compile", "sha256:second"));

        assertEquals(ReactPlanCompletionDecision.SYSTEM_FAILURE, gate.evaluate(ledger, null));
    }

    @Test
    void pendingEffectAndIncorrectReceiptBindingCannotComplete() {
        ReactPlanFactLedger pending = ReactPlanFactLedger.empty()
                .append(ReactPlanFactLedgerTest.request());
        assertEquals(ReactPlanCompletionDecision.WAITING_FOR_RECEIPT,
                gate.evaluate(pending, null));

        ReactPlanFactLedger terminal = terminal(
                ReactPlanReceiptKind.TASK_SUCCEEDED, "compile-ok");
        ReactPlanDelivery forged = new ReactPlanDelivery("done", Set.of("model-copied-id"));
        assertEquals(ReactPlanCompletionDecision.DELIVERY_BINDING_MISMATCH,
                gate.evaluate(terminal, forged));
    }

    private void assertReady(ReactPlanReceiptKind kind, String resultCode) {
        ReactPlanFactLedger ledger = terminal(kind, resultCode);
        assertEquals(ReactPlanCompletionDecision.WAITING_FOR_DELIVERY,
                gate.evaluate(ledger, null));
        ReactPlanDelivery delivery = binder.bind(ledger, "Receipt-backed conclusion");
        assertEquals(Set.of("receipt-1"), delivery.receiptIds());
        assertEquals(ReactPlanCompletionDecision.READY, gate.evaluate(ledger, delivery));
    }

    private static ReactPlanFactLedger terminal(ReactPlanReceiptKind kind, String resultCode) {
        return ReactPlanFactLedger.empty()
                .append(ReactPlanFactLedgerTest.request())
                .append(ReactPlanFactLedgerTest.receipt(kind, resultCode));
    }
}
