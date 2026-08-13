package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads and identity-checks formal review, gap and permission authorities. */
final class ProductReviewPendingAuthority {
    private static final ChainContextModule MODULE =
            ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS;
    private final ChainFoundationRepository foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductReviewPendingTransitionAuthority transitions;

    ProductReviewPendingAuthority(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        transitions = new ProductReviewPendingTransitionAuthority(workflow);
    }

    ProductReviewPendingFacts load(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        foundations.findTask(building.taskId())
                .orElseThrow(() -> blocked("task is missing"));
        long taskCut = foundations.highestAuthorityEventSequence(
                building.taskId());
        List<ChainPersistenceRecords.AuthorityEventRecord> eventPrefix =
                eventPrefix(building.taskId(), taskCut);
        Map<String, Long> sequences = new HashMap<>();
        eventPrefix.forEach(event -> sequences.put(
                event.eventId(), event.eventSequence()));
        Map<String, ChainPersistenceRecords.CandidateStepResultRecord>
                candidates = candidates(building.taskId(), sequences);
        List<ProductReviewPendingFacts.ReviewView> reviews = reviews(
                building.taskId(), sequences, candidates);
        List<ProductReviewPendingFacts.PendingView> pending = pending(
                building.taskId(), sequences);
        Map<String, ProductReviewPendingFacts.PendingView> pendingById =
                new HashMap<>();
        pending.forEach(value -> pendingById.put(
                value.item().gapId(), value));
        List<ProductReviewPendingFacts.PermissionView> permissions =
                permissions(building.taskId(), sequences, pendingById);
        List<ProductReviewPendingFacts.DispositionView> dispositions =
                dispositions(building.taskId(), sequences);
        return new ProductReviewPendingFacts(
                building, taskCut, sequences, reviews, pending, permissions,
                dispositions, transitions.load(
                building.taskId(), eventPrefix));
    }

    private List<ChainPersistenceRecords.AuthorityEventRecord> eventPrefix(
            String taskId, long cut) {
        List<ChainPersistenceRecords.AuthorityEventRecord> values =
                foundations.findAuthorityEvents(taskId, cut);
        long previous = 0;
        Map<String, Boolean> ids = new HashMap<>();
        for (var event : values) {
            if (!taskId.equals(event.taskId())
                    || event.eventSequence() <= previous
                    || event.eventSequence() > cut
                    || ids.put(event.eventId(), true) != null) {
                throw blocked("task authority event prefix is inconsistent");
            }
            previous = event.eventSequence();
        }
        return List.copyOf(values);
    }

    private Map<String, ChainPersistenceRecords.CandidateStepResultRecord>
            candidates(String taskId, Map<String, Long> sequences) {
        Map<String, ChainPersistenceRecords.CandidateStepResultRecord> values =
                new HashMap<>();
        for (var candidate : workflow.findCandidateStepResults(taskId)) {
            requireTaskEvent(candidate, taskId, sequences, "candidate result");
            if (values.put(candidate.candidateResultId(), candidate) != null) {
                throw blocked("candidate result identity is duplicated");
            }
        }
        return Map.copyOf(values);
    }

    private List<ProductReviewPendingFacts.ReviewView> reviews(
            String taskId, Map<String, Long> sequences,
            Map<String, ChainPersistenceRecords.CandidateStepResultRecord>
                    candidates) {
        List<ProductReviewPendingFacts.ReviewView> values = new ArrayList<>();
        for (var review : workflow.findReviewDecisions(taskId)) {
            long sequence = requireTaskEvent(
                    review, taskId, sequences, "ReviewDecision");
            ChainPersistenceRecords.CandidateStepResultRecord candidate = null;
            if ("CANDIDATE_STEP_RESULT".equals(review.reviewObjectType())) {
                candidate = candidates.get(review.reviewObjectId());
                if (candidate == null) throw blocked(
                        "ReviewDecision candidate authority is missing");
                if (!review.versionFenceSha256().equals(
                        candidate.versionFenceSha256())) {
                    throw blocked("ReviewDecision candidate fence mismatches");
                }
                if (sequences.get(candidate.eventId()) >= sequence) {
                    throw blocked(
                            "ReviewDecision precedes its candidate authority");
                }
            }
            values.add(new ProductReviewPendingFacts.ReviewView(
                    review, sequence, candidate));
        }
        values.sort(Comparator.comparingLong(
                ProductReviewPendingFacts.ReviewView::eventSequence));
        return List.copyOf(values);
    }

