package io.paperagent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final long AUTHORITY_CUT = 10L;
    private static final String READ_BOUNDARY =
            "authority-event-sequence=10;test=true";

    @Test
    void allTenFrozenFactCategoriesAreRequiredInOrder() {
        ChainRecoveryRuntime.RecoverySnapshot snapshot = snapshot(List.of());
        assertEquals(List.of(ChainRecoveryRuntime.RecoveryFactKind.values()),
                snapshot.factCuts().stream().map(ChainRecoveryRuntime.FactCut::kind).toList());
        assertEquals(10, snapshot.factCuts().size());

        List<ChainRecoveryRuntime.FactCut> missing = new ArrayList<>(factCuts());
        missing.remove(missing.size() - 1);
        assertThrows(IllegalArgumentException.class, () ->
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", missing, List.of(), projection()));

        List<ChainRecoveryRuntime.FactCut> reordered = new ArrayList<>(factCuts());
        java.util.Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () ->
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", reordered, List.of(), projection()));
    }

    @Test
    void frozenRoleProjectionMustMatchTaskCutAndReadBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", factCuts(), List.of(),
                        new TestProjection("task-2", AUTHORITY_CUT,
                                READ_BOUNDARY)));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", factCuts(), List.of(),
                        new TestProjection("task-1", AUTHORITY_CUT - 1,
                                READ_BOUNDARY)));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", factCuts(), List.of(),
                        new TestProjection("task-1", AUTHORITY_CUT,
                                "authority-event-sequence=10;other=true")));
    }

    @Test
    void everyPersistedCompositeTransitionStageResumesBeforeRoleSelection() {
        for (ChainTransitionType type : ChainTransitionType.values()) {
            List<ChainTransitionStage> stages = type.paths().stream()
                    .flatMap(List::stream).distinct()
                    .filter(stage -> stage != ChainTransitionStage.COMPLETE)
                    .toList();
            for (ChainTransitionStage stage : stages) {
                List<String> calls = new ArrayList<>();
                AtomicInteger loads = new AtomicInteger();
                ChainRecoveryRuntime.TransitionRef ref =
                        new ChainRecoveryRuntime.TransitionRef(
                                "transition-" + type + "-" + stage,
                                "task-1", type, stage, 1);
                ChainRecoveryRuntime runtime = new ChainRecoveryRuntime(
                        taskId -> {
                            calls.add("load");
                            return snapshot(loads.getAndIncrement() == 0
                                    ? List.of(ref) : List.of());
                        },
                        transition -> {
                            calls.add("resume:" + transition.transitionId());
                            return new ChainRecoveryRuntime.TransitionRecoveryResult(
                                    transition.transitionId(), transition.transitionType(),
                                    ChainTransitionStage.COMPLETE);
                        },
                        (taskId, observedAt) -> {
                            calls.add("in-flight");
                            return new ChainRecoveryRuntime.RecoveryResult(List.of(), false);
                        },
                        recovered -> {
                            calls.add("select-role");
                            return new ChainRecoveryRuntime.NextDirective(
                                    ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                                    "STEP", "step-1");
                        });

                ChainRecoveryRuntime.RecoveryOutcome outcome = runtime.recover(
                        new ChainRecoveryRuntime.RecoveryRequest("task-1", NOW));

                assertEquals(ChainRecoveryRuntime.RecoveryDisposition.NEXT_ROLE_SELECTED,
                        outcome.disposition(), type + "/" + stage);
                assertTrue(calls.indexOf("resume:" + ref.transitionId())
                                < calls.indexOf("select-role"),
                        type + "/" + stage);
                assertTrue(calls.indexOf("in-flight") < calls.indexOf("select-role"),
                        type + "/" + stage);
            }
        }
    }

    @Test
    void unresolvedInFlightActionPreventsRoleSelection() {
        AtomicBoolean selected = new AtomicBoolean();
        ChainRecoveryRuntime runtime = new ChainRecoveryRuntime(
                ignored -> snapshot(List.of()),
                transition -> {
                    throw new IllegalStateException("transition recovery not expected");
                },
                (taskId, observedAt) -> new ChainRecoveryRuntime.RecoveryResult(
                        List.of(new ChainRecoveryRuntime.ActionRecoveryFact(
                                "action-1", "key-1",
                                io.paperagent.v2.chain.effect.ChainEffectRuntime.OutcomeKind
                                        .UNKNOWN_SIDE_EFFECT,
                                null, null, "effect-intent-1", true)), true),
                recovered -> {
                    selected.set(true);
                    return new ChainRecoveryRuntime.NextDirective(
                            ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                            "STEP", "step-1");
                });

        ChainRecoveryRuntime.RecoveryOutcome outcome = runtime.recover(
                new ChainRecoveryRuntime.RecoveryRequest("task-1", NOW));

        assertEquals(ChainRecoveryRuntime.RecoveryDisposition.WAITING_IN_FLIGHT,
                outcome.disposition());
        assertTrue(!selected.get());
    }

    @Test
    void failedValidationCheckSelectsReflectorWithItsExactFormalIdentity() {
        assertFormalFailureWaitsForReflectorAndCompletesAfterHandoff(
                new ChainRecoveryRuntime.CheckFailureWait(
                        "finalization-check-1",
                        ChainFinalization.ErrorCode.VALIDATION_NOT_SUCCESSFUL));
    }

    @Test
    void formalPublishFailureWaitsForReflectorAndCompletesAfterHandoff() {
        assertFormalFailureWaitsForReflectorAndCompletesAfterHandoff(
                new ChainRecoveryRuntime.PublishFailureWait(
                        "finalization-check-1", "publish-failure-1",
                        ChainProjectPublishPort.ErrorCode.VERSION_CONFLICT,
                        false));
    }

    private static void
            assertFormalFailureWaitsForReflectorAndCompletesAfterHandoff(
                    ChainRecoveryRuntime.FormalSuccessorWait failure) {
        AtomicBoolean handoffCommitted = new AtomicBoolean();
        AtomicBoolean transitionCompleted = new AtomicBoolean();
        AtomicInteger inFlightCalls = new AtomicInteger();
        AtomicInteger roleSelections = new AtomicInteger();
        ChainRecoveryRuntime.TransitionRef ref =
                new ChainRecoveryRuntime.TransitionRef(
                        "transition-finalization-1", "task-1",
                        ChainTransitionType.FINALIZATION,
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED, 1);
        ChainRecoveryRuntime runtime = new ChainRecoveryRuntime(
                ignored -> snapshot(transitionCompleted.get()
                        ? List.of() : List.of(ref)),
                transition -> {
                    if (!handoffCommitted.get()) {
                        return ChainRecoveryRuntime.TransitionRecoveryResult
                                .waitingForFormalSuccessor(
                                        transition.transitionId(), failure);
                    }
                    transitionCompleted.set(true);
                    return new ChainRecoveryRuntime.TransitionRecoveryResult(
                            transition.transitionId(), transition.transitionType(),
                            ChainTransitionStage.COMPLETE);
                },
                (taskId, observedAt) -> {
                    inFlightCalls.incrementAndGet();
                    return new ChainRecoveryRuntime.RecoveryResult(
                            List.of(), false);
                },
                recovered -> {
                    roleSelections.incrementAndGet();
                    return new ChainRecoveryRuntime.NextDirective(
                            ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                            "STEP", "step-1");
                });

        ChainRecoveryRuntime.RecoveryOutcome waiting = runtime.recover(
                new ChainRecoveryRuntime.RecoveryRequest("task-1", NOW));

        assertEquals(
                ChainRecoveryRuntime.RecoveryDisposition
                        .WAITING_FORMAL_SUCCESSOR,
                waiting.disposition(), failure.getClass().getSimpleName());
        assertEquals(ChainRole.REFLECTOR, waiting.nextDirective().role());
        assertEquals(ChainWorkState.AWAITING_REVIEW,
                waiting.nextDirective().workState());
        assertEquals(failure.sourceAuthorityType(),
                waiting.nextDirective().sourceAuthorityType());
        assertEquals(failure.sourceAuthorityRef(),
                waiting.nextDirective().sourceAuthorityRef());
        assertEquals(0, inFlightCalls.get());
        assertEquals(0, roleSelections.get());
        assertTrue(!transitionCompleted.get(),
                "failure predecessor cannot pretend COMPLETE");

        handoffCommitted.set(true);
        ChainRecoveryRuntime.RecoveryOutcome completed = runtime.recover(
                new ChainRecoveryRuntime.RecoveryRequest("task-1", NOW));

        assertEquals(ChainRecoveryRuntime.RecoveryDisposition.NEXT_ROLE_SELECTED,
                completed.disposition());
        assertTrue(transitionCompleted.get());
        assertEquals(1, inFlightCalls.get());
        assertEquals(1, roleSelections.get());
    }

    @Test
    void cancelledFactSelectsTerminalAnswerAndNeverExecutor() {
        List<ChainRecoveryRuntime.FactCut> cancelled = new ArrayList<>(factCuts());
        int index = ChainRecoveryRuntime.RecoveryFactKind.PAUSE_CANCEL_AND_SUPERSEDE.ordinal();
        cancelled.set(index, new ChainRecoveryRuntime.FactCut(
                ChainRecoveryRuntime.RecoveryFactKind.PAUSE_CANCEL_AND_SUPERSEDE,
                "outcome-v1", READ_BOUNDARY,
                List.of("CANCELLED:outcome-1")));
        ChainRecoveryRuntime.RecoverySnapshot terminal =
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", cancelled, List.of(), projection());
        ChainRecoveryRuntime runtime = new ChainRecoveryRuntime(
                ignored -> terminal,
                transition -> {
                    throw new IllegalStateException("transition recovery not expected");
                },
                (taskId, observedAt) -> new ChainRecoveryRuntime.RecoveryResult(
                        List.of(), false),
                recovered -> {
                    assertEquals(List.of("CANCELLED:outcome-1"),
                            recovered.factCuts().get(index).authorityRefs());
                    return new ChainRecoveryRuntime.NextDirective(
                            ChainRole.ANSWER, ChainWorkState.TERMINAL,
                            "TASK_OUTCOME", "outcome-1");
                });

        ChainRecoveryRuntime.RecoveryOutcome outcome = runtime.recover(
                new ChainRecoveryRuntime.RecoveryRequest("task-1", NOW));
        assertEquals(ChainRole.ANSWER, outcome.nextDirective().role());
        assertEquals(ChainWorkState.TERMINAL, outcome.nextDirective().workState());
    }

    private static ChainRecoveryRuntime.RecoverySnapshot snapshot(
            List<ChainRecoveryRuntime.TransitionRef> transitions) {
        return new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", factCuts(), transitions, projection());
    }

    private static List<ChainRecoveryRuntime.FactCut> factCuts() {
        List<ChainRecoveryRuntime.FactCut> cuts = new ArrayList<>();
        for (ChainRecoveryRuntime.RecoveryFactKind kind
                : ChainRecoveryRuntime.RecoveryFactKind.values()) {
            List<String> refs = switch (kind) {
                case INSTRUCTION_AND_PENDING -> List.of("instruction-1", "gap-1");
                case TASKFRAME_PLAN_AND_STEP -> List.of("frame-1", "revision-1", "step-1");
                case ACTION_RECEIPT_AND_ERROR -> List.of("action-1", "receipt-1");
                case CANDIDATE_RESULT_AND_REVIEW -> List.of("result-1", "review-1");
                case WORKSPACE_AND_CANDIDATE -> List.of("workspace-1", "candidate-1");
                case REVIEW_READINESS_GAP_AND_TRANSITION ->
                        List.of("review-1", "readiness-1", "transition-1");
                case PROPOSAL_STATE -> List.of("proposal-1:ACCEPTED");
                case VALIDATION_FINALIZATION_AND_PUBLISH ->
                        List.of("validation-1", "finalization-check-1", "publish-1");
                case PAUSE_CANCEL_AND_SUPERSEDE -> List.of("ACTIVE");
                case IN_FLIGHT_ACTION -> List.of("action-1:key-1");
            };
            cuts.add(new ChainRecoveryRuntime.FactCut(
                    kind, "source-v1", READ_BOUNDARY, refs));
        }
        return List.copyOf(cuts);
    }

    private static TestProjection projection() {
        return new TestProjection("task-1", AUTHORITY_CUT, READ_BOUNDARY);
    }

    private record TestProjection(
            String taskId, long authorityCut, String readBoundary)
            implements ChainRecoveryRuntime.FrozenRoleProjection {
    }
}
