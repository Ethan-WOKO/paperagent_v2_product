package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;

public final class StepActivationCompositionProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final StepActivationCompositionStage stage;
    private final StepActivationCompositionProtocolCode code;
    private final String path;
    private final StepActivationLeaseDisposition leaseDisposition;

    StepActivationCompositionProtocolException(
            PlanId planId,
            StepActivationCompositionStage stage,
            StepActivationCompositionProtocolCode code,
            String path,
            StepActivationLeaseDisposition leaseDisposition,
            Throwable cause) {
        super("Step activation composition protocol failure: stage="
                        + StepActivationCompositionValues.requiredInternal(stage, "stage")
                        + ", code="
                        + StepActivationCompositionValues.requiredInternal(code, "code")
                        + ", path="
                        + StepActivationCompositionValues.protocolPath(path)
                        + ", leaseDisposition="
                        + StepActivationCompositionValues.requiredInternal(
                                leaseDisposition, "leaseDisposition"),
                sanitizedCause(cause));
        this.planId = StepActivationCompositionValues.requiredInternal(planId, "planId");
        this.stage = stage;
        this.code = code;
        this.path = path;
        this.leaseDisposition = leaseDisposition;
    }

    public PlanId planId() {
        return planId;
    }

    public StepActivationCompositionStage stage() {
        return stage;
    }

    public StepActivationCompositionProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public StepActivationLeaseDisposition leaseDisposition() {
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
