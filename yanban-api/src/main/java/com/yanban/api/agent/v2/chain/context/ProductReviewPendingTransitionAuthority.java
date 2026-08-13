package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads complete transition prefixes discovered from the formal task event cut. */
final class ProductReviewPendingTransitionAuthority {
    private static final ChainContextModule MODULE =
            ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS;
    private final ProductChainWorkflowRepositoryAdapter workflow;

    ProductReviewPendingTransitionAuthority(
            ProductChainWorkflowRepositoryAdapter workflow) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    List<ProductReviewPendingFacts.TransitionView> load(
            String taskId,
            List<ChainPersistenceRecords.AuthorityEventRecord> eventPrefix) {
        Map<String, ChainPersistenceRecords.AuthorityEventRecord> events =
                new HashMap<>();
        eventPrefix.forEach(event -> events.put(event.eventId(), event));
        Set<String> ids = new LinkedHashSet<>();
        eventPrefix.stream().filter(event -> event.transitionId() != null)
                .forEach(event -> ids.add(event.transitionId()));
        List<ProductReviewPendingFacts.TransitionView> values =
                new ArrayList<>();
        for (String id : ids) {
            var transition = workflow.findTransition(id)
                    .orElseThrow(() -> blocked(
                            "formal transition definition is missing"));
            if (!taskId.equals(transition.taskId())
                    || !id.equals(transition.transitionId())) {
                throw blocked("transition crosses task identity");
            }
            long definitionSequence = sequence(transition.eventId(),
                    transition.transitionId(), events, "transition");
            List<ProductReviewPendingFacts.TransitionStageView> stages =
                    stages(taskId, transition, events, definitionSequence);
            values.add(new ProductReviewPendingFacts.TransitionView(
                    transition, definitionSequence, stages));
        }
        values.sort(Comparator.comparingLong(
                ProductReviewPendingFacts.TransitionView::eventSequence));
        return List.copyOf(values);
    }

    private List<ProductReviewPendingFacts.TransitionStageView> stages(
            String taskId,
            ChainPersistenceRecords.TransitionRecord transition,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events,
            long definitionSequence) {
        List<ChainPersistenceRecords.TransitionStageRecord> records =
                new ArrayList<>(workflow.findTransitionStages(
                        transition.transitionId()));
        records.sort(Comparator.comparingInt(
                ChainPersistenceRecords.TransitionStageRecord::stageOrdinal));
        List<ProductReviewPendingFacts.TransitionStageView> result =
                new ArrayList<>();
        int expected = 0;
        long previousEventSequence = definitionSequence;
        for (var stage : records) {
            if (!taskId.equals(stage.taskId())
                    || !transition.transitionId().equals(stage.transitionId())
                    || stage.stageOrdinal() != expected++
                    || !transition.transitionType().isValidOrdinal(
                    stage.stageCode(), stage.stageOrdinal())) {
                throw blocked("transition stage prefix is inconsistent");
            }
            stage.validateFor(transition.transitionType());
            long eventSequence = sequence(stage.eventId(),
                    transition.transitionId(), events, "transition stage");
            if (eventSequence <= previousEventSequence) {
                throw blocked("transition stage event order is inconsistent");
            }
            previousEventSequence = eventSequence;
            result.add(new ProductReviewPendingFacts.TransitionStageView(
                    stage, eventSequence));
        }
        return List.copyOf(result);
    }

    private static long sequence(
            String eventId, String transitionId,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events,
            String fact) {
        var event = events.get(eventId);
        if (event == null) throw blocked(
                fact + " lacks a formal task event");
        if (!transitionId.equals(event.transitionId())) {
            throw blocked(fact + " event transition identity mismatches");
        }
        return event.eventSequence();
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
