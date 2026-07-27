package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

import java.util.Objects;

public record PlanExecutionContextConfirmationRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        WorkspaceMaterializationSpec materializationSpec,
        ContentHash sourceManifestFingerprint) {

    public PlanExecutionContextConfirmationRequest {
        Objects.requireNonNull(planId, "planId");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(materializationSpec, "materializationSpec");
        Objects.requireNonNull(
                sourceManifestFingerprint, "sourceManifestFingerprint");
    }

    @Override
    public String toString() {
        return "PlanExecutionContextConfirmationRequest["
                + "planId=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "materializationSpec=<provided>, "
                + "sourceManifestFingerprint=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
