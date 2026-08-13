package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure role selection plus source and read-boundary vectors for module 10. */
final class ProductReviewPendingProjectionValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS;

    private ProductReviewPendingProjectionValues() {
    }

    static Values create(
            List<String> requiredFields, ProductReviewPendingFacts facts) {
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            fields.put(field, ProductReviewPendingRoleValues.fieldValue(
                    field, facts));
        }
        Map<String, ChainContextValue> source = Map.of(
                "reviewCut", ChainContextValue.object(Map.of(
                        "reviewEventSequence", ChainContextValue.number(
                                maxReviews(facts.reviews())),
                        "instructionDispositionEventSequence",
                        ChainContextValue.number(maxDispositions(
                                facts.dispositions())))),
                "pendingCut", ChainContextValue.number(
                        maxPending(facts.pendingItems())),
                "permissionCut", ChainContextValue.number(
                        facts.permissions().stream().mapToLong(
                                ProductReviewPendingFacts.PermissionView
                                        ::eventSequence).max().orElse(0)),
                "transitionCut", ChainContextValue.number(
                        maxTransitions(facts.transitions())));
        return new Values(source, boundary(facts), parameters(facts), fields);
    }

    static Map<String, ChainContextValue> emptySource() {
        return Map.of(
                "reviewCut", ChainContextValue.object(Map.of(
                        "reviewEventSequence", ChainContextValue.number(0),
                        "instructionDispositionEventSequence",
                        ChainContextValue.number(0))),
                "pendingCut", ChainContextValue.number(0),
                "permissionCut", ChainContextValue.number(0),
                "transitionCut", ChainContextValue.number(0));
    }

    static Map<String, ChainContextValue> boundary(
            ProductReviewPendingFacts facts) {
        var building = facts.building();
        return Map.of(
                "taskEventCut", ChainContextValue.number(
                        facts.taskEventCut()),
                "planAndStepBinding", ChainContextValue.object(Map.of(
                        "taskRef", ref(building.taskId()),
                        "instructionRef", ref(building.instructionId()),
                        "taskFrameRef", nullable(building.taskFrameId()),
                        "planRef", nullable(building.planId()),
                        "planRevisionRef", nullable(
                                building.planRevisionId()),
                        "stepRef", nullable(building.stepId()),
                        "activationRef", nullable(
                                building.activationEventId()))));
    }

    private static Map<String, ChainContextValue> parameters(
            ProductReviewPendingFacts facts) {
        return Map.of(
                "taskRef", ref(facts.building().taskId()),
                "role", ChainContextValue.text(
                        facts.building().role().name()),
                "callReason", ChainContextValue.text(
                        facts.building().callReason()));
    }

    private static long maxReviews(
            List<ProductReviewPendingFacts.ReviewView> values) {
        return values.stream().mapToLong(
                ProductReviewPendingFacts.ReviewView::eventSequence)
                .max().orElse(0);
    }

    private static long maxDispositions(
            List<ProductReviewPendingFacts.DispositionView> values) {
        return values.stream().mapToLong(
                ProductReviewPendingFacts.DispositionView::eventSequence)
                .max().orElse(0);
    }

    private static long maxPending(
            List<ProductReviewPendingFacts.PendingView> values) {
        return values.stream().flatMapToLong(value -> {
            var eventCuts = value.events().stream().mapToLong(
                    ProductReviewPendingFacts.PendingEventView::eventSequence);
            return java.util.stream.LongStream.concat(
                    java.util.stream.LongStream.of(value.eventSequence()),
                    eventCuts);
        }).max().orElse(0);
    }

    private static long maxTransitions(
            List<ProductReviewPendingFacts.TransitionView> values) {
        return values.stream().flatMapToLong(value ->
                java.util.stream.LongStream.concat(
                        java.util.stream.LongStream.of(value.eventSequence()),
                        value.stages().stream().mapToLong(
                                ProductReviewPendingFacts.TransitionStageView
                                        ::eventSequence)))
                .max().orElse(0);
    }

    private static ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil() : ref(value);
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    record Values(Map<String, ChainContextValue> sourceVersion,
                  Map<String, ChainContextValue> readBoundary,
                  Map<String, ChainContextValue> parameters,
                  Map<String, ChainContextValue> fields) {
    }
}
