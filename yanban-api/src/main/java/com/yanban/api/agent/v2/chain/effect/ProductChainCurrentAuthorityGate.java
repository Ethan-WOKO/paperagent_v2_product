package com.yanban.api.agent.v2.chain.effect;

import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.instruction.ChainInstructionState;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.step.ChainStepCommitGate;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** One product gate for instruction, Plan, active Step and Candidate fences. */
@Component
public final class ProductChainCurrentAuthorityGate
        implements ChainEffectRuntime.CurrentAuthorityGate, ChainStepCommitGate {
    private final ChainInstructionStateReader instructions;
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final StepRecoveryRepository recovery;
    private final ChainStepAuthorityPort steps;

    public ProductChainCurrentAuthorityGate(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            StepRecoveryRepository recovery,
            ChainStepAuthorityPort steps) {
        this.instructions = new ChainInstructionStateReader(
                Objects.requireNonNull(foundations, "foundations"),
                Objects.requireNonNull(workflow, "workflow"),
                Objects.requireNonNull(finalization, "finalization"));
        this.foundations = foundations;
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.steps = Objects.requireNonNull(steps, "steps");
    }

    @Override
    public ChainEffectRuntime.GateStatus classify(
            ChainEffectRuntime.FrozenMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        ChainInstructionState state = instructions.read(mutation.taskId());
        if (!state.currentInstruction().instructionId().equals(
                mutation.instructionId())) {
            return ChainEffectRuntime.GateStatus.SUPERSEDED;
        }
        return switch (state.gate()) {
            case CANCELLED -> ChainEffectRuntime.GateStatus.CANCELLED;
            case SUPERSEDED, TERMINAL ->
                    ChainEffectRuntime.GateStatus.SUPERSEDED;
            case PAUSED_FOR_DISPOSITION, PAUSED_FOR_PENDING_VALIDATION,
                    PLANNING, DIRECT_ANSWER ->
                    ChainEffectRuntime.GateStatus.PAUSED;
            case SIDE_EFFECTS_ALLOWED -> currentMutation(state, mutation);
        };
    }

    @Override
    public void requireCurrent(GateQuery query) {
        Objects.requireNonNull(query, "query");
        ChainInstructionState state = instructions.read(query.taskId());
        boolean instructionCurrent = state.currentInstruction().instructionId().equals(
                query.instructionId());
        boolean gateOpen = state.gate()
                == ChainInstructionState.Gate.SIDE_EFFECTS_ALLOWED;
        boolean planCurrent = currentPlan(
                state, query.taskId(), query.instructionId(),
                query.taskFrameId(), query.planId(), query.planRevisionId());
        boolean stepCurrent = currentStep(
                query.kind(), query.taskId(), query.planId(),
                query.taskFrameId(), query.planRevisionId(),
                query.stepId(), query.activationEventId());
        if (!instructionCurrent || !gateOpen || !planCurrent || !stepCurrent) {
            String stepDiagnostic = stepCurrent ? "current"
                    : diagnoseCurrentStep(
                            query.taskId(), query.planId(), query.taskFrameId(),
                            query.planRevisionId(), query.stepId(),
                            query.activationEventId());
            throw new IllegalStateException(
                    "chain Step authority is no longer current"
                            + " [instructionCurrent=" + instructionCurrent
                            + ", gateOpen=" + gateOpen
                            + ", planCurrent=" + planCurrent
                            + ", stepCurrent=" + stepCurrent
                            + ", stepDiagnostic=" + stepDiagnostic + "]");
        }
    }

    private ChainEffectRuntime.GateStatus currentMutation(
            ChainInstructionState state,
            ChainEffectRuntime.FrozenMutation mutation) {
        if (!currentPlan(state,
                mutation.taskId(), mutation.instructionId(),
                mutation.taskFrameId(), mutation.planId(),
                mutation.planRevisionId())
                || !currentStep(ChainStepCommitGate.CommitKind.ACTION_BINDING,
                mutation.taskId(), mutation.planId(), mutation.taskFrameId(),
                mutation.planRevisionId(), mutation.stepId(),
                mutation.activationEventId())) {
            return ChainEffectRuntime.GateStatus.STALE_VERSION;
        }
        return currentCandidate(mutation)
                ? ChainEffectRuntime.GateStatus.CURRENT
                : ChainEffectRuntime.GateStatus.STALE_VERSION;
    }

    private boolean currentPlan(
            ChainInstructionState state,
            String taskId, String instructionId,
            String taskFrameId, String planId, String planRevisionId) {
        List<PlanBindingRecord> current = workflow.findPlanBindings(
                        taskId).stream()
                .filter(value -> value.planBindingId().equals(
                        state.gateAuthorityRef()))
                .toList();
        if (current.size() != 1) {
            return false;
        }
        PlanBindingRecord binding = current.get(0);
        boolean bindingIdentity = binding.taskId().equals(taskId)
                && binding.instructionId().equals(instructionId)
                && binding.taskFrameId().equals(taskFrameId)
                && binding.planId().equals(planId);
        if (!bindingIdentity) {
            return false;
        }
        if (binding.planRevisionId().equals(planRevisionId)) {
            return true;
        }
        // A formally completed Step produces the revision used by the next
        // active Step while the immutable chain binding remains on its source
        // revision. Accept only a revision that the exact Task/Plan Step
        // authority can resolve and that contains a formal completion event.
        // currentStep still fences every commit to the exact current
        // activation, so this does not admit an arbitrary historical revision.
        return steps.findPlan(taskId, planRevisionId)
                .map(snapshot -> snapshot.planId().equals(planId)
                        && snapshot.taskFrameId().equals(taskFrameId)
                        && steps.findStepEvents(taskId, planRevisionId).stream()
                        .anyMatch(event -> event.command().eventKind()
                                == ChainStepAuthorityPort.StepEventKind.COMPLETED))
                .orElse(false);
    }

    private boolean currentStep(
            ChainStepCommitGate.CommitKind kind, String taskId, String planId,
            String taskFrameId, String planRevisionId, String stepId,
            String activationEventId) {
        var inspected = recovery.inspect(new PlanId(planId));
        if (!inspected.successful()) {
            return false;
        }
        var snapshot = inspected.value().orElse(null);
        if (snapshot instanceof PersistedStepRecoveryActive active) {
            if (kind == ChainStepCommitGate.CommitKind.FINALIZATION_READINESS) {
                // The durable Step authority is the completion source of truth.
                // Its formal terminal event may already exist while the
                // execution-recovery projection still reports ACTIVE.
                boolean matches = active.plan().id().value().equals(planId)
                        && active.plan().taskFrameId().value().equals(taskFrameId)
                        && active.plan().latestRevision().id().value().equals(
                        planRevisionId)
                        && active.activation().stepId().value().equals(stepId)
                        && active.activation().activationEvent().id().value().equals(
                        activationEventId)
                        && formalCompletedStep(taskId, planRevisionId, stepId,
                        activationEventId);
                return matches;
            }
            return active.plan().id().value().equals(planId)
                    && active.plan().taskFrameId().value().equals(taskFrameId)
                    && active.activation().stepId().value().equals(stepId)
                    && active.activation().activationEvent().id().value().equals(
                            activationEventId)
                    && formalActivatedStep(taskId, planRevisionId, stepId,
                            activationEventId);
        }
        if (kind == ChainStepCommitGate.CommitKind.FINALIZATION_READINESS
                && snapshot instanceof PersistedStepRecoverySucceeded succeeded) {
            var plan = succeeded.plan();
            var checkpoint = succeeded.checkpoint().checkpoint();
            var fact = plan.latestRevision().completedFacts().get(
                    new io.paperagent.v2.contracts.PlanStepId(stepId));
            return plan.id().value().equals(planId)
                    && plan.taskFrameId().value().equals(taskFrameId)
                    && exactCompletionLineageFrom(plan, planRevisionId)
                    && checkpoint.stepStates().get(
                    new io.paperagent.v2.contracts.PlanStepId(stepId))
                    == io.paperagent.v2.contracts.StepExecutionState.SUCCEEDED
                    && fact != null
                    && steps.findStepEvents(taskId, planRevisionId).stream()
                    .filter(event -> event.command().eventKind()
                            == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                    .filter(event -> event.command().stepId().equals(stepId))
                    .filter(event -> event.command().activationEventId().equals(
                            activationEventId))
                    .count() == 1;
        }
        return false;
    }

    private static boolean exactCompletionLineageFrom(
            Plan plan, String sourceRevisionId) {
        var revisions = plan.revisions();
        List<Integer> matches = java.util.stream.IntStream.range(
                        0, revisions.size())
                .filter(index -> revisions.get(index).id().value().equals(
                        sourceRevisionId))
                .boxed().toList();
        if (matches.size() != 1
                || !revisions.get(revisions.size() - 1).equals(
                plan.latestRevision())) {
            return false;
        }
        for (int index = matches.get(0) + 1;
                index < revisions.size(); index++) {
            var previous = revisions.get(index - 1);
            var current = revisions.get(index);
            if (!current.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                    || current.number() != previous.number() + 1
                    || !current.taskFrameId().equals(previous.taskFrameId())
                    || !current.steps().equals(previous.steps())
                    || current.completedFacts().size()
                        != previous.completedFacts().size() + 1
                    || !current.completedFacts().entrySet().containsAll(
                        previous.completedFacts().entrySet())) {
                return false;
            }
        }
        return true;
    }

    private boolean formalCompletedStep(String taskId, String planRevisionId,
            String stepId, String activationEventId) {
        return steps.findStepEvents(taskId, planRevisionId).stream()
                .filter(event -> event.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                .filter(event -> event.command().stepId().equals(stepId))
                .filter(event -> event.command().activationEventId().equals(
                        activationEventId))
                .count() == 1;
    }

    private boolean formalActivatedStep(String taskId, String planRevisionId,
            String stepId, String activationEventId) {
        return steps.findStepEvents(taskId, planRevisionId).stream()
                .filter(event -> event.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .filter(event -> event.command().stepId().equals(stepId))
                .filter(event -> event.command().activationEventId().equals(
                        activationEventId))
                .count() == 1;
    }

    private String diagnoseCurrentStep(
            String taskId, String planId, String taskFrameId,
            String planRevisionId, String stepId, String activationEventId) {
        var inspected = recovery.inspect(new PlanId(planId));
        if (!inspected.successful()) {
            return "recoverySuccessful=false";
        }
        var snapshot = inspected.value().orElse(null);
        if (snapshot instanceof PersistedStepRecoveryActive active) {
            return "snapshot=ACTIVE"
                    + ",plan=" + active.plan().id().value().equals(planId)
                    + ",taskFrame=" + active.plan().taskFrameId().value()
                            .equals(taskFrameId)
                    + ",revision=" + active.plan().latestRevision().id().value()
                            .equals(planRevisionId)
                    + ",step=" + active.activation().stepId().value()
                            .equals(stepId)
                    + ",activation=" + active.activation().activationEvent()
                            .id().value().equals(activationEventId);
        }
        if (snapshot instanceof PersistedStepRecoverySucceeded succeeded) {
            return "snapshot=SUCCEEDED"
                    + ",plan=" + succeeded.plan().id().value().equals(planId)
                    + ",taskFrame=" + succeeded.plan().taskFrameId().value()
                            .equals(taskFrameId)
                    + ",revision=" + succeeded.plan().latestRevision().id().value()
                            .equals(planRevisionId)
                    + ",stepSucceeded="
                    + (succeeded.checkpoint().checkpoint().stepStates().get(
                            new io.paperagent.v2.contracts.PlanStepId(stepId))
                            == io.paperagent.v2.contracts.StepExecutionState.SUCCEEDED);
        }
        return "snapshot=" + (snapshot == null
                ? "NONE" : snapshot.getClass().getSimpleName());
    }

    private boolean currentCandidate(
            ChainEffectRuntime.FrozenMutation mutation) {
        List<WorkspaceCandidateRecord> candidates =
                workflow.findWorkspaceCandidates(mutation.taskId());
        if (candidates.isEmpty()) {
            return ChainIdentity.NONE.equals(mutation.baseCandidateKey());
        }
        Map<String, WorkspaceCandidateRecord> byEvent = candidates.stream()
                .collect(Collectors.toMap(
                        WorkspaceCandidateRecord::eventId,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException(
                                    "duplicate Workspace/Candidate event");
                        }));
        List<WorkspaceCandidateRecord> ordered = foundations
                .findAuthorityEvents(mutation.taskId(), Long.MAX_VALUE).stream()
                .filter(event -> byEvent.containsKey(event.eventId()))
                .sorted(java.util.Comparator.comparingLong(
                        AuthorityEventRecord::eventSequence))
                .map(event -> byEvent.get(event.eventId()))
                .toList();
        if (ordered.size() != candidates.size()) {
            return false;
        }
        WorkspaceCandidateRecord current = ordered.get(ordered.size() - 1);
        if (!current.workspaceId().equals(mutation.workspaceId())) {
            return false;
        }
        if (current.actionId().equals(mutation.actionId())) {
            return current.versionFenceSha256().equals(
                    mutation.versionFenceSha256());
        }
        return current.candidateFingerprint().equals(
                mutation.baseCandidateKey());
    }
}
