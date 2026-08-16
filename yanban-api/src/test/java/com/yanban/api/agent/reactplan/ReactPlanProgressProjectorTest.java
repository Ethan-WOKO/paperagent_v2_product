package com.yanban.api.agent.reactplan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReactPlanProgressProjectorTest {
    private final ReactPlanCompletionGate gate = new ReactPlanCompletionGate();
    private final ReactPlanDeliveryBinder binder = new ReactPlanDeliveryBinder();
    private final ReactPlanProgressProjector projector = new ReactPlanProgressProjector(gate);

    @Test
    void progressComesFromToolAndReceiptFacts() {
        ReactPlanFactLedger empty = ReactPlanFactLedger.empty();
        assertEquals(ReactPlanProgressPhase.READY_TO_EXECUTE,
                projector.project(empty, null).phase());

        ReactPlanFactLedger pending = empty.append(ReactPlanFactLedgerTest.request());
        assertEquals(ReactPlanProgressPhase.EXECUTING,
                projector.project(pending, null).phase());

        ReactPlanFactLedger terminal = pending.append(ReactPlanFactLedgerTest.receipt(
                ReactPlanReceiptKind.TASK_FAILED, "javac-error"));
        ReactPlanProgress ready = projector.project(terminal, null);
        assertEquals(ReactPlanProgressPhase.READY_TO_DELIVER, ready.phase());
        assertEquals(1, ready.requestedToolCalls());
        assertEquals(1, ready.terminalReceipts());

        ReactPlanDelivery delivery = binder.bind(terminal, "Compilation failed with a compiler error");
        assertEquals(ReactPlanProgressPhase.COMPLETED,
                projector.project(terminal, delivery).phase());
    }
}