    private List<ProductReviewPendingFacts.PendingView> pending(
            String taskId, Map<String, Long> sequences) {
        List<ProductReviewPendingFacts.PendingView> values = new ArrayList<>();
        Map<String, Boolean> gapIds = new HashMap<>();
        for (var item : workflow.findPendingItems(taskId)) {
            long sequence = requireTaskEvent(
                    item, taskId, sequences, "PendingItem");
            if (gapIds.put(item.gapId(), true) != null) {
                throw blocked("PendingItem identity is duplicated");
            }
            List<ProductReviewPendingFacts.PendingEventView> events =
                    new ArrayList<>();
            long previous = sequence;
            for (var event : workflow.findPendingItemEvents(item.gapId())) {
                if (!item.gapId().equals(event.gapId())) {
                    throw blocked("PendingItem event crosses gap identity");
                }
                long eventSequence = requireTaskEvent(
                        event, taskId, sequences, "PendingItem event");
                if (eventSequence <= previous) {
                    throw blocked("PendingItem event order is inconsistent");
                }
                previous = eventSequence;
                events.add(new ProductReviewPendingFacts.PendingEventView(
                        event, eventSequence));
            }
            values.add(new ProductReviewPendingFacts.PendingView(
                    item, sequence, events));
        }
        values.sort(Comparator.comparingLong(
                ProductReviewPendingFacts.PendingView::eventSequence));
        return List.copyOf(values);
    }

    private List<ProductReviewPendingFacts.PermissionView> permissions(
            String taskId, Map<String, Long> sequences,
            Map<String, ProductReviewPendingFacts.PendingView> pending) {
        List<ProductReviewPendingFacts.PermissionView> values =
                new ArrayList<>();
        for (var decision : workflow.findPermissionDecisions(taskId)) {
            long sequence = requireTaskEvent(
                    decision, taskId, sequences, "PermissionDecision");
            var gap = pending.get(decision.gapId());
            if (gap == null
                    || !Objects.equals(gap.item().permissionScope(),
                    decision.permissionScope())) {
                throw blocked("PermissionDecision gap identity mismatches");
            }
            if (gap.eventSequence() >= sequence) {
                throw blocked(
                        "PermissionDecision precedes its PendingItem");
            }
            values.add(new ProductReviewPendingFacts.PermissionView(
                    decision, sequence));
        }
        values.sort(Comparator.comparingLong(
                ProductReviewPendingFacts.PermissionView::eventSequence));
        return List.copyOf(values);
    }

    private List<ProductReviewPendingFacts.DispositionView> dispositions(
            String taskId, Map<String, Long> sequences) {
        List<ProductReviewPendingFacts.DispositionView> values =
                new ArrayList<>();
        for (var disposition : workflow.findInstructionDispositions(taskId)) {
            values.add(new ProductReviewPendingFacts.DispositionView(
                    disposition, requireTaskEvent(disposition, taskId,
                    sequences, "InstructionDisposition")));
        }
        values.sort(Comparator.comparingLong(
                ProductReviewPendingFacts.DispositionView::eventSequence));
        return List.copyOf(values);
    }

    private static long requireTaskEvent(
            ChainPersistenceRecords.TaskAuthorityFact fact, String taskId,
            Map<String, Long> sequences, String name) {
        if (!taskId.equals(fact.taskId())) {
            throw blocked(name + " crosses task identity");
        }
        Long sequence = sequences.get(fact.eventId());
        if (sequence == null) throw blocked(
                name + " lacks a formal task event");
        return sequence;
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
