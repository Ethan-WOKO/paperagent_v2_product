package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

public final class SingleTurnStepKernelProtocolException extends IllegalStateException {
    private final PlanId planId;
    private final PlanStepId stepId;
    private final SingleTurnStepKernelStage stage;
    private final SingleTurnStepKernelProtocolCode code;
    private final String path;

    SingleTurnStepKernelProtocolException(
            PlanId planId,
            PlanStepId stepId,
            SingleTurnStepKernelStage stage,
            SingleTurnStepKernelProtocolCode code,
            String path,
            Throwable cause) {
        super("Single-turn Step kernel protocol failure: stage="
                        + SingleTurnStepKernelValues.requiredInternal(stage, "stage")
                        + ", code="
                        + SingleTurnStepKernelValues.requiredInternal(code, "code")
                        + ", path="
                        + SingleTurnStepKernelValues.protocolPath(path),
                sanitizedCause(cause));
        this.planId = SingleTurnStepKernelValues.requiredInternal(planId, "planId");
        this.stepId = SingleTurnStepKernelValues.requiredInternal(stepId, "stepId");
        this.stage = stage;
        this.code = code;
        this.path = path;
    }

    public PlanId planId() {
        return planId;
    }

    public PlanStepId stepId() {
        return stepId;
    }

    public SingleTurnStepKernelStage stage() {
        return stage;
    }

    public SingleTurnStepKernelProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    private static Throwable sanitizedCause(Throwable cause) {
        if (cause == null) {
            return null;
        }
        return new SanitizedCollaboratorException(cause.getClass().getName());
    }

    private static final class SanitizedCollaboratorException extends RuntimeException {
        private SanitizedCollaboratorException(String originalTypeName) {
            super("collaborator exception details redacted [type="
                    + originalTypeName + "]", null, false, false);
        }
    }
}
