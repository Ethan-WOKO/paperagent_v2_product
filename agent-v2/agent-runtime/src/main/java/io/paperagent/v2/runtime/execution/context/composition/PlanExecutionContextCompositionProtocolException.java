package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;

public final class PlanExecutionContextCompositionProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final PlanExecutionContextCompositionStage stage;
    private final PlanExecutionContextCompositionProtocolCode code;
    private final String path;
    private final PlanExecutionContextLeaseDisposition leaseDisposition;

    PlanExecutionContextCompositionProtocolException(
            PlanId planId,
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            PlanExecutionContextLeaseDisposition leaseDisposition,
            Throwable cause) {
        super(
                message(
                        PlanExecutionContextCompositionValues.requiredInternal(
                                stage,
                                "stage"),
                        PlanExecutionContextCompositionValues.requiredInternal(
                                code,
                                "code"),
                        PlanExecutionContextCompositionValues.protocolPath(path),
                        PlanExecutionContextCompositionValues.requiredInternal(
                                leaseDisposition,
                                "leaseDisposition")),
                sanitizedCause(cause));
        this.planId = PlanExecutionContextCompositionValues.requiredInternal(
                planId,
                "planId");
        this.stage = stage;
        this.code = code;
        this.path = path;
        this.leaseDisposition = leaseDisposition;
    }

    public PlanId planId() {
        return planId;
    }

    public PlanExecutionContextCompositionStage stage() {
        return stage;
    }

    public PlanExecutionContextCompositionProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public PlanExecutionContextLeaseDisposition leaseDisposition() {
        return leaseDisposition;
    }

    private static String message(
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        return "Plan execution-context composition protocol failure: stage="
                + stage
                + ", code="
                + code
                + ", path="
                + path
                + ", leaseDisposition="
                + leaseDisposition;
    }

    private static Throwable sanitizedCause(Throwable cause) {
        if (cause == null) {
            return null;
        }
        return new SanitizedCollaboratorException(
                cause.getClass().getName());
    }

    private static final class SanitizedCollaboratorException
            extends RuntimeException {
        private SanitizedCollaboratorException(String originalTypeName) {
            super(
                    "collaborator exception details redacted [type="
                            + originalTypeName
                            + "]",
                    null,
                    false,
                    false);
        }
    }
}
