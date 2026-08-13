package io.paperagent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.Objects;

/**
 * Typed completion boundary. Implementations must delegate to the sole
 * {@link io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime}.
 */
public interface ChainCompletedOutcomePort {
    CompletionSubmission complete(CompletionCommand command);

    record CompletionCommand(
            String sourceCommandId,
            String finalizationTransitionId,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainProjectPublishPort.Published published) {
        public CompletionCommand {
            sourceCommandId = required(sourceCommandId, "sourceCommandId");
            finalizationTransitionId = required(
                    finalizationTransitionId, "finalizationTransitionId");
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(check, "check");
        }
    }

    record CompletionSubmission(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            boolean replayed) {
        public CompletionSubmission {
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
}
