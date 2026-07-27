package io.paperagent.v2.contracts;

public record ContractViolation(ViolationCode code, String path, String message) {
    public ContractViolation {
        if (code == null) {
            throw new IllegalArgumentException("code is required");
        }
        path = path == null ? "" : path;
        message = message == null ? "" : message;
    }
}
