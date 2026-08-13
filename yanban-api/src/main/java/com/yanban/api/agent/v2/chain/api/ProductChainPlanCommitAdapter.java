package com.yanban.api.agent.v2.chain.api;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.chain.context.ProductChainPermissionPolicySource;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanCodec;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductActiveStepReplanCodec;
import com.yanban.api.agent.v2.persistence.ProductActiveStepReplanRepositoryAdapter;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainAuthorityTime;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.route.ChainPlanCommitPort;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.RequirementDeclarationMode;
import io.paperagent.v2.contracts.ValidationSubject;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedPlanReplan;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanReplanRepository;
import io.paperagent.v2.persistence.PlanReplanRequest;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import org.springframework.stereotype.Component;

/** Adapts an accepted chain PERSISTENT_PLAN to the stable V2 Plan bootstrap. */
@Component
public final class ProductChainPlanCommitAdapter implements ChainPlanCommitPort {
    private static final String AUTHORITY_TYPE = "STABLE_V2_PLAN";
    private static final Duration STEP_LIMIT = Duration.ofMinutes(10);

    private final ChainFoundationRepository foundations;
    private final AgentTurnProductContextResolver contexts;
    private final ProductPersistentPlanBootstrapRequestAdapter requests;
    private final PersistentPlanBootstrapper bootstraps;
    private final ProductPlanBootstrapCodec codec;
    private final StepRecoveryRepository recoveries;
    private final LeaseRepository leases;
    private final PlanReplanRepository replans;
    private final ProductPlanReplanMarkerReader replanMarkers;
    private final ProductPlanReplanCodec replanCodec;
    private final ProductActiveStepReplanRepositoryAdapter activeReplans;
    private final ProductActiveStepReplanCodec activeReplanCodec;

