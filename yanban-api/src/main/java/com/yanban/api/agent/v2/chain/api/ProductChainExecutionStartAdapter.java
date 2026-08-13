package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainAuthorityTime;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartOutcome;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartRequest;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Starts the stable V2 execution bound to one chain PLAN_CHANGE transition. */
@Component
public final class ProductChainExecutionStartAdapter {
    private static final String PLAN_AUTHORITY_TYPE = "STABLE_V2_PLAN";
    private static final String START_AUTHORITY_TYPE =
            "STABLE_V2_EXECUTION_START";
    private static final EventType PLAN_STARTED = new EventType("PLAN_STARTED");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(30);

    private final ChainWorkflowRepository workflow;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductPlanBootstrapCodec codec;
    private final FreshExecutionStarter starter;

    public ProductChainExecutionStartAdapter(
            ChainWorkflowRepository workflow,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductPlanBootstrapCodec codec,
            FreshExecutionStarter starter) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    /**
     * Establishes the execution start, or replays the exact same authority.
     * The supplied binding must already be an authoritative chain fact.
     */
    public ExecutionStartAuthority ensureStarted(PlanBindingRecord binding) {
        Objects.requireNonNull(binding, "binding");
        String transitionId = required(
                binding.transitionId(), "CHAIN_EXECUTION_TRANSITION_ID_MISSING");
        TransitionRecord transition = workflow.findTransition(transitionId)
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTION_TRANSITION_NOT_FOUND"));
        verifyChainAuthority(binding, transition);

        PlanId planId = new PlanId(binding.planId());
        PersistedPlanBootstrap bootstrap = bootstraps.find(planId)
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTION_BOOTSTRAP_NOT_FOUND"));
        verifyBootstrap(binding, bootstrap);

        FreshExecutionStartAttempt attempt = attempt(transition);
        FreshExecutionStartOutcome outcome = starter.start(
                new FreshExecutionStartRequest(
                        PersistenceResult.applied(bootstrap),
                        Optional.of(attempt)));
        if (!(outcome instanceof FreshExecutionStarted started)) {
            throw failure("CHAIN_EXECUTION_START_NOT_COMMITTED");
        }
        PersistedExecutionStart persisted = started.persistedStart();
        verifyStarted(binding, attempt, persisted);
        return new ExecutionStartAuthority(
                binding.taskId(), transitionId, binding.taskFrameId(),
                binding.planId(), binding.planRevisionId(),
                binding.planRevisionNumber(), START_AUTHORITY_TYPE,
                persisted.startEvent().id().value(),
                persisted.startEvent().id().value(),
                persisted.leaseOwnerId(), persisted.fencingToken(),
                persisted.startedCheckpoint().version());
    }

    private void verifyChainAuthority(
            PlanBindingRecord binding, TransitionRecord transition) {
        if (!transition.taskId().equals(binding.taskId())
                || transition.transitionType() != ChainTransitionType.PLAN_CHANGE
                || !transition.transitionId().equals(binding.transitionId())
                || !PLAN_AUTHORITY_TYPE.equals(binding.authorityType())
                || !binding.authorityId().equals(binding.planRevisionId())) {
            throw failure("CHAIN_EXECUTION_CHAIN_AUTHORITY_MISMATCH");
        }
        List<PlanBindingRecord> matching = workflow
                .findPlanBindings(binding.taskId()).stream()
                .filter(value -> value.planBindingId().equals(
                        binding.planBindingId()))
                .toList();
        if (matching.size() != 1 || !matching.get(0).equals(binding)) {
            throw failure("CHAIN_EXECUTION_PLAN_BINDING_NOT_STABLE");
        }
    }

    private void verifyBootstrap(
            PlanBindingRecord binding, PersistedPlanBootstrap bootstrap) {
        var frame = bootstrap.taskFrame();
        var plan = bootstrap.plan();
        var revision = plan.latestRevision();
        var checkpoint = bootstrap.initialCheckpoint();
        boolean exact = frame.id().value().equals(binding.taskFrameId())
                && plan.id().value().equals(binding.planId())
                && plan.taskFrameId().equals(frame.id())
                && revision.id().value().equals(binding.planRevisionId())
                && revision.number() == binding.planRevisionNumber()
                && checkpoint.version() == 1
                && checkpoint.checkpoint().taskFrameId().equals(frame.id())
                && checkpoint.checkpoint().planId().equals(plan.id())
                && checkpoint.checkpoint().revisionId().equals(revision.id())
                && checkpoint.checkpoint().revisionNumber()
                        == revision.number()
                && checkpoint.checkpoint().lastEventSequence() == 0
                && checkpoint.checkpoint().planState()
                        == PlanExecutionState.NOT_STARTED
                && codec.encode(bootstrap).sha256().equals(
                        binding.authoritySha256());
        if (!exact) {
            throw failure("CHAIN_EXECUTION_BOOTSTRAP_IDENTITY_MISMATCH");
        }
    }

