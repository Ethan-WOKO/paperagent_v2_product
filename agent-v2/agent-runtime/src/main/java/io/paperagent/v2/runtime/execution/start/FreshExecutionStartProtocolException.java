package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.PlanId;

public final class FreshExecutionStartProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final FreshExecutionStartProtocolStage stage;
    private final FreshExecutionStartProtocolCode code;
    private final String path;
    private final FreshExecutionLeaseDisposition leaseDisposition;

    FreshExecutionStartProtocolException(
            PlanId planId,
            FreshExecutionStartProtocolStage stage,
            FreshExecutionStartProtocolCode code,
            String path,
            FreshExecutionLeaseDisposition leaseDisposition,
            Throwable cause) {
        super(
                message(stage, code, path, leaseDisposition),
                sanitizedCause(cause));
        this.planId = planId;
        this.stage = stage;
        this.code = code;
        this.path = path;
        this.leaseDisposition = leaseDisposition;
    }

    public PlanId planId() {
        return planId;
    }

    public FreshExecutionStartProtocolStage stage() {
        return stage;
    }

    public FreshExecutionStartProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public FreshExecutionLeaseDisposition leaseDisposition() {
        return leaseDisposition;
    }

    private static String message(
            FreshExecutionStartProtocolStage stage,
            FreshExecutionStartProtocolCode code,
            String path,
            FreshExecutionLeaseDisposition leaseDisposition) {
        return "fresh execution-start protocol failure: stage="
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
                    true);
        }
    }
}
