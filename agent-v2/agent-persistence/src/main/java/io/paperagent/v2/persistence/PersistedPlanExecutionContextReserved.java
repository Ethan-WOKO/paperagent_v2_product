package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

import java.util.Objects;

public record PersistedPlanExecutionContextReserved(
        PlanId planId,
        WorkspaceMaterializationSpec materializationSpec,
        String leaseOwnerId,
        long fencingToken)
        implements PlanExecutionContextSnapshot {

    public PersistedPlanExecutionContextReserved {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(materializationSpec, "materializationSpec");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    @Override
    public String toString() {
        return "PersistedPlanExecutionContextReserved["
                + "planId=<provided>, "
                + "materializationSpec=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
