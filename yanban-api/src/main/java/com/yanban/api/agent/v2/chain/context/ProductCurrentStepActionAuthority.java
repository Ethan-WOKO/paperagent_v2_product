package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads module 7 only through formal action and retained effect authorities. */
final class ProductCurrentStepActionAuthority {
    private static final ChainContextModule MODULE =
            ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS;
    private final ChainFoundationRepository foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final EffectIntentRepository intents;
    private final EffectOutcomeRepository outcomes;
    private final ChainFinalizationRepository finalization;
    private final ProductChainCandidateMaterializationFailureRepositoryAdapter
            candidateFailures;

    ProductCurrentStepActionAuthority(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization,
            ProductChainCandidateMaterializationFailureRepositoryAdapter
                    candidateFailures) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.candidateFailures = Objects.requireNonNull(
                candidateFailures, "candidateFailures");
    }

    ProductCurrentStepActionFacts load(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        foundations.findTask(building.taskId())
                .orElseThrow(() -> blocked("task is missing"));
        long taskCut = foundations.highestAuthorityEventSequence(
                building.taskId());
        Map<String, Long> sequences = authorityPrefix(
                building.taskId(), taskCut);
        List<ChainPersistenceRecords.ActionBindingRecord> all =
                workflow.findActionBindings(building.taskId());
        List<ChainPersistenceRecords.ActionBindingRecord> selected =
                selectExact(building, all, sequences);
        Map<String, ChainPersistenceRecords.WorkspaceCandidateRecord>
                candidates = candidatePrefix(building.taskId(), sequences);
        List<ProductCurrentStepActionFacts.ActionView> views =
                new ArrayList<>(selected.size());
        for (var action : selected) {
            views.add(effectView(action, sequences.get(action.eventId()),
                    sequences, candidates.get(action.actionId())));
        }
        verifyAttemptOrder(views);
        var taskOutcome = finalization.findTaskOutcome(
                building.taskId()).orElse(null);
        verifyOutcome(building, taskOutcome, sequences);
        return new ProductCurrentStepActionFacts(
                building, taskCut, sequences, views, taskOutcome);
    }

    private Map<String, Long> authorityPrefix(String taskId, long cut) {
        Map<String, Long> sequences = new HashMap<>();
        long previous = 0;
        for (var event : foundations.findAuthorityEvents(taskId, cut)) {
            if (!taskId.equals(event.taskId())
                    || event.eventSequence() <= previous
                    || event.eventSequence() > cut
                    || sequences.put(event.eventId(),
                    event.eventSequence()) != null) {
                throw blocked("task authority event prefix is inconsistent");
            }
            previous = event.eventSequence();
        }
        return Map.copyOf(sequences);
    }

    private List<ChainPersistenceRecords.ActionBindingRecord> selectExact(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<ChainPersistenceRecords.ActionBindingRecord> all,
            Map<String, Long> sequences) {
        List<ChainPersistenceRecords.ActionBindingRecord> selected =
                new ArrayList<>();
        for (var action : all) {
            if (!building.taskId().equals(action.taskId())) {
                throw blocked("action authority crosses task identity");
            }
            if (!sequences.containsKey(action.eventId())) {
                throw blocked("action authority has no formal task event");
            }
            if (building.role() == io.paperagent.v2.chain.ChainRole.PLANNER
                    && building.activationEventId() == null) {
                selected.add(action);
                continue;
            }
            if (building.activationEventId() == null
                    || !building.activationEventId().equals(
                    action.activationEventId())) {
                continue;
            }
            if (!sameFrozenIdentity(building, action)) {
                throw blocked("action activation conflicts with frozen identity");
            }
            selected.add(action);
        }
        selected.sort(Comparator.comparingLong(
                action -> sequences.get(action.eventId())));
        return List.copyOf(selected);
    }

    private static boolean sameFrozenIdentity(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.ActionBindingRecord action) {
        return Objects.equals(building.instructionId(), action.instructionId())
                && Objects.equals(building.taskFrameId(), action.taskFrameId())
                && Objects.equals(building.planId(), action.planId())
                && Objects.equals(building.planRevisionId(),
                action.planRevisionId())
                && Objects.equals(building.stepId(), action.stepId())
                && Objects.equals(building.workspaceId(), action.workspaceId());
    }

    private ProductCurrentStepActionFacts.ActionView effectView(
            ChainPersistenceRecords.ActionBindingRecord action,
            long eventSequence, Map<String, Long> sequences,
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate) {
        ToolCallId callId = new ToolCallId(action.actionId());
        PersistedEffectIntent intent = optional(
                intents.find(callId), "EffectIntent");
        if (intent != null && (!callId.equals(intent.intent().toolCallId())
                || !action.planId().equals(intent.intent().planId().value())
                || !action.stepId().equals(intent.intent().stepId().value())
                || !action.activationEventId().equals(
                intent.activationEventId().value()))) {
            throw blocked("EffectIntent conflicts with ActionBinding");
        }
        List<PersistedEffectProgress> progress = progress(
                outcomes.readProgress(callId), callId);
        PersistedEffectResult result = optional(
                outcomes.findResult(callId), "EffectResult");
        if (result != null && !callId.equals(
                result.receipt().toolCallId())) {
            throw blocked("Receipt belongs to another action");
        }
        if ((!progress.isEmpty() || result != null) && intent == null) {
            throw blocked("effect outcome exists without EffectIntent");
        }
        var candidateFailure = candidateFailures
                .findCandidateMaterializationFailure(
                        action.taskId(), action.actionId()).orElse(null);
        if (candidateFailure != null
                && (!action.taskId().equals(candidateFailure.taskId())
                || !action.actionId().equals(candidateFailure.actionId())
                || !action.workspaceId().equals(
                candidateFailure.workspaceId())
                || !action.baseCandidateKey().equals(
                candidateFailure.baseCandidateKey())
                || !action.versionFenceSha256().equals(
                candidateFailure.versionFenceSha256())
                || !sequences.containsKey(candidateFailure.eventId())
                || (result != null && result.receipt().status()
                != io.paperagent.v2.contracts.ReceiptStatus.SUCCESS))) {
            throw blocked("Candidate failure conflicts with exact action authority");
        }
        if (candidate != null
                && (!action.taskId().equals(candidate.taskId())
                || !action.actionId().equals(candidate.actionId())
                || !action.workspaceId().equals(candidate.workspaceId())
                || !action.versionFenceSha256().equals(
                candidate.versionFenceSha256())
                || !sequences.containsKey(candidate.eventId())
                || candidateFailure != null
                || (result != null && result.receipt().status()
                != io.paperagent.v2.contracts.ReceiptStatus.SUCCESS))) {
            throw blocked("Candidate conflicts with exact action authority");
        }
        return new ProductCurrentStepActionFacts.ActionView(
                action, eventSequence, intent, progress, result,
                candidate, candidateFailure);
    }

    private Map<String, ChainPersistenceRecords.WorkspaceCandidateRecord>
            candidatePrefix(String taskId, Map<String, Long> sequences) {
        Map<String, ChainPersistenceRecords.WorkspaceCandidateRecord> result =
                new HashMap<>();
        for (var candidate : workflow.findWorkspaceCandidates(taskId)) {
            if (!taskId.equals(candidate.taskId())
                    || !sequences.containsKey(candidate.eventId())
                    || result.put(candidate.actionId(), candidate) != null) {
                throw blocked("Candidate authority prefix is inconsistent");
            }
        }
        return Map.copyOf(result);
    }

    private List<PersistedEffectProgress> progress(
            PersistenceResult<List<PersistedEffectProgress>> result,
            ToolCallId callId) {
        List<PersistedEffectProgress> values = optional(
                result, "EffectProgress");
        if (values == null) return List.of();
        long expected = 1;
        for (var value : values) {
            if (!callId.equals(value.progress().toolCallId())
                    || value.progress().sequence() != expected++) {
                throw blocked("effect progress prefix is inconsistent");
            }
        }
        return List.copyOf(values);
    }

    private static <T> T optional(
            PersistenceResult<T> result, String authority) {
        if (result.successful()) return result.value().orElseThrow();
        if (result.failure().orElseThrow().code()
                == PersistenceErrorCode.NOT_FOUND) return null;
        throw blocked(authority + " authority query failed");
    }

    private static void verifyAttemptOrder(
            List<ProductCurrentStepActionFacts.ActionView> views) {
        Map<String, Integer> expectedByActivation = new HashMap<>();
        for (var view : views) {
            var action = view.binding();
            String identity = action.planRevisionId() + "\0" + action.stepId()
                    + "\0" + action.activationEventId();
            int expected = expectedByActivation.getOrDefault(identity, 1);
            if (action.attemptNo() != expected) {
                throw blocked("action attempt prefix is not contiguous");
            }
            expectedByActivation.put(identity, expected + 1);
        }
    }

    private static void verifyOutcome(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            Map<String, Long> sequences) {
        if (outcome == null) return;
        if (!building.taskId().equals(outcome.taskId())
                || !sequences.containsKey(outcome.eventId())
                || !building.instructionId().equals(outcome.instructionId())
                || (outcome.finalPlanRevisionId() != null
                && (!Objects.equals(building.taskFrameId(), outcome.taskFrameId())
                || !Objects.equals(building.planId(), outcome.finalPlanId())
                || !Objects.equals(building.planRevisionId(),
                outcome.finalPlanRevisionId())))) {
            throw blocked("TaskOutcome conflicts with frozen action context");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