    public ProductChainPlanCommitAdapter(
            ChainFoundationRepository foundations,
            AgentTurnProductContextResolver contexts,
            ProductPersistentPlanBootstrapRequestAdapter requests,
            PersistentPlanBootstrapper bootstraps,
            ProductPlanBootstrapCodec codec,
            StepRecoveryRepository recoveries,
            LeaseRepository leases,
            PlanReplanRepository replans,
            ProductPlanReplanMarkerReader replanMarkers,
            ProductPlanReplanCodec replanCodec,
            ProductActiveStepReplanRepositoryAdapter activeReplans,
            ProductActiveStepReplanCodec activeReplanCodec) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.recoveries = Objects.requireNonNull(recoveries, "recoveries");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.replans = Objects.requireNonNull(replans, "replans");
        this.replanMarkers = Objects.requireNonNull(
                replanMarkers, "replanMarkers");
        this.replanCodec = Objects.requireNonNull(replanCodec, "replanCodec");
        this.activeReplans = Objects.requireNonNull(
                activeReplans, "activeReplans");
        this.activeReplanCodec = Objects.requireNonNull(
                activeReplanCodec, "activeReplanCodec");
    }

    /**
     * Rejects a revision draft that tries to keep the identity of the Step
     * currently being superseded.  The active-step replan transaction cannot
     * make one Step both superseded and current in the replacement revision.
     */
    void validateActiveStepReplacementIdentity(
            ChainPersistenceRecords.PlanBindingRecord currentPlan,
            PlannerPayload.PlanRevision payload) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(payload, "payload");
        var inspected = recoveries.inspect(new PlanId(currentPlan.planId()));
        if (inspected == null
                || inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || inspected.value().isEmpty()
                || !(inspected.value().orElseThrow()
                instanceof PersistedStepRecoveryActive active)) {
            return;
        }
        requireCompletionOnlyDescendant(active.plan(), currentPlan);
        PlanRevision latest = active.plan().latestRevision();
        String activeStepId = active.activation().stepId().value();
        List<String> violations = new ArrayList<>();
        boolean reused = payload.newRevisionDraft().steps().stream()
                .anyMatch(step -> step.stepKey().equals(activeStepId));
        if (reused) {
            violations.add(
                    "newRevisionDraft must replace the superseded active Step "
                            + "with a new stepKey and update dependent "
                            + "dependencyStepKeys; it must not reuse active "
                            + "stepKey " + activeStepId);
        }
        Map<PlanStepId, PlanStep> replacements = stableSteps(
                payload.newRevisionDraft()).stream().collect(
                java.util.stream.Collectors.toMap(
                        PlanStep::id, step -> step));
        for (PlanStepId completedStepId
                : latest.completedFacts().keySet()) {
            PlanStep completed = latest.steps().stream()
                    .filter(step -> step.id().equals(completedStepId))
                    .findFirst().orElseThrow();
            if (!completed.equals(replacements.get(completedStepId))) {
                violations.add(
                        "newRevisionDraft must preserve completed Step "
                                + completedStepId.value()
                                + " byte-for-byte; do not change its "
                                + "dependencies, fields, or identity");
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }

    @Override
    public CommittedPlan commitPersistent(PersistentPlanCommand command) {
        Objects.requireNonNull(command, "command");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId())
                .orElseThrow(() -> failure("CHAIN_PLAN_TASK_NOT_FOUND"));
        ChainPersistenceRecords.InstructionRecord instruction = foundations
                .findInstruction(command.instructionId())
                .orElseThrow(() -> failure("CHAIN_PLAN_INSTRUCTION_NOT_FOUND"));
        requireExactSource(task, instruction, command);

        PlannerPayload.PersistentPlan payload = command.payload();
        ProposalFields.TaskFrameDraft frame = payload.taskFrameDraft();
        requireExplicitRequirements(frame.requirements());
        if (!task.initialProjectVersion().equals(frame.projectVersion())) {
            throw failure("CHAIN_PLAN_PROJECT_VERSION_MISMATCH");
        }
        ExecutionProfile profile = permissionProfile(
                frame.permissionTier(), payload.initialPlan());
        List<PlanStep> steps = stableSteps(payload.initialPlan());
        Instant createdAt = ChainAuthorityTime.atOrAfter(
                command.committedAt(), instruction.createdAt());
        ProductPersistentPlanBootstrapCommand bootstrapCommand =
                new ProductPersistentPlanBootstrapCommand(
                        new RoutingDecision(
                                new RoutingRequestId(command.routeDecisionId()),
                                Route.PERSISTENT_PLAN_EXECUTE,
                                RoutingDecisionReason.DECLARED_REQUIREMENT,
                                routingRequirements(payload)),
                        new TaskFrameDraft(frame.objective(), frame.objects(),
                                frame.deliverables(), frame.constraints(),
                                frame.requirements()),
                        profile,
                        new InitialPlanDraft(
                                "Accepted chain PERSISTENT_PLAN "
                                        + command.proposalId(),
                                steps),
                        createdAt, createdAt, createdAt);

        var context = contexts.resolve(task.userId(), task.turnId());
        if (!Objects.equals(context.identity().userId(), task.userId())
                || !Objects.equals(context.identity().sessionId(),
                task.sessionId())
                || !Objects.equals(context.identity().projectId(),
                task.projectId())) {
            throw failure("CHAIN_PLAN_PRODUCT_IDENTITY_MISMATCH");
        }
        // Current ownership is revalidated above. Replay must still consume
        // the immutable ProjectVersion frozen by the chain Task.
        var request = requests.adapt(context.identity(),
                Optional.of(task.initialProjectVersion()), bootstrapCommand);
        PersistenceResult<PersistedPlanBootstrap> result =
                bootstraps.bootstrap(request);
        PersistedPlanBootstrap persisted = requireCommitted(result);
        verifyCommitted(task, payload, profile, steps, bootstrapCommand,
                persisted);
        ProductPlanBootstrapCodec.EncodedPayload encoded = codec.encode(persisted);
        return new CommittedPlan(
                task.taskId(), persisted.taskFrame().id().value(),
                persisted.plan().id().value(),
                persisted.plan().latestRevision().id().value(),
                persisted.plan().latestRevision().number(), AUTHORITY_TYPE,
                persisted.plan().latestRevision().id().value(),
                encoded.sha256());
    }

    @Override
    public CommittedPlan commitRevision(PlanRevisionCommand command) {
        Objects.requireNonNull(command, "command");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId())
                .orElseThrow(() -> failure("CHAIN_PLAN_TASK_NOT_FOUND"));
        ChainPersistenceRecords.InstructionRecord instruction = foundations
                .findInstruction(command.instructionId())
                .orElseThrow(() -> failure("CHAIN_PLAN_INSTRUCTION_NOT_FOUND"));
        requireExactRevisionSource(task, instruction, command);
        PlanId planId = new PlanId(command.planId());
        String suffix = digest(command.proposalId() + "\0"
                + command.transitionId() + "\0"
                + command.oldPlanRevisionId()).substring(0, 32);
        String eventId = "chain-replan-" + suffix;
        var activeReplay = activeReplans.findCommitted(eventId);
        if (activeReplay.isPresent()) {
            return replayActiveRevision(command,
                    activeReplay.orElseThrow());
        }
        List<ProductPlanReplanMarkerReader.Marker> replay = replanMarkers
                .findAllByPlanId(command.planId()).stream()
                .filter(value -> value.result().replanEvent().id().value()
                        .equals(eventId))
                .toList();
        if (replay.size() > 1) {
            throw failure("CHAIN_PLAN_REVISION_REPLAY_AMBIGUOUS");
        }
        if (replay.size() == 1) {
            return replayRevision(command, replay.get(0).result());
        }
        var inspected = recoveries.inspect(planId);
        if (inspected == null
                || inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || inspected.value().isEmpty()) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_NOT_READY");
        }
        if (inspected.value().orElseThrow()
                instanceof PersistedStepRecoveryActive active) {
            return commitActiveRevision(command, instruction, suffix,
                    eventId, active);
        }
        if (!(inspected.value().orElseThrow()
                instanceof PersistedStepRecoveryReady ready)) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_NOT_READY");
        }
        validatePlanRequirements(ready.taskFrame(),
                command.payload().newRevisionDraft());
        PlanRevision previous = ready.plan().latestRevision();
        Checkpoint head = ready.checkpoint().checkpoint();
        requireCompletionOnlyDescendant(ready.plan(), command);
        if (!ready.taskFrame().id().value().equals(command.taskFrameId())
                || !ready.plan().id().value().equals(command.planId())
                || !head.revisionId().equals(previous.id())
                || head.revisionNumber() != previous.number()) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
        }
        List<PlanStep> steps = stableSteps(
                command.payload().newRevisionDraft());
        Set<PlanStepId> stepIds = steps.stream().map(PlanStep::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!stepIds.containsAll(previous.completedFacts().keySet())) {
            throw failure("CHAIN_PLAN_REVISION_DROPPED_COMPLETED_STEP");
        }
        Instant committedAt = ChainAuthorityTime.atOrAfter(
                command.committedAt(), head.createdAt(),
                instruction.createdAt());
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("chain-revision-" + suffix),
                previous.taskFrameId(), previous.number() + 1,
                Optional.of(previous.id()),
                "Accepted chain PLAN_REVISION " + command.proposalId(),
                committedAt, steps, previous.completedFacts());
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId), previous.taskFrameId(), planId,
                head.lastEventSequence() + 1, committedAt,
                new EventType("PLAN_REPLANNED"), Optional.empty(),
                eventId, new InlineEventPayload(
                new ObjectValue(Map.of())));
        Map<PlanStepId, StepExecutionState> states = new HashMap<>();
        for (PlanStep step : steps) {
            states.put(step.id(), previous.completedFacts()
                    .containsKey(step.id())
                    ? StepExecutionState.SUCCEEDED
                    : StepExecutionState.NOT_STARTED);
        }
        Checkpoint checkpoint = new Checkpoint(
                head.taskFrameId(), planId, revision.id(),
                revision.number(), event.sequence(),
                PlanExecutionState.ACTIVE, states,
                head.receiptReferences(), committedAt);
        var lease = leases.find(planId);
        if (lease == null || lease.outcome() != PersistenceOutcome.FOUND
                || lease.failure().isPresent() || lease.value().isEmpty()) {
            throw failure("CHAIN_PLAN_REVISION_LEASE_NOT_FOUND");
        }
        var authority = lease.value().orElseThrow();
        PersistenceResult<PersistedPlanReplan> result = replans.replan(
                new PlanReplanRequest(planId, authority.leaseToken(),
                        authority.fencingToken(), previous.id(),
                        previous.number(), ready.checkpoint().version(),
                        head.lastEventSequence(), event, revision,
                        checkpoint));
        PersistedPlanReplan persisted = requireReplanCommitted(result);
        return committedRevision(command, persisted);
    }

    private CommittedPlan commitActiveRevision(
            PlanRevisionCommand command,
            ChainPersistenceRecords.InstructionRecord instruction,
            String suffix,
            String replanEventId,
            PersistedStepRecoveryActive active) {
        PlanRevision previous = active.plan().latestRevision();
        Checkpoint head = active.checkpoint().checkpoint();
        requireCompletionOnlyDescendant(active.plan(), command);
        if (!active.taskFrame().id().value().equals(command.taskFrameId())
                || !active.planId().value().equals(command.planId())
                || head.stepStates().get(active.activation().stepId())
                != StepExecutionState.ACTIVE) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
        }
        validatePlanRequirements(active.taskFrame(),
                command.payload().newRevisionDraft());
        List<PlanStep> steps = stableSteps(
                command.payload().newRevisionDraft());
        Set<PlanStepId> stepIds = steps.stream().map(PlanStep::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!stepIds.containsAll(previous.completedFacts().keySet())) {
            throw failure("CHAIN_PLAN_REVISION_DROPPED_COMPLETED_STEP");
        }
        Instant first = ChainAuthorityTime.atOrAfter(
                command.committedAt(), head.createdAt(),
                instruction.createdAt());
        Instant second = first;
        String supersessionId = "step.superseded_by_replan." + digest(
                command.taskId() + "\0" + command.oldPlanRevisionId()
                        + "\0" + active.activation().stepId().value()
                        + "\0" + active.activation().activationEvent()
                                .id().value()
                        + "\0" + command.transitionId());
        EventEnvelope supersession = new EventEnvelope(
                new EventId(supersessionId), previous.taskFrameId(),
                active.planId(), head.lastEventSequence() + 1, first,
                new EventType("STEP_SUPERSEDED_BY_REPLAN"),
                Optional.of(active.activation().activationEvent().id()),
                command.transitionId(), new InlineEventPayload(
                new ObjectValue(Map.of())));
        Map<PlanStepId, StepExecutionState> supersededStates =
                new HashMap<>(head.stepStates());
        supersededStates.put(active.activation().stepId(),
                StepExecutionState.SUPERSEDED_BY_REPLAN);
        Checkpoint superseded = new Checkpoint(
                head.taskFrameId(), head.planId(), head.revisionId(),
                head.revisionNumber(), supersession.sequence(),
                PlanExecutionState.ACTIVE, supersededStates,
                head.receiptReferences(), first);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("chain-revision-" + suffix),
                previous.taskFrameId(), previous.number() + 1,
                Optional.of(previous.id()),
                "Accepted chain PLAN_REVISION " + command.proposalId(),
                second, steps, previous.completedFacts());
        EventEnvelope replan = new EventEnvelope(
                new EventId(replanEventId), previous.taskFrameId(),
                active.planId(), supersession.sequence() + 1, second,
                new EventType("PLAN_REPLANNED"),
                Optional.of(supersession.id()), replanEventId,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Map<PlanStepId, StepExecutionState> replacement = new HashMap<>();
        for (PlanStep step : steps) {
            replacement.put(step.id(), previous.completedFacts()
                    .containsKey(step.id())
                    ? StepExecutionState.SUCCEEDED
                    : StepExecutionState.NOT_STARTED);
        }
        Checkpoint replanned = new Checkpoint(
                head.taskFrameId(), head.planId(), revision.id(),
                revision.number(), replan.sequence(),
                PlanExecutionState.ACTIVE, replacement,
                head.receiptReferences(), second);
        var leaseResult = leases.find(active.planId());
        if (leaseResult == null
                || leaseResult.outcome() != PersistenceOutcome.FOUND
                || leaseResult.failure().isPresent()
                || leaseResult.value().isEmpty()
                || !leaseResult.value().orElseThrow().ownerId().equals(
                active.activation().leaseOwnerId())
                || leaseResult.value().orElseThrow().fencingToken()
                != active.activation().fencingToken()) {
            throw failure("CHAIN_PLAN_REVISION_LEASE_NOT_FOUND");
        }
        var lease = leaseResult.value().orElseThrow();
        var result = activeReplans.supersedeAndReplan(
                new ActiveStepReplanRequest(active.planId(),
                        lease.leaseToken(),
                        active.activation().fencingToken(), previous.id(),
                        previous.number(), active.checkpoint().version(),
                        head.lastEventSequence(),
                        active.activation().stepId(), supersession,
                        superseded, replan, revision, replanned));
        if (result == null || result.failure().isPresent()
                || result.value().isEmpty()
                || (result.outcome() != PersistenceOutcome.APPLIED
                && result.outcome() != PersistenceOutcome.REPLAYED)) {
            throw failure("CHAIN_PLAN_ACTIVE_REVISION_REJECTED");
        }
        return committedActiveRevision(command,
                result.value().orElseThrow());
    }

    private CommittedPlan replayActiveRevision(
            PlanRevisionCommand command,
            PersistedActiveStepReplan persisted) {
        if (!persisted.planId().value().equals(command.planId())
                || !persisted.replannedRevision().taskFrameId().value()
                .equals(command.taskFrameId())
                || persisted.replannedRevision().number()
                <= command.oldPlanRevisionNumber()
                || persisted.replannedRevision().parentRevisionId().isEmpty()
                || !persisted.replannedRevision().steps().equals(
                stableSteps(command.payload().newRevisionDraft()))) {
            throw failure("CHAIN_PLAN_REVISION_REPLAY_MISMATCH");
        }
        return committedActiveRevision(command, persisted);
    }

    private CommittedPlan committedActiveRevision(
            PlanRevisionCommand command,
            PersistedActiveStepReplan persisted) {
        PlanRevision revision = persisted.replannedRevision();
        return new CommittedPlan(command.taskId(), command.taskFrameId(),
                command.planId(), revision.id().value(), revision.number(),
                AUTHORITY_TYPE, revision.id().value(),
                activeReplanCodec.authoritySha256(persisted));
    }

    private CommittedPlan replayRevision(
            PlanRevisionCommand command, PersistedPlanReplan persisted) {
        List<PlanStep> expected = stableSteps(
                command.payload().newRevisionDraft());
        if (!persisted.planId().value().equals(command.planId())
                || !persisted.replannedRevision().taskFrameId().value()
                .equals(command.taskFrameId())
                || persisted.replannedRevision().number()
                <= command.oldPlanRevisionNumber()
                || persisted.replannedRevision().parentRevisionId().isEmpty()
                || !persisted.replannedRevision().steps().equals(expected)) {
            throw failure("CHAIN_PLAN_REVISION_REPLAY_MISMATCH");
        }
        return committedRevision(command, persisted);
    }

    private CommittedPlan committedRevision(
            PlanRevisionCommand command, PersistedPlanReplan persisted) {
        PlanRevision revision = persisted.replannedRevision();
        return new CommittedPlan(command.taskId(), command.taskFrameId(),
                command.planId(), revision.id().value(), revision.number(),
                AUTHORITY_TYPE, revision.id().value(),
                replanCodec.authoritySha256(persisted));
    }

    private static PersistedPlanReplan requireReplanCommitted(
            PersistenceResult<PersistedPlanReplan> result) {
        if (result == null
                || result.failure().isPresent()
                || result.value().isEmpty()
                || (result.outcome() != PersistenceOutcome.APPLIED
                && result.outcome() != PersistenceOutcome.REPLAYED)) {
            throw failure("CHAIN_PLAN_REVISION_REJECTED");
        }
        return result.value().orElseThrow();
    }

    private static void requireExactRevisionSource(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            PlanRevisionCommand command) {
        if (!task.taskId().equals(instruction.originTaskId())
                || task.sessionId() != instruction.sessionId()
                || !command.taskId().equals(task.taskId())
                || !command.instructionId().equals(
                instruction.instructionId())
                || !command.taskFrameId().equals(
                command.payload().taskFrameRef())
                || !command.oldPlanRevisionId().equals(
                command.payload().oldRevisionRef())
                || !command.sourceAuthorityRef().equals(
                command.payload().triggerDecisionOrGapRef())) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
        }
    }

    private static PlanRevision requireCompletionOnlyDescendant(
            io.paperagent.v2.contracts.Plan plan,
            ChainPlanCommitPort.PlanRevisionCommand command) {
        return requireCompletionOnlyDescendant(plan,
                command.taskFrameId(), command.planId(),
                command.oldPlanRevisionId(),
                command.oldPlanRevisionNumber());
    }

    private static PlanRevision requireCompletionOnlyDescendant(
            io.paperagent.v2.contracts.Plan plan,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        return requireCompletionOnlyDescendant(plan,
                binding.taskFrameId(), binding.planId(),
                binding.planRevisionId(), binding.planRevisionNumber());
    }

    /**
     * A chain PlanBinding names the last planning revision. Successful Step
     * completion appends internal revisions without creating another chain
     * binding. Accept that exact completion-only lineage while still rejecting
     * any unrelated or semantic Plan revision.
     */
    private static PlanRevision requireCompletionOnlyDescendant(
            io.paperagent.v2.contracts.Plan plan,
            String taskFrameId, String planId,
            String boundRevisionId, long boundRevisionNumber) {
        if (!plan.id().value().equals(planId)
                || !plan.taskFrameId().value().equals(taskFrameId)) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
        }
        int boundIndex = -1;
        for (int index = 0; index < plan.revisions().size(); index++) {
            PlanRevision revision = plan.revisions().get(index);
            if (revision.id().value().equals(boundRevisionId)
                    && revision.number() == boundRevisionNumber) {
                if (boundIndex >= 0) {
                    throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
                }
                boundIndex = index;
            }
        }
        if (boundIndex < 0) {
            throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
        }
        PlanRevision bound = plan.revisions().get(boundIndex);
        for (int index = boundIndex + 1;
                index < plan.revisions().size(); index++) {
            PlanRevision previous = plan.revisions().get(index - 1);
            PlanRevision current = plan.revisions().get(index);
            if (!current.parentRevisionId().equals(Optional.of(previous.id()))
                    || current.number() != previous.number() + 1
                    || !current.taskFrameId().equals(previous.taskFrameId())
                    || !current.steps().equals(previous.steps())
                    || current.completedFacts().size()
                    != previous.completedFacts().size() + 1
                    || !current.completedFacts().entrySet().containsAll(
                    previous.completedFacts().entrySet())) {
                throw failure("CHAIN_PLAN_REVISION_SOURCE_MISMATCH");
            }
        }
        return bound;
    }

    private static void requireExactSource(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            PersistentPlanCommand command) {
        if (!task.taskId().equals(instruction.originTaskId())
                || task.sessionId() != instruction.sessionId()
                || !command.taskId().equals(task.taskId())
                || !command.instructionId().equals(
                        instruction.instructionId())
                || task.projectId() == null
                || task.initialProjectVersion() == null
                || task.initialProjectVersion().isBlank()) {
            throw failure("CHAIN_PLAN_SOURCE_MISMATCH");
        }
    }

    private static ExecutionProfile permissionProfile(
            String permissionTier, ProposalFields.PlanDraft plan) {
        if (!ProductChainPermissionPolicySource.supports(permissionTier)) {
            throw failure("CHAIN_PLAN_PERMISSION_TIER_UNSUPPORTED");
        }
        return ProductChainPermissionPolicySource.executionProfile(
                plan.steps().stream().anyMatch(
                        ProposalFields.StepDraft::mayChangeCandidate));
    }

    private static Set<RoutingRequirement> routingRequirements(
            PlannerPayload.PersistentPlan payload) {
        LinkedHashSet<RoutingRequirement> requirements =
                new LinkedHashSet<>();
        ProposalFields.RoutingBoundary boundary =
                payload.routingBoundary();
        if (boundary.needsProject()) {
            requirements.add(RoutingRequirement.PROJECT_FILE_ACCESS);
        }
        if (boundary.needsTool()) {
            requirements.add(RoutingRequirement.TOOL_USE);
        }
        if (boundary.needsNetwork()) {
            requirements.add(RoutingRequirement.NETWORK);
        }
        if (boundary.needsPersistentProgress()) {
            requirements.add(RoutingRequirement.EXECUTION);
        }
        if (payload.initialPlan().steps().stream().anyMatch(
                ProposalFields.StepDraft::mayChangeCandidate)) {
            requirements.add(RoutingRequirement.MODIFICATION);
        }
        return Set.copyOf(requirements);
    }

    private static List<PlanStep> stableSteps(
            ProposalFields.PlanDraft plan) {
        Map<String, PlanStepId> ids = new LinkedHashMap<>();
        for (ProposalFields.StepDraft step : plan.steps()) {
            PlanStepId id = new PlanStepId(step.stepKey());
            if (ids.put(step.stepKey(), id) != null) {
                throw failure("CHAIN_PLAN_STEP_ID_DUPLICATE");
            }
        }
        List<PlanStep> result = new ArrayList<>();
        for (ProposalFields.StepDraft step : plan.steps()) {
            LinkedHashSet<PlanStepId> dependencies = new LinkedHashSet<>();
            for (String dependency : step.dependencyStepKeys()) {
                PlanStepId id = ids.get(dependency);
                if (id == null) {
                    throw failure("CHAIN_PLAN_STEP_DEPENDENCY_MISSING");
                }
                dependencies.add(id);
            }
            List<String> criteria = new ArrayList<>(
                    step.completionConditions());
            step.scopes().forEach(scope -> criteria.add(
                    "Allowed scope: " + scope));
            step.constraints().forEach(constraint -> criteria.add(
                    "Step constraint: " + constraint));
            criteria.add(step.mayChangeCandidate()
                    ? "Candidate modification: allowed"
                    : "Candidate modification: forbidden");
            result.add(new PlanStep(
                    ids.get(step.stepKey()), step.objective(),
                    String.join("; ", step.deliverables()), dependencies,
                    criteria, new BoundedExecutionHints(8, STEP_LIMIT),
                    step.constraints(), step.mayChangeCandidate(),
                    step.candidateValidationCompletionCondition(),
                    step.validationRequirementIds()));
        }
        return List.copyOf(result);
    }

    private static PersistedPlanBootstrap requireCommitted(
            PersistenceResult<PersistedPlanBootstrap> result) {
        if (result == null
                || (result.outcome() != PersistenceOutcome.APPLIED
                && result.outcome() != PersistenceOutcome.REPLAYED)
                || result.failure().isPresent()
                || result.value().isEmpty()) {
            throw failure("CHAIN_PLAN_BOOTSTRAP_REJECTED");
        }
        return result.value().orElseThrow();
    }

    private static void verifyCommitted(
            ChainPersistenceRecords.TaskRecord task,
            PlannerPayload.PersistentPlan payload,
            ExecutionProfile profile,
            List<PlanStep> steps,
            ProductPersistentPlanBootstrapCommand requested,
            PersistedPlanBootstrap persisted) {
        var frame = payload.taskFrameDraft();
        var stableFrame = persisted.taskFrame();
        var revision = persisted.plan().latestRevision();
        boolean exactProject = stableFrame.sourceProjectVersion().isPresent()
                && String.valueOf(task.projectId()).equals(
                        stableFrame.sourceProjectVersion().orElseThrow()
                                .projectId())
                && task.initialProjectVersion().equals(
                        stableFrame.sourceProjectVersion().orElseThrow()
                                .versionId());
        boolean exact = stableFrame.objective().equals(frame.objective())
                && stableFrame.targets().equals(frame.objects())
                && stableFrame.deliverables().equals(frame.deliverables())
                && stableFrame.constraints().equals(frame.constraints())
                && stableFrame.requirements().equals(frame.requirements())
                && stableFrame.executionProfile().equals(profile)
                && stableFrame.createdAt().equals(requested.taskFrameCreatedAt())
                && exactProject
                && persisted.plan().taskFrameId().equals(stableFrame.id())
                && persisted.plan().revisions().size() == 1
                && revision.number() == 1
                && revision.parentRevisionId().isEmpty()
                && revision.reason().equals(requested.initialPlanDraft().reason())
                && revision.createdAt().equals(requested.planCreatedAt())
                && revision.steps().equals(steps)
                && revision.completedFacts().isEmpty()
                && persisted.initialCheckpoint().version() == 1
                && persisted.initialCheckpoint().checkpoint().planId()
                        .equals(persisted.plan().id())
                && persisted.initialCheckpoint().checkpoint().taskFrameId()
                        .equals(stableFrame.id())
                && persisted.initialCheckpoint().checkpoint().revisionId()
                        .equals(revision.id())
                && persisted.initialCheckpoint().checkpoint().revisionNumber()
                        == 1;
        if (!exact) {
            throw failure("CHAIN_PLAN_BOOTSTRAP_MISMATCH");
        }
    }

    private static void requireExplicitRequirements(
            io.paperagent.v2.contracts.TaskRequirements requirements) {
        if (requirements == null
                || requirements.declarationMode()
                != RequirementDeclarationMode.EXPLICIT) {
            throw failure("CHAIN_PLAN_REQUIREMENTS_NOT_EXPLICIT");
        }
    }

    private static void validatePlanRequirements(
            TaskFrame taskFrame,
            ProposalFields.PlanDraft plan) {
        requireExplicitRequirements(taskFrame.requirements());
        Map<String, io.paperagent.v2.contracts.ValidationRequirement> required =
                new LinkedHashMap<>();
        taskFrame.requirements().validationRequirements().forEach(requirement ->
                required.put(requirement.requirementId(), requirement));
        Map<String, ProposalFields.StepDraft> bindingSteps =
                new LinkedHashMap<>();
        ProposalFields.StepDraft lastCandidateChangingStep = null;
        for (ProposalFields.StepDraft step : plan.steps()) {
            if (step.mayChangeCandidate()) {
                lastCandidateChangingStep = step;
            }
            for (String requirementId : step.validationRequirementIds()) {
                var requirement = required.get(requirementId);
                if (requirement == null
                        || !step.completionConditions().contains(
                        requirement.completionCondition())
                        || bindingSteps.put(requirementId, step) != null) {
                    throw failure("CHAIN_PLAN_VALIDATION_BINDING_INVALID");
                }
            }
        }
        if (!bindingSteps.keySet().equals(required.keySet())) {
            throw failure("CHAIN_PLAN_VALIDATION_BINDING_INCOMPLETE");
        }
        if (lastCandidateChangingStep != null) {
            List<io.paperagent.v2.contracts.ValidationRequirement> candidate =
                    required.values().stream()
                            .filter(requirement -> requirement.subject()
                                    == ValidationSubject.CANDIDATE)
                            .toList();
            ProposalFields.StepDraft validationStep = candidate.size() == 1
                    ? bindingSteps.get(candidate.get(0).requirementId())
                    : null;
            if (candidate.size() != 1 || validationStep == null
                    || validationStep.mayChangeCandidate()
                    || !dependsOn(validationStep,
                    lastCandidateChangingStep, plan.steps())) {
                throw failure("CHAIN_PLAN_CANDIDATE_VALIDATION_BINDING_INVALID");
            }
        }
    }

    private static boolean dependsOn(
            ProposalFields.StepDraft step,
            ProposalFields.StepDraft requiredPredecessor,
            List<ProposalFields.StepDraft> steps) {
        Map<String, ProposalFields.StepDraft> byKey = new LinkedHashMap<>();
        steps.forEach(value -> byKey.put(value.stepKey(), value));
        java.util.ArrayDeque<String> remaining = new java.util.ArrayDeque<>(
                step.dependencyStepKeys());
        Set<String> visited = new java.util.HashSet<>();
        while (!remaining.isEmpty()) {
            String dependency = remaining.removeFirst();
            if (!visited.add(dependency)) {
                continue;
            }
            if (dependency.equals(requiredPredecessor.stepKey())) {
                return true;
            }
            ProposalFields.StepDraft predecessor = byKey.get(dependency);
            if (predecessor != null) {
                remaining.addAll(predecessor.dependencyStepKeys());
            }
        }
        return false;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
