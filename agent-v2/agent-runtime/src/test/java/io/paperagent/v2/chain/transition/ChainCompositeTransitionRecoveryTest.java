package io.paperagent.v2.chain.transition;

import io.paperagent.v2.chain.*;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime.Branch;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime.StageCommitResult;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime.TransitionRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainCompositeTransitionRecoveryTest {
    private static final Instant NOW =
            Instant.parse("2026-08-07T04:00:00Z");

    @Test
    void allFiveTypesUseTheFrozenIdAndExactStagePath() {
        for (ChainTransitionType type : ChainTransitionType.values()) {
            ChainTransitionTestStore store = new ChainTransitionTestStore();
            ChainCompositeTransitionRuntime runtime =
                    new ChainCompositeTransitionRuntime(store, store, store);
            TransitionRequest request = request(type,
                    type == ChainTransitionType.FINALIZATION
                            ? Branch.FINALIZATION_SUCCESS : Branch.STANDARD);

            var outcome = runtime.resume(request,
                    command -> formal(store, command, request.branch()));

            String expectedId = new ChainIdentity.Transition(
                    type, request.taskId(), request.sourceDecisionId(),
                    request.targetIdentityDigest()).transitionId();
            assertEquals(expectedId, outcome.transition().transitionId());
            assertEquals(type.paths().get(0), outcome.committedStages()
                    .stream().map(value -> value.stageCode()).toList());
            assertTrue(outcome.complete());
            var replay = runtime.resume(request, command -> {
                throw new AssertionError(
                        "completed recovery cannot recommit a stage");
            });
            assertEquals(0, replay.recoveredStages());
        }
    }

    @Test
    void resumeThroughCommitsOnlyTheRequestedFormalPrefix() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(store, store, store);
        TransitionRequest request = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);

        var bounded = runtime.resumeThrough(
                request, ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                command -> formal(store, command, request.branch()));

        assertEquals(java.util.List.of(
                        ChainTransitionStage.OPEN,
                        ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED),
                bounded.committedStages().stream()
                        .map(TransitionStageRecord::stageCode).toList());
        assertEquals(2, bounded.recoveredStages());
        assertFalse(bounded.complete());

        var completed = runtime.resume(
                request, command -> formal(
                        store, command, request.branch()));
        assertTrue(completed.complete());
        assertEquals(2, completed.recoveredStages());
    }

    @Test
    void resumeThroughReplaysAnAlreadyCommittedTargetWithoutAdvancing() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(store, store, store);
        TransitionRequest request = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);
        runtime.resumeThrough(
                request, ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                command -> formal(store, command, request.branch()));

        var replay = runtime.resumeThrough(
                request, ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                command -> {
                    throw new AssertionError(
                            "a committed target cannot invoke its committer");
                });

        assertEquals(0, replay.recoveredStages());
        assertEquals(2, replay.committedStages().size());
        assertFalse(replay.complete());
    }

    @Test
    void gapNormalSuccessorAcceptsFormalInstructionDisposition() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        store.register("INSTRUCTION_DISPOSITION", "disposition-1", null);
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(store, store, store);
        TransitionRequest request = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);

        var outcome = runtime.resumeThrough(
                request, ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                command -> StageCommitResult.successor(
                        "INSTRUCTION_DISPOSITION", "disposition-1"));

        assertEquals("INSTRUCTION_DISPOSITION", outcome.committedStages()
                .get(1).successorAuthorityType());
        assertEquals("disposition-1", outcome.committedStages()
                .get(1).successorAuthorityRef());
    }

    @Test
    void resumeThroughDoesNotMarkAnUnverifiedTargetAsCommitted() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        TransitionRequest request = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);
        AtomicBoolean rejectNormal = new AtomicBoolean(true);
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(
                        store, store, query -> query.stage().stageCode()
                        == ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED
                        && rejectNormal.get()
                        ? new ChainCompositeTransitionRuntime
                        .AuthorityVerification(false, null, false)
                        : store.verify(query));

        assertThrows(ChainCompositeTransitionException.class,
                () -> runtime.resumeThrough(
                        request,
                        ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                        command -> formal(
                                store, command, request.branch())));
        String transitionId = new ChainIdentity.Transition(
                request.type(), request.taskId(), request.sourceDecisionId(),
                request.targetIdentityDigest()).transitionId();
        assertEquals(java.util.List.of(ChainTransitionStage.OPEN),
                store.findTransitionStages(transitionId).stream()
                        .map(TransitionStageRecord::stageCode).toList());

        rejectNormal.set(false);
        var recovered = runtime.resumeThrough(
                request, ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                command -> formal(store, command, request.branch()));
        assertEquals(1, recovered.recoveredStages());
        assertEquals(ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                recovered.committedStages().get(1).stageCode());
    }

    @Test
    void resumeThroughRejectsAnOutOfOrderPersistedPrefix() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(store, store, store);
        TransitionRequest request = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);
        var opened = runtime.resumeThrough(
                request, ChainTransitionStage.OPEN,
                command -> formal(store, command, request.branch()));
        String transitionId = opened.transition().transitionId();
        store.stages.get(transitionId).add(new TransitionStageRecord(
                transitionId, ChainTransitionStage.PENDING_RESOLVED,
                request.taskId(), "out-of-order-event", 2,
                null, null, "PENDING_ITEM_EVENT", "resolved-1", NOW));

        assertThrows(ChainCompositeTransitionException.class,
                () -> runtime.resumeThrough(
                        request,
                        ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                        command -> {
                            throw new AssertionError(
                                    "an invalid prefix cannot advance");
                        }));
        assertEquals(2, store.findTransitionStages(transitionId).size());
    }

    @Test
    void acceptsDatabaseMicrosecondNormalizationForTransitionAndStages() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        store.normalizeAuditTimesToMicros();
        Instant nanosecondTime = Instant.parse(
                "2026-08-07T04:00:00.123456789Z");
        TransitionRequest request = new TransitionRequest(
                ChainTransitionType.PLAN_CHANGE, "task-1",
                "decision-PLAN_CHANGE", sha256("target-PLAN_CHANGE"),
                Branch.STANDARD, nanosecondTime);

        var outcome = new ChainCompositeTransitionRuntime(
                store, store, store).resume(
                request, command -> formal(
                        store, command, request.branch()));

        Instant expected = Instant.parse(
                "2026-08-07T04:00:00.123456Z");
        assertTrue(outcome.complete());
        assertEquals(expected, outcome.transition().createdAt());
        assertTrue(outcome.committedStages().stream().allMatch(
                stage -> expected.equals(stage.committedAt())));
    }

    @Test
    void recoveryReplaysOnlyTheMissingIdempotentAuthorityStage() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ChainCompositeTransitionRuntime interrupted =
                new ChainCompositeTransitionRuntime(
                        store, store, store, stage -> {
                    if (stage == ChainTransitionStage.APPLICABILITY_COMMITTED
                            && failOnce.getAndSet(false)) {
                        throw new SimulatedCrash();
                    }
                });
        TransitionRequest request = request(
                ChainTransitionType.ACCEPT_STEP, Branch.STANDARD);
        Map<ChainTransitionStage, Integer> calls = new EnumMap<>(
                ChainTransitionStage.class);
        Set<ChainTransitionStage> uniqueFormalWrites = new HashSet<>();
        ChainCompositeTransitionRuntime.StageCommitter committer = command -> {
            calls.merge(command.stage(), 1, Integer::sum);
            uniqueFormalWrites.add(command.stage());
            return formal(store, command, request.branch());
        };

        assertThrows(SimulatedCrash.class,
                () -> interrupted.resume(request, committer));
        assertEquals(2, store.findTransitionStages(
                new ChainIdentity.Transition(
                        request.type(), request.taskId(),
                        request.sourceDecisionId(),
                        request.targetIdentityDigest()).transitionId()).size());

        var recovered = new ChainCompositeTransitionRuntime(
                store, store, store).resume(request, committer);

        assertTrue(recovered.complete());
        assertEquals(2, calls.get(
                ChainTransitionStage.APPLICABILITY_COMMITTED));
        assertEquals(request.type().paths().get(0).size() - 2,
                uniqueFormalWrites.size());
        assertEquals(0, new ChainCompositeTransitionRuntime(
                store, store, store)
                .resume(request, committer).recoveredStages());
    }

    @Test
    void gapCannotResolveBeforeNestedNormalSuccessorCompletes() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        TransitionRequest successorRequest = request(
                ChainTransitionType.PLAN_CHANGE, Branch.STANDARD);
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(store, store, store);
        String successorId = new ChainIdentity.Transition(
                successorRequest.type(), successorRequest.taskId(),
                successorRequest.sourceDecisionId(),
                successorRequest.targetIdentityDigest()).transitionId();
        AtomicBoolean stopAfterOpen = new AtomicBoolean(true);
        assertThrows(SimulatedCrash.class, () ->
                new ChainCompositeTransitionRuntime(
                        store, store, store, stage -> {
                    if (stage == ChainTransitionStage.OPEN
                            && stopAfterOpen.getAndSet(false)) {
                        throw new SimulatedCrash();
                    }
                }).resume(successorRequest,
                        command -> StageCommitResult.none()));
        TransitionRequest gap = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);
        assertThrows(ChainCompositeTransitionException.class,
                () -> runtime.resume(gap, command ->
                        command.stage()
                                == ChainTransitionStage
                                .NORMAL_SUCCESSOR_COMMITTED
                                ? StageCommitResult.successor(
                                "TRANSITION", successorId)
                                : formal(store, command, gap.branch())));

        runtime.resume(successorRequest,
                command -> formal(
                        store, command, successorRequest.branch()));
        assertTrue(runtime.resume(gap, command ->
                command.stage()
                        == ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED
                        ? StageCommitResult.successor(
                        "TRANSITION", successorId)
                        : formal(store, command, gap.branch())).complete());
    }

    @Test
    void gapAcceptsAVerifiedNonTransitionNormalSuccessor() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        TransitionRequest gap = request(
                ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);

        assertTrue(new ChainCompositeTransitionRuntime(
                store, store, store).resume(gap,
                command -> formal(store, command, gap.branch())).complete());
    }

    @Test
    void gapAcceptsTheFiniteBlockedSuccessorAuthorities() {
        for (String authorityType : Set.of(
                "PENDING_ITEM", "TASK_OUTCOME", "MODEL_INVOCATION")) {
            ChainTransitionTestStore store = new ChainTransitionTestStore();
            TransitionRequest gap = request(
                    ChainTransitionType.GAP_RESOLUTION, Branch.STANDARD);
            String authorityRef = "blocked-" + authorityType;
            store.register(authorityType, authorityRef, null);

            assertTrue(new ChainCompositeTransitionRuntime(
                    store, store, store).resume(gap,
                    command -> command.stage()
                            == ChainTransitionStage
                            .NORMAL_SUCCESSOR_COMMITTED
                            ? StageCommitResult.successor(
                            authorityType, authorityRef)
                            : formal(store, command,
                            gap.branch())).complete());
        }
    }

    @Test
    void planChangeAllowsOnlyAVerifiedEmptyApplicabilityBarrier() {
        TransitionRequest planChange = request(
                ChainTransitionType.PLAN_CHANGE, Branch.STANDARD);
        ChainTransitionTestStore unverified =
                new ChainTransitionTestStore();
        assertThrows(ChainCompositeTransitionException.class, () ->
                new ChainCompositeTransitionRuntime(
                        unverified, unverified, query ->
                        query.stage().stageCode()
                                == ChainTransitionStage
                                .APPLICABILITY_COMMITTED
                                ? ChainCompositeTransitionRuntime
                                .AuthorityVerification.verified()
                                : unverified.verify(query))
                        .resume(planChange, command ->
                                command.stage()
                                        == ChainTransitionStage
                                        .APPLICABILITY_COMMITTED
                                        ? StageCommitResult.none()
                                        : formal(unverified, command,
                                        planChange.branch())));

        ChainTransitionTestStore verified =
                new ChainTransitionTestStore();
        assertTrue(new ChainCompositeTransitionRuntime(
                verified, verified, verified).resume(
                planChange, command -> command.stage()
                        == ChainTransitionStage.APPLICABILITY_COMMITTED
                        ? StageCommitResult.none()
                        : formal(verified, command,
                        planChange.branch())).complete());
    }

    @Test
    void rejectsArbitraryStageAuthorityAndWrongFinalizationBranch() {
        ChainTransitionTestStore arbitraryStore =
                new ChainTransitionTestStore();
        TransitionRequest accept = request(
                ChainTransitionType.ACCEPT_STEP, Branch.STANDARD);
        assertThrows(ChainCompositeTransitionException.class, () ->
                new ChainCompositeTransitionRuntime(
                        arbitraryStore, arbitraryStore, arbitraryStore)
                        .resume(accept, command ->
                                StageCommitResult.successor(
                                        "FORMAL_FACT", "forged")));

        ChainTransitionTestStore branchStore =
                new ChainTransitionTestStore();
        TransitionRequest success = request(
                ChainTransitionType.FINALIZATION,
                Branch.FINALIZATION_SUCCESS);
        assertThrows(ChainCompositeTransitionException.class, () ->
                new ChainCompositeTransitionRuntime(
                        branchStore, branchStore, branchStore)
                        .resume(success, command -> formal(
                                branchStore, command,
                                command.stage()
                                        == ChainTransitionStage
                                        .FINALIZATION_CHECK_COMMITTED
                                        ? Branch.FINALIZATION_FAILED
                                        : success.branch())));
    }

    @Test
    void failedFinalizationRequiresAFormalReflectorHandoff() {
        TransitionRequest failed = request(
                ChainTransitionType.FINALIZATION,
                Branch.FINALIZATION_FAILED);
        ChainTransitionTestStore pendingOnly =
                new ChainTransitionTestStore();
        assertThrows(ChainCompositeTransitionException.class, () ->
                new ChainCompositeTransitionRuntime(
                        pendingOnly, pendingOnly, pendingOnly)
                        .resume(failed, command -> command.stage()
                                == ChainTransitionStage
                                .FAILED_CHECK_HANDOFF_COMMITTED
                                ? StageCommitResult.successor(
                                "PENDING_ITEM", "direct-gap")
                                : formal(pendingOnly, command,
                                failed.branch())));

        ChainTransitionTestStore reviewed =
                new ChainTransitionTestStore();
        assertTrue(new ChainCompositeTransitionRuntime(
                reviewed, reviewed, reviewed).resume(
                failed, command -> formal(
                        reviewed, command, failed.branch())).complete());
        assertEquals("REVIEW_DECISION", reviewed.findTransitionStages(
                        new ChainIdentity.Transition(
                                failed.type(), failed.taskId(),
                                failed.sourceDecisionId(),
                                failed.targetIdentityDigest()).transitionId())
                .stream().filter(stage -> stage.stageCode()
                        == ChainTransitionStage
                        .FAILED_CHECK_HANDOFF_COMMITTED)
                .findFirst().orElseThrow().successorAuthorityType());
    }

    @Test
    void passedCheckCanUseOnlyAnExactPublishFailureHandoff() {
        TransitionRequest failed = request(
                ChainTransitionType.FINALIZATION,
                Branch.FINALIZATION_FAILED);
        ChainTransitionTestStore missingPublish =
                new ChainTransitionTestStore();
        assertThrows(ChainCompositeTransitionException.class, () ->
                new ChainCompositeTransitionRuntime(
                        missingPublish, missingPublish, missingPublish)
                        .resume(failed, command -> formal(
                                missingPublish, command,
                                command.stage() == ChainTransitionStage
                                        .FINALIZATION_CHECK_COMMITTED
                                        ? Branch.FINALIZATION_SUCCESS
                                        : failed.branch())));

        ChainTransitionTestStore exact = new ChainTransitionTestStore();
        assertTrue(new ChainCompositeTransitionRuntime(
                exact, exact, exact).resume(failed, command -> {
            if (command.stage() == ChainTransitionStage
                    .FINALIZATION_CHECK_COMMITTED) {
                return formal(exact, command, Branch.FINALIZATION_SUCCESS);
            }
            if (command.stage() == ChainTransitionStage
                    .FAILED_CHECK_HANDOFF_COMMITTED) {
                exact.register("PUBLISH_FAILURE", "publish-failure-2", null);
                exact.register("REVIEW_DECISION", "publish-review", null);
                return new StageCommitResult(
                        "PUBLISH_FAILURE", "publish-failure-2",
                        "REVIEW_DECISION", "publish-review");
            }
            return formal(exact, command, failed.branch());
        }).complete());
    }

    @Test
    void applicabilityRuntimeAcceptsOnlyExactFormalSourcesAndReplaysTuple() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        store.acceptedResults.add(new AcceptedResultRecord(
                "accepted-1", "task-1", "event-accepted",
                "candidate-1", "review-1", "accept-transition",
                "content-1", sha256("accepted"), NOW));
        ChainApplicabilityRuntime runtime = new ChainApplicabilityRuntime(
                store, store, store);

        for (ChainApplicability.SourceType type
                : ChainApplicability.SourceType.values()) {
            String sourceDecisionId;
            String sourceTransitionId = null;
            if (type
                    != ChainApplicability.SourceType
                    .USER_INSTRUCTION_DISPOSITION) {
                ChainTransitionType transitionType = type
                        == ChainApplicability.SourceType.ACCEPT_STEP
                        ? ChainTransitionType.ACCEPT_STEP
                        : ChainTransitionType.PLAN_CHANGE;
                String target = sha256("source-" + type);
                sourceDecisionId = new ChainIdentity.Transition(
                        transitionType, "task-1", "decision-" + type,
                        target).transitionId();
                sourceTransitionId = sourceDecisionId;
                store.transitions.put(sourceDecisionId, new TransitionRecord(
                        sourceDecisionId, "task-1", "event-" + type,
                        transitionType, "decision-" + type, target, NOW));
            } else {
                sourceDecisionId = "formal-source-" + type;
            }
            ChainApplicability.Identity identity =
                    new ChainApplicability.Identity(
                            "accepted-1", type, sourceDecisionId,
                            "frame-1", "plan-1", "revision-1",
                            ChainIdentity.NONE, "instruction-1");
            store.applicabilityAuthority =
                    new ChainApplicabilityAuthorityPort.SourceAuthority(
                            type, sourceDecisionId, identity,
                            sourceTransitionId, true);
            ChainApplicabilityRuntime.CommitRequest command =
                    new ChainApplicabilityRuntime.CommitRequest(
                            "task-1", identity,
                            ChainApplicability.Outcome.APPLICABLE,
                            "formal source", NOW);

            assertTrue(!runtime.commit(command).replayed());
            assertTrue(runtime.commit(
                    new ChainApplicabilityRuntime.CommitRequest(
                            command.taskId(), command.identity(),
                            command.conclusion(), command.reason(),
                            NOW.plusSeconds(1))).replayed());
        }
        assertEquals(4, store.applicabilityDecisions.size());

        ChainApplicability.Identity persistentWithoutTransition =
                new ChainApplicability.Identity(
                        "accepted-1",
                        ChainApplicability.SourceType.PERSISTENT_PLAN,
                        "persistent-without-transition", "frame-2", "plan-2",
                        "revision-2", ChainIdentity.NONE, "instruction-2");
        store.applicabilityAuthority =
                new ChainApplicabilityAuthorityPort.SourceAuthority(
                        persistentWithoutTransition.sourceType(),
                        persistentWithoutTransition.sourceDecisionId(),
                        persistentWithoutTransition, null, true);
        assertThrows(ChainCompositeTransitionException.class, () ->
                runtime.commit(new ChainApplicabilityRuntime.CommitRequest(
                        "task-1", persistentWithoutTransition,
                        ChainApplicability.Outcome.APPLICABLE,
                        "missing transition", NOW)));

        ChainApplicability.Identity suggestion =
                new ChainApplicability.Identity(
                        "accepted-1",
                        ChainApplicability.SourceType.PLAN_REVISION,
                        "model-replan-suggestion", "frame-1", "plan-1",
                        "revision-2", ChainIdentity.NONE, "instruction-1");
        store.applicabilityAuthority =
                new ChainApplicabilityAuthorityPort.SourceAuthority(
                        suggestion.sourceType(), suggestion.sourceDecisionId(),
                        suggestion, null, false);
        assertThrows(ChainCompositeTransitionException.class, () ->
                runtime.commit(new ChainApplicabilityRuntime.CommitRequest(
                        "task-1", suggestion,
                        ChainApplicability.Outcome.NOT_APPLICABLE,
                        "model suggestion", NOW)));
        ChainApplicability.Identity exactSource =
                new ChainApplicability.Identity(
                        "accepted-1",
                        ChainApplicability.SourceType
                                .USER_INSTRUCTION_DISPOSITION,
                        "formal-new-instruction", "frame-2", "plan-2",
                        "revision-2", ChainIdentity.NONE, "instruction-2");
        ChainApplicability.Identity wrongTarget =
                new ChainApplicability.Identity(
                        "accepted-1", exactSource.sourceType(),
                        exactSource.sourceDecisionId(), "frame-wrong",
                        "plan-2", "revision-2", ChainIdentity.NONE,
                        "instruction-2");
        store.applicabilityAuthority =
                new ChainApplicabilityAuthorityPort.SourceAuthority(
                        exactSource.sourceType(),
                        exactSource.sourceDecisionId(), wrongTarget,
                        null, true);
        assertThrows(ChainCompositeTransitionException.class, () ->
                runtime.commit(new ChainApplicabilityRuntime.CommitRequest(
                        "task-1", exactSource,
                        ChainApplicability.Outcome.APPLICABLE,
                        "wrong target", NOW)));
        ChainApplicability.Identity instructionDisposition =
                new ChainApplicability.Identity(
                        "accepted-1",
                        ChainApplicability.SourceType
                                .USER_INSTRUCTION_DISPOSITION,
                        "instruction-disposition", "frame-2", "plan-2",
                        "revision-2", ChainIdentity.NONE, "instruction-2");
        store.applicabilityAuthority =
                new ChainApplicabilityAuthorityPort.SourceAuthority(
                        instructionDisposition.sourceType(),
                        instructionDisposition.sourceDecisionId(),
                        instructionDisposition,
                        store.transitions.keySet().iterator().next(), true);
        assertThrows(ChainCompositeTransitionException.class, () ->
                runtime.commit(new ChainApplicabilityRuntime.CommitRequest(
                        "task-1", instructionDisposition,
                        ChainApplicability.Outcome.APPLICABLE,
                        "unexpected transition", NOW)));
        assertEquals(4, store.applicabilityDecisions.size());
    }

    @Test
    void applicabilityAcceptsWriterOwnedAuditTimestampOnly() {
        ChainTransitionTestStore store = new ChainTransitionTestStore();
        String targetIdentity = sha256("target-audit");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.ACCEPT_STEP, "task-audit", "review-audit",
                targetIdentity).transitionId();
        store.acceptedResults.add(new AcceptedResultRecord(
                "accepted-audit", "task-audit", "event-accepted-audit",
                "candidate-audit", "review-audit", transitionId,
                "content-audit", sha256("accepted-audit"), NOW));
        ChainApplicability.Identity identity = new ChainApplicability.Identity(
                "accepted-audit", ChainApplicability.SourceType.ACCEPT_STEP,
                transitionId, "frame-audit", "plan-audit",
                "revision-audit", ChainIdentity.NONE, "instruction-audit");
        store.transitions.put(transitionId, new TransitionRecord(
                transitionId, "task-audit", "event-transition-audit",
                ChainTransitionType.ACCEPT_STEP, "review-audit",
                targetIdentity, NOW));
        store.applicabilityAuthority =
                new ChainApplicabilityAuthorityPort.SourceAuthority(
                        identity.sourceType(), identity.sourceDecisionId(), identity,
                        transitionId, true);
        ChainApplicabilityWriter auditWriter = value -> {
            AuthoritativeAppendResult<ResultApplicabilityRecord> appended =
                    store.appendApplicability(value);
            ResultApplicabilityRecord fact = appended.fact();
            ResultApplicabilityRecord canonical = new ResultApplicabilityRecord(
                    fact.applicabilityId(), fact.taskId(), fact.eventId(),
                    fact.acceptedResultId(), fact.sourceType(),
                    fact.sourceDecisionId(), fact.targetTaskFrameId(),
                    fact.targetPlanId(), fact.targetPlanRevisionId(),
                    fact.targetCandidateKey(), fact.targetInstructionVersionId(),
                    fact.conclusion(), fact.reason(), NOW.plusNanos(999));
            return new AuthoritativeAppendResult<>(
                    appended.event(), canonical, appended.replayed());
        };
        ChainApplicabilityRuntime runtime = new ChainApplicabilityRuntime(
                store, auditWriter, store);

        AuthoritativeAppendResult<ResultApplicabilityRecord> appended =
                runtime.commit(new ChainApplicabilityRuntime.CommitRequest(
                        "task-audit", identity,
                        ChainApplicability.Outcome.APPLICABLE,
                        "same decision with writer audit time", NOW));

        assertEquals(NOW.plusNanos(999), appended.fact().createdAt());
    }

    private static StageCommitResult formal(
            ChainTransitionTestStore store,
            ChainCompositeTransitionRuntime.StageCommand command,
            Branch branch) {
        ChainTransitionStage stage = command.stage();
        StageCommitResult result = switch (stage) {
            case NORMAL_SUCCESSOR_COMMITTED ->
                    successor("ROUTE_DECISION", stage);
            case PENDING_RESOLVED -> successor(
                    "PENDING_ITEM_EVENT", stage);
            case ACCEPTED_RESULT_COMMITTED,
                    ACCEPTED_RESULT_COMMITTED_OR_VERIFIED -> successor(
                    "ACCEPTED_RESULT", stage);
            case APPLICABILITY_COMMITTED,
                    APPLICABILITY_COMMITTED_OR_EMPTY -> successor(
                    "RESULT_APPLICABILITY", stage);
            case STEP_COMPLETED, STEP_COMPLETED_OR_VERIFIED,
                    NEXT_STEP_ACTIVATED_OR_NONE,
                    OLD_STEP_SUPERSEDED_OR_NONE,
                    NEW_STEP_ACTIVATED -> successor("STEP_EVENT", stage);
            case TASKFRAME_PLAN_COMMITTED -> successor(
                    "PLAN_BINDING", stage);
            case READINESS_COMMITTED -> successor(
                    "FINALIZATION_READINESS", stage);
            case READINESS_VERIFIED -> new StageCommitResult(
                    "FINALIZATION_READINESS", ref(stage), null, null);
            case FINALIZATION_CHECK_COMMITTED -> successor(
                    "FINALIZATION_CHECK", stage);
            case PUBLISH_COMMITTED_OR_NOT_REQUIRED -> successor(
                    "PUBLISH_RECEIPT", stage);
            case TASK_OUTCOME_COMMITTED -> successor(
                    "TASK_OUTCOME", stage);
            case FAILED_CHECK_HANDOFF_COMMITTED -> successor(
                    "REVIEW_DECISION", stage);
            case OPEN, COMPLETE -> StageCommitResult.none();
        };
        String type = result.successorAuthorityType() != null
                ? result.successorAuthorityType()
                : result.predecessorAuthorityType();
        String authorityRef = result.successorAuthorityRef() != null
                ? result.successorAuthorityRef()
                : result.predecessorAuthorityRef();
        if (type != null && !"TRANSITION".equals(type)) {
            store.register(type, authorityRef,
                    stage == ChainTransitionStage
                            .FINALIZATION_CHECK_COMMITTED
                            ? branch == Branch.FINALIZATION_SUCCESS
                            ? ChainCompositeTransitionRuntime
                            .FinalizationCheckOutcome.PASSED
                            : ChainCompositeTransitionRuntime
                            .FinalizationCheckOutcome.FAILED
                            : null);
        }
        return result;
    }

    private static StageCommitResult successor(
            String type, ChainTransitionStage stage) {
        return StageCommitResult.successor(type, ref(stage));
    }

    private static String ref(ChainTransitionStage stage) {
        return "formal-" + stage.name();
    }

    private static TransitionRequest request(
            ChainTransitionType type, Branch branch) {
        return new TransitionRequest(
                type, "task-1", "decision-" + type.name(),
                sha256("target-" + type.name()), branch, NOW);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class SimulatedCrash extends RuntimeException {
    }
}
