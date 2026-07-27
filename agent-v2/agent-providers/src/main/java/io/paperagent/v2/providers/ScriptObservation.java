package io.paperagent.v2.providers;

import java.util.Optional;

public record ScriptObservation(
        long attemptSequence,
        int scriptIndex,
        ModelRequest request,
        boolean consumed,
        Optional<ProviderFailureCode> failureCode) {

    public ScriptObservation {
        if (attemptSequence < 0) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_SCRIPT,
                    "scriptObservation.attemptSequence",
                    "attemptSequence must be non-negative");
        }
        if (scriptIndex < 0) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_SCRIPT,
                    "scriptObservation.scriptIndex",
                    "scriptIndex must be non-negative");
        }
        ProviderValues.required(request, "scriptObservation.request");
        failureCode = ProviderValues.required(
                failureCode,
                "scriptObservation.failureCode");
    }
}
