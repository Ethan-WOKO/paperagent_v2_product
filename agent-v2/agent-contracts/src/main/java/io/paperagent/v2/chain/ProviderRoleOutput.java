package io.paperagent.v2.chain;

import java.util.Objects;

/** Temporary provider DTO. It never contains runtime-generated authority identities. */
public record ProviderRoleOutput(String schemaVersion, String kind, ChainProposalPayload payload) {
    public static final String SCHEMA_VERSION = "1";

    public ProviderRoleOutput {
        schemaVersion = ChainValues.required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        kind = ChainValues.required(kind, "kind");
        payload = Objects.requireNonNull(payload, "payload");
        if (!payload.kind().wireName().equals(kind)) {
            throw new IllegalArgumentException("kind does not match typed payload");
        }
    }

    public ChainProposalPayload content() {
        return payload;
    }

    public void validateFor(ChainRole expectedRole, ChainWorkState workState, String boundGapId) {
        Objects.requireNonNull(expectedRole, "expectedRole");
        Objects.requireNonNull(workState, "workState");
        if (content().role() != expectedRole) {
            throw new IllegalArgumentException("provider payload role does not match the invocation role");
        }
        GapValidation gap = payload.gapValidation();
        if (workState != ChainWorkState.VALIDATING_PENDING_ITEM) {
            if (gap != null || boundGapId != null) {
                throw new IllegalArgumentException(
                        "gap validation and bound gap are only legal in pending-item validation context");
            }
            return;
        }
        if (expectedRole != ChainRole.PLANNER && expectedRole != ChainRole.EXECUTOR) {
            throw new IllegalArgumentException("only Planner or Executor may validate a pending item");
        }
        boundGapId = ChainValues.required(boundGapId, "boundGapId");
        if (gap == null || !boundGapId.equals(gap.gapId())) {
            throw new IllegalArgumentException("gap validation must match the invocation's unique bound gap");
        }
        if (gap.outcome() == GapValidation.Outcome.STILL_PENDING) {
            if (content() instanceof PlannerPayload.NeedUserInput) {
                return;
            }
            if (content() instanceof ExecutorPayload.StepBlocked blocked
                    && !blocked.remainingMissingFields().isEmpty()) {
                return;
            }
            throw new IllegalArgumentException(
                    "STILL_PENDING requires Planner NEED_USER_INPUT or questioned Executor STEP_BLOCKED");
        }
    }
}
