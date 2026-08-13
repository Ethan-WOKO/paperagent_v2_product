package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;

/** Pure four-role field views over one frozen module 10 authority cut. */
final class ProductReviewPendingRoleValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS;

    private ProductReviewPendingRoleValues() {
    }

    static ChainContextValue fieldValue(
            String field, ProductReviewPendingFacts facts) {
        var codec = ProductReviewPendingValueCodec.INSTANCE;
        List<ProductReviewPendingFacts.ReviewView> related =
                relatedReviews(facts);
        ProductReviewPendingFacts.PendingView current = currentGap(facts);
        return switch (field) {
            case "review.latestDecision" -> latestReview(related, codec);
            case "review.replanGap" -> latestReview(facts.reviews().stream()
                    .filter(value -> value.decision().decisionKind()
                            == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED)
                    .toList(), codec);
            case "review.instructionDisposition" -> latestDisposition(
                    facts, codec);
            case "review.resumePosition" -> current == null
                    ? ChainContextValue.nil()
                    : codec.canonical(current.item().resumePosition());
            case "review.previousReviewGap" -> previousGap(
                    facts, ChainRole.EXECUTOR, codec);
            case "review.loopState" -> loopState(
                    facts, related, current, codec);
            case "review.objectBoundDecisionHistory" ->
                    ChainContextValue.array(related.stream()
                            .map(codec::review).toList());
            case "review.currentGap" -> current == null
                    ? ChainContextValue.nil() : codec.pending(current);
            case "review.currentQuestionPermissionFailureCompletionOrInstructionDecision"
                    -> answerDecision(facts, current, codec);
            case "foundation.latestDecisionCallReasonAndPendingItem" ->
                    ChainContextValue.object(Map.of(
                            "callReason", ChainContextValue.text(
                                    facts.building().callReason()),
                            "latestDecision", latestReview(related, codec),
                            "currentPendingItem", current == null
                                    ? ChainContextValue.nil()
                                    : codec.pending(current)));
            default -> throw blocked("unsupported review field: " + field);
        };
    }

    private static List<ProductReviewPendingFacts.ReviewView> relatedReviews(
            ProductReviewPendingFacts facts) {
        if (facts.building().role() == ChainRole.PLANNER
                || facts.building().role() == ChainRole.ANSWER) {
            return facts.reviews();
        }
        return facts.reviews().stream().filter(value -> {
            var candidate = value.candidate();
            if (candidate == null) {
                return value.decision().reviewObjectId().equals(
                        facts.building().activationEventId())
                        || value.decision().reviewObjectId().equals(
                        facts.building().stepId());
            }
            return candidate.instructionId().equals(
                    facts.building().instructionId())
                    && candidate.taskFrameId().equals(
                    facts.building().taskFrameId())
                    && candidate.planId().equals(facts.building().planId())
                    && candidate.planRevisionId().equals(
                    facts.building().planRevisionId())
                    && candidate.stepId().equals(facts.building().stepId())
                    && candidate.activationEventId().equals(
                    facts.building().activationEventId());
        }).toList();
    }

    private static ChainContextValue latestReview(
            List<ProductReviewPendingFacts.ReviewView> values,
            ProductReviewPendingValueCodec codec) {
        return values.isEmpty() ? ChainContextValue.nil()
                : codec.review(values.get(values.size() - 1));
    }

    private static ChainContextValue latestDisposition(
            ProductReviewPendingFacts facts,
            ProductReviewPendingValueCodec codec) {
        List<ProductReviewPendingFacts.DispositionView> exact =
                facts.dispositions().stream().filter(value ->
                        value.disposition().instructionId().equals(
                                facts.building().instructionId())).toList();
        return exact.isEmpty() ? ChainContextValue.nil()
                : codec.disposition(exact.get(exact.size() - 1));
    }

    private static ProductReviewPendingFacts.PendingView currentGap(
            ProductReviewPendingFacts facts) {
        List<ProductReviewPendingFacts.PendingView> open = facts.pendingItems()
                .stream().filter(ProductReviewPendingFacts.PendingView::open)
                .toList();
        if (open.size() > 1) throw blocked(
                "multiple open PendingItems are formally visible");
        return open.isEmpty() ? null : open.get(0);
    }

    private static ChainContextValue previousGap(
            ProductReviewPendingFacts facts, ChainRole role,
            ProductReviewPendingValueCodec codec) {
        List<ProductReviewPendingFacts.PendingView> values =
                facts.pendingItems().stream().filter(value ->
                        value.item().resumeRole() == role).toList();
        return values.isEmpty() ? ChainContextValue.nil()
                : codec.pending(values.get(values.size() - 1));
    }

    private static ChainContextValue loopState(
            ProductReviewPendingFacts facts,
            List<ProductReviewPendingFacts.ReviewView> reviews,
            ProductReviewPendingFacts.PendingView current,
            ProductReviewPendingValueCodec codec) {
        return ChainContextValue.object(Map.of(
                "relatedDecisionCount", ChainContextValue.number(
                        reviews.size()),
                "currentGap", current == null ? ChainContextValue.nil()
                        : codec.pending(current),
                "incompleteTransitions", ChainContextValue.array(
                        facts.transitions().stream()
                                .filter(value -> !value.complete())
                                .map(codec::transition).toList())));
    }

    private static ChainContextValue answerDecision(
            ProductReviewPendingFacts facts,
            ProductReviewPendingFacts.PendingView current,
            ProductReviewPendingValueCodec codec) {
        var permissionDecisions = current == null
                ? facts.permissions().stream()
                : facts.permissions().stream().filter(value ->
                        value.decision().gapId().equals(
                                current.item().gapId()));
        ChainContextValue permission = permissionDecisions
                .reduce((left, right) -> right)
                .<ChainContextValue>map(codec::permission)
                .orElseGet(ChainContextValue::nil);
        return ChainContextValue.object(Map.of(
                "currentGap", current == null ? ChainContextValue.nil()
                        : codec.pending(current),
                "permissionDecision", permission,
                "latestReview", latestReview(facts.reviews(), codec),
                "instructionDisposition", latestDisposition(facts, codec)));
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
