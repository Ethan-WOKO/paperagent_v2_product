package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.time.Instant;
import java.util.Objects;

/**
 * Typed cancellation boundary. Implementations must translate this command to
 * {@link io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime.Cancelled} and
 * delegate to the sole {@code ChainTaskOutcomeRuntime}; they must not write a
 * TaskOutcome directly.
 */
public interface ChainTaskOutcomeCommandPort {
    CancellationSubmission submitCancelled(CancelledTaskOutcomeCommand command);

    record CancelledTaskOutcomeCommand(
            String eventId,
            String taskId,
            String sourceCommandId,
            String instructionId,
            String sourceRequestSha256,
            Instant createdAt) {
        public CancelledTaskOutcomeCommand {
            eventId = required(eventId, "eventId");
            taskId = required(taskId, "taskId");
            sourceCommandId = required(sourceCommandId, "sourceCommandId");
            instructionId = required(instructionId, "instructionId");
            sha256(sourceRequestSha256, "sourceRequestSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    record CancellationSubmission(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            boolean replayed) {
        public CancellationSubmission {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void sha256(String value, String name) {
        required(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }
}
