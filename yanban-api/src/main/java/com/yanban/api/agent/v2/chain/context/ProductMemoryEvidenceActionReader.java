package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads all formal task Action receipts without executing any effect. */
final class ProductMemoryEvidenceActionReader {
    private final ChainFoundationRepository foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final EffectIntentRepository intents;
    private final EffectOutcomeRepository outcomes;
    private final ChainFinalizationRepository finalization;

    ProductMemoryEvidenceActionReader(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
    }

    Cut load(ChainPersistenceRecords.ContextRevisionRecord building) {
        foundations.findTask(building.taskId())
                .orElseThrow(() -> blocked("task is missing"));
        long taskCut = foundations.highestAuthorityEventSequence(
                building.taskId());
        Map<String, Long> sequences = prefix(building.taskId(), taskCut);
        List<ActionView> actions = new ArrayList<>();
        for (var binding : workflow.findActionBindings(building.taskId())) {
            if (!binding.taskId().equals(building.taskId())
                    || !sequences.containsKey(binding.eventId())) {
                throw blocked("ActionBinding lacks formal task event");
            }
            verifyCurrentIdentity(building, binding);
            ToolCallId callId = new ToolCallId(binding.actionId());
            PersistedEffectIntent intent = optional(
                    intents.find(callId), "EffectIntent");
            PersistedEffectResult result = optional(
                    outcomes.findResult(callId), "EffectResult");
            verify(binding, callId, intent, result);
            actions.add(new ActionView(binding,
                    sequences.get(binding.eventId()), result));
        }
        actions.sort(Comparator.comparingLong(ActionView::eventSequence));
        verifyAttemptPrefixes(actions);
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(building.taskId()).orElse(null);
        if (outcome != null && (!outcome.taskId().equals(building.taskId())
                || !sequences.containsKey(outcome.eventId())
                || !outcome.instructionId().equals(building.instructionId())
                || !Objects.equals(outcome.taskFrameId(),
                building.taskFrameId())
                || !Objects.equals(outcome.finalPlanId(), building.planId())
                || !Objects.equals(outcome.finalPlanRevisionId(),
                building.planRevisionId()))) {
            throw blocked("TaskOutcome evidence identity mismatches");
        }
        return new Cut(taskCut, sequences, actions, outcome);
    }

    private static void verifyAttemptPrefixes(List<ActionView> actions) {
        Map<String, Integer> expected = new HashMap<>();
        for (var action : actions) {
            var binding = action.binding();
            String activation = binding.planRevisionId() + "\0"
                    + binding.stepId() + "\0" + binding.activationEventId();
            int next = expected.getOrDefault(activation, 1);
            if (binding.attemptNo() != next) {
                throw blocked("Action evidence attempt prefix is inconsistent");
            }
            expected.put(activation, next + 1);
        }
    }

    private static void verifyCurrentIdentity(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.ActionBindingRecord binding) {
        if (building.activationEventId() == null
                || !building.activationEventId().equals(
                binding.activationEventId())) return;
        if (!building.instructionId().equals(binding.instructionId())
                || !Objects.equals(building.taskFrameId(), binding.taskFrameId())
                || !Objects.equals(building.planId(), binding.planId())
                || !Objects.equals(building.planRevisionId(),
                binding.planRevisionId())
                || !Objects.equals(building.stepId(), binding.stepId())
                || !Objects.equals(building.workspaceId(),
                binding.workspaceId())) {
            throw blocked("current activation Action evidence conflicts");
        }
    }

    private Map<String, Long> prefix(String taskId, long cut) {
        Map<String, Long> result = new HashMap<>();
        long previous = 0;
        for (var event : foundations.findAuthorityEvents(taskId, cut)) {
            if (!event.taskId().equals(taskId)
                    || event.eventSequence() <= previous
                    || event.eventSequence() > cut
                    || result.put(event.eventId(), event.eventSequence()) != null) {
                throw blocked("task authority event prefix is inconsistent");
            }
            previous = event.eventSequence();
        }
        return Map.copyOf(result);
    }

    private static void verify(
            ChainPersistenceRecords.ActionBindingRecord binding,
            ToolCallId callId, PersistedEffectIntent intent,
            PersistedEffectResult result) {
        if (intent != null && (!callId.equals(intent.intent().toolCallId())
                || !binding.planId().equals(intent.intent().planId().value())
                || !binding.stepId().equals(intent.intent().stepId().value())
                || !binding.activationEventId().equals(
                intent.activationEventId().value()))) {
            throw blocked("EffectIntent conflicts with ActionBinding");
        }
        if (result != null && (!callId.equals(result.receipt().toolCallId())
                || intent == null)) {
            throw blocked("EffectResult lacks exact Action/Intent binding");
        }
    }

    private static <T> T optional(
            PersistenceResult<T> result, String authority) {
        if (result.successful()) return result.value().orElseThrow();
        if (result.failure().orElseThrow().code()
                == PersistenceErrorCode.NOT_FOUND) return null;
        throw blocked(authority + " authority query failed");
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                reason);
    }

    record Cut(
            long taskEventCut,
            Map<String, Long> eventSequences,
            List<ActionView> actions,
            ChainPersistenceRecords.TaskOutcomeRecord taskOutcome) {
        Cut {
            eventSequences = Map.copyOf(eventSequences);
            actions = List.copyOf(actions);
        }
    }

    record ActionView(
            ChainPersistenceRecords.ActionBindingRecord binding,
            long eventSequence,
            PersistedEffectResult result) {
    }
}
