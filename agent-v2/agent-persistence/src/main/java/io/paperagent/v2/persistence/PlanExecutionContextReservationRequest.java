package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

import java.util.Objects;

public record PlanExecutionContextReservationRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        PlanRevisionId expectedRevisionId,
        long expectedRevisionNumber,
        long expectedCheckpointVersion,
        long expectedEventHeadSequence,
        WorkspaceMaterializationSpec materializationSpec) {

    public PlanExecutionContextReservationRequest {
        Objects.requireNonNull(planId, "planId");
        requireText(leaseToken, "leaseToken");
        requirePositive(fencingToken, "fencingToken");
        Objects.requireNonNull(expectedRevisionId, "expectedRevisionId");
        requirePositive(expectedRevisionNumber, "expectedRevisionNumber");
        requirePositive(expectedCheckpointVersion, "expectedCheckpointVersion");
        requirePositive(expectedEventHeadSequence, "expectedEventHeadSequence");
        Objects.requireNonNull(materializationSpec, "materializationSpec");
    }

    @Override
    public String toString() {
        return "PlanExecutionContextReservationRequest["
                + "planId=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "expectedRevisionId=<provided>, "
                + "expectedRevisionNumber=<provided>, "
                + "expectedCheckpointVersion=<provided>, "
                + "expectedEventHeadSequence=<provided>, "
                + "materializationSpec=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }

    private static void requirePositive(long value, String path) {
        if (value < 1) {
            throw new IllegalArgumentException(path + " must be positive");
        }
    }
}
