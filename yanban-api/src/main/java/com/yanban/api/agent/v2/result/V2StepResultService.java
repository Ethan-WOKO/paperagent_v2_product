package com.yanban.api.agent.v2.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable lifecycle for untrusted proposals and accepted Step results. */
@Service
public class V2StepResultService {
    private static final int MAX_RESULT_CHARACTERS = 20_000;
    private static final int MAX_REASON_CHARACTERS = 1_000;
    private static final int MAX_RECEIPTS = 256;
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() { };

    private final V2StepResultJpaRepository repository;
    private final StepRecoveryRepository recoveries;
    private final ObjectMapper json;
    private final V2EffectHistorySource effectHistory;

    public V2StepResultService(
            V2StepResultJpaRepository repository,
            StepRecoveryRepository recoveries,
            ObjectMapper json) {
        this(repository, recoveries, json, null);
    }

    @Autowired
    public V2StepResultService(
            V2StepResultJpaRepository repository,
            StepRecoveryRepository recoveries,
            ObjectMapper json,
            V2EffectHistorySource effectHistory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.recoveries = Objects.requireNonNull(recoveries, "recoveries");
        this.json = Objects.requireNonNull(json, "json");
        this.effectHistory = effectHistory;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public V2StepResultSnapshot propose(
            RecoveredActiveStep active,
            V2StepResultSource source,
            String proposedText,
            List<ReceiptId> evidenceReceiptIds) {
        Objects.requireNonNull(active, "active");
        return propose(
                active.recovery(), source, proposedText,
                evidenceReceiptIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public V2StepResultSnapshot proposeCurrent(
            PlanId planId,
            PlanStepId stepId,
            V2StepResultSource source,
            String proposedText,
            List<ReceiptId> evidenceReceiptIds) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        var inspected = recoveries.inspect(planId);
        if (inspected == null
                || inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || !(inspected.value().orElse(null)
                        instanceof io.paperagent.v2.persistence
                                .PersistedStepRecoveryActive active)
                || !active.activation().stepId().equals(stepId)) {
            throw new IllegalStateException(
                    "current Step result authority is unavailable");
        }
        return propose(active, source, proposedText, evidenceReceiptIds);
    }

    private V2StepResultSnapshot propose(
            io.paperagent.v2.persistence.PersistedStepRecoveryActive recovery,
            V2StepResultSource source,
            String proposedText,
            List<ReceiptId> evidenceReceiptIds) {
        Objects.requireNonNull(source, "source");
        String text = boundedText(proposedText, "proposedText");
        List<ReceiptId> receipts = receipts(evidenceReceiptIds);
        requireReceiptAuthority(
                recovery.planId(), recovery.activation().stepId(), receipts);
        String activationId = recovery.activation()
                .activationEvent().id().value();
        String proposalSha = hash(String.join("\0",
                "step-result-proposal-v1",
                recovery.planId().value(),
                recovery.plan().latestRevision().id().value(),
                recovery.activation().stepId().value(),
                activationId,
                source.name(),
                text,
                receipts.stream().map(ReceiptId::value)
                        .sorted().reduce("", (left, right) ->
                                left + "\0" + right)));
        Optional<V2StepResultEntity> replay = repository
                .findByActivationEventIdAndSourceAndProposedSha256(
                        activationId, source.name(), proposalSha);
        if (replay.isPresent()) {
            return snapshot(replay.orElseThrow());
        }
        Instant now = Instant.now();
        String resultId = "step-result." + hash(String.join("\0",
                "step-result-id-v1", activationId,
                source.name(), proposalSha));
        V2StepResultEntity entity = new V2StepResultEntity(
                resultId,
                recovery.planId().value(),
                recovery.plan().latestRevision().id().value(),
                recovery.activation().stepId().value(),
                activationId,
                source.name(),
                text,
                proposalSha,
                writeReceiptIds(receipts),
                now);
        return snapshot(repository.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public V2StepResultSnapshot accept(
            String resultId, String acceptedText) {
        V2StepResultEntity entity = repository
                .findLockedByResultId(requiredId(resultId))
                .orElseThrow(() -> new IllegalStateException(
                        "Step result proposal is missing"));
        String text = boundedText(acceptedText, "acceptedText");
        if (!V2StepResultStatus.ACCEPTED.name().equals(entity.status())) {
            requireCurrentAuthority(entity);
            repository
                    .findFirstByActivationEventIdAndStatusOrderByUpdatedAtDesc(
                            entity.activationEventId(),
                            V2StepResultStatus.ACCEPTED.name())
                    .filter(other -> !other.resultId().equals(
                            entity.resultId()))
                    .ifPresent(other -> {
                        throw new IllegalStateException(
                                "Step activation already has an accepted result");
                    });
        }
        String acceptedSha = hash(String.join("\0",
                "step-result-accepted-v1", entity.resultId(), text));
        entity.accept(text, acceptedSha, Instant.now());
        return snapshot(repository.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public V2StepResultSnapshot reject(
            String resultId, String reason) {
        V2StepResultEntity entity = repository
                .findLockedByResultId(requiredId(resultId))
                .orElseThrow(() -> new IllegalStateException(
                        "Step result proposal is missing"));
        if (V2StepResultStatus.ACCEPTED.name().equals(entity.status())) {
            return snapshot(entity);
        }
        String boundedReason = reason == null ? "not accepted"
                : reason.strip();
        if (boundedReason.isBlank()) {
            boundedReason = "not accepted";
        }
        if (boundedReason.length() > MAX_REASON_CHARACTERS) {
            boundedReason = boundedReason.substring(
                    0, MAX_REASON_CHARACTERS);
        }
        entity.reject(boundedReason, Instant.now());
        return snapshot(repository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public Optional<V2StepResultSnapshot> acceptedForActive(
            io.paperagent.v2.persistence.PersistedStepRecoveryActive active) {
        Objects.requireNonNull(active, "active");
        String activationId = active.activation().activationEvent()
                .id().value();
        return repository
                .findFirstByActivationEventIdAndStatusOrderByUpdatedAtDesc(
                        activationId,
                        V2StepResultStatus.ACCEPTED.name())
                .map(value -> {
                    if (!value.planId().equals(active.planId().value())
                            || !value.stepId().equals(active.activation()
                                    .stepId().value())
                            || !value.planRevisionId().equals(active.plan()
                                    .latestRevision().id().value())) {
                        throw new IllegalStateException(
                                "accepted Step result authority mismatch");
                    }
                    return snapshot(value);
                });
    }

    @Transactional(readOnly = true)
    public Optional<V2StepResultSnapshot> recoverableForActive(
            RecoveredActiveStep active) {
        Objects.requireNonNull(active, "active");
        String activationId = active.recovery().activation()
                .activationEvent().id().value();
        Optional<V2StepResultEntity> accepted = repository
                .findFirstByActivationEventIdAndStatusOrderByUpdatedAtDesc(
                        activationId,
                        V2StepResultStatus.ACCEPTED.name());
        Optional<V2StepResultEntity> recoverable = accepted.isPresent()
                ? accepted
                : repository
                        .findFirstByActivationEventIdAndStatusOrderByUpdatedAtDesc(
                                activationId,
                                V2StepResultStatus.PROPOSED.name());
        return recoverable.map(value -> {
            if (!value.planId().equals(active.planId().value())
                    || !value.stepId().equals(active.recovery()
                            .activation().stepId().value())
                    || !value.planRevisionId().equals(active.recovery()
                            .plan().latestRevision().id().value())) {
                throw new IllegalStateException(
                        "recoverable Step result authority mismatch");
            }
            return snapshot(value);
        });
    }

    @Transactional(readOnly = true)
    public List<V2StepResultSnapshot> acceptedCompletedFacts(
            PlanId planId) {
        Objects.requireNonNull(planId, "planId");
        var inspected = recoveries.inspect(planId);
        if (inspected == null
                || inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || inspected.value().isEmpty()) {
            return List.of();
        }
        var snapshot = inspected.value().orElseThrow();
        java.util.Set<PlanStepId> completed;
        if (snapshot instanceof io.paperagent.v2.persistence
                .PersistedStepRecoveryActive value) {
            completed = value.plan().latestRevision()
                    .completedFacts().keySet();
        } else if (snapshot instanceof io.paperagent.v2.persistence
                .PersistedStepRecoveryReady value) {
            completed = value.plan().latestRevision()
                    .completedFacts().keySet();
        } else if (snapshot instanceof io.paperagent.v2.persistence
                .PersistedStepRecoverySucceeded value) {
            completed = value.plan().latestRevision()
                    .completedFacts().keySet();
        } else {
            return List.of();
        }
        java.util.Map<String, V2StepResultEntity> latestByStep =
                new java.util.LinkedHashMap<>();
        repository.findAllByPlanIdOrderByCreatedAtAsc(
                        planId.value()).stream()
                .filter(value -> V2StepResultStatus.ACCEPTED.name()
                        .equals(value.status()))
                .filter(value -> completed.contains(
                        new PlanStepId(value.stepId())))
                .forEach(value -> latestByStep.put(
                        value.stepId(), value));
        return latestByStep.values().stream()
                .map(this::snapshot).toList();
    }

    @Transactional(readOnly = true)
    public Optional<V2StepResultSnapshot> latestAcceptedCompleted(
            PlanId planId) {
        return acceptedCompletedFacts(planId).stream()
                .reduce((ignored, latest) -> latest);
    }

    private void requireCurrentAuthority(V2StepResultEntity entity) {
        var inspected = recoveries.inspect(new PlanId(entity.planId()));
        if (inspected == null
                || inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || inspected.value().isEmpty()
                || !(inspected.value().orElseThrow()
                        instanceof io.paperagent.v2.persistence
                                .PersistedStepRecoveryActive active)
                || !active.activation().stepId().value()
                        .equals(entity.stepId())
                || !active.activation().activationEvent().id().value()
                        .equals(entity.activationEventId())
                || !active.plan().latestRevision().id().value()
                        .equals(entity.planRevisionId())) {
            throw new IllegalStateException(
                    "Step result authority is no longer active");
        }
    }

    private V2StepResultSnapshot snapshot(V2StepResultEntity entity) {
        return new V2StepResultSnapshot(
                entity.resultId(),
                new PlanId(entity.planId()),
                new io.paperagent.v2.contracts.PlanRevisionId(
                        entity.planRevisionId()),
                new PlanStepId(entity.stepId()),
                new io.paperagent.v2.contracts.EventId(
                        entity.activationEventId()),
                V2StepResultSource.valueOf(entity.source()),
                entity.proposedText(),
                entity.proposedSha256(),
                readReceiptIds(entity.evidenceReceiptIdsJson()),
                V2StepResultStatus.valueOf(entity.status()),
                Optional.ofNullable(entity.acceptedText()),
                Optional.ofNullable(entity.acceptedSha256()),
                entity.createdAt(), entity.updatedAt());
    }

    private String writeReceiptIds(List<ReceiptId> receiptIds) {
        try {
            return json.writeValueAsString(receiptIds.stream()
                    .map(ReceiptId::value).toList());
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Step result receipt references cannot be encoded");
        }
    }

    private void requireReceiptAuthority(
            PlanId planId, PlanStepId stepId,
            List<ReceiptId> receiptIds) {
        if (receiptIds.isEmpty()) {
            return;
        }
        if (effectHistory == null) {
            throw new IllegalStateException(
                    "Step result receipt authority is unavailable");
        }
        java.util.Set<ReceiptId> authoritative = effectHistory
                .inspect(planId, stepId).stream()
                .filter(V2EffectHistorySource.Entry::completed)
                .map(entry -> entry.result().receipt().id())
                .collect(java.util.stream.Collectors.toSet());
        if (!authoritative.containsAll(receiptIds)) {
            throw new IllegalStateException(
                    "Step result receipt authority mismatch");
        }
    }

    private List<ReceiptId> readReceiptIds(String encoded) {
        try {
            return json.readValue(encoded, STRING_LIST).stream()
                    .map(ReceiptId::new).toList();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Step result receipt references are invalid");
        }
    }

    private static List<ReceiptId> receipts(List<ReceiptId> value) {
        List<ReceiptId> result = List.copyOf(
                Objects.requireNonNull(value, "evidenceReceiptIds"));
        if (result.size() > MAX_RECEIPTS
                || result.stream().anyMatch(Objects::isNull)
                || result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException(
                    "evidenceReceiptIds are invalid");
        }
        return result;
    }

    private static String boundedText(String value, String name) {
        if (value == null || value.isBlank()
                || value.length() > MAX_RESULT_CHARACTERS) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }

    private static String requiredId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("resultId is invalid");
        }
        return value;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
