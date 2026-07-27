package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface PlanExecutionContextCompositionOutcome
        permits PlanExecutionContextReady,
                PlanExecutionContextNotRequired,
                PlanExecutionContextAdvancedUnsupported,
                PlanExecutionContextPersistenceRejected,
                PlanExecutionContextWorkspaceRejected,
                PlanExecutionContextRetryRequired {
    PlanId planId();

    PlanExecutionContextLeaseDisposition leaseDisposition();
}
