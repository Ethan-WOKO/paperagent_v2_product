package io.paperagent.v2.sandbox;

public final class ScriptedSandboxAssertionException extends AssertionError {
    private final SandboxFailure failure;

    public ScriptedSandboxAssertionException(SandboxFailure failure) {
        super(SandboxValues.required(
                failure,
                "scriptedSandboxAssertion.failure").message());
        this.failure = failure;
    }

    public SandboxFailure failure() {
        return failure;
    }
}
