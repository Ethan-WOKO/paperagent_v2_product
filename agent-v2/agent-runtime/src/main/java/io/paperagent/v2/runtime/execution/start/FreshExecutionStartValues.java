package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.PlanId;

final class FreshExecutionStartValues {
    private FreshExecutionStartValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    path + " is required");
        }
        return value;
    }

    static String identifier(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw failure(
                    FreshExecutionStartValidationCode.INVALID_IDENTIFIER,
                    path,
                    path + " must not be blank");
        }
        return value;
    }

    static FreshExecutionStartValidationException failure(
            FreshExecutionStartValidationCode code,
            String path,
            String message) {
        return new FreshExecutionStartValidationException(
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                requiredInternal(message, "message"));
    }

    static FreshExecutionStartProtocolException protocolFailure(
            PlanId planId,
            FreshExecutionStartProtocolStage stage,
            FreshExecutionStartProtocolCode code,
            String path,
            FreshExecutionLeaseDisposition leaseDisposition,
            Throwable cause) {
        return new FreshExecutionStartProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                requiredInternal(leaseDisposition, "leaseDisposition"),
                cause);
    }

    private static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
