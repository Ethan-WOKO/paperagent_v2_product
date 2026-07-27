package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

public final class BoundedStepReplanCompositionProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final PlanStepId stepId;
    private final BoundedStepReplanCompositionStage stage;
    private final BoundedStepReplanCompositionProtocolCode code;
    private final String path;

    BoundedStepReplanCompositionProtocolException(
            PlanId planId,
            PlanStepId stepId,
            BoundedStepReplanCompositionStage stage,
            BoundedStepReplanCompositionProtocolCode code,
            String path,
            Throwable cause) {
        super("Bounded Step replan composition protocol failure: stage="
                        + BoundedStepReplanCompositionValues.requiredInternal(stage, "stage")
                        + ", code="
                        + BoundedStepReplanCompositionValues.requiredInternal(code, "code")
                        + ", path="
                        + BoundedStepReplanCompositionValues.protocolPath(path),
                sanitizedCause(cause));
        this.planId = BoundedStepReplanCompositionValues.requiredInternal(planId, "planId");
        this.stepId = BoundedStepReplanCompositionValues.requiredInternal(stepId, "stepId");
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

    public BoundedStepReplanCompositionStage stage() {
        return stage;
    }

    public BoundedStepReplanCompositionProtocolCode code() {
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
