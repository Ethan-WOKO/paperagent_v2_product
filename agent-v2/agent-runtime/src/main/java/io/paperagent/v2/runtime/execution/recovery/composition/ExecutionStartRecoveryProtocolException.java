package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;

public final class ExecutionStartRecoveryProtocolException
        extends IllegalStateException {
    private final PlanId planId;
    private final ExecutionStartRecoveryStage stage;
    private final ExecutionStartRecoveryProtocolCode code;
    private final String path;
    private final ExecutionStartRecoveryLeaseDisposition leaseDisposition;

    ExecutionStartRecoveryProtocolException(
            PlanId planId,
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryProtocolCode code,
            String path,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition,
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

    public ExecutionStartRecoveryStage stage() {
        return stage;
    }

    public ExecutionStartRecoveryProtocolCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    public ExecutionStartRecoveryLeaseDisposition leaseDisposition() {
        return leaseDisposition;
    }

    private static String message(
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryProtocolCode code,
            String path,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition) {
        return "execution-start recovery protocol failure: stage="
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
