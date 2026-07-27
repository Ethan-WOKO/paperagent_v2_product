package io.paperagent.v2.sandbox;

import java.util.Map;

public record SandboxFailure(
        SandboxFailureCode code,
        String message,
        Map<String, String> details) implements SandboxResult {

    public SandboxFailure {
        SandboxValues.required(code, "sandboxFailure.code");
        message = SandboxValues.boundedText(
                message,
                "sandboxFailure.message",
                SandboxLimits.MAX_METADATA_VALUE_LENGTH);
        details = SandboxValues.boundedMetadata(details, "sandboxFailure.details");
    }
}
