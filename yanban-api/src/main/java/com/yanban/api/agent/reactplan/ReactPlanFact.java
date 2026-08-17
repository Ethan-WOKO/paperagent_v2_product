package com.yanban.api.agent.reactplan;

/** Append-only authority fact used to rebuild one ReAct step after restart. */
public sealed interface ReactPlanFact permits ReactPlanToolRequested, ReactPlanReceiptRecorded {
    String toolCallId();
}
