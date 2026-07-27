package io.paperagent.v2.runtime.execution.loop;

public final class BoundedStepAgentLoopValidationException extends IllegalArgumentException {
    private final BoundedStepAgentLoopValidationCode code;
    private final String path;

    BoundedStepAgentLoopValidationException(
            BoundedStepAgentLoopValidationCode code,
            String path) {
        super("Bounded Step Agent Loop validation failure: code="
                + BoundedStepAgentLoopValues.requiredInternal(code, "code")
                + ", path="
                + BoundedStepAgentLoopValues.validationPath(path));
        this.code = code;
        this.path = path;
    }

    public BoundedStepAgentLoopValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
