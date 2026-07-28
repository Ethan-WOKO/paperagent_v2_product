package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.PersistedEffectIntent;
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
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministic, sanitized drafts derived exclusively from persisted facts. */
final class EffectDrivenStepProgressionDrafts {
    private EffectDrivenStepProgressionDrafts() {
    }

    static ActiveStepCompletionMaterializationRequest completion(
            RecoveredActiveStep active,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        String evidenceHash = receiptHash(intent, receipt);
        EventId eventId = new EventId(deterministic(
                "effect-completion-event", evidenceHash));
        PlanRevisionId revisionId = new PlanRevisionId(deterministic(
                "effect-completion-revision", evidenceHash));
        var occurredAt = receipt.endedAt();
        var payload = payload(
                intent.intent().planId(), intent.intent().stepId(),
                intent.intent().toolCallId().value(), receipt.id().value(),
                receipt.status().name(),
                intent.activationEventId().value());
        return new ActiveStepCompletionMaterializationRequest(
                active,
                new ActiveStepCompletionFactDraft(
                        evidenceHash, occurredAt, List.of(receipt.id())),
                new ActiveStepCompletionEventDraft(
                        eventId, occurredAt, new EventType("STEP_COMPLETED"),
                        Optional.of(intent.activationEventId()),
                        deterministic("effect-completion-correlation",
                                evidenceHash),
                        payload),
                new ActiveStepCompletionRevisionDraft(
                        revisionId,
                        "complete Step from successful persisted effect",
                        occurredAt),
                occurredAt);
    }

    static StepActivationAttempt activation(
            PersistedStepRecoveryReady ready,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt,
            EffectDrivenStepProgressionActivationLeaseAttempt lease) {
        EventId completionEventId = completionEventId(intent, receipt);
        String authority = nextActivationAuthority(
                ready.readyStepId(), intent, receipt);
        var occurredAt = ready.checkpoint().checkpoint().createdAt();
        return new StepActivationAttempt(
                lease.leaseOwnerId(), lease.leaseToken(),
                lease.leaseExpiresAt(),
                new StepActivationEventDraft(
                        nextActivationEventId(
                                ready.readyStepId(), intent, receipt),
                        occurredAt,
                        new EventType("STEP_ACTIVATED"),
                        Optional.of(completionEventId),
                        deterministic(
                                "effect-next-activation-correlation",
                                authority),
                        payload(
                                intent.intent().planId(),
                                ready.readyStepId(),
                                intent.intent().toolCallId().value(),
                                receipt.id().value(),
                                receipt.status().name(),
                                completionEventId.value())),
                occurredAt);
    }

    static EventId completionEventId(
            PersistedEffectIntent intent, ExecutionReceipt receipt) {
        return new EventId(deterministic(
                "effect-completion-event", receiptHash(intent, receipt)));
    }

    static EventId nextActivationEventId(
            PlanStepId nextStepId,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        return new EventId(deterministic(
                "effect-next-activation-event",
                nextActivationAuthority(nextStepId, intent, receipt)));
    }

    private static String nextActivationAuthority(
            PlanStepId nextStepId,
            PersistedEffectIntent intent,
            ExecutionReceipt receipt) {
        return deterministic(
                "effect-next-activation-authority",
                completionEventId(intent, receipt).value(),
                nextStepId.value(),
                receipt.id().value());
    }

    static String receiptHash(
            PersistedEffectIntent intent, ExecutionReceipt receipt) {
        MessageDigest digest = digest();
        add(digest, "effect-completion-evidence-v1");
        add(digest, intent.intent().planId().value());
        add(digest, intent.intent().stepId().value());
        add(digest, intent.activationEventId().value());
        add(digest, receipt.id().value());
        add(digest, receipt.toolCallId().value());
        add(digest, receipt.status().name());
        add(digest, receipt.startedAt().toString());
        add(digest, receipt.endedAt().toString());
        addOptional(digest, receipt.exitCode().map(String::valueOf));
        addOptional(digest, receipt.resultCode());
        addCapture(digest, receipt.standardOutput());
        addCapture(digest, receipt.standardError());
        addList(digest, receipt.artifactReferences().stream()
                .map(ArtifactRef::value).toList());
        addOptional(digest, receipt.resultingDiff()
                .map(value -> value.value()));
        addList(digest, receipt.eventReferences().stream()
                .map(EventId::value).toList());
        return "sha256." + hex(digest.digest());
    }

    private static InlineEventPayload payload(
            PlanId planId, PlanStepId stepId, String toolCallId,
            String receiptId, String status, String causeId) {
        return new InlineEventPayload(new ObjectValue(Map.of(
                "planId", new TextValue(planId.value()),
                "stepId", new TextValue(stepId.value()),
                "toolCallId", new TextValue(toolCallId),
                "receiptId", new TextValue(receiptId),
                "status", new TextValue(status),
                "causeId", new TextValue(causeId))));
    }

    private static String deterministic(String domain, String... values) {
        MessageDigest digest = digest();
        add(digest, domain);
        for (String value : values) {
            add(digest, value);
        }
        return domain + "." + hex(digest.digest());
    }

    private static void addCapture(
            MessageDigest digest,
            io.paperagent.v2.contracts.OutputCapture capture) {
        addOptional(digest, capture.inlineText());
        addOptional(digest, capture.artifactRef().map(ArtifactRef::value));
        add(digest, Boolean.toString(capture.truncated()));
    }

    private static void addList(MessageDigest digest, List<String> values) {
        add(digest, Integer.toString(values.size()));
        values.forEach(value -> add(digest, value));
    }

    private static void addOptional(
            MessageDigest digest, Optional<String> value) {
        add(digest, value.isPresent() ? "1" : "0");
        value.ifPresent(item -> add(digest, item));
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 0xf, 16));
            value.append(Character.forDigit(item & 0xf, 16));
        }
        return value.toString();
    }
}
