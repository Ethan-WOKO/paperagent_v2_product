package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.stream.Collectors;

final class InMemoryExecutionMutationAuthority {
    private InMemoryExecutionMutationAuthority() {
    }

    static AuthoritativeSource validateAuthoritativeSource(
            InMemoryState state,
            PlanId planId) {
        PlanRoot planRoot = validatePlanRoot(state, planId);
        if (planRoot == null) {
            return null;
        }
        Plan plan = planRoot.plan();
        PersistedPlanBootstrap bootstrap = planRoot.bootstrap();
        InMemoryState.ExecutionStartMarker start =
                state.executionStarts.get(planId);
        VersionedCheckpoint current = state.checkpoints.get(planId);
        InMemoryState.ExecutionMutationHead currentHead =
                state.executionMutationHeads.get(planId);
        List<InMemoryState.ExecutionMutationLink> links =
                state.executionMutationLinks.get(planId);
        Map<EventId, InMemoryState.StepActivationMarker> activationMarkers =
                state.stepActivations.get(planId);
        Map<EventId, InMemoryState.StepCompletionMarker> completionMarkers =
                state.stepCompletions.get(planId);
        Map<EventId, InMemoryState.StepPauseMarker> pauseMarkers =
                state.stepPauses.get(planId);
        Map<EventId, InMemoryState.StepFailMarker> failureMarkers =
                state.stepFailures.get(planId);
        Map<EventId, InMemoryState.StepCancelMarker> cancellationMarkers =
                state.stepCancellations.get(planId);
        Map<EventId, InMemoryState.PlanReplanMarker> replanMarkers =
                state.planReplans.get(planId);
        Map<EventId, InMemoryState.ActiveStepReplanMarker>
                activeStepReplanMarkers = state.activeStepReplans.get(planId);
        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        if (plan == null
                || bootstrap == null
                || start == null
                || current == null
                || currentHead == null
                || !isCompleteHead(currentHead)
                || links == null
                || activationMarkers == null
                || completionMarkers == null
                || pauseMarkers == null
                || failureMarkers == null
                || cancellationMarkers == null
                || stream == null
                || stream.isEmpty()) {
            return null;
        }
        TaskFrame taskFrame = planRoot.taskFrame();
        if (!hasCanonicalStart(
                        planId, taskFrame, plan, bootstrap, start)
                || !hasConsistentEventProjection(
                        state, planId, taskFrame, stream)
                || !start.result().startEvent().equals(stream.get(1L))) {
            return null;
        }

        MutationHistory history = reconstructMutationHistory(state, planId);
        if (history == null
                || !history.plan().equals(plan)
                || !history.checkpoint().equals(current)
                || !history.head().equals(currentHead)) {
            return null;
        }

        PlanRevision latest = plan.latestRevision();
        Checkpoint checkpoint = current.checkpoint();
        EventEnvelope headEvent = stream.lastEntry().getValue();
        if (links.isEmpty()) {
            if (!start.result().startedCheckpoint().equals(current)) {
                return null;
            }
        } else {
            InMemoryState.ExecutionMutationLink tip =
                    links.get(links.size() - 1);
            VersionedCheckpoint tipCheckpoint;
            String operationType = tip.markerIdentity().operationType();
            if ("STEP_ACTIVATION".equals(operationType)) {
                InMemoryState.StepActivationMarker tipMarker =
                        activationMarkers.get(tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().activatedCheckpoint();
            } else if ("STEP_COMPLETION".equals(operationType)) {
                InMemoryState.StepCompletionMarker tipMarker =
                        completionMarkers.get(tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().completedCheckpoint();
            } else if ("STEP_PAUSE".equals(operationType)) {
                InMemoryState.StepPauseMarker tipMarker =
                        pauseMarkers.get(tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().interruptedCheckpoint();
            } else if ("STEP_FAIL".equals(operationType)) {
                InMemoryState.StepFailMarker tipMarker =
                        failureMarkers.get(tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().interruptedCheckpoint();
            } else if ("STEP_CANCEL".equals(operationType)) {
                InMemoryState.StepCancelMarker tipMarker =
                        cancellationMarkers.get(tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().interruptedCheckpoint();
            } else if ("PLAN_REPLAN".equals(operationType)) {
                InMemoryState.PlanReplanMarker tipMarker = replanMarkers == null
                        ? null
                        : replanMarkers.get(tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().replannedCheckpoint();
            } else if ("ACTIVE_STEP_REPLAN_SUPERSESSION".equals(operationType)) {
                InMemoryState.ActiveStepReplanMarker tipMarker =
                        activeStepReplanMarkerForEvent(
                                activeStepReplanMarkers,
                                tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().supersededCheckpoint();
            } else if ("ACTIVE_STEP_REPLAN_REPLAN".equals(operationType)) {
                InMemoryState.ActiveStepReplanMarker tipMarker =
                        activeStepReplanMarkerForEvent(
                                activeStepReplanMarkers,
                                tip.markerIdentity().eventId());
                if (tipMarker == null) {
                    return null;
                }
                tipCheckpoint = tipMarker.result().replannedCheckpoint();
            } else {
                return null;
            }
            if (!tipCheckpoint.equals(current)) {
                return null;
            }
        }
        if (!currentHead.revisionId().equals(latest.id())
                || currentHead.revisionNumber() != latest.number()
                || currentHead.checkpointVersion() != current.version()
                || currentHead.eventHeadSequence() != stream.lastKey()
                || !currentHead.mutationEventId().equals(headEvent.id())
                || !checkpoint.revisionId().equals(latest.id())
                || checkpoint.revisionNumber() != latest.number()
                || checkpoint.lastEventSequence() != stream.lastKey()
                || checkpoint.lastEventSequence() == 0
                || current.version() < 2
                || !checkpoint.planId().equals(planId)
                || !checkpoint.taskFrameId().equals(taskFrame.id())
                || !hasCoherentStepAndFactShape(checkpoint, latest)
                || !CheckpointValidators.validate(
                                checkpoint,
                                taskFrame,
                                plan,
                                start.result().startedCheckpoint().checkpoint())
                        .isEmpty()
                || !referencedReceiptsExist(state, checkpoint, latest)) {
            return null;
        }
        return new AuthoritativeSource(
                taskFrame,
                plan,
                current,
                stream.lastKey(),
                currentHead,
                stream,
                links,
                activationMarkers);
    }

    static PlanRoot validatePlanRoot(
            InMemoryState state,
            PlanId planId) {
        Plan plan = state.plans.get(planId);
        PersistedPlanBootstrap bootstrap =
                state.planBootstraps.get(planId);
        if (plan == null || bootstrap == null) {
            return null;
        }
        TaskFrame taskFrame = state.taskFrames.get(plan.taskFrameId());
        return hasCanonicalBootstrapRoot(
                        planId, taskFrame, plan, bootstrap)
                ? new PlanRoot(taskFrame, plan, bootstrap)
                : null;
    }

    static InMemoryState.ExecutionMutationHead headFromStart(
            PersistedExecutionStart start) {
        Checkpoint checkpoint = start.startedCheckpoint().checkpoint();
        return new InMemoryState.ExecutionMutationHead(
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                start.startedCheckpoint().version(),
                start.startEvent().sequence(),
                start.startEvent().id());
    }

    private static boolean hasCanonicalBootstrapRoot(
            PlanId planId,
            TaskFrame taskFrame,
            Plan currentPlan,
            PersistedPlanBootstrap bootstrap) {
        if (taskFrame == null
                || !taskFrame.equals(
                        bootstrap == null
                                ? null
                                : bootstrap.taskFrame())
                || !taskFrame.id().equals(currentPlan.taskFrameId())
                || !planId.equals(currentPlan.id())
                || !planId.equals(bootstrap.plan().id())
                || !taskFrame.id().equals(bootstrap.plan().taskFrameId())
                || !isExactPrefix(
                        bootstrap.plan().revisions(),
                        currentPlan.revisions())) {
            return false;
        }
        VersionedCheckpoint initial = bootstrap.initialCheckpoint();
        PlanRevision revision = bootstrap.plan().latestRevision();
        Checkpoint checkpoint = initial.checkpoint();
        return initial.version() == 1
                && checkpoint.taskFrameId().equals(taskFrame.id())
                && checkpoint.planId().equals(planId)
                && matchesRevision(revision, checkpoint)
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && hasExactStepShape(
                        checkpoint, revision, StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty()
                && CheckpointValidators.validate(
                                checkpoint,
                                taskFrame,
                                bootstrap.plan(),
                                null)
                        .isEmpty();
    }

    private static boolean hasCanonicalStart(
            PlanId planId,
            TaskFrame taskFrame,
            Plan currentPlan,
            PersistedPlanBootstrap bootstrap,
            InMemoryState.ExecutionStartMarker marker) {
        if (marker.request() == null || marker.result() == null) {
            return false;
        }
        ExecutionStartRequest request = marker.request();
        PersistedExecutionStart result = marker.result();
        EventEnvelope event = result.startEvent();
        VersionedCheckpoint started = result.startedCheckpoint();
        Checkpoint checkpoint = started.checkpoint();
        PlanRevision revision = findRevision(
                currentPlan, checkpoint.revisionNumber());
        Plan planAtStart = revision == null
                ? null
                : planEndingAt(currentPlan, revision);
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.fencingToken() == result.fencingToken()
                && request.startEvent().equals(event)
                && request.startedCheckpoint().equals(checkpoint)
                && started.version() == 2
                && event.planId().equals(planId)
                && event.taskFrameId().equals(taskFrame.id())
                && event.sequence() == 1
                && checkpoint.planId().equals(planId)
                && checkpoint.taskFrameId().equals(taskFrame.id())
                && checkpoint.lastEventSequence() == 1
                && checkpoint.planState() == PlanExecutionState.ACTIVE
                && !checkpoint.createdAt().isBefore(
                        bootstrap.initialCheckpoint().checkpoint().createdAt())
                && isNotBeforeBootstrap(
                        checkpoint,
                        bootstrap.initialCheckpoint().checkpoint())
                && revision != null
                && matchesRevision(revision, checkpoint)
                && revision.completedFacts().isEmpty()
                && hasExactStepShape(
                        checkpoint,
                        revision,
                        StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && planAtStart != null
                && CheckpointValidators.validate(
                                checkpoint,
                                taskFrame,
                                planAtStart,
                                bootstrap.initialCheckpoint().checkpoint())
                        .isEmpty();
    }

    private static boolean isNotBeforeBootstrap(
            Checkpoint started,
            Checkpoint bootstrap) {
        return started.revisionNumber() > bootstrap.revisionNumber()
                || started.revisionNumber() == bootstrap.revisionNumber()
                        && started.revisionId().equals(bootstrap.revisionId());
    }

    private static boolean hasConsistentEventProjection(
            InMemoryState state,
            PlanId planId,
            TaskFrame taskFrame,
            NavigableMap<Long, EventEnvelope> stream) {
        Set<EventId> ids = new HashSet<>();
        long previous = 0;
        for (Map.Entry<Long, EventEnvelope> entry : stream.entrySet()) {
            Long sequence = entry.getKey();
            EventEnvelope event = entry.getValue();
            if (sequence == null
                    || event == null
                    || sequence != event.sequence()
                    || sequence <= previous
                    || !event.planId().equals(planId)
                    || !event.taskFrameId().equals(taskFrame.id())
                    || !ids.add(event.id())
                    || !event.equals(state.eventsById.get(event.id()))) {
                return false;
            }
            previous = sequence;
        }
        int indexedForPlan = 0;
        for (Map.Entry<EventId, EventEnvelope> indexed :
                state.eventsById.entrySet()) {
            EventEnvelope event = indexed.getValue();
            if (event != null && planId.equals(event.planId())) {
                indexedForPlan++;
                if (!event.id().equals(indexed.getKey())
                        || !event.equals(stream.get(event.sequence()))) {
                    return false;
                }
            }
        }
        return indexedForPlan == stream.size();
    }

    static boolean hasValidInterruptionReplayProvenance(
            InMemoryState state,
            PlanId planId,
            EventId eventId,
            StepInterruptionKind kind,
            Object marker) {
        if (!isStoredInterruptionMarker(
                state, planId, eventId, kind, marker)) {
            return false;
        }
        MutationHistory history = reconstructMutationHistory(state, planId);
        return history != null && history.markerIdentities().contains(
                interruptionMarkerIdentity(kind, eventId));
    }

    static boolean hasValidPlanReplanReplayProvenance(
            InMemoryState state,
            PlanId planId,
            EventId eventId,
            InMemoryState.PlanReplanMarker marker) {
        MutationHistory history = reconstructMutationHistory(state, planId);
        return state.planReplans.get(planId) != null
                && state.planReplans.get(planId).get(eventId) == marker
                && isSelfConsistentPlanReplanMarker(planId, eventId, marker)
                && history != null
                && history.markerIdentities()
                        .contains(InMemoryState.ExecutionMutationMarkerIdentity
                                .planReplan(eventId));
    }

    static boolean hasValidActiveStepReplanReplayProvenance(
            InMemoryState state,
            PlanId planId,
            EventId supersessionEventId,
            InMemoryState.ActiveStepReplanMarker marker) {
        MutationHistory history = reconstructMutationHistory(state, planId);
        return state.activeStepReplans.get(planId) != null
                && state.activeStepReplans.get(planId).get(supersessionEventId)
                == marker
                && isSelfConsistentActiveStepReplanMarker(
                        planId, supersessionEventId, marker)
                && history != null
                && history.markerIdentities().contains(
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .activeStepReplanSupersession(
                                        supersessionEventId))
                && history.markerIdentities().contains(
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .activeStepReplanReplan(
                                        marker.request().replanEvent().id()));
    }

    private static boolean isStoredInterruptionMarker(
            InMemoryState state,
            PlanId planId,
            EventId eventId,
            StepInterruptionKind kind,
            Object marker) {
        if (kind == null || eventId == null) {
            return false;
        }
        return switch (kind) {
            case PAUSE -> marker instanceof InMemoryState.StepPauseMarker pause
                    && state.stepPauses.get(planId) != null
                    && state.stepPauses.get(planId).get(eventId) == pause
                    && isSelfConsistentPauseMarker(planId, eventId, pause);
            case FAIL -> marker instanceof InMemoryState.StepFailMarker failure
                    && state.stepFailures.get(planId) != null
                    && state.stepFailures.get(planId).get(eventId) == failure
                    && isSelfConsistentFailMarker(planId, eventId, failure);
            case CANCEL -> marker instanceof InMemoryState.StepCancelMarker cancellation
                    && state.stepCancellations.get(planId) != null
                    && state.stepCancellations.get(planId).get(eventId) == cancellation
                    && isSelfConsistentCancelMarker(
                            planId, eventId, cancellation);
        };
    }

    /*
     * Replay must not consult the mutable Plan/checkpoint projection. Rebuild
     * the durable execution history from the bootstrap, start, marker links,
     * and event projection instead.
     */
    private static MutationHistory reconstructMutationHistory(
            InMemoryState state,
            PlanId planId) {
        PersistedPlanBootstrap bootstrap = state.planBootstraps.get(planId);
        InMemoryState.ExecutionStartMarker start =
                state.executionStarts.get(planId);
        List<InMemoryState.ExecutionMutationLink> links =
                state.executionMutationLinks.get(planId);
        Map<EventId, InMemoryState.StepActivationMarker> activationMarkers =
                state.stepActivations.get(planId);
        Map<EventId, InMemoryState.StepCompletionMarker> completionMarkers =
                state.stepCompletions.get(planId);
        Map<EventId, InMemoryState.StepPauseMarker> pauseMarkers =
                state.stepPauses.get(planId);
        Map<EventId, InMemoryState.StepFailMarker> failureMarkers =
                state.stepFailures.get(planId);
        Map<EventId, InMemoryState.StepCancelMarker> cancellationMarkers =
                state.stepCancellations.get(planId);
        Map<EventId, InMemoryState.PlanReplanMarker> replanMarkers =
                state.planReplans.get(planId);
        Map<EventId, InMemoryState.ActiveStepReplanMarker>
                activeStepReplanMarkers = state.activeStepReplans.get(planId);
        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        if (bootstrap == null
                || start == null
                || links == null
                || activationMarkers == null
                || completionMarkers == null
                || pauseMarkers == null
                || failureMarkers == null
                || cancellationMarkers == null
                || stream == null
                || stream.isEmpty()) {
            return null;
        }
        Plan plan = start.startPlan() == null
                ? bootstrap.plan()
                : start.startPlan();
        TaskFrame taskFrame = state.taskFrames.get(plan.taskFrameId());
        if (!hasCanonicalBootstrapRoot(planId, taskFrame, plan, bootstrap)
                || !hasCanonicalStart(planId, taskFrame, plan, bootstrap, start)
                || !hasConsistentEventProjection(
                        state, planId, taskFrame, stream)
                || !start.result().startEvent().equals(stream.get(1L))) {
            return null;
        }

        int replanMarkerCount = replanMarkers == null ? 0 : replanMarkers.size();
        int activeStepReplanMarkerCount = activeStepReplanMarkers == null
                ? 0
                : activeStepReplanMarkers.size();
        int markerCount = activationMarkers.size()
                + completionMarkers.size()
                + pauseMarkers.size()
                + failureMarkers.size()
                + cancellationMarkers.size()
                + replanMarkerCount
                + activeStepReplanMarkerCount * 2;
        if (links.size() != markerCount) {
            return null;
        }
        Set<EventId> markerIds = new HashSet<>(activationMarkers.keySet());
        markerIds.addAll(completionMarkers.keySet());
        markerIds.addAll(pauseMarkers.keySet());
        markerIds.addAll(failureMarkers.keySet());
        markerIds.addAll(cancellationMarkers.keySet());
        if (replanMarkers != null) {
            markerIds.addAll(replanMarkers.keySet());
        }
        if (activeStepReplanMarkers != null) {
            for (Map.Entry<EventId, InMemoryState.ActiveStepReplanMarker> entry
                    : activeStepReplanMarkers.entrySet()) {
                InMemoryState.ActiveStepReplanMarker marker = entry.getValue();
                if (entry.getKey() == null
                        || marker == null
                        || marker.request() == null
                        || !entry.getKey().equals(
                                marker.request().supersessionEvent().id())
                        || !markerIds.add(entry.getKey())
                        || !markerIds.add(
                                marker.request().replanEvent().id())) {
                    return null;
                }
            }
        }
        if (markerIds.size() != markerCount) {
            return null;
        }

        InMemoryState.ExecutionMutationHead previousHead =
                headFromStart(start.result());
        VersionedCheckpoint previousCheckpoint =
                start.result().startedCheckpoint();
        EventEnvelope previousEvent = start.result().startEvent();
        Set<EventId> visitedEventIds = new HashSet<>();
        Set<InMemoryState.ExecutionMutationMarkerIdentity> markerIdentities =
                new HashSet<>();
        for (InMemoryState.ExecutionMutationLink link : links) {
            if (!isCompleteLink(link)
                    || !link.previousHead().equals(previousHead)
                    || !visitedEventIds.add(link.markerIdentity().eventId())
                    || !markerIdentities.add(link.markerIdentity())
                    || link.resultHead().checkpointVersion()
                            != previousHead.checkpointVersion() + 1
                    || link.resultHead().eventHeadSequence()
                            <= previousHead.eventHeadSequence()) {
                return null;
            }
            HistoricalTransition transition = historicalTransition(
                    planId,
                    taskFrame,
                    plan,
                    previousCheckpoint.checkpoint(),
                    link,
                    activationMarkers,
                    completionMarkers,
                    pauseMarkers,
                    failureMarkers,
                    cancellationMarkers,
                    replanMarkers,
                    activeStepReplanMarkers);
            if (transition == null
                    || !isValidHistoricalEvent(
                            transition.event(), previousEvent, stream)) {
                return null;
            }
            plan = transition.plan();
            previousHead = link.resultHead();
            previousCheckpoint = transition.checkpoint();
            previousEvent = transition.event();
        }
        Set<EventId> successorEvents = stream.values().stream()
                .filter(event -> event.sequence() != 1)
                .map(EventEnvelope::id)
                .collect(Collectors.toSet());
        InMemoryState.ExecutionMutationHead storedHead =
                state.executionMutationHeads.get(planId);
        return visitedEventIds.equals(markerIds)
                && successorEvents.equals(visitedEventIds)
                && stream.size() == links.size() + 1
                && previousHead.equals(storedHead)
                ? new MutationHistory(
                        plan,
                        previousCheckpoint,
                        previousHead,
                        Set.copyOf(markerIdentities))
                : null;
    }

    private static HistoricalTransition historicalTransition(
            PlanId planId,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous,
            InMemoryState.ExecutionMutationLink link,
            Map<EventId, InMemoryState.StepActivationMarker> activationMarkers,
            Map<EventId, InMemoryState.StepCompletionMarker> completionMarkers,
            Map<EventId, InMemoryState.StepPauseMarker> pauseMarkers,
            Map<EventId, InMemoryState.StepFailMarker> failureMarkers,
            Map<EventId, InMemoryState.StepCancelMarker> cancellationMarkers,
            Map<EventId, InMemoryState.PlanReplanMarker> replanMarkers,
            Map<EventId, InMemoryState.ActiveStepReplanMarker>
                    activeStepReplanMarkers) {
        String operationType = link.markerIdentity().operationType();
        EventId eventId = link.markerIdentity().eventId();
        if ("STEP_ACTIVATION".equals(operationType)) {
            InMemoryState.StepActivationMarker marker = activationMarkers.get(eventId);
            if (!isActivationMarkerBackedLink(planId, marker, link)
                    || !isNarrowActivationTransition(
                            taskFrame, plan, previous, marker)) {
                return null;
            }
            return new HistoricalTransition(
                    plan,
                    marker.result().activatedCheckpoint(),
                    marker.result().activationEvent());
        }
        if ("STEP_COMPLETION".equals(operationType)) {
            InMemoryState.StepCompletionMarker marker = completionMarkers.get(eventId);
            Plan completedPlan = marker == null
                    ? null
                    : appendCompletionRevision(plan, marker.request());
            if (completedPlan == null
                    || !isCompletionMarkerBackedLink(
                            planId, completedPlan, marker, link)
                    || !isNarrowCompletionTransition(
                            taskFrame, plan, completedPlan, previous, marker)) {
                return null;
            }
            return new HistoricalTransition(
                    completedPlan,
                    marker.result().completedCheckpoint(),
                    marker.result().completionEvent());
        }
        if ("STEP_PAUSE".equals(operationType)) {
            InMemoryState.StepPauseMarker marker = pauseMarkers.get(eventId);
            return interruptionTransition(
                    planId, taskFrame, plan, previous, link,
                    StepInterruptionKind.PAUSE,
                    marker == null ? null : marker.request(),
                    marker == null ? null : marker.result(),
                    marker == null ? null : marker.provenanceLink(),
                    marker == null ? null : marker.result().interruptionEvent());
        }
        if ("STEP_FAIL".equals(operationType)) {
            InMemoryState.StepFailMarker marker = failureMarkers.get(eventId);
            return interruptionTransition(
                    planId, taskFrame, plan, previous, link,
                    StepInterruptionKind.FAIL,
                    marker == null ? null : marker.request(),
                    marker == null ? null : marker.result(),
                    marker == null ? null : marker.provenanceLink(),
                    marker == null ? null : marker.result().interruptionEvent());
        }
        if ("STEP_CANCEL".equals(operationType)) {
            InMemoryState.StepCancelMarker marker = cancellationMarkers.get(eventId);
            return interruptionTransition(
                    planId, taskFrame, plan, previous, link,
                    StepInterruptionKind.CANCEL,
                    marker == null ? null : marker.request(),
                    marker == null ? null : marker.result(),
                    marker == null ? null : marker.provenanceLink(),
                    marker == null ? null : marker.result().interruptionEvent());
        }
        if ("PLAN_REPLAN".equals(operationType)) {
            InMemoryState.PlanReplanMarker marker = replanMarkers == null
                    ? null
                    : replanMarkers.get(eventId);
            Plan replannedPlan = marker == null
                    ? null
                    : appendReplanRevision(plan, marker.request());
            if (replannedPlan == null
                    || !isPlanReplanMarkerBackedLink(
                            planId, replannedPlan, marker, link)
                    || !isNarrowPlanReplanTransition(
                            taskFrame, plan, replannedPlan, previous, marker)) {
                return null;
            }
            return new HistoricalTransition(
                    replannedPlan,
                    marker.result().replannedCheckpoint(),
                    marker.result().replanEvent());
        }
        if ("ACTIVE_STEP_REPLAN_SUPERSESSION".equals(operationType)) {
            InMemoryState.ActiveStepReplanMarker marker =
                    activeStepReplanMarkerForEvent(activeStepReplanMarkers, eventId);
            if (marker == null
                    || !isActiveStepReplanMarkerBackedLinks(
                            planId, plan, marker)
                    || !isNarrowActiveStepReplanSupersessionTransition(
                            taskFrame, plan, previous, marker)) {
                return null;
            }
            return new HistoricalTransition(
                    plan,
                    marker.result().supersededCheckpoint(),
                    marker.result().supersessionEvent());
        }
        if ("ACTIVE_STEP_REPLAN_REPLAN".equals(operationType)) {
            InMemoryState.ActiveStepReplanMarker marker =
                    activeStepReplanMarkerForEvent(activeStepReplanMarkers, eventId);
            Plan replannedPlan = marker == null
                    ? null
                    : appendActiveStepReplanRevision(plan, marker.request());
            if (replannedPlan == null
                    || !isActiveStepReplanMarkerBackedLinks(
                            planId, plan, marker)
                    || !isNarrowActiveStepReplanReplanTransition(
                            taskFrame,
                            plan,
                            replannedPlan,
                            previous,
                            marker)) {
                return null;
            }
            return new HistoricalTransition(
                    replannedPlan,
                    marker.result().replannedCheckpoint(),
                    marker.result().replanEvent());
        }
        return null;
    }

    private static HistoricalTransition interruptionTransition(
            PlanId planId,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous,
            InMemoryState.ExecutionMutationLink link,
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result,
            InMemoryState.ExecutionMutationLink provenanceLink,
            EventEnvelope event) {
        if (!isInterruptionMarkerBackedLink(
                        planId, kind, request, result, provenanceLink, link)
                || !isNarrowInterruptionTransition(
                        taskFrame, plan, previous, kind, request, result)) {
            return null;
        }
        return new HistoricalTransition(
                plan, result.interruptedCheckpoint(), event);
    }

    private static boolean isValidHistoricalEvent(
            EventEnvelope event,
            EventEnvelope previous,
            NavigableMap<Long, EventEnvelope> stream) {
        return event != null
                && event.sequence() > previous.sequence()
                && !event.occurredAt().isBefore(previous.occurredAt())
                && event.equals(stream.get(event.sequence()));
    }

    private static boolean isNarrowActivationTransition(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous,
            InMemoryState.StepActivationMarker marker) {
        if (marker == null || marker.request() == null || marker.result() == null) {
            return false;
        }
        StepActivationRequest request = marker.request();
        Checkpoint target = request.activatedCheckpoint();
        return InMemoryStepActivationRepository.isEligible(
                        plan, previous, request.stepId())
                && target.lastEventSequence()
                        == request.activationEvent().sequence()
                && hasSameCheckpointIdentity(previous, target)
                && target.planState() == PlanExecutionState.ACTIVE
                && target.createdAt().compareTo(previous.createdAt()) >= 0
                && target.receiptReferences().equals(previous.receiptReferences())
                && hasOnlyChangedStep(
                        previous, target, request.stepId(),
                        StepExecutionState.ACTIVE)
                && CheckpointValidators.validate(
                                target, taskFrame, plan, previous)
                        .isEmpty();
    }

    private static Plan appendCompletionRevision(
            Plan plan,
            StepCompletionRequest request) {
        if (request == null
                || !isExactCompletionRevision(
                        plan.latestRevision(), request, request.completedRevision())) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(plan.revisions());
        revisions.add(request.completedRevision());
        try {
            return new Plan(plan.id(), plan.taskFrameId(), revisions);
        } catch (ContractViolationException invalid) {
            return null;
        }
    }

    private static Plan appendReplanRevision(
            Plan plan,
            PlanReplanRequest request) {
        if (request == null
                || !isExactReplanRevision(
                        plan.latestRevision(), request.replannedRevision())) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(plan.revisions());
        revisions.add(request.replannedRevision());
        try {
            return new Plan(plan.id(), plan.taskFrameId(), revisions);
        } catch (ContractViolationException invalid) {
            return null;
        }
    }

    private static Plan appendActiveStepReplanRevision(
            Plan plan,
            ActiveStepReplanRequest request) {
        if (request == null
                || !isExactActiveStepReplanRevision(
                        plan.latestRevision(),
                        request.activeStepId(),
                        request.replannedRevision())) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(plan.revisions());
        revisions.add(request.replannedRevision());
        try {
            return new Plan(plan.id(), plan.taskFrameId(), revisions);
        } catch (ContractViolationException invalid) {
            return null;
        }
    }

    private static boolean isExactReplanRevision(
            PlanRevision previous,
            PlanRevision replanned) {
        return previous != null
                && replanned != null
                && replanned.number() == previous.number() + 1
                && replanned.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                && !replanned.createdAt().isBefore(previous.createdAt())
                && replanned.taskFrameId().equals(previous.taskFrameId())
                && replanned.completedFacts().equals(previous.completedFacts());
    }

    private static boolean isExactActiveStepReplanRevision(
            PlanRevision previous,
            PlanStepId supersededStepId,
            PlanRevision replanned) {
        return isExactReplanRevision(previous, replanned)
                && supersededStepId != null
                && replanned.steps().stream()
                        .noneMatch(step -> step.id().equals(supersededStepId));
    }

    private static boolean isNarrowCompletionTransition(
            TaskFrame taskFrame,
            Plan previousPlan,
            Plan completedPlan,
            Checkpoint previous,
            InMemoryState.StepCompletionMarker marker) {
        if (marker == null || marker.request() == null || marker.result() == null) {
            return false;
        }
        StepCompletionRequest request = marker.request();
        Checkpoint target = request.completedCheckpoint();
        List<io.paperagent.v2.contracts.ReceiptId> expectedReceipts =
                new ArrayList<>(previous.receiptReferences());
        expectedReceipts.addAll(request.completionFact().receiptReferences());
        boolean allSucceeded = target.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);
        return isEligibleCompletionSource(
                        previousPlan, previous, request)
                && target.lastEventSequence()
                        == request.completionEvent().sequence()
                && target.taskFrameId().equals(previous.taskFrameId())
                && target.planId().equals(previous.planId())
                && target.revisionId().equals(request.completedRevision().id())
                && target.revisionNumber()
                        == request.completedRevision().number()
                && target.createdAt().compareTo(previous.createdAt()) >= 0
                && target.receiptReferences().equals(expectedReceipts)
                && hasOnlyChangedStep(
                        previous, target, request.stepId(),
                        StepExecutionState.SUCCEEDED)
                && target.planState() == (allSucceeded
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.ACTIVE)
                && CheckpointValidators.validate(
                                target, taskFrame, completedPlan, previous)
                        .isEmpty();
    }

    private static boolean isNarrowPlanReplanTransition(
            TaskFrame taskFrame,
            Plan previousPlan,
            Plan replannedPlan,
            Checkpoint previous,
            InMemoryState.PlanReplanMarker marker) {
        if (marker == null || marker.request() == null || marker.result() == null) {
            return false;
        }
        PlanReplanRequest request = marker.request();
        Checkpoint target = request.replannedCheckpoint();
        PlanRevision revision = request.replannedRevision();
        return isEligiblePlanReplanSource(previousPlan, previous)
                && isExactReplanRevision(previousPlan.latestRevision(), revision)
                && target.lastEventSequence() == request.replanEvent().sequence()
                && target.taskFrameId().equals(previous.taskFrameId())
                && target.planId().equals(previous.planId())
                && target.revisionId().equals(revision.id())
                && target.revisionNumber() == revision.number()
                && !target.createdAt().isBefore(previous.createdAt())
                && target.receiptReferences().equals(previous.receiptReferences())
                && target.planState() == PlanExecutionState.ACTIVE
                && hasExpectedReplanStepShape(target, revision)
                && CheckpointValidators.validate(
                                target, taskFrame, replannedPlan, previous)
                        .isEmpty();
    }

    private static boolean isNarrowActiveStepReplanSupersessionTransition(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous,
            InMemoryState.ActiveStepReplanMarker marker) {
        if (marker == null || marker.request() == null || marker.result() == null) {
            return false;
        }
        ActiveStepReplanRequest request = marker.request();
        Checkpoint target = request.supersededCheckpoint();
        return isEligibleActiveStepReplanSource(
                        plan, previous, request.activeStepId())
                && target.lastEventSequence()
                        == request.supersessionEvent().sequence()
                && hasSameCheckpointIdentity(previous, target)
                && target.planState() == PlanExecutionState.ACTIVE
                && !target.createdAt().isBefore(previous.createdAt())
                && target.receiptReferences().equals(previous.receiptReferences())
                && hasOnlyChangedStep(
                        previous,
                        target,
                        request.activeStepId(),
                        StepExecutionState.SUPERSEDED_BY_REPLAN)
                && CheckpointValidators.validate(
                                target, taskFrame, plan, previous)
                        .isEmpty();
    }

    private static boolean isNarrowActiveStepReplanReplanTransition(
            TaskFrame taskFrame,
            Plan sourcePlan,
            Plan replannedPlan,
            Checkpoint superseded,
            InMemoryState.ActiveStepReplanMarker marker) {
        if (marker == null || marker.request() == null || marker.result() == null) {
            return false;
        }
        ActiveStepReplanRequest request = marker.request();
        PlanRevision revision = request.replannedRevision();
        Checkpoint target = request.replannedCheckpoint();
        return isExactActiveStepReplanRevision(
                        sourcePlan.latestRevision(),
                        request.activeStepId(),
                        revision)
                && target.lastEventSequence() == request.replanEvent().sequence()
                && target.taskFrameId().equals(superseded.taskFrameId())
                && target.planId().equals(superseded.planId())
                && target.revisionId().equals(revision.id())
                && target.revisionNumber() == revision.number()
                && !target.createdAt().isBefore(superseded.createdAt())
                && target.receiptReferences().equals(superseded.receiptReferences())
                && target.planState() == PlanExecutionState.ACTIVE
                && hasExpectedReplanStepShape(target, revision)
                && CheckpointValidators.validate(
                                target, taskFrame, replannedPlan, superseded)
                        .isEmpty();
    }

    private static boolean isEligiblePlanReplanSource(
            Plan plan,
            Checkpoint checkpoint) {
        if (checkpoint.planState() != PlanExecutionState.ACTIVE) {
            return false;
        }
        PlanRevision revision = plan.latestRevision();
        for (PlanStep step : revision.steps()) {
            StepExecutionState expected = revision.completedFacts().containsKey(step.id())
                    ? StepExecutionState.SUCCEEDED
                    : StepExecutionState.NOT_STARTED;
            if (checkpoint.stepStates().get(step.id()) != expected) {
                return false;
            }
        }
        return checkpoint.stepStates().size() == revision.steps().size();
    }

    static boolean isEligibleActiveStepReplanSource(
            Plan plan,
            Checkpoint checkpoint,
            PlanStepId activeStepId) {
        PlanRevision revision = plan.latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || checkpoint.stepStates().get(activeStepId)
                != StepExecutionState.ACTIVE
                || revision.completedFacts().containsKey(activeStepId)
                || revision.steps().stream()
                        .noneMatch(step -> step.id().equals(activeStepId))) {
            return false;
        }
        int activeCount = 0;
        for (PlanStep step : revision.steps()) {
            StepExecutionState expected = revision.completedFacts().containsKey(step.id())
                    ? StepExecutionState.SUCCEEDED
                    : step.id().equals(activeStepId)
                    ? StepExecutionState.ACTIVE
                    : StepExecutionState.NOT_STARTED;
            if (checkpoint.stepStates().get(step.id()) != expected) {
                return false;
            }
            if (expected == StepExecutionState.ACTIVE) {
                activeCount++;
            }
        }
        return activeCount == 1
                && checkpoint.stepStates().size() == revision.steps().size();
    }

    private static boolean hasExpectedReplanStepShape(
            Checkpoint checkpoint,
            PlanRevision revision) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        if (!checkpoint.stepStates().keySet().equals(stepIds)) {
            return false;
        }
        for (PlanStep step : revision.steps()) {
            StepExecutionState expected = revision.completedFacts().containsKey(step.id())
                    ? StepExecutionState.SUCCEEDED
                    : StepExecutionState.NOT_STARTED;
            if (checkpoint.stepStates().get(step.id()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEligibleCompletionSource(
            Plan plan,
            Checkpoint checkpoint,
            StepCompletionRequest request) {
        PlanRevision revision = plan.latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || !request.completionFact().stepId().equals(request.stepId())
                || checkpoint.stepStates().get(request.stepId())
                        != StepExecutionState.ACTIVE
                || revision.completedFacts().containsKey(request.stepId())
                || revision.steps().stream()
                        .noneMatch(step -> step.id().equals(request.stepId()))) {
            return false;
        }
        int active = 0;
        for (StepExecutionState state : checkpoint.stepStates().values()) {
            if (state == StepExecutionState.ACTIVE) {
                active++;
            }
            if (state == StepExecutionState.PAUSED
                    || state == StepExecutionState.FAILED
                    || state == StepExecutionState.CANCELLED) {
                return false;
            }
        }
        return active == 1;
    }

    private static boolean isNarrowInterruptionTransition(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous,
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result) {
        InterruptionData data = interruptionData(kind, request, result);
        if (data == null
                || !isEligibleInterruptionSource(
                        plan, previous, data.stepId())) {
            return false;
        }
        Checkpoint target = data.checkpoint();
        return target.lastEventSequence() == data.event().sequence()
                && hasSameCheckpointIdentity(previous, target)
                && target.createdAt().compareTo(previous.createdAt()) >= 0
                && target.receiptReferences().equals(previous.receiptReferences())
                && target.planState() == interruptionPlanState(kind)
                && hasOnlyChangedStep(
                        previous, target, data.stepId(),
                        interruptionStepState(kind))
                && CheckpointValidators.validate(
                                target, taskFrame, plan, previous)
                        .isEmpty();
    }

    private static boolean isEligibleInterruptionSource(
            Plan plan,
            Checkpoint checkpoint,
            PlanStepId stepId) {
        PlanRevision revision = plan.latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || checkpoint.stepStates().get(stepId) != StepExecutionState.ACTIVE
                || revision.completedFacts().containsKey(stepId)
                || revision.steps().stream()
                        .noneMatch(step -> step.id().equals(stepId))) {
            return false;
        }
        int active = 0;
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                checkpoint.stepStates().entrySet()) {
            if (entry.getValue() == StepExecutionState.ACTIVE) {
                active++;
            }
            if (!entry.getKey().equals(stepId)
                    && entry.getValue() != StepExecutionState.NOT_STARTED
                    && entry.getValue() != StepExecutionState.SUCCEEDED) {
                return false;
            }
        }
        return active == 1;
    }

    private static boolean hasSameCheckpointIdentity(
            Checkpoint previous,
            Checkpoint target) {
        return target.taskFrameId().equals(previous.taskFrameId())
                && target.planId().equals(previous.planId())
                && target.revisionId().equals(previous.revisionId())
                && target.revisionNumber() == previous.revisionNumber()
                && target.stepStates().keySet().equals(
                        previous.stepStates().keySet());
    }

    private static boolean hasOnlyChangedStep(
            Checkpoint previous,
            Checkpoint target,
            PlanStepId targetId,
            StepExecutionState targetState) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                previous.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? targetState
                    : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean isActivationMarkerBackedLink(
            PlanId planId,
            InMemoryState.StepActivationMarker marker,
            InMemoryState.ExecutionMutationLink link) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || !isCompleteLink(link)
                || !marker.provenanceLink().equals(link)) {
            return false;
        }
        StepActivationRequest request = marker.request();
        PersistedStepActivation result = marker.result();
        Checkpoint target = request.activatedCheckpoint();
        VersionedCheckpoint persisted = result.activatedCheckpoint();
        InMemoryState.ExecutionMutationHead previous = link.previousHead();
        InMemoryState.ExecutionMutationHead resultHead = link.resultHead();
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.activationEvent().id().equals(
                        link.markerIdentity().eventId())
                && request.activationEvent().equals(result.activationEvent())
                && request.stepId().equals(result.stepId())
                && request.fencingToken() == result.fencingToken()
                && request.expectedRevisionId().equals(previous.revisionId())
                && request.expectedRevisionNumber()
                        == previous.revisionNumber()
                && request.expectedCheckpointVersion()
                        == previous.checkpointVersion()
                && request.expectedEventHeadSequence()
                        == previous.eventHeadSequence()
                && persisted.version()
                        == request.expectedCheckpointVersion() + 1
                && persisted.checkpoint().equals(target)
                && resultHead.revisionId().equals(previous.revisionId())
                && resultHead.revisionNumber() == previous.revisionNumber()
                && resultHead.revisionId().equals(target.revisionId())
                && resultHead.revisionNumber() == target.revisionNumber()
                && resultHead.checkpointVersion() == persisted.version()
                && resultHead.eventHeadSequence()
                        == request.activationEvent().sequence()
                && resultHead.mutationEventId().equals(
                        request.activationEvent().id());
    }

    private static boolean isInterruptionMarkerBackedLink(
            PlanId planId,
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result,
            InMemoryState.ExecutionMutationLink provenanceLink,
            InMemoryState.ExecutionMutationLink link) {
        InterruptionData data = interruptionData(kind, request, result);
        if (data == null
                || provenanceLink == null
                || !provenanceLink.equals(link)
                || !isCompleteLink(link)) {
            return false;
        }
        InMemoryState.ExecutionMutationHead previous = link.previousHead();
        InMemoryState.ExecutionMutationHead resultHead = link.resultHead();
        VersionedCheckpoint persisted = result.interruptedCheckpoint();
        return data.planId().equals(planId)
                && result.planId().equals(planId)
                && result.kind() == kind
                && data.stepId().equals(result.stepId())
                && data.fencingToken() == result.fencingToken()
                && data.event().equals(result.interruptionEvent())
                && data.expectedRevisionId().equals(previous.revisionId())
                && data.expectedRevisionNumber() == previous.revisionNumber()
                && data.expectedCheckpointVersion()
                        == previous.checkpointVersion()
                && data.expectedEventHeadSequence()
                        == previous.eventHeadSequence()
                && persisted.version() == data.expectedCheckpointVersion() + 1
                && persisted.checkpoint().equals(data.checkpoint())
                && data.checkpoint().revisionId().equals(previous.revisionId())
                && data.checkpoint().revisionNumber() == previous.revisionNumber()
                && data.checkpoint().lastEventSequence()
                        == data.event().sequence()
                && data.checkpoint().planState() == interruptionPlanState(kind)
                && data.checkpoint().stepStates().get(data.stepId())
                        == interruptionStepState(kind)
                && link.markerIdentity().equals(
                        interruptionMarkerIdentity(kind, data.event().id()))
                && resultHead.revisionId().equals(previous.revisionId())
                && resultHead.revisionNumber() == previous.revisionNumber()
                && resultHead.checkpointVersion() == persisted.version()
                && resultHead.eventHeadSequence() == data.event().sequence()
                && resultHead.mutationEventId().equals(data.event().id());
    }

    private static InterruptionData interruptionData(
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result) {
        if (result == null || result.kind() != kind) {
            return null;
        }
        return switch (kind) {
            case PAUSE -> request instanceof StepPauseRequest pause
                    ? new InterruptionData(
                            pause.planId(),
                            pause.stepId(),
                            pause.fencingToken(),
                            pause.expectedRevisionId(),
                            pause.expectedRevisionNumber(),
                            pause.expectedCheckpointVersion(),
                            pause.expectedEventHeadSequence(),
                            pause.pauseEvent(),
                            pause.pausedCheckpoint())
                    : null;
            case FAIL -> request instanceof StepFailRequest failure
                    ? new InterruptionData(
                            failure.planId(),
                            failure.stepId(),
                            failure.fencingToken(),
                            failure.expectedRevisionId(),
                            failure.expectedRevisionNumber(),
                            failure.expectedCheckpointVersion(),
                            failure.expectedEventHeadSequence(),
                            failure.failureEvent(),
                            failure.failedCheckpoint())
                    : null;
            case CANCEL -> request instanceof StepCancelRequest cancellation
                    ? new InterruptionData(
                            cancellation.planId(),
                            cancellation.stepId(),
                            cancellation.fencingToken(),
                            cancellation.expectedRevisionId(),
                            cancellation.expectedRevisionNumber(),
                            cancellation.expectedCheckpointVersion(),
                            cancellation.expectedEventHeadSequence(),
                            cancellation.cancellationEvent(),
                            cancellation.cancelledCheckpoint())
                    : null;
        };
    }

    private static InMemoryState.ExecutionMutationMarkerIdentity
            interruptionMarkerIdentity(
                    StepInterruptionKind kind,
                    EventId eventId) {
        return switch (kind) {
            case PAUSE -> InMemoryState.ExecutionMutationMarkerIdentity
                    .stepPause(eventId);
            case FAIL -> InMemoryState.ExecutionMutationMarkerIdentity
                    .stepFail(eventId);
            case CANCEL -> InMemoryState.ExecutionMutationMarkerIdentity
                    .stepCancel(eventId);
        };
    }

    private static StepExecutionState interruptionStepState(
            StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    private static PlanExecutionState interruptionPlanState(
            StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    private static boolean isCompleteLink(
            InMemoryState.ExecutionMutationLink link) {
        return link != null
                && isCompleteHead(link.previousHead())
                && isCompleteHead(link.resultHead())
                && link.markerIdentity() != null
                && link.markerIdentity().operationType() != null
                && link.markerIdentity().eventId() != null;
    }

    private static boolean isCompleteHead(
            InMemoryState.ExecutionMutationHead head) {
        return head != null
                && head.revisionId() != null
                && head.revisionNumber() > 0
                && head.checkpointVersion() > 0
                && head.eventHeadSequence() > 0
                && head.mutationEventId() != null;
    }

    static boolean isSelfConsistentMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.StepActivationMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || marker.provenanceLink().markerIdentity() == null) {
            return false;
        }
        return "STEP_ACTIVATION".equals(
                        marker.provenanceLink()
                                .markerIdentity()
                                .operationType())
                && markerKey.equals(
                        marker.provenanceLink()
                                .markerIdentity()
                                .eventId())
                && markerKey.equals(
                        marker.request().activationEvent().id())
                && isActivationMarkerBackedLink(
                        planId, marker, marker.provenanceLink());
    }

    static boolean isSelfConsistentCompletionMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.StepCompletionMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || marker.provenanceLink().markerIdentity() == null) {
            return false;
        }
        return "STEP_COMPLETION".equals(
                        marker.provenanceLink()
                                .markerIdentity()
                                .operationType())
                && markerKey.equals(
                        marker.provenanceLink()
                                .markerIdentity()
                                .eventId())
                && markerKey.equals(
                        marker.request().completionEvent().id())
                && isCompletionMarkerBackedLink(
                        planId, null, marker, marker.provenanceLink());
    }

    static boolean isSelfConsistentPauseMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.StepPauseMarker marker) {
        return isSelfConsistentInterruptionMarker(
                planId,
                markerKey,
                StepInterruptionKind.PAUSE,
                marker == null ? null : marker.request(),
                marker == null ? null : marker.result(),
                marker == null ? null : marker.provenanceLink());
    }

    static boolean isSelfConsistentFailMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.StepFailMarker marker) {
        return isSelfConsistentInterruptionMarker(
                planId,
                markerKey,
                StepInterruptionKind.FAIL,
                marker == null ? null : marker.request(),
                marker == null ? null : marker.result(),
                marker == null ? null : marker.provenanceLink());
    }

    static boolean isSelfConsistentCancelMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.StepCancelMarker marker) {
        return isSelfConsistentInterruptionMarker(
                planId,
                markerKey,
                StepInterruptionKind.CANCEL,
                marker == null ? null : marker.request(),
                marker == null ? null : marker.result(),
                marker == null ? null : marker.provenanceLink());
    }

    static boolean isSelfConsistentPlanReplanMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.PlanReplanMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || marker.provenanceLink().markerIdentity() == null) {
            return false;
        }
        return "PLAN_REPLAN".equals(
                        marker.provenanceLink().markerIdentity().operationType())
                && markerKey.equals(marker.provenanceLink().markerIdentity().eventId())
                && markerKey.equals(marker.request().replanEvent().id())
                && isPlanReplanMarkerBackedLink(
                        planId, null, marker, marker.provenanceLink());
    }

    static boolean isSelfConsistentActiveStepReplanMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.ActiveStepReplanMarker marker) {
        return markerKey != null
                && marker != null
                && marker.request() != null
                && marker.result() != null
                && marker.supersessionProvenanceLink() != null
                && marker.replanProvenanceLink() != null
                && markerKey.equals(marker.request().supersessionEvent().id())
                && isActiveStepReplanMarkerBackedLinks(planId, null, marker);
    }

    private static boolean isSelfConsistentInterruptionMarker(
            PlanId planId,
            EventId markerKey,
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result,
            InMemoryState.ExecutionMutationLink link) {
        return markerKey != null
                && link != null
                && link.markerIdentity() != null
                && markerKey.equals(link.markerIdentity().eventId())
                && isInterruptionMarkerBackedLink(
                        planId, kind, request, result, link, link);
    }

    private static boolean isCompletionMarkerBackedLink(
            PlanId planId,
            Plan plan,
            InMemoryState.StepCompletionMarker marker,
            InMemoryState.ExecutionMutationLink link) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || !isCompleteLink(link)
                || !marker.provenanceLink().equals(link)) {
            return false;
        }
        StepCompletionRequest request = marker.request();
        PersistedStepCompletion result = marker.result();
        EventEnvelope event = request.completionEvent();
        PlanRevision revision = request.completedRevision();
        Checkpoint checkpoint = request.completedCheckpoint();
        VersionedCheckpoint persisted = result.completedCheckpoint();
        InMemoryState.ExecutionMutationHead previous = link.previousHead();
        InMemoryState.ExecutionMutationHead resultHead = link.resultHead();
        CompletionFact fact = revision.completedFacts().get(request.stepId());
        PlanRevision previousRevision = plan == null
                ? null
                : findRevision(plan, previous.revisionNumber());
        PlanRevision authoritativeRevision = plan == null
                ? null
                : findRevision(plan, revision.number());
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.stepId().equals(result.stepId())
                && request.completionFact().stepId().equals(request.stepId())
                && request.completionFact().equals(fact)
                && event.id().equals(link.markerIdentity().eventId())
                && event.planId().equals(planId)
                && event.taskFrameId().equals(revision.taskFrameId())
                && event.equals(result.completionEvent())
                && revision.equals(result.completedRevision())
                && checkpoint.equals(persisted.checkpoint())
                && request.fencingToken() == result.fencingToken()
                && request.expectedRevisionId().equals(previous.revisionId())
                && request.expectedRevisionNumber()
                        == previous.revisionNumber()
                && request.expectedCheckpointVersion()
                        == previous.checkpointVersion()
                && request.expectedEventHeadSequence()
                        == previous.eventHeadSequence()
                && event.sequence() > previous.eventHeadSequence()
                && revision.number() == previous.revisionNumber() + 1
                && revision.parentRevisionId().equals(
                        java.util.Optional.of(previous.revisionId()))
                && (plan == null || revision.equals(authoritativeRevision)
                        && isExactCompletionRevision(
                                previousRevision, request, revision))
                && persisted.version()
                        == request.expectedCheckpointVersion() + 1
                && checkpoint.planId().equals(planId)
                && checkpoint.taskFrameId().equals(revision.taskFrameId())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == event.sequence()
                && checkpoint.stepStates().get(request.stepId())
                        == StepExecutionState.SUCCEEDED
                && hasCoherentStepAndFactShape(checkpoint, revision)
                && resultHead.revisionId().equals(revision.id())
                && resultHead.revisionNumber() == revision.number()
                && resultHead.checkpointVersion() == persisted.version()
                && resultHead.eventHeadSequence() == event.sequence()
                && resultHead.mutationEventId().equals(event.id());
    }

    private static boolean isPlanReplanMarkerBackedLink(
            PlanId planId,
            Plan plan,
            InMemoryState.PlanReplanMarker marker,
            InMemoryState.ExecutionMutationLink link) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || !isCompleteLink(link)
                || !marker.provenanceLink().equals(link)) {
            return false;
        }
        PlanReplanRequest request = marker.request();
        PersistedPlanReplan result = marker.result();
        EventEnvelope event = request.replanEvent();
        PlanRevision revision = request.replannedRevision();
        Checkpoint checkpoint = request.replannedCheckpoint();
        VersionedCheckpoint persisted = result.replannedCheckpoint();
        InMemoryState.ExecutionMutationHead previous = link.previousHead();
        InMemoryState.ExecutionMutationHead resultHead = link.resultHead();
        PlanRevision previousRevision = plan == null
                ? null
                : findRevision(plan, previous.revisionNumber());
        PlanRevision authoritativeRevision = plan == null
                ? null
                : findRevision(plan, revision.number());
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.fencingToken() == result.fencingToken()
                && event.id().equals(link.markerIdentity().eventId())
                && link.markerIdentity().equals(
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .planReplan(event.id()))
                && event.equals(result.replanEvent())
                && revision.equals(result.replannedRevision())
                && checkpoint.equals(persisted.checkpoint())
                && request.expectedRevisionId().equals(previous.revisionId())
                && request.expectedRevisionNumber() == previous.revisionNumber()
                && request.expectedCheckpointVersion()
                        == previous.checkpointVersion()
                && request.expectedEventHeadSequence()
                        == previous.eventHeadSequence()
                && event.sequence() > previous.eventHeadSequence()
                && revision.number() == previous.revisionNumber() + 1
                && revision.parentRevisionId().equals(
                        java.util.Optional.of(previous.revisionId()))
                && (plan == null || revision.equals(authoritativeRevision)
                        && isExactReplanRevision(previousRevision, revision))
                && persisted.version()
                        == request.expectedCheckpointVersion() + 1
                && checkpoint.planId().equals(planId)
                && checkpoint.taskFrameId().equals(revision.taskFrameId())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == event.sequence()
                && checkpoint.planState() == PlanExecutionState.ACTIVE
                && hasExpectedReplanStepShape(checkpoint, revision)
                && resultHead.revisionId().equals(revision.id())
                && resultHead.revisionNumber() == revision.number()
                && resultHead.checkpointVersion() == persisted.version()
                && resultHead.eventHeadSequence() == event.sequence()
                && resultHead.mutationEventId().equals(event.id());
    }

    private static boolean isActiveStepReplanMarkerBackedLinks(
            PlanId planId,
            Plan sourcePlan,
            InMemoryState.ActiveStepReplanMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.supersessionProvenanceLink() == null
                || marker.replanProvenanceLink() == null
                || !isCompleteLink(marker.supersessionProvenanceLink())
                || !isCompleteLink(marker.replanProvenanceLink())) {
            return false;
        }
        ActiveStepReplanRequest request = marker.request();
        PersistedActiveStepReplan result = marker.result();
        EventEnvelope supersession = request.supersessionEvent();
        EventEnvelope replan = request.replanEvent();
        PlanRevision revision = request.replannedRevision();
        Checkpoint superseded = request.supersededCheckpoint();
        Checkpoint replanned = request.replannedCheckpoint();
        VersionedCheckpoint persistedSuperseded = result.supersededCheckpoint();
        VersionedCheckpoint persistedReplanned = result.replannedCheckpoint();
        InMemoryState.ExecutionMutationLink supersessionLink =
                marker.supersessionProvenanceLink();
        InMemoryState.ExecutionMutationLink replanLink =
                marker.replanProvenanceLink();
        InMemoryState.ExecutionMutationHead previous =
                supersessionLink.previousHead();
        InMemoryState.ExecutionMutationHead supersededHead =
                supersessionLink.resultHead();
        InMemoryState.ExecutionMutationHead replannedHead =
                replanLink.resultHead();
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.activeStepId().equals(result.supersededStepId())
                && request.fencingToken() == result.fencingToken()
                && supersession.equals(result.supersessionEvent())
                && replan.equals(result.replanEvent())
                && revision.equals(result.replannedRevision())
                && superseded.equals(persistedSuperseded.checkpoint())
                && replanned.equals(persistedReplanned.checkpoint())
                && supersessionLink.markerIdentity().equals(
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .activeStepReplanSupersession(supersession.id()))
                && replanLink.markerIdentity().equals(
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .activeStepReplanReplan(replan.id()))
                && request.expectedRevisionId().equals(previous.revisionId())
                && request.expectedRevisionNumber() == previous.revisionNumber()
                && request.expectedCheckpointVersion()
                == previous.checkpointVersion()
                && request.expectedEventHeadSequence()
                == previous.eventHeadSequence()
                && supersession.sequence() > previous.eventHeadSequence()
                && replan.sequence() > supersession.sequence()
                && persistedSuperseded.version()
                == request.expectedCheckpointVersion() + 1
                && superseded.revisionId().equals(previous.revisionId())
                && superseded.revisionNumber() == previous.revisionNumber()
                && superseded.lastEventSequence() == supersession.sequence()
                && superseded.planState() == PlanExecutionState.ACTIVE
                && superseded.stepStates().get(request.activeStepId())
                == StepExecutionState.SUPERSEDED_BY_REPLAN
                && supersededHead.revisionId().equals(previous.revisionId())
                && supersededHead.revisionNumber() == previous.revisionNumber()
                && supersededHead.checkpointVersion()
                == persistedSuperseded.version()
                && supersededHead.eventHeadSequence() == supersession.sequence()
                && supersededHead.mutationEventId().equals(supersession.id())
                && replanLink.previousHead().equals(supersededHead)
                && persistedReplanned.version()
                == request.expectedCheckpointVersion() + 2
                && revision.number() == previous.revisionNumber() + 1
                && revision.parentRevisionId().equals(
                        java.util.Optional.of(previous.revisionId()))
                && replanned.revisionId().equals(revision.id())
                && replanned.revisionNumber() == revision.number()
                && replanned.lastEventSequence() == replan.sequence()
                && replanned.planState() == PlanExecutionState.ACTIVE
                && replannedHead.revisionId().equals(revision.id())
                && replannedHead.revisionNumber() == revision.number()
                && replannedHead.checkpointVersion()
                == persistedReplanned.version()
                && replannedHead.eventHeadSequence() == replan.sequence()
                && replannedHead.mutationEventId().equals(replan.id())
                && (sourcePlan == null || isExactActiveStepReplanRevision(
                        sourcePlan.latestRevision(),
                        request.activeStepId(),
                        revision));
    }

    private static boolean isExactCompletionRevision(
            PlanRevision previous,
            StepCompletionRequest request,
            PlanRevision completed) {
        if (previous == null
                || completed.number() != previous.number() + 1
                || !completed.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || completed.createdAt().isBefore(previous.createdAt())
                || !completed.taskFrameId().equals(previous.taskFrameId())
                || !completed.steps().equals(previous.steps())) {
            return false;
        }
        Map<PlanStepId, CompletionFact> expected =
                new java.util.LinkedHashMap<>(previous.completedFacts());
        expected.put(request.stepId(), request.completionFact());
        return completed.completedFacts().equals(expected);
    }

    private static boolean referencedReceiptsExist(
            InMemoryState state,
            Checkpoint checkpoint,
            PlanRevision revision) {
        for (var receiptId : checkpoint.receiptReferences()) {
            if (!state.receipts.containsKey(receiptId)) {
                return false;
            }
        }
        for (CompletionFact fact : revision.completedFacts().values()) {
            for (var receiptId : fact.receiptReferences()) {
                if (!state.receipts.containsKey(receiptId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasCoherentStepAndFactShape(
            Checkpoint checkpoint,
            PlanRevision revision) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        if (!checkpoint.stepStates().keySet().equals(stepIds)) {
            return false;
        }
        for (PlanStepId stepId : stepIds) {
            boolean succeeded =
                    checkpoint.stepStates().get(stepId)
                            == StepExecutionState.SUCCEEDED;
            if (succeeded != revision.completedFacts().containsKey(stepId)) {
                return false;
            }
        }
        return checkpoint.planState() != PlanExecutionState.SUCCEEDED
                || checkpoint.stepStates().values().stream()
                        .allMatch(value ->
                                value == StepExecutionState.SUCCEEDED);
    }

    private static InMemoryState.ActiveStepReplanMarker
            activeStepReplanMarkerForEvent(
                    Map<EventId, InMemoryState.ActiveStepReplanMarker> markers,
                    EventId eventId) {
        if (markers == null || eventId == null) {
            return null;
        }
        InMemoryState.ActiveStepReplanMarker found = null;
        for (Map.Entry<EventId, InMemoryState.ActiveStepReplanMarker> entry
                : markers.entrySet()) {
            InMemoryState.ActiveStepReplanMarker marker = entry.getValue();
            if (marker == null || marker.request() == null) {
                continue;
            }
            if (eventId.equals(entry.getKey())
                    || eventId.equals(marker.request().replanEvent().id())) {
                if (found != null && found != marker) {
                    return null;
                }
                found = marker;
            }
        }
        return found;
    }

    static boolean hasPlanScopedOccupancy(
            InMemoryState state,
            PlanId planId) {
        return state.plans.containsKey(planId)
                || state.planBootstraps.containsKey(planId)
                || state.checkpoints.containsKey(planId)
                || state.eventStreams.containsKey(planId)
                || state.eventsById.values().stream()
                        .anyMatch(event ->
                                event != null && planId.equals(event.planId()))
                || state.executionStarts.containsKey(planId)
                || state.executionMutationHeads.containsKey(planId)
                || state.executionMutationLinks.containsKey(planId)
                || state.stepActivations.containsKey(planId)
                || state.stepCompletions.containsKey(planId)
                || state.stepPauses.containsKey(planId)
                || state.stepFailures.containsKey(planId)
                || state.stepCancellations.containsKey(planId)
                || state.planReplans.containsKey(planId)
                || state.activeStepReplans.containsKey(planId)
                || state.planExecutionContextReservations.containsKey(planId)
                || state.planExecutionContextConfirmations.containsKey(planId)
                || InMemoryPlanExecutionContextAuthority
                        .hasOwnerReference(state, planId)
                || state.leases.containsKey(planId)
                || state.fencingTokens.containsKey(planId);
    }

    private static PlanRevision findRevision(Plan plan, long number) {
        return plan.revisions().stream()
                .filter(revision -> revision.number() == number)
                .findFirst()
                .orElse(null);
    }

    private static Plan planEndingAt(
            Plan plan,
            PlanRevision revision) {
        int index = plan.revisions().indexOf(revision);
        return index < 0
                ? null
                : new Plan(
                        plan.id(),
                        plan.taskFrameId(),
                        plan.revisions().subList(0, index + 1));
    }

    private static boolean matchesRevision(
            PlanRevision revision,
            Checkpoint checkpoint) {
        return revision.id().equals(checkpoint.revisionId())
                && revision.number() == checkpoint.revisionNumber();
    }

    private static boolean isExactPrefix(List<?> prefix, List<?> values) {
        return values.size() >= prefix.size()
                && values.subList(0, prefix.size()).equals(prefix);
    }

    private static boolean hasExactStepShape(
            Checkpoint checkpoint,
            PlanRevision revision,
            StepExecutionState expected) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        return checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == expected);
    }

    record AuthoritativeSource(
            TaskFrame taskFrame,
            Plan plan,
            VersionedCheckpoint checkpoint,
            long eventHeadSequence,
            InMemoryState.ExecutionMutationHead head,
            NavigableMap<Long, EventEnvelope> eventStream,
            List<InMemoryState.ExecutionMutationLink> links,
            Map<EventId, InMemoryState.StepActivationMarker>
                    activationMarkers) {

        @Override
        public String toString() {
            return "AuthoritativeSource["
                    + "taskFrame=<provided>, "
                    + "plan=<provided>, "
                    + "checkpoint=<provided>, "
                    + "eventHeadSequence=<provided>, "
                    + "head=<provided>, "
                    + "eventStream=<provided>, "
                    + "links=<provided>, "
                    + "activationMarkers=<provided>]";
        }
    }

    private record InterruptionData(
            PlanId planId,
            PlanStepId stepId,
            long fencingToken,
            io.paperagent.v2.contracts.PlanRevisionId expectedRevisionId,
            long expectedRevisionNumber,
            long expectedCheckpointVersion,
            long expectedEventHeadSequence,
            EventEnvelope event,
            Checkpoint checkpoint) {
    }

    private record HistoricalTransition(
            Plan plan,
            VersionedCheckpoint checkpoint,
            EventEnvelope event) {
    }

    private record MutationHistory(
            Plan plan,
            VersionedCheckpoint checkpoint,
            InMemoryState.ExecutionMutationHead head,
            Set<InMemoryState.ExecutionMutationMarkerIdentity> markerIdentities) {
    }

    record PlanRoot(
            TaskFrame taskFrame,
            Plan plan,
            PersistedPlanBootstrap bootstrap) {

        @Override
        public String toString() {
            return "PlanRoot["
                    + "taskFrame=<provided>, "
                    + "plan=<provided>, "
                    + "bootstrap=<provided>]";
        }
    }
}
