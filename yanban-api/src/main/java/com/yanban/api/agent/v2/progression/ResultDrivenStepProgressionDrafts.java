package com.yanban.api.agent.v2.progression;

import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionEventDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionFactDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionRevisionDraft;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministic drafts derived from one accepted model-only Step result. */
final class ResultDrivenStepProgressionDrafts {
    private ResultDrivenStepProgressionDrafts() {
    }

    static ActiveStepCompletionMaterializationRequest completion(
            RecoveredActiveStep active,
            V2StepResultSnapshot result) {
        String acceptedHash = result.acceptedSha256().orElseThrow();
        Instant authorityTime = active.recovery().checkpoint()
                .checkpoint().createdAt();
        Instant occurredAt = result.updatedAt().isBefore(authorityTime)
                ? authorityTime : result.updatedAt();
        EventId eventId = completionEventId(result);
        return new ActiveStepCompletionMaterializationRequest(
                active,
                new ActiveStepCompletionFactDraft(
                        "sha256." + acceptedHash,
                        occurredAt, List.of()),
                new ActiveStepCompletionEventDraft(
                        eventId, occurredAt,
                        new EventType("STEP_COMPLETED"),
                        Optional.of(result.activationEventId()),
                        deterministic(
                                "result-completion-correlation",
                                result.resultId(), acceptedHash),
                        payload(result, "ACCEPTED")),
                new ActiveStepCompletionRevisionDraft(
                        new PlanRevisionId(deterministic(
                                "result-completion-revision",
                                result.resultId(), acceptedHash)),
                        "complete Step from accepted persisted result",
                        occurredAt),
                occurredAt);
    }

    static StepActivationAttempt activation(
            PersistedStepRecoveryReady ready,
            V2StepResultSnapshot result,
            EffectDrivenStepProgressionActivationLeaseAttempt lease) {
        Instant occurredAt = ready.checkpoint().checkpoint().createdAt();
        EventId completionEventId = completionEventId(result);
        String acceptedHash = result.acceptedSha256().orElseThrow();
        return new StepActivationAttempt(
                lease.leaseOwnerId(), lease.leaseToken(),
                lease.leaseExpiresAt(),
                new StepActivationEventDraft(
                        new EventId(deterministic(
                                "result-next-activation-event",
                                result.resultId(), acceptedHash,
                                ready.readyStepId().value())),
                        occurredAt,
                        new EventType("STEP_ACTIVATED"),
                        Optional.of(completionEventId),
                        deterministic(
                                "result-next-activation-correlation",
                                result.resultId(), acceptedHash,
                                ready.readyStepId().value()),
                        payload(result, "NEXT_STEP")),
                occurredAt);
    }

    static EventId completionEventId(V2StepResultSnapshot result) {
        return new EventId(deterministic(
                "result-completion-event", result.resultId(),
                result.acceptedSha256().orElseThrow()));
    }

    private static InlineEventPayload payload(
            V2StepResultSnapshot result, String status) {
        return new InlineEventPayload(new ObjectValue(Map.of(
                "planId", new TextValue(result.planId().value()),
                "stepId", new TextValue(result.stepId().value()),
                "resultId", new TextValue(result.resultId()),
                "resultHash", new TextValue(
                        result.acceptedSha256().orElseThrow()),
                "status", new TextValue(status),
                "causeId", new TextValue(
                        result.activationEventId().value()))));
    }

    private static String deterministic(String domain, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, domain);
            for (String value : values) {
                add(digest, value);
            }
            return domain + "."
                    + HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
