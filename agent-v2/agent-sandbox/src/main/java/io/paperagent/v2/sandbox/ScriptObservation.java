package io.paperagent.v2.sandbox;

import java.util.Optional;

public record ScriptObservation(
        long attemptSequence,
        int scriptIndex,
        SandboxRequest request,
        boolean consumed,
        Optional<SandboxFailureCode> failureCode) {

    public ScriptObservation {
        if (attemptSequence < 0 || scriptIndex < 0) {
            SandboxValues.fail(
                    SandboxValidationCode.INVALID_SCRIPT,
                    "scriptObservation",
                    "attempt sequence and script index must be non-negative");
        }
        SandboxValues.required(request, "scriptObservation.request");
        failureCode = SandboxValues.required(
                failureCode,
                "scriptObservation.failureCode");
    }
}
