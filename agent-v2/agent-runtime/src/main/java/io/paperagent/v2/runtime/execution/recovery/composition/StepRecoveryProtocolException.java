package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;

public final class StepRecoveryProtocolException extends IllegalStateException {
    private final PlanId planId;
    private final StepRecoveryStage stage;
    private final StepRecoveryProtocolCode code;
    private final String path;
    private final StepRecoveryLeaseDisposition leaseDisposition;

    StepRecoveryProtocolException(
            PlanId planId,
            StepRecoveryStage stage,
            StepRecoveryProtocolCode code,
            String path,
            StepRecoveryLeaseDisposition leaseDisposition,
            Throwable cause) {
        super("Step recovery protocol failure: stage="
                        + StepRecoveryCompositionValues.requiredInternal(stage, "stage")
                        + ", code="
                        + StepRecoveryCompositionValues.requiredInternal(code, "code")
                        + ", path="
                        + StepRecoveryCompositionValues.protocolPath(path)
                        + ", leaseDisposition="
                        + StepRecoveryCompositionValues.requiredInternal(
                                leaseDisposition, "leaseDisposition"),
                sanitizedCause(cause));
        this.planId = StepRecoveryCompositionValues.requiredInternal(planId, "planId");
        this.stage = stage;
        this.code = code;
        this.path = path;
        this.leaseDisposition = leaseDisposition;
    }

    public PlanId planId() {
        return planId;
    }

    public StepRecoveryStage stage() {
        return stage;
    }

    public StepRecoveryProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public StepRecoveryLeaseDisposition leaseDisposition() {
        return leaseDisposition;
    }

    private static Throwable sanitizedCause(Throwable cause) {
        if (cause == null) {
            return null;
        }
        return new SanitizedCollaboratorException(cause.getClass().getName());
    }

    private static final class SanitizedCollaboratorException
            extends RuntimeException {
        private SanitizedCollaboratorException(String originalTypeName) {
            super("collaborator exception details redacted [type="
                    + originalTypeName + "]", null, false, false);
        }
    }
}
