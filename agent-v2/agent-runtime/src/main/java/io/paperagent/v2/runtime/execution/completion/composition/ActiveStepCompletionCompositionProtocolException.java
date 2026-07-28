package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;

public final class ActiveStepCompletionCompositionProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final ActiveStepCompletionCompositionStage stage;
    private final ActiveStepCompletionCompositionProtocolCode code;
    private final String path;
    private final ActiveStepCompletionLeaseDisposition leaseDisposition;

    ActiveStepCompletionCompositionProtocolException(
            PlanId planId,
            ActiveStepCompletionCompositionStage stage,
            ActiveStepCompletionCompositionProtocolCode code,
            String path,
            Throwable cause) {
        super("active-Step completion composition protocol failed: stage="
                        + stage + ", code=" + code + ", path=" + path
                        + ", leaseDisposition="
                        + ActiveStepCompletionLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                sanitized(cause));
        this.planId = ActiveStepCompletionCompositionValues.requiredInternal(
                planId, "planId");
        this.stage = ActiveStepCompletionCompositionValues.requiredInternal(
                stage, "stage");
        this.code = ActiveStepCompletionCompositionValues.requiredInternal(
                code, "code");
        this.path = ActiveStepCompletionCompositionValues.protocolPath(path);
        this.leaseDisposition =
                ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY;
    }

    public PlanId planId() {
        return planId;
    }

    public ActiveStepCompletionCompositionStage stage() {
        return stage;
    }

    public ActiveStepCompletionCompositionProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public ActiveStepCompletionLeaseDisposition leaseDisposition() {
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
