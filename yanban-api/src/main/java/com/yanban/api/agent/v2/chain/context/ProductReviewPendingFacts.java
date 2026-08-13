package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable formal authority cut consumed by the module 10 projector. */
record ProductReviewPendingFacts(
        ChainPersistenceRecords.ContextRevisionRecord building,
        long taskEventCut,
        Map<String, Long> eventSequences,
        List<ReviewView> reviews,
        List<PendingView> pendingItems,
        List<PermissionView> permissions,
        List<DispositionView> dispositions,
        List<TransitionView> transitions) {
    ProductReviewPendingFacts {
        Objects.requireNonNull(building, "building");
        eventSequences = Map.copyOf(eventSequences);
        reviews = List.copyOf(reviews);
        pendingItems = List.copyOf(pendingItems);
        permissions = List.copyOf(permissions);
        dispositions = List.copyOf(dispositions);
        transitions = List.copyOf(transitions);
    }

    boolean hasNoFacts() {
        return reviews.isEmpty() && pendingItems.isEmpty()
                && permissions.isEmpty() && dispositions.isEmpty()
                && transitions.isEmpty();
    }

    record ReviewView(
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            long eventSequence,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
    }

    record PendingView(
            ChainPersistenceRecords.PendingItemRecord item,
            long eventSequence,
            List<PendingEventView> events) {
        PendingView {
            events = List.copyOf(events);
        }

        ChainPendingItemStatus currentStatus() {
            return events.isEmpty() ? ChainPendingItemStatus.PENDING
                    : events.get(events.size() - 1).event().eventKind();
        }

        boolean open() {
            return currentStatus() == ChainPendingItemStatus.PENDING
                    || currentStatus()
                    == ChainPendingItemStatus.RESPONSE_RECEIVED;
        }
    }

    record PendingEventView(
            ChainPersistenceRecords.PendingItemEventRecord event,
            long eventSequence) {
    }

    record PermissionView(
            ChainPersistenceRecords.PermissionDecisionRecord decision,
            long eventSequence) {
    }

    record DispositionView(
            ChainPersistenceRecords.InstructionDispositionRecord disposition,
            long eventSequence) {
    }

    record TransitionView(
            ChainPersistenceRecords.TransitionRecord transition,
            long eventSequence,
            List<TransitionStageView> stages) {
        TransitionView {
            stages = List.copyOf(stages);
        }

        boolean complete() {
            return !stages.isEmpty()
                    && stages.get(stages.size() - 1).stage().stageCode()
                    == io.paperagent.v2.chain.ChainTransitionStage.COMPLETE;
        }
    }

    record TransitionStageView(
            ChainPersistenceRecords.TransitionStageRecord stage,
            long eventSequence) {
    }
}
