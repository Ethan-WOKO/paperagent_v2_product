package io.paperagent.v2.runtime.execution.replan.composition;

public final class BoundedStepReplanCompositionValidationException
        extends IllegalArgumentException {
    private final BoundedStepReplanCompositionValidationCode code;
    private final String path;

    BoundedStepReplanCompositionValidationException(
            BoundedStepReplanCompositionValidationCode code,
            String path) {
        super("Bounded Step replan composition validation failure: code="
                + BoundedStepReplanCompositionValues.requiredInternal(code, "code")
                + ", path="
                + BoundedStepReplanCompositionValues.validationPath(path));
        this.code = code;
        this.path = path;
    }

    public BoundedStepReplanCompositionValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
