package io.paperagent.v2.providers;

public final class ScriptedProviderAssertionException extends AssertionError {
    private final ProviderFailure failure;

    public ScriptedProviderAssertionException(ProviderFailure failure) {
        super(ProviderValues.required(failure, "scriptedProviderAssertion.failure").message());
        this.failure = failure;
    }

    public ProviderFailure failure() {
        return failure;
    }
}
