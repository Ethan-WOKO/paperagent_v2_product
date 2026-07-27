package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

public sealed interface PlanExecutionContextSnapshot
        permits PersistedPlanExecutionContextReserved,
                PersistedPlanExecutionContextConfirmed {
    PlanId planId();

    WorkspaceMaterializationSpec materializationSpec();
}