    private static FreshExecutionStartAttempt attempt(
            TransitionRecord transition) {
        String identity = transition.taskId() + "\0"
                + transition.transitionId();
        String digest = sha256(identity);
        Instant occurredAt = ChainAuthorityTime.normalize(
                transition.createdAt());
        return new FreshExecutionStartAttempt(
                "chain-exec-owner." + digest,
                "chain-exec-token." + digest,
                transition.createdAt().plus(LEASE_DURATION),
                new ExecutionStartEventDraft(
                        new EventId("chain-exec-start." + digest),
                        occurredAt, PLAN_STARTED, Optional.empty(),
                        transition.transitionId(),
                        new InlineEventPayload(
                                new ObjectValue(Map.of()))),
                occurredAt);
    }

    private static void verifyStarted(
            PlanBindingRecord binding,
            FreshExecutionStartAttempt attempt,
            PersistedExecutionStart persisted) {
        EventEnvelope event = persisted.startEvent();
        var checkpoint = persisted.startedCheckpoint();
        boolean exact = persisted.planId().value().equals(binding.planId())
                && persisted.leaseOwnerId().equals(attempt.leaseOwnerId())
                && persisted.fencingToken() > 0
                && event.id().equals(attempt.eventDraft().id())
                && event.taskFrameId().value().equals(binding.taskFrameId())
                && event.planId().value().equals(binding.planId())
                && event.sequence() == 1
                && event.occurredAt().equals(
                        attempt.eventDraft().occurredAt())
                && event.type().equals(PLAN_STARTED)
                && event.causationId().isEmpty()
                && event.correlationId().equals(binding.transitionId())
                && event.payload().equals(attempt.eventDraft().payload())
                && checkpoint.version() == 2
                && checkpoint.checkpoint().taskFrameId().value()
                        .equals(binding.taskFrameId())
                && checkpoint.checkpoint().planId().value()
                        .equals(binding.planId())
                && checkpoint.checkpoint().revisionId().value()
                        .equals(binding.planRevisionId())
                && checkpoint.checkpoint().revisionNumber()
                        == binding.planRevisionNumber()
                && checkpoint.checkpoint().lastEventSequence() == 1
                && checkpoint.checkpoint().planState()
                        == PlanExecutionState.ACTIVE
                && checkpoint.checkpoint().createdAt().equals(
                        attempt.checkpointCreatedAt());
        if (!exact) {
            throw failure("CHAIN_EXECUTION_START_IDENTITY_MISMATCH");
        }
    }

    private static String required(String value, String code) {
        if (value == null || value.isBlank()) {
            throw failure(code);
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    public record ExecutionStartAuthority(
            String taskId,
            String transitionId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            long planRevisionNumber,
            String authorityType,
            String authorityRef,
            String startEventId,
            String leaseOwnerId,
            long fencingToken,
            long checkpointVersion) {

        public ExecutionStartAuthority {
            required(taskId, "CHAIN_EXECUTION_AUTHORITY_TASK_ID_MISSING");
            required(transitionId,
                    "CHAIN_EXECUTION_AUTHORITY_TRANSITION_ID_MISSING");
            required(taskFrameId,
                    "CHAIN_EXECUTION_AUTHORITY_TASK_FRAME_ID_MISSING");
            required(planId, "CHAIN_EXECUTION_AUTHORITY_PLAN_ID_MISSING");
            required(planRevisionId,
                    "CHAIN_EXECUTION_AUTHORITY_REVISION_ID_MISSING");
            required(authorityType,
                    "CHAIN_EXECUTION_AUTHORITY_TYPE_MISSING");
            required(authorityRef,
                    "CHAIN_EXECUTION_AUTHORITY_REF_MISSING");
            required(startEventId,
                    "CHAIN_EXECUTION_AUTHORITY_EVENT_ID_MISSING");
            required(leaseOwnerId,
                    "CHAIN_EXECUTION_AUTHORITY_LEASE_OWNER_MISSING");
            if (planRevisionNumber < 1 || fencingToken < 1
                    || checkpointVersion < 1) {
                throw failure("CHAIN_EXECUTION_AUTHORITY_NUMBER_INVALID");
            }
        }
    }
}
