package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.PlanId;

public final class ActiveStepInterruptionCompositionProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final ActiveStepInterruptionCompositionStage stage;
    private final ActiveStepInterruptionCompositionProtocolCode code;
    private final String path;
    private final ActiveStepInterruptionLeaseDisposition leaseDisposition;

    ActiveStepInterruptionCompositionProtocolException(
            PlanId planId,
            ActiveStepInterruptionCompositionStage stage,
            ActiveStepInterruptionCompositionProtocolCode code,
            String path,
            Throwable cause) {
        super("active-Step interruption composition protocol failed: stage="
                        + stage + ", code=" + code + ", path=" + path
                        + ", leaseDisposition="
                        + ActiveStepInterruptionLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                sanitized(cause));
        this.planId = ActiveStepInterruptionCompositionValues.requiredInternal(
                planId, "planId");
        this.stage = ActiveStepInterruptionCompositionValues.requiredInternal(
                stage, "stage");
        this.code = ActiveStepInterruptionCompositionValues.requiredInternal(
                code, "code");
        this.path = ActiveStepInterruptionCompositionValues.protocolPath(path);
        this.leaseDisposition =
                ActiveStepInterruptionLeaseDisposition.RETAINED_FOR_RECOVERY;
    }

    public PlanId planId() {
        return planId;
    }

    public ActiveStepInterruptionCompositionStage stage() {
        return stage;
    }

    public ActiveStepInterruptionCompositionProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public ActiveStepInterruptionLeaseDisposition leaseDisposition() {
        return leaseDisposition;
    }

    private static Throwable sanitized(Throwable cause) {
        if (cause == null) {
            return null;
        }
        return new SanitizedCollaboratorException(cause.getClass().getName());
    }

    private static final class SanitizedCollaboratorException
            extends RuntimeException {
        private SanitizedCollaboratorException(String originalType) {
            super("collaborator exception details redacted [type="
                    + originalType + "]", null, false, false);
        }
    }
}
