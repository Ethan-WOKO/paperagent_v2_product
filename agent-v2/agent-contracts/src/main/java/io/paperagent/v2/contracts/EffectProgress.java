package io.paperagent.v2.contracts;

import java.time.Instant;

/**
 * Immutable, provider-neutral progress fact for a durable effect intent.
 */
public record EffectProgress(
        EffectProgressId id,
        ToolCallId toolCallId,
        long sequence,
        Instant occurredAt,
        ObjectValue details) {

    public EffectProgress {
        id = Contracts.required(id, "effectProgress.id");
        toolCallId = Contracts.required(toolCallId, "effectProgress.toolCallId");
        if (sequence < 1) {
            Contracts.fail(
                    ViolationCode.INVALID_ID,
                    "effectProgress.sequence",
                    "effect progress sequence must be positive");
        }
        occurredAt = Contracts.required(occurredAt, "effectProgress.occurredAt");
        details = Contracts.required(details, "effectProgress.details");
    }

    @Override
    public String toString() {
        return "EffectProgress["
                + "id=<provided>, "
                + "toolCallId=<provided>, "
                + "sequence=<provided>, "
                + "occurredAt=<provided>, "
                + "details=<provided>]";
    }
}
