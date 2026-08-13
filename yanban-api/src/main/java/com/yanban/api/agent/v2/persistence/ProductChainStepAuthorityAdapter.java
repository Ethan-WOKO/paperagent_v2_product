package com.yanban.api.agent.v2.persistence;

import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnStepActivationCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnStepActivationComposer;
import com.yanban.api.agent.v2.progression.AuthenticatedAgentTurnStepProgressionComposer;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionEventDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionFactDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionRevisionDraft;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionOutcome;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;
import java.time.Instant;

/** Bridges the chain Step state machine to the stable V2 Plan/Step authority. */
@Component
public final class ProductChainStepAuthorityAdapter
        implements ChainStepAuthorityPort {
    private static final String STEP_EVENT = "STEP_EVENT";
    private final ChainWorkflowRepository workflows;
    private final ChainFoundationRepository foundations;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductActiveStepReplanJpaRepository replans;
    private final ProductActiveStepReplanMarkerReader replanMarkers;
    private final ProductPlanRevisionAuthoritySource revisionAuthorities;
    private final LeaseRepository leases;
    private final AuthenticatedAgentTurnStepActivationComposer composer;
    private final AuthenticatedAgentTurnStepProgressionComposer progression;
    private final JdbcTemplate jdbc;
    private final StepRecoverer recoverer;
    private final ActiveStepCompletionComposer completion;
    private final ProductStepCompletionCodec completionCodec;
    private final ProductEffectIntentJpaRepository effectIntents;
    private final ProductEffectOutcomeResultJpaRepository effectResults;

    public ProductChainStepAuthorityAdapter(
            ChainWorkflowRepository workflows,
            ChainFoundationRepository foundations,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductStepCompletionJpaRepository completions,
            ProductActiveStepReplanJpaRepository replans,
            LeaseRepository leases,
            AuthenticatedAgentTurnStepActivationComposer composer,
            JdbcTemplate jdbc,
            StepRecoverer recoverer,
            ActiveStepCompletionComposer completion) {
        this(workflows, foundations, bootstraps, activations, activationCodec,
                completions, replans, leases, composer, jdbc, recoverer,
                completion, null, null, null, null, null, null);
    }

    @Autowired
    public ProductChainStepAuthorityAdapter(
            ChainWorkflowRepository workflows,
            ChainFoundationRepository foundations,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductStepCompletionJpaRepository completions,
            ProductActiveStepReplanJpaRepository replans,
            LeaseRepository leases,
            AuthenticatedAgentTurnStepActivationComposer composer,
            JdbcTemplate jdbc,
            StepRecoverer recoverer,
            ActiveStepCompletionComposer completion,
            ProductStepCompletionCodec completionCodec,
            ProductEffectIntentJpaRepository effectIntents,
            ProductEffectOutcomeResultJpaRepository effectResults,
            AuthenticatedAgentTurnStepProgressionComposer progression,
            ProductActiveStepReplanMarkerReader replanMarkers,
            ProductPlanRevisionAuthoritySource revisionAuthorities) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.activations = Objects.requireNonNull(activations, "activations");
        this.activationCodec = Objects.requireNonNull(
                activationCodec, "activationCodec");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.replans = Objects.requireNonNull(replans, "replans");
        this.replanMarkers = replanMarkers;
        this.revisionAuthorities = revisionAuthorities;
        this.leases = Objects.requireNonNull(leases, "leases");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.progression = progression;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.recoverer = recoverer;
        this.completion = completion;
        this.completionCodec = completionCodec;
        this.effectIntents = effectIntents;
        this.effectResults = effectResults;
    }

    /** Compatibility constructor used by activation-only unit tests. */
    public ProductChainStepAuthorityAdapter(
            ChainWorkflowRepository workflows,
            ChainFoundationRepository foundations,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductStepCompletionJpaRepository completions,
            ProductActiveStepReplanJpaRepository replans,
            LeaseRepository leases,
            AuthenticatedAgentTurnStepActivationComposer composer,
            JdbcTemplate jdbc) {
        this(workflows, foundations, bootstraps, activations, activationCodec,
                completions, replans, leases, composer, jdbc, null, null);
    }

    @Override
    public Optional<PlanSnapshot> findPlan(
            String taskId, String planRevisionId) {
        required(taskId, "taskId");
        required(planRevisionId, "planRevisionId");
        List<io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord>
                allBindings = workflows.findPlanBindings(taskId);
        List<io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord>
                bindings = allBindings.stream()
                .filter(value -> value.planRevisionId().equals(planRevisionId))
                .toList();
        if (bindings.isEmpty()) {
            bindings = allBindings.stream()
                    .filter(value -> bootstraps.find(new PlanId(value.planId()))
                            .map(value2 -> value2.plan().revisions().stream()
                                    .anyMatch(revision -> revision.id().value()
                                            .equals(planRevisionId)))
                            .orElse(false)
                            || hasCompletedRevision(value.planId(), planRevisionId))
                    .toList();
        }
        if (bindings.isEmpty()) {
            return Optional.empty();
        }
        if (bindings.size() != 1) {
            throw failure("CHAIN_STEP_PLAN_BINDING_AMBIGUOUS");
        }
        var binding = bindings.get(0);
        PersistedPlanBootstrap bootstrap = bootstraps
                .find(new PlanId(binding.planId()))
                .orElseThrow(() -> failure("CHAIN_STEP_PLAN_BOOTSTRAP_MISSING"));
        if (!bootstrap.taskFrame().id().value().equals(binding.taskFrameId())
                || !bootstrap.plan().taskFrameId().value().equals(
                binding.taskFrameId())) {
            throw failure("CHAIN_STEP_PLAN_BOOTSTRAP_IDENTITY_MISMATCH");
        }
        var revisions = bootstrap.plan().revisions().stream()
                .filter(value -> value.id().value().equals(planRevisionId))
                .toList();
        PlanRevision revision = revisions.size() == 1 ? revisions.get(0)
                : storedRevision(binding.planId(), planRevisionId);
        if (revision == null) {
            throw failure("CHAIN_STEP_PLAN_REVISION_NOT_STABLE");
        }
        if ((revision.id().value().equals(binding.planRevisionId())
                && revision.number() != binding.planRevisionNumber())
                || !revision.taskFrameId().value().equals(binding.taskFrameId())) {
            throw failure("CHAIN_STEP_PLAN_REVISION_IDENTITY_MISMATCH");
        }
        List<StepDefinition> steps = new ArrayList<>();
        for (int index = 0; index < revision.steps().size(); index++) {
            var step = revision.steps().get(index);
            steps.add(new StepDefinition(
                    step.id().value(), index + 1,
                    step.dependencies().stream().map(PlanStepId::value)
                            .collect(Collectors.toUnmodifiableSet())));
        }
        String candidateKey = workflows.findWorkspaceCandidates(taskId).stream()
                .reduce((left, right) -> right)
                .map(ChainPersistenceRecords.WorkspaceCandidateRecord::workspaceCandidateId)
                .orElse(ChainIdentity.NONE);
        return Optional.of(new PlanSnapshot(
                taskId, binding.taskFrameId(), binding.planId(),
                planRevisionId, candidateKey, binding.instructionId(),
                steps));
    }

    public PlanRevision latestPlanRevision(String planId, String fallbackRevisionId) {
        PlanRevision completed = jdbc.query("""
                SELECT result_format_version, result_sha256, result_json
                  FROM agent_v2_step_completions
                 WHERE plan_id = ?
                 ORDER BY result_revision_number DESC
                 LIMIT 1
                """, rs -> rs.next() ? completionCodec.decodeResult(
                        rs.getInt(1), rs.getString(2), rs.getString(3))
                        .completedRevision() : null, planId);
        if (completed != null) {
            return completed;
        }
        return bootstraps.find(new PlanId(planId)).orElseThrow()
                .plan().revisions().stream()
                .filter(value -> value.id().value().equals(fallbackRevisionId))
                .findFirst().orElseThrow();
    }

    /** Exact stable revision lookup; never selects by latest time or number. */
    public Optional<PlanRevision> findPlanRevision(
            String taskId, String planRevisionId) {
        PlanSnapshot snapshot = findPlan(taskId, planRevisionId).orElse(null);
        if (snapshot == null) return Optional.empty();
        PersistedPlanBootstrap bootstrap = bootstraps
                .find(new PlanId(snapshot.planId()))
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_PLAN_BOOTSTRAP_MISSING"));
        List<PlanRevision> bootstrapMatches = bootstrap.plan().revisions()
                .stream().filter(value -> value.id().value().equals(
                        planRevisionId)).toList();
        if (bootstrapMatches.size() > 1) {
            throw failure("CHAIN_STEP_PLAN_REVISION_AMBIGUOUS");
        }
        if (bootstrapMatches.size() == 1) {
            return Optional.of(bootstrapMatches.get(0));
        }
        return Optional.ofNullable(storedRevision(
                snapshot.planId(), planRevisionId));
    }

    private boolean hasCompletedRevision(String planId, String revisionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent_v2_step_completions
                 WHERE plan_id = ? AND result_revision_id = ?
                """, Integer.class, planId, revisionId);
        return count != null && count == 1;
    }

    private PlanRevision completionRevision(String planId, String revisionId) {
        return jdbc.query("""
                SELECT result_format_version, result_sha256, result_json
                  FROM agent_v2_step_completions
                 WHERE plan_id = ? AND result_revision_id = ?
                 LIMIT 1
                """, rs -> rs.next() ? completionCodec.decodeResult(
                        rs.getInt(1), rs.getString(2), rs.getString(3))
                .completedRevision() : null, planId, revisionId);
    }

    private PlanRevision storedRevision(String planId, String revisionId) {
        ArrayList<PlanRevision> matches = new ArrayList<>();
        PlanRevision completed = completionRevision(planId, revisionId);
        if (completed != null) {
            matches.add(completed);
        }
        if (revisionAuthorities != null) {
            revisionAuthorities.find(planId, revisionId)
                    .map(ProductPlanRevisionAuthoritySource.RevisionAuthority
                            ::revision)
                    .ifPresent(matches::add);
        }
        if (matches.size() > 1) {
            throw failure("CHAIN_STEP_PLAN_REVISION_AMBIGUOUS");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    @Override
    public List<StepEvent> findStepEvents(
            String taskId, String planRevisionId) {
        PlanSnapshot plan = findPlan(taskId, planRevisionId).orElse(null);
        if (plan == null) {
            return List.of();
        }
        Map<String, StageRef> stages = stageReferences(taskId);
        List<StepEvent> result = new ArrayList<>();
        List<ProductActiveStepReplanEntity> replanRows = replans
                .findAllByPlanIdOrderBySourceEventSequenceAsc(plan.planId());
        StepEventWindow eventWindow = stepEventWindow(
                planRevisionId, replanRows);
        activations.findAllByPlanId(plan.planId()).stream()
                .filter(row -> eventWindow.contains(
                        row.resultEventSequence()))
                .forEach(row -> addActivationIfFormal(
                        result, stages, taskId, planRevisionId,
                        plan.planId(), row));
        completions.findAllByPlanId(plan.planId()).stream()
                .filter(row -> eventWindow.contains(
                        row.resultEventSequence()))
                .forEach(row -> addCompletionIfFormal(result, stages, taskId,
                        planRevisionId, row));
        replanRows.stream()
                .filter(row -> eventWindow.contains(
                        row.supersessionEventSequence()))
                .forEach(row -> addIfFormal(result, stages, taskId,
                        planRevisionId,
                        row.supersessionEventId(), row.supersededStepId(),
                        activationId(plan.planId(), row.supersededStepId()),
                        StepEventKind.SUPERSEDED_BY_REPLAN,
                        row.supersessionEventSequence(), row.committedAt()));
        return result.stream()
                .sorted(Comparator.comparingLong(StepEvent::authoritySequence))
                .toList();
    }

    private static StepEventWindow stepEventWindow(
            String planRevisionId,
            List<ProductActiveStepReplanEntity> replans) {
        List<ProductActiveStepReplanEntity> starts = replans.stream()
                .filter(row -> row.resultRevisionId().equals(planRevisionId))
                .toList();
        if (starts.size() > 1) {
            throw failure("CHAIN_STEP_PLAN_REVISION_AMBIGUOUS");
        }
        long startExclusive = starts.isEmpty()
                ? 0L : starts.get(0).resultEventSequence();
        Long endInclusive = replans.stream()
                .filter(row -> row.resultEventSequence() > startExclusive)
                .min(Comparator.comparingLong(
                        ProductActiveStepReplanEntity::resultEventSequence))
                .map(ProductActiveStepReplanEntity::supersessionEventSequence)
                .orElse(null);
        return new StepEventWindow(startExclusive, endInclusive);
    }

    @Override
    public AppendResult<StepEvent> appendStepEvent(StepEventCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.eventKind() == StepEventKind.SUPERSEDED_BY_REPLAN) {
            return replaySupersession(command);
        }
        PlanSnapshot plan = findPlan(command.taskId(), command.planRevisionId())
                .orElseThrow(() -> failure("CHAIN_STEP_PLAN_NOT_FOUND"));
        if (plan.steps().stream().noneMatch(
                value -> value.stepId().equals(command.stepId()))) {
            throw failure("CHAIN_STEP_DEFINITION_NOT_FOUND");
        }
        if (command.eventKind() != StepEventKind.ACTIVATED) {
            return appendTerminalEvent(command, plan);
        }
        var transition = workflows.findTransition(command.transitionId())
                .orElseThrow(() -> failure("CHAIN_STEP_TRANSITION_NOT_FOUND"));
        if (!transition.taskId().equals(command.taskId())
                || !transition.sourceDecisionId().equals(
                command.sourceDecisionId())) {
            throw failure("CHAIN_STEP_TRANSITION_IDENTITY_MISMATCH");
        }
        ProductStepActivationEntity existing = activations
                .findById(command.eventId()).orElse(null);
        if (existing != null) {
            return replayExisting(command, plan, existing);
        }
        var task = foundations.findTask(command.taskId())
                .orElseThrow(() -> failure("CHAIN_STEP_TASK_NOT_FOUND"));
        // The execution start already owns the live fenced lease for this
        // plan. Step activation must continue with that exact authority;
        // acquiring a second token would be rejected as LEASE_HELD.
        LeaseRecord executionLease = requireLease(new PlanId(plan.planId()));
        var payload = activationPayload(command);
        var attempt = new StepActivationAttempt(
                executionLease.ownerId(), executionLease.leaseToken(),
                executionLease.expiresAt(),
                new StepActivationEventDraft(
                        new EventId(command.eventId()), command.committedAt(),
                        new EventType("STEP_ACTIVATED"), Optional.empty(),
                        command.transitionId(), payload),
                command.committedAt());
        boolean firstActivation = activations.findAllByPlanId(
                plan.planId()).isEmpty();
        StepActivationCompositionOutcome outcome;
        if (firstActivation) {
            outcome = composer.activate(task.userId(), task.turnId(),
                    new AuthenticatedAgentTurnStepActivationCommand(
                            new PlanStepId(command.stepId()), attempt));
        } else {
            if (progression == null) {
                throw failure("CHAIN_STEP_PROGRESSION_AUTHORITY_NOT_CONFIGURED");
            }
            // Only the first activation is materialized from the immutable
            // execution-start checkpoint. Every later activation must use the
            // recovery inspector's latest READY checkpoint, where completed
            // dependencies and their facts are authoritative.
            outcome = progression.activateReady(
                    task.userId(), task.turnId(), attempt);
        }
        if (!(outcome instanceof StepActivationCommitted committed)) {
            if (outcome instanceof io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseRejected rejected) {
                throw failure("CHAIN_STEP_ACTIVATION_LEASE_REJECTED_"
                        + rejected.failure().code().name());
            }
            if (outcome instanceof io.paperagent.v2.runtime.execution.activation.composition.StepActivationPersistenceRejected rejected) {
                throw failure("CHAIN_STEP_ACTIVATION_PERSISTENCE_REJECTED_"
                        + rejected.failure().code().name());
            }
            throw failure("CHAIN_STEP_ACTIVATION_NOT_COMMITTED");
        }
        var persisted = committed.persistedActivation();
        boolean stableTimeBound = firstActivation
                ? persisted.activationEvent().occurredAt().equals(
                        command.committedAt())
                : !persisted.activationEvent().occurredAt().isBefore(
                        command.committedAt())
                        && persisted.activationEvent().occurredAt().equals(
                        persisted.activatedCheckpoint().checkpoint()
                                .createdAt());
        String stableRevisionId = persisted.activatedCheckpoint().checkpoint()
                .revisionId().value();
        boolean stableRevisionBound = firstActivation
                ? stableRevisionId.equals(command.planRevisionId())
                : stableRevisionId.equals(command.planRevisionId())
                        || hasCompletedRevision(plan.planId(), stableRevisionId);
        if (!persisted.planId().value().equals(plan.planId())
                || !persisted.stepId().value().equals(command.stepId())
                || !persisted.activationEvent().id().value().equals(
                command.eventId())
                || !stableTimeBound
                || !persisted.activationEvent().correlationId().equals(
                command.transitionId())
                || !persisted.activationEvent().payload().equals(payload)
                || !stableRevisionBound) {
            throw failure("CHAIN_STEP_ACTIVATION_IDENTITY_MISMATCH");
        }
        LeaseRecord currentLease = requireLease(persisted.planId());
        if (!currentLease.ownerId().equals(executionLease.ownerId())
                || !currentLease.leaseToken().equals(
                executionLease.leaseToken())
                || currentLease.fencingToken() != persisted.fencingToken()) {
            throw failure("CHAIN_STEP_ACTIVATION_LEASE_MISMATCH");
        }
        StepEvent event = new StepEvent(command,
                persisted.activationEvent().sequence());
        return new AppendResult<>(event,
                committed.activationOutcome() == PersistenceOutcome.REPLAYED);
    }

    private AppendResult<StepEvent> appendTerminalEvent(
            StepEventCommand command, PlanSnapshot plan) {
        if (command.eventKind() != StepEventKind.COMPLETED) {
            throw failure("CHAIN_STEP_TERMINAL_EVENT_UNSUPPORTED");
        }
        String expectedEventId = "step.completed." + sha256(
                command.taskId() + "\0" + command.planRevisionId() + "\0"
                        + command.stepId() + "\0" + command.activationEventId()
                        + "\0" + command.transitionId());
        if (!expectedEventId.equals(command.eventId())) {
            throw failure("CHAIN_STEP_TERMINAL_IDENTITY_MISMATCH");
        }
        if (recoverer == null || completion == null) {
            throw failure("CHAIN_STEP_TERMINAL_AUTHORITY_NOT_CONFIGURED");
        }
        var transition = workflows.findTransition(command.transitionId())
                .orElseThrow(() -> failure("CHAIN_STEP_TRANSITION_NOT_FOUND"));
        if (!transition.taskId().equals(command.taskId())
                || !transition.sourceDecisionId().equals(
                        command.sourceDecisionId())) {
            throw failure("CHAIN_STEP_TRANSITION_IDENTITY_MISMATCH");
        }
        var bindings = workflows.findPlanBindings(command.taskId()).stream()
                .filter(value -> value.planRevisionId().equals(
                        command.planRevisionId()))
                .toList();
        if (bindings.size() != 1
                || !bindings.get(0).taskId().equals(command.taskId())
                || !bindings.get(0).planId().equals(plan.planId())
                || !bindings.get(0).taskFrameId().equals(plan.taskFrameId())) {
            throw failure("CHAIN_STEP_PLAN_BINDING_IDENTITY_MISMATCH");
        }
        List<StepEvent> activations = findStepEvents(
                command.taskId(), command.planRevisionId()).stream()
                .filter(value -> value.command().eventKind()
                        == StepEventKind.ACTIVATED)
                .filter(value -> value.command().stepId().equals(command.stepId()))
                .filter(value -> value.command().eventId().equals(
                        command.activationEventId()))
                .toList();
        if (activations.size() != 1) {
            throw failure("CHAIN_STEP_ACTIVATION_IDENTITY_MISMATCH");
        }
        var existing = completions.findByPlanIdAndStepIdAndActivationEventId(
                plan.planId(), command.stepId(), command.activationEventId());
        if (existing.isPresent()) {
            ProductStepCompletionEntity row = existing.get();
            if (!row.completionEventId().equals(command.eventId())
                    || !row.sourceRevisionId().equals(command.planRevisionId())
                    || row.resultEventSequence() < 1) {
                throw failure("CHAIN_STEP_TERMINAL_REPLAY_MISMATCH");
            }
            return new AppendResult<>(new StepEvent(command,
                    row.resultEventSequence()), true);
        }
        LeaseRecord lease = requireLease(new PlanId(plan.planId()));
        StepRecoveryCompositionOutcome recovered = recoverer.recover(
                new StepRecoveryRequest(new PlanId(plan.planId()),
                        new StepRecoveryLeaseAttempt(lease.ownerId(),
                                lease.leaseToken(), lease.expiresAt())));
        if (!(recovered instanceof RecoveredActiveStep active)
                || !active.recovery().activation().stepId().value()
                        .equals(command.stepId())
                || !active.recovery().activation().activationEvent().id().value()
                        .equals(command.activationEventId())) {
            throw failure("CHAIN_STEP_TERMINAL_RECOVERY_IDENTITY_MISMATCH");
        }
        Instant occurredAt = command.committedAt().isBefore(
                active.recovery().checkpoint().checkpoint().createdAt())
                ? active.recovery().checkpoint().checkpoint().createdAt()
                : command.committedAt();
        String eventId = command.eventId();
        String digest = sha256(command.taskId() + "\0" + command.planRevisionId()
                + "\0" + command.stepId() + "\0" + command.activationEventId()
                + "\0" + command.transitionId());
        var request = new ActiveStepCompletionMaterializationRequest(
                active,
                new ActiveStepCompletionFactDraft(
                        "sha256." + sha256("chain-step-completion\0" + digest),
                        occurredAt, effectReceiptReferences(
                                plan.planId(), command.stepId(),
                                command.activationEventId())),
                new ActiveStepCompletionEventDraft(new EventId(eventId),
                        occurredAt, new EventType("STEP_COMPLETED"),
                        Optional.of(new EventId(command.activationEventId())),
                        command.transitionId(), new InlineEventPayload(
                                new ObjectValue(Map.of(
                                        "taskId", new TextValue(command.taskId()),
                                        "planRevisionId", new TextValue(
                                                command.planRevisionId()),
                                        "stepId", new TextValue(command.stepId()),
                                        "activationEventId", new TextValue(
                                                command.activationEventId()),
                                        "transitionId", new TextValue(
                                                command.transitionId()))))),
                new ActiveStepCompletionRevisionDraft(
                        new PlanRevisionId("chain-completion." + digest),
                        "complete Step from accepted chain result", occurredAt),
                occurredAt);
        var result = completion.compose(request);
        if (!(result instanceof ActiveStepCompletionCommitted committed)
                || !committed.persistedCompletion().completionEvent().id()
                        .value().equals(eventId)) {
            throw failure("CHAIN_STEP_TERMINAL_COMMIT_NOT_CONFIRMED");
        }
        return new AppendResult<>(new StepEvent(command,
                committed.persistedCompletion().completionEvent().sequence()),
                committed.persistenceOutcome() == PersistenceOutcome.REPLAYED);
    }

    private AppendResult<StepEvent> replaySupersession(
            StepEventCommand command) {
        if (replanMarkers == null) {
            throw failure("CHAIN_STEP_REPLAN_AUTHORITY_NOT_CONFIGURED");
        }
        ProductActiveStepReplanEntity row = replans
                .findBySupersessionEventId(command.eventId())
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_REPLAN_AUTHORITY_NOT_FOUND"));
        ProductActiveStepReplanMarkerReader.Marker marker =
                replanMarkers.read(row);
        if (marker == null) {
            throw failure("CHAIN_STEP_REPLAN_AUTHORITY_INVALID");
        }
        var result = marker.result();
        var event = result.supersessionEvent();
        var superseded = result.supersededCheckpoint().checkpoint();
        var transition = workflows.findTransition(command.transitionId())
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_REPLAN_TRANSITION_NOT_FOUND"));
        var binding = workflows.findPlanBindings(command.taskId()).stream()
                .filter(value -> value.planRevisionId().equals(
                        command.planRevisionId()))
                .toList();
        if (binding.size() != 1
                || !transition.taskId().equals(command.taskId())
                || !transition.sourceDecisionId().equals(
                command.sourceDecisionId())
                || transition.transitionType()
                != io.paperagent.v2.chain.ChainTransitionType.PLAN_CHANGE
                || !binding.get(0).planId().equals(
                result.planId().value())
                || !result.supersededStepId().value().equals(
                command.stepId())
                || !event.id().value().equals(command.eventId())
                || !event.correlationId().equals(command.transitionId())
                || event.causationId().isEmpty()
                || !event.causationId().orElseThrow().value().equals(
                command.activationEventId())
                || !superseded.revisionId().value().equals(
                command.planRevisionId())
                || superseded.stepStates().get(
                result.supersededStepId())
                != io.paperagent.v2.contracts.StepExecutionState
                .SUPERSEDED_BY_REPLAN
                || event.occurredAt().isBefore(command.committedAt())) {
            throw failure("CHAIN_STEP_REPLAN_IDENTITY_MISMATCH");
        }
        return new AppendResult<>(new StepEvent(command, event.sequence()),
                true);
    }

    private List<ReceiptId> effectReceiptReferences(
            String planId, String stepId, String activationEventId) {
        if (effectIntents == null || effectResults == null) {
            return List.of();
        }
        return effectIntents.findAllByPlanId(planId).stream()
                .filter(row -> row.stepId().equals(stepId)
                        && row.activationEventId().equals(activationEventId))
                .sorted(Comparator.comparing(ProductEffectIntentEntity::toolCallId))
                .map(row -> effectResults.findById(row.toolCallId()).orElse(null))
                .filter(Objects::nonNull)
                .map(row -> new ReceiptId(row.receiptId()))
                .toList();
    }

    private AppendResult<StepEvent> replayExisting(
            StepEventCommand command, PlanSnapshot plan,
            ProductStepActivationEntity row) {
        var request = activationCodec.decodeRequest(
                row.requestFormatVersion(), row.requestSha256(),
                row.requestJson());
        var result = activationCodec.decodeResult(
                row.resultFormatVersion(), row.resultSha256(),
                row.resultJson());
        InlineEventPayload expectedPayload = activationPayload(command);
        boolean stableTimeBound = request.expectedCheckpointVersion() == 2
                ? request.activationEvent().occurredAt().equals(
                        command.committedAt())
                : !request.activationEvent().occurredAt().isBefore(
                        command.committedAt())
                        && request.activationEvent().occurredAt().equals(
                        result.activatedCheckpoint().checkpoint().createdAt());
        String stableRevisionId = request.expectedRevisionId().value();
        boolean stableRevisionBound = stableRevisionId.equals(
                command.planRevisionId())
                || hasCompletedRevision(plan.planId(), stableRevisionId);
        boolean exact = row.planId().equals(plan.planId())
                && row.stepId().equals(command.stepId())
                && row.activationEventId().equals(command.eventId())
                && row.sourceRevisionId().equals(command.planRevisionId())
                && request.planId().value().equals(plan.planId())
                && request.stepId().value().equals(command.stepId())
                && stableRevisionBound
                && request.activationEvent().id().value().equals(
                command.eventId())
                && stableTimeBound
                && request.activationEvent().correlationId().equals(
                command.transitionId())
                && request.activationEvent().payload().equals(expectedPayload)
                && result.planId().value().equals(plan.planId())
                && result.stepId().value().equals(command.stepId())
                && result.activationEvent().equals(request.activationEvent())
                && result.activatedCheckpoint().checkpoint().revisionId()
                .equals(request.expectedRevisionId());
        if (!exact) {
            throw failure("CHAIN_STEP_ACTIVATION_REPLAY_MISMATCH");
        }
        return new AppendResult<>(new StepEvent(
                command, result.activationEvent().sequence()), true);
    }

    private Map<String, StageRef> stageReferences(String taskId) {
        List<StageRef> values = jdbc.query("""
                SELECT stage.successor_authority_ref,
                       stage.transition_id,
                       transition_row.source_decision_id
                  FROM agent_v2_chain_transition_stages stage
                  JOIN agent_v2_chain_transitions transition_row
                    ON transition_row.transition_id = stage.transition_id
                 WHERE transition_row.task_id = ?
                   AND stage.successor_authority_type = ?
                """, (rs, row) -> new StageRef(
                        rs.getString(1), rs.getString(2), rs.getString(3)),
                taskId, STEP_EVENT);
        Map<String, StageRef> result = new HashMap<>();
        for (StageRef value : values) {
            StageRef previous = result.putIfAbsent(value.eventId(), value);
            if (previous != null && !previous.equals(value)) {
                throw failure("CHAIN_STEP_FORMAL_STAGE_AMBIGUOUS");
            }
        }
        return Map.copyOf(result);
    }

    private static void addIfFormal(
            List<StepEvent> target, Map<String, StageRef> stages,
            String taskId, String planRevisionId,
            String eventId, String stepId, String activationEventId,
            StepEventKind kind, long authoritySequence,
            java.time.Instant committedAt) {
        StageRef stage = stages.get(eventId);
        if (stage != null) {
            target.add(new StepEvent(new StepEventCommand(
                    eventId, taskId, planRevisionId, stepId,
                    activationEventId, kind, stage.sourceDecisionId(),
                    stage.transitionId(), committedAt), authoritySequence));
        }
    }

    private void addCompletionIfFormal(
            List<StepEvent> target, Map<String, StageRef> stages,
            String taskId, String planRevisionId,
            ProductStepCompletionEntity row) {
        StageRef stage = stages.get(row.completionEventId());
        if (stage == null && completionCodec != null) {
            var persisted = completionCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            var transition = workflows.findTransition(
                    persisted.completionEvent().correlationId()).orElse(null);
            if (transition != null && transition.taskId().equals(taskId)) {
                stage = new StageRef(row.completionEventId(),
                        transition.transitionId(), transition.sourceDecisionId());
            }
        }
        if (stage != null) {
            addIfFormal(target, Map.of(row.completionEventId(), stage), taskId,
                    planRevisionId, row.completionEventId(), row.stepId(),
                    row.activationEventId(), StepEventKind.COMPLETED,
                    row.resultEventSequence(), row.committedAt());
        }
    }

    private void addActivationIfFormal(
            List<StepEvent> target, Map<String, StageRef> stages,
            String taskId, String planRevisionId, String planId,
            ProductStepActivationEntity row) {
        StageRef stage = stages.get(row.activationEventId());
        if (stage == null) {
            var persisted = activationCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            boolean stableIdentity = persisted.planId().value().equals(planId)
                    && persisted.stepId().value().equals(row.stepId())
                    && persisted.activationEvent().id().value().equals(
                            row.activationEventId());
            var transition = stableIdentity
                    ? workflows.findTransition(
                            persisted.activationEvent().correlationId())
                            .orElse(null)
                    : null;
            if (transition != null && transition.taskId().equals(taskId)) {
                stage = new StageRef(row.activationEventId(),
                        transition.transitionId(),
                        transition.sourceDecisionId());
            }
        }
        if (stage != null) {
            addIfFormal(target, Map.of(row.activationEventId(), stage), taskId,
                    planRevisionId, row.activationEventId(), row.stepId(),
                    row.activationEventId(), StepEventKind.ACTIVATED,
                    row.resultEventSequence(), row.committedAt());
        }
    }

    private String activationId(String planId, String stepId) {
        List<ProductStepActivationEntity> rows = activations
                .findAllByPlanId(planId).stream()
                .filter(value -> value.stepId().equals(stepId)).toList();
        if (rows.size() != 1) {
            throw failure("CHAIN_STEP_ACTIVATION_AUTHORITY_MISSING");
        }
        return rows.get(0).activationEventId();
    }

    private static InlineEventPayload activationPayload(
            StepEventCommand command) {
        Map<String, io.paperagent.v2.contracts.ContractValue> values =
                new LinkedHashMap<>();
        values.put("sourceDecisionId", new TextValue(
                command.sourceDecisionId()));
        values.put("transitionId", new TextValue(command.transitionId()));
        values.put("taskId", new TextValue(command.taskId()));
        values.put("planRevisionId", new TextValue(
                command.planRevisionId()));
        values.put("stepId", new TextValue(command.stepId()));
        return new InlineEventPayload(new ObjectValue(values));
    }

    private LeaseRecord requireLease(PlanId planId) {
        var result = leases.find(planId);
        if (result == null || result.outcome() != PersistenceOutcome.FOUND
                || result.failure().isPresent()
                || result.value().isEmpty()) {
            throw failure("CHAIN_STEP_ACTIVATION_LEASE_NOT_FOUND");
        }
        return result.value().orElseThrow();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record StageRef(
            String eventId, String transitionId, String sourceDecisionId) { }

    private record StepEventWindow(long startExclusive, Long endInclusive) {
        private boolean contains(long eventSequence) {
            return eventSequence > startExclusive
                    && (endInclusive == null
                    || eventSequence <= endInclusive);
        }
    }
}
