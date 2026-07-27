package io.paperagent.v2.sandbox;

public record SandboxRequestId(String value) {
    public SandboxRequestId {
        value = SandboxValues.id(value, "sandboxRequestId");
    }
}
