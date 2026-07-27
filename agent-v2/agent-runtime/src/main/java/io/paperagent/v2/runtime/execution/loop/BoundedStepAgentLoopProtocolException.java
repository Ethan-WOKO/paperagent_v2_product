package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

public final class BoundedStepAgentLoopProtocolException extends IllegalStateException {
    private final PlanId planId;
    private final PlanStepId stepId;
    private final BoundedStepAgentLoopStage stage;
    private final BoundedStepAgentLoopProtocolCode code;
    private final String path;

    BoundedStepAgentLoopProtocolException(
            PlanId planId,
            PlanStepId stepId,
            BoundedStepAgentLoopStage stage,
            BoundedStepAgentLoopProtocolCode code,
            String path,
            Throwable cause) {
        super("Bounded Step Agent Loop protocol failure: stage="
                        + BoundedStepAgentLoopValues.requiredInternal(stage, "stage")
                        + ", code="
                        + BoundedStepAgentLoopValues.requiredInternal(code, "code")
                        + ", path="
                        + BoundedStepAgentLoopValues.protocolPath(path),
                sanitizedCause(cause));
        this.planId = BoundedStepAgentLoopValues.requiredInternal(planId, "planId");
        this.stepId = BoundedStepAgentLoopValues.requiredInternal(stepId, "stepId");
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

    public BoundedStepAgentLoopStage stage() {
        return stage;
    }

    public BoundedStepAgentLoopProtocolCode code() {
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
