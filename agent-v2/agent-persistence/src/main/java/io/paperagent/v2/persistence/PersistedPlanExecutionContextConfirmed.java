package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

import java.util.Objects;

public record PersistedPlanExecutionContextConfirmed(
        PersistedPlanExecutionContextReserved reservation,
        String leaseOwnerId,
        long fencingToken,
        ContentHash sourceManifestFingerprint)
        implements PlanExecutionContextSnapshot {

    public PersistedPlanExecutionContextConfirmed {
        Objects.requireNonNull(reservation, "reservation");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(
                sourceManifestFingerprint, "sourceManifestFingerprint");
    }

    @Override
    public PlanId planId() {
        return reservation.planId();
    }

    @Override
    public WorkspaceMaterializationSpec materializationSpec() {
        return reservation.materializationSpec();
    }

    @Override
    public String toString() {
        return "PersistedPlanExecutionContextConfirmed["
                + "reservation=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
                + "sourceManifestFingerprint=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
