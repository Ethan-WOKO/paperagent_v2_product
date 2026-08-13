package io.paperagent.v2.chain.delivery;

import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.time.Instant;
import java.util.Objects;

/**
 * Product message boundary used only by {@link ChainDeliveryRuntime}. The
 * implementation reserves a stable message identity and must atomically append
 * the returned Delivery event with assistant-message insertion on SUCCEEDED.
 * It receives only the authoritative content ref and digest, never a body copy.
 * Attempt authority-event source digests are fixed as SHA-256 of
 * {@code deliveryId\0attemptNo\0status} for success and
 * {@code deliveryId\0attemptNo\0status\0errorCode} for failure.
 */
public interface ChainDeliveryMessagePort {
    long reserveAssistantMessage(Reservation command);

    AttemptSubmission attempt(AttemptCommand command);

    record Reservation(
            String deliveryId,
            String taskId,
            String answerContentId,
            String answerBodySha256) {
        public Reservation {
            deliveryId = required(deliveryId, "deliveryId");
            taskId = required(taskId, "taskId");
            answerContentId = required(answerContentId, "answerContentId");
            sha256(answerBodySha256, "answerBodySha256");
        }
    }

    record AttemptCommand(
            String deliveryId,
            String taskId,
            String answerContentId,
            String answerBodySha256,
            long assistantMessageId,
            int attemptNo,
            long eventSequence,
            String successEventId,
            String failureEventId,
            boolean terminalOnFailure,
            String runtimePolicyVersion,
            Instant committedAt) {
        public AttemptCommand {
            deliveryId = required(deliveryId, "deliveryId");
            taskId = required(taskId, "taskId");
            answerContentId = required(answerContentId, "answerContentId");
            sha256(answerBodySha256, "answerBodySha256");
            if (assistantMessageId < 1) {
                throw new IllegalArgumentException(
                        "assistantMessageId must be positive");
            }
            if (attemptNo < 1 || eventSequence < 2) {
                throw new IllegalArgumentException(
                        "delivery attempt identity must be positive");
            }
            successEventId = required(successEventId, "successEventId");
            failureEventId = required(failureEventId, "failureEventId");
            runtimePolicyVersion = required(
                    runtimePolicyVersion, "runtimePolicyVersion");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    record AttemptSubmission(
            ChainPersistenceRecords.DeliveryEventRecord event,
            boolean replayed) {
        public AttemptSubmission {
            Objects.requireNonNull(event, "event");
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
            throw new IllegalArgumentException(
                    name + " must be lowercase SHA-256");
        }
    }
}
