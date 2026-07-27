package io.paperagent.v2.runtime.execution.context.composition;

public final class PlanExecutionContextCompositionValidationException
        extends IllegalArgumentException {
    private final PlanExecutionContextCompositionValidationCode code;
    private final String path;

    PlanExecutionContextCompositionValidationException(
            PlanExecutionContextCompositionValidationCode code,
            String path,
            String ignoredMessage) {
        super(safeMessage(code, path));
        this.code = code;
        this.path = path;
    }

    public PlanExecutionContextCompositionValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    private static String safeMessage(
            PlanExecutionContextCompositionValidationCode code,
            String path) {
        PlanExecutionContextCompositionValidationCode requiredCode =
                PlanExecutionContextCompositionValues.requiredInternal(
                        code,
                        "code");
        String canonicalPath =
                PlanExecutionContextCompositionValues.validationPath(path);
        return "Plan execution-context composition validation failure: "
                + "code="
                + requiredCode
                + ", path="
                + canonicalPath;
    }
}
