package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

public interface PlanExecutionContextRepository {
    PersistenceResult<PersistedPlanExecutionContextReserved> reserve(
            PlanExecutionContextReservationRequest request);

    PersistenceResult<PersistedPlanExecutionContextConfirmed> confirm(
            PlanExecutionContextConfirmationRequest request);

    PersistenceResult<PlanExecutionContextSnapshot> inspect(PlanId planId);
}
