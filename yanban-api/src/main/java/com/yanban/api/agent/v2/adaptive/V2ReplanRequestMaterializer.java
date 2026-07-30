package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.adaptive.reflection.ReflectionOutcome;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class V2ReplanRequestMaterializer {
    public ActiveStepReplanRequest materialize(
            RecoveredActiveStep recovered, ReflectionOutcome reflection) {
        if (reflection == null
                || reflection.decision()
                != com.yanban.api.agent.v2.adaptive.reflection
                        .ReflectionAction.REPLAN) {
            throw new IllegalArgumentException("REPLAN decision is required");
        }
        PersistedStepRecoveryActive source = recovered.recovery();
        Checkpoint active = source.checkpoint().checkpoint();
        PlanRevision current = source.plan().latestRevision();
        PlanStepId activeId = source.activation().stepId();
        Instant first = after(active.createdAt());
        Instant second = first.plusMillis(1);
        String suffix = hash(source.planId().value() + "\0"
                + active.revisionNumber() + "\0"
                + active.lastEventSequence() + "\0"
                + reflection.reason()).substring(0, 32);

        EventEnvelope supersession = event(
                "adaptive-supersede-" + suffix, active,
                active.lastEventSequence() + 1, first,
                "STEP_SUPERSEDED_BY_REPLAN",
                Optional.of(source.activation().activationEvent().id()));
        Map<PlanStepId, StepExecutionState> supersededStates =
                new LinkedHashMap<>(active.stepStates());
        supersededStates.put(activeId,
                StepExecutionState.SUPERSEDED_BY_REPLAN);
        Checkpoint superseded = checkpoint(
                active, active.revisionId(), active.revisionNumber(),
                supersession.sequence(), supersededStates, first);

        List<PlanStep> replacement = new ArrayList<>();
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>();
        for (PlanStep step : current.steps()) {
            if (active.stepStates().get(step.id())
                    == StepExecutionState.SUCCEEDED) {
                replacement.add(step);
                CompletionFact fact = current.completedFacts().get(step.id());
                if (fact == null) {
                    throw new IllegalStateException(
                            "completed Step has no immutable fact");
                }
                facts.put(step.id(), fact);
            }
        }
        Set<PlanStepId> ids = new HashSet<>();
        replacement.forEach(step -> ids.add(step.id()));
        reflection.replacementSteps().forEach(value -> {
            if (!ids.add(value.step().id())) {
                throw new IllegalArgumentException(
                        "replacement Step reuses completed identity");
            }
            replacement.add(value.step());
        });
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("adaptive-revision-" + suffix),
                active.taskFrameId(), active.revisionNumber() + 1,
                Optional.of(active.revisionId()), reflection.reason(),
                second, replacement, facts);
        EventEnvelope replan = event(
                "adaptive-replan-" + suffix, active,
                supersession.sequence() + 1, second,
                "PLAN_REPLANNED", Optional.of(supersession.id()));
        Map<PlanStepId, StepExecutionState> replannedStates =
                new LinkedHashMap<>();
        replacement.forEach(step -> replannedStates.put(
                step.id(), facts.containsKey(step.id())
                        ? StepExecutionState.SUCCEEDED
                        : StepExecutionState.NOT_STARTED));
        Checkpoint replanned = checkpoint(
                active, revision.id(), revision.number(), replan.sequence(),
                replannedStates, second);
        return new ActiveStepReplanRequest(
                source.planId(), recovered.lease().leaseToken(),
                recovered.lease().fencingToken(),
                active.revisionId(), active.revisionNumber(),
                source.checkpoint().version(), active.lastEventSequence(),
                activeId, supersession, superseded, replan, revision,
                replanned);
    }

    private static Checkpoint checkpoint(
            Checkpoint source, PlanRevisionId revisionId,
            long revisionNumber, long eventSequence,
            Map<PlanStepId, StepExecutionState> states, Instant time) {
        return new Checkpoint(
                source.taskFrameId(), source.planId(), revisionId,
                revisionNumber, eventSequence, PlanExecutionState.ACTIVE,
                states, source.receiptReferences(), time);
    }

    private static EventEnvelope event(
            String id, Checkpoint source, long sequence, Instant time,
            String type, Optional<EventId> causation) {
        return new EventEnvelope(
                new EventId(id), source.taskFrameId(), source.planId(),
                sequence, time, new EventType(type), causation, id,
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    private static Instant after(Instant source) {
        return source.plusMillis(1);
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
