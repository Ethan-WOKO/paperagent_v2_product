package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.*;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainStepStateMachineTest {
    private static final Instant NOW =
            Instant.parse("2026-08-07T04:00:00Z");
    private static final String HASH = "0".repeat(64);

    @Test
    void derivesStableStepStatesAndActivatesOnlyTheFirstReadyStep() {
        ChainStepTestStore store = store();
        ChainStepStateMachine machine = machine(store);

        var initial = machine.derive("task-1", "revision-1");
        assertEquals(ChainStepStatus.READY,
                initial.steps().get(0).status());
        assertEquals(ChainStepStatus.NOT_STARTED,
                initial.steps().get(1).status());
        var activation = machine.activateNext(
                "task-1", "revision-1", "plan-decision",
                "transition.plan", NOW);
        assertEquals(ChainStepStateMachine.ActivationKind.ACTIVATED,
                activation.kind());
        String activationId = activation.append().value()
                .command().activationEventId();
        assertEquals("step-1", machine.derive(
                "task-1", "revision-1").activeStep().orElseThrow().stepId());

        CandidateStepResultRecord candidate = candidate(
                "candidate-1", "event-candidate", "proposal-result",
                "content-result", "step-1", activationId);
        store.candidates.add(candidate);
        store.addAuthority("task-1", candidate.eventId());
        assertEquals(ChainStepStatus.AWAITING_REVIEW,
                machine.derive("task-1", "revision-1")
                        .activeStep().orElseThrow().status());

        ReviewDecisionRecord continueReview = review(
                "review-continue", "event-review-continue",
                candidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_CONTINUE_STEP);
        store.reviews.add(continueReview);
        store.addAuthority("task-1", continueReview.eventId());
        assertEquals(ChainStepStatus.ACTIVE,
                machine.derive("task-1", "revision-1")
                        .activeStep().orElseThrow().status());

        bindOpenGap(store, activationId);
        assertEquals(ChainStepStatus.WAITING_GAP,
                machine.derive("task-1", "revision-1")
                        .activeStep().orElseThrow().status());
        store.openPending.clear();
        ReviewDecisionRecord acceptReview = review(
                "review-accept", "event-review-accept",
                candidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_ACCEPT_STEP);
        store.reviews.add(acceptReview);
        store.addAuthority("task-1", acceptReview.eventId());
        String acceptedIdentity = sha256("accepted-identity");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.ACCEPT_STEP, "task-1",
                "review-accept", acceptedIdentity).transitionId();
        store.transitions.add(new TransitionRecord(
                transitionId, "task-1", "event-transition",
                ChainTransitionType.ACCEPT_STEP, "review-accept",
                acceptedIdentity, NOW));
        AcceptedResultRecord accepted = new AcceptedResultRecord(
                "accepted-1", "task-1", "event-accepted",
                candidate.candidateResultId(), "review-accept",
                transitionId, candidate.contentId(), acceptedIdentity, NOW);
        store.accepted.add(accepted);
        store.addAuthority("task-1", accepted.eventId());
        ResultApplicabilityRecord applicable = new ResultApplicabilityRecord(
                "app-1", "task-1", "event-app", "accepted-1",
                ChainApplicability.SourceType.ACCEPT_STEP,
                transitionId, "frame-1", "plan-1",
                "revision-1", ChainIdentity.NONE, "instruction-1",
                ChainApplicability.Outcome.APPLICABLE, "accepted", NOW);
        store.applicability.add(applicable);
        store.addAuthority("task-1", applicable.eventId());
        AcceptedResultRecord priorAccepted = new AcceptedResultRecord(
                "accepted-prior", "task-1", "event-accepted-prior",
                "candidate-prior", "review-prior", "transition-prior",
                "content-prior", sha256("prior-accepted"), NOW);
        store.accepted.add(priorAccepted);
        store.addAuthority("task-1", priorAccepted.eventId());
        ResultApplicabilityRecord priorApplicability =
                new ResultApplicabilityRecord(
                        "app-prior", "task-1", "event-app-prior",
                        "accepted-prior",
                        ChainApplicability.SourceType.ACCEPT_STEP,
                        transitionId, "frame-1", "plan-1", "revision-1",
                        ChainIdentity.NONE, "instruction-1",
                        ChainApplicability.Outcome.NOT_APPLICABLE,
                        "superseded by the new candidate", NOW);
        store.applicability.add(priorApplicability);
        store.addAuthority("task-1", priorApplicability.eventId());
        ChainStepStateMachine.StepTerminalCommand completion =
                new ChainStepStateMachine.StepTerminalCommand(
                        "task-1", "revision-1", "step-1", activationId,
                        "review-accept", transitionId, NOW);
        assertThrows(ChainStepException.class,
                () -> machine.completeAcceptedStep(completion));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.OPEN, 0,
                null, null));
        store.transitionStages.add(stage(
                transitionId,
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED, 1,
                "ACCEPTED_RESULT", "accepted-1"));
        store.transitionStages.add(stage(
                transitionId,
                ChainTransitionStage.APPLICABILITY_COMMITTED, 2,
                "RESULT_APPLICABILITY", "app-1"));
        store.applicability.set(0, new ResultApplicabilityRecord(
                "app-1", "task-1", "event-app", "accepted-1",
                ChainApplicability.SourceType.ACCEPT_STEP,
                transitionId, "frame-1", "plan-1", "revision-1",
                ChainIdentity.NONE, "instruction-1",
                ChainApplicability.Outcome.NOT_APPLICABLE,
                "invalid current result disposition", NOW));
        assertThrows(ChainStepException.class,
                () -> machine.completeAcceptedStep(completion));
        store.applicability.set(0, applicable);
        machine.completeAcceptedStep(completion);

        var afterCompletion = machine.derive("task-1", "revision-1");
        assertEquals(ChainStepStatus.COMPLETED,
                afterCompletion.steps().get(0).status());
        assertEquals(ChainStepStatus.READY,
                afterCompletion.steps().get(1).status());
        var next = machine.activateNext(
                "task-1", "revision-1", "review-accept",
                transitionId, NOW);
        assertEquals("step-2", next.step().stepId());
        assertFalse(machine.derive("task-1", "revision-1")
                .schedulingBlocked());
    }

    @Test
    void dependencyRequiresThePrerequisiteStepFormalCompletion() {
        ChainStepTestStore store = store();
        store.accepted.add(new AcceptedResultRecord(
                "accepted-unrelated", "task-1", "event-accepted-unrelated",
                "candidate-unrelated", "review-unrelated",
                "transition-unrelated", "content-unrelated",
                sha256("unrelated"), NOW));
        store.addAuthority("task-1", "event-accepted-unrelated");

        assertEquals(ChainStepStatus.NOT_STARTED,
                machine(store).derive("task-1", "revision-1")
                        .steps().get(1).status());
    }

    @Test
    void invalidDependencyGraphsFailClosed() {
        ChainStepTestStore store = store();
        store.plan = planWithSteps(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of("missing")));
        assertEquals("CHAIN_STEP_PLAN_DEPENDENCY_UNKNOWN",
                assertThrows(ChainStepException.class,
                        () -> machine(store).derive(
                                "task-1", "revision-1")).code());

        store.plan = planWithSteps(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of("step-1")));
        assertEquals("CHAIN_STEP_PLAN_SELF_DEPENDENCY",
                assertThrows(ChainStepException.class,
                        () -> machine(store).derive(
                                "task-1", "revision-1")).code());

        store.plan = planWithSteps(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of("step-2")),
                new ChainStepAuthorityPort.StepDefinition(
                        "step-2", 2, Set.of("step-1")));
        assertEquals("CHAIN_STEP_PLAN_DEPENDENCY_CYCLE",
                assertThrows(ChainStepException.class,
                        () -> machine(store).derive(
                                "task-1", "revision-1")).code());

        store.plan = planWithSteps(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of("step-2")),
                new ChainStepAuthorityPort.StepDefinition(
                        "step-2", 2, Set.of()));
        assertEquals("CHAIN_STEP_PLAN_DEPENDENCY_ORDER_INVALID",
                assertThrows(ChainStepException.class,
                        () -> machine(store).derive(
                                "task-1", "revision-1")).code());
    }

    @Test
    void onlyStepResultRuntimeCanCommitCandidateAndAcceptedResult() {
        ChainStepTestStore store = store();
        String body = "candidate result body";
        store.contents.put("content-result", new ContentRecord(
                "content-result", "task-1", "invocation-result",
                ChainContentKind.CANDIDATE_STEP_RESULT, body,
                sha256(body), "text/plain", NOW));
        store.proposals.put("proposal-result", new ModelProposalRecord(
                "proposal-result", "task-1", "invocation-result", 1,
                ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_STEP_RESULT,
                canonical("{\"candidateResultBodyRef\":\"content-result\"}"),
                canonical("{\"refs\":[]}"),
                ChainContentKind.CANDIDATE_STEP_RESULT.name(),
                "content-result", NOW));
        store.proposalStates.put("proposal-result", List.of(
                new ProposalStateEventRecord(
                        "proposal-result", 1, "task-1",
                        "event-proposal-accepted", ChainProposalState.ACCEPTED,
                        null, null, NOW)));
        store.invocations.put("invocation-result", new ModelInvocationRecord(
                "invocation-result", "task-1", "context-result",
                "token-result", ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "step-result",
                "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), NOW));
        store.contexts.put("context-result", completeExecutorContext());
        ChainStepResultRuntime runtime = new ChainStepResultRuntime(
                store, store, store, store, store, store);
        CandidateStepResultRecord candidate = candidate(
                "candidate-1", "event-candidate", "proposal-result",
                "content-result", "step-1", "activation-1");

        store.failBindingOnce = true;
        assertThrows(IllegalStateException.class,
                () -> runtime.commitCandidate(candidate));
        assertEquals(1, store.candidates.size());
        assertTrue(runtime.commitCandidate(candidate).replayed());
        assertEquals(2, store.proposalStates.get(
                "proposal-result").size());
        ReviewDecisionRecord acceptReview = review(
                "review-accept", "event-review", "candidate-1",
                ChainProposalKind.REFLECTOR_ACCEPT_STEP);
        store.reviews.add(acceptReview);
        String targetDigest = sha256("accepted-identity");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.ACCEPT_STEP, "task-1",
                "review-accept", targetDigest).transitionId();
        store.transitions.add(new TransitionRecord(
                transitionId, "task-1", "event-transition",
                ChainTransitionType.ACCEPT_STEP, "review-accept",
                targetDigest, NOW));
        AcceptedResultRecord accepted = new AcceptedResultRecord(
                "accepted-1", "task-1", "event-accepted",
                "candidate-1", "review-accept", transitionId,
                "content-result", sha256("accepted-identity"), NOW);

        assertFalse(runtime.accept(accepted).replayed());
        assertEquals(List.of(accepted), store.accepted);
        AcceptedResultRecord conflictingIdentity =
                new AcceptedResultRecord(
                        "accepted-2", "task-1", "event-accepted-2",
                        "candidate-1", "review-accept", transitionId,
                        "content-result", targetDigest, NOW);
        assertThrows(ChainStepException.class,
                () -> runtime.accept(conflictingIdentity));

        store.reviews.clear();
        store.reviews.add(review(
                "review-accept", "event-review", "candidate-1",
                ChainProposalKind.REFLECTOR_CONTINUE_STEP));
        assertThrows(ChainStepException.class,
                () -> runtime.accept(accepted));
    }

    @Test
    void candidateRejectsAProposalStatePrefixFromAnotherTask() {
        ChainStepTestStore store = store();
        String body = "candidate result body";
        store.contents.put("content-result", new ContentRecord(
                "content-result", "task-1", "invocation-result",
                ChainContentKind.CANDIDATE_STEP_RESULT, body,
                sha256(body), "text/plain", NOW));
        store.proposals.put("proposal-result", new ModelProposalRecord(
                "proposal-result", "task-1", "invocation-result", 1,
                ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_STEP_RESULT,
                canonical("{\"candidateResultBodyRef\":\"content-result\"}"),
                canonical("{\"refs\":[]}"),
                ChainContentKind.CANDIDATE_STEP_RESULT.name(),
                "content-result", NOW));
        store.proposalStates.put("proposal-result", List.of(
                new ProposalStateEventRecord(
                        "proposal-result", 1, "task-other",
                        "event-proposal-accepted", ChainProposalState.ACCEPTED,
                        null, null, NOW)));
        store.invocations.put("invocation-result", new ModelInvocationRecord(
                "invocation-result", "task-1", "context-result",
                "token-result", ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "step-result",
                "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), NOW));
        store.contexts.put("context-result", completeExecutorContext());
        ChainStepResultRuntime runtime = new ChainStepResultRuntime(
                store, store, store, store, store, store);

        assertThrows(ChainStepException.class, () -> runtime.commitCandidate(
                candidate("candidate-1", "event-candidate",
                        "proposal-result", "content-result", "step-1",
                        "activation-1")));
        assertTrue(store.candidates.isEmpty());
    }

    @Test
    void replanSupersedeRequiresItsPlanAndApplicabilityBarrier() {
        ChainStepTestStore store = store();
        ChainStepStateMachine machine = machine(store);
        String activationId = machine.activateNext(
                "task-1", "revision-1", "plan-decision",
                "transition-plan", NOW).append().value()
                .command().activationEventId();
        String target = sha256("new-plan");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.PLAN_CHANGE, "task-1",
                "replan-decision", target).transitionId();
        store.transitions.add(new TransitionRecord(
                transitionId, "task-1", "event-replan",
                ChainTransitionType.PLAN_CHANGE, "replan-decision",
                target, NOW));
        ChainStepStateMachine.StepTerminalCommand command =
                new ChainStepStateMachine.StepTerminalCommand(
                        "task-1", "revision-1", "step-1", activationId,
                        "replan-decision", transitionId, NOW);
        assertThrows(ChainStepException.class,
                () -> machine.supersedeForReplan(command));

        store.planBindings.add(new PlanBindingRecord(
                "binding-2", "task-1", "event-binding-2",
                "instruction-1", "route-1", "frame-2", "plan-2",
                "revision-2", 2L, "PLAN_REVISION", "revision-2",
                target, transitionId, NOW));
        store.applicability.add(new ResultApplicabilityRecord(
                "app-replan", "task-1", "event-app-replan",
                "accepted-before-replan",
                ChainApplicability.SourceType.PLAN_REVISION,
                transitionId, "frame-2", "plan-2", "revision-2",
                ChainIdentity.NONE, "instruction-1",
                ChainApplicability.Outcome.NOT_APPLICABLE,
                "not applicable to the new Plan", NOW));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.OPEN, 0,
                null, null));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.TASKFRAME_PLAN_COMMITTED,
                1, "PLAN_BINDING", "binding-2"));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.APPLICABILITY_COMMITTED,
                2, "RESULT_APPLICABILITY", "app-replan"));

        machine.supersedeForReplan(command);
        assertEquals(ChainStepStatus.SUPERSEDED_BY_REPLAN,
                machine.derive("task-1", "revision-1")
                        .steps().get(0).status());
    }

    @Test
    void finalReadinessAllowsAProvenEmptyApplicabilityStage() {
        ChainStepTestStore store = store();
        store.plan = new ChainStepAuthorityPort.PlanSnapshot(
                "task-1", "frame-1", "plan-1", "revision-1",
                ChainIdentity.NONE, "instruction-1", List.of(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of())));
        ChainStepStateMachine machine = machine(store);
        String activationId = machine.activateNext(
                "task-1", "revision-1", "plan-decision",
                "transition-plan", NOW).append().value()
                .command().activationEventId();
        CandidateStepResultRecord candidate = candidate(
                "candidate-final", "event-candidate-final",
                "proposal-final", "content-final", "step-1", activationId);
        store.candidates.add(candidate);
        store.addAuthority("task-1", candidate.eventId());
        ReviewDecisionRecord review = review(
                "review-final", "event-review-final",
                candidate.candidateResultId(),
                ChainProposalKind
                        .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE);
        store.reviews.add(review);
        store.addAuthority("task-1", review.eventId());
        String identity = sha256("final-accepted");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.FINAL_STEP_READINESS, "task-1",
                review.reviewDecisionId(), identity).transitionId();
        store.transitions.add(new TransitionRecord(
                transitionId, "task-1", "event-transition-final",
                ChainTransitionType.FINAL_STEP_READINESS,
                review.reviewDecisionId(), identity, NOW));
        store.accepted.add(new AcceptedResultRecord(
                "accepted-final", "task-1", "event-accepted-final",
                candidate.candidateResultId(), review.reviewDecisionId(),
                transitionId, candidate.contentId(), identity, NOW));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.OPEN, 0,
                null, null));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage
                        .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                1, "ACCEPTED_RESULT", "accepted-final"));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage
                        .APPLICABILITY_COMMITTED_OR_EMPTY,
                2, null, null));

        var terminal = machine.completeAcceptedStep(
                new ChainStepStateMachine.StepTerminalCommand(
                        "task-1", "revision-1", "step-1", activationId,
                        review.reviewDecisionId(), transitionId, NOW));
        assertEquals(ChainStepStatus.COMPLETED,
                machine.derive("task-1", "revision-1")
                        .steps().get(0).status());
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage
                        .STEP_COMPLETED_OR_VERIFIED,
                3, "STEP_EVENT", terminal.value().command().eventId()));
        CanonicalJson acceptedSet = canonical("[\"accepted-final\"]");
        CanonicalJson coverage = canonical(
                "{\"allRequirementsSatisfied\":true}");
        ChainReadinessAuthorityPort.VerifiedReadinessMaterial
                verifiedMaterial = readinessMaterial(
                activationId, acceptedSet, coverage,
                store.highestAuthorityEventSequence("task-1"));
        ChainStepRuntime runtime = new ChainStepRuntime(
                machine, store, store, store, store, store);
        ChainStepRuntime.ReadinessCommand readiness =
                new ChainStepRuntime.ReadinessCommand(
                        "task-1", transitionId,
                        review.reviewDecisionId(), NOW);
        assertThrows(ChainStepException.class,
                () -> runtime.commitReadiness(readiness));
        store.readinessMaterial = readinessMaterial(
                activationId, canonical("[\"another-result\"]"), coverage,
                store.highestAuthorityEventSequence("task-1"));
        assertThrows(ChainStepException.class,
                () -> runtime.commitReadiness(readiness));
        store.readinessMaterial = verifiedMaterial;
        store.gateBlocked = true;
        assertThrows(ChainStepException.class,
                () -> runtime.commitReadiness(readiness));
        assertTrue(store.readinessFacts.isEmpty());
        store.gateBlocked = false;
        Instant storedReadinessTime = NOW.plusSeconds(30);
        store.authoritativeActionTime = storedReadinessTime;
        var firstReadiness = runtime.commitReadiness(readiness);
        assertFalse(firstReadiness.replayed());
        assertEquals(storedReadinessTime,
                firstReadiness.fact().createdAt());
        assertEquals(storedReadinessTime,
                firstReadiness.event().committedAt());
        store.gateBlocked = true;
        assertTrue(runtime.commitReadiness(readiness).replayed());
        assertEquals(1, store.readinessFacts.size());

        ReviewDecisionRecord secondReview = review(
                "review-final-2", "event-review-final-2",
                candidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE);
        store.reviews.add(secondReview);
        String secondTarget = sha256("second-readiness-target");
        String secondTransitionId = new ChainIdentity.Transition(
                ChainTransitionType.FINAL_STEP_READINESS, "task-1",
                secondReview.reviewDecisionId(), secondTarget).transitionId();
        store.transitions.add(new TransitionRecord(
                secondTransitionId, "task-1", "event-transition-final-2",
                ChainTransitionType.FINAL_STEP_READINESS,
                secondReview.reviewDecisionId(), secondTarget, NOW));
        store.transitionStages.add(stage(
                secondTransitionId, ChainTransitionStage.OPEN, 0,
                null, null));
        store.transitionStages.add(stage(
                secondTransitionId, ChainTransitionStage
                        .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                1, "ACCEPTED_RESULT", "accepted-final"));
        store.transitionStages.add(stage(
                secondTransitionId, ChainTransitionStage
                        .APPLICABILITY_COMMITTED_OR_EMPTY,
                2, null, null));
        store.transitionStages.add(stage(
                secondTransitionId, ChainTransitionStage
                        .STEP_COMPLETED_OR_VERIFIED,
                3, "STEP_EVENT", terminal.value().command().eventId()));
        assertThrows(ChainStepException.class, () ->
                runtime.commitReadiness(
                        new ChainStepRuntime.ReadinessCommand(
                                "task-1", secondTransitionId,
                                secondReview.reviewDecisionId(),
                                NOW.plusSeconds(1))));
    }

    @Test
    void replanAllowsAnExplicitlyEmptyApplicabilityBarrier() {
        ChainStepTestStore store = store();
        ChainStepStateMachine machine = machine(store);
        String activationId = machine.activateNext(
                "task-1", "revision-1", "plan-decision",
                "transition-plan", NOW).append().value()
                .command().activationEventId();
        String target = sha256("empty-new-plan");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.PLAN_CHANGE, "task-1",
                "empty-replan", target).transitionId();
        store.transitions.add(new TransitionRecord(
                transitionId, "task-1", "event-empty-replan",
                ChainTransitionType.PLAN_CHANGE, "empty-replan",
                target, NOW));
        store.planBindings.add(new PlanBindingRecord(
                "binding-empty", "task-1", "event-binding-empty",
                "instruction-1", "route-1", "frame-2", "plan-2",
                "revision-2", 2L, "PLAN_REVISION", "revision-2",
                target, transitionId, NOW));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.OPEN, 0,
                null, null));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.TASKFRAME_PLAN_COMMITTED,
                1, "PLAN_BINDING", "binding-empty"));
        store.transitionStages.add(stage(
                transitionId, ChainTransitionStage.APPLICABILITY_COMMITTED,
                2, null, null));

        machine.supersedeForReplan(
                new ChainStepStateMachine.StepTerminalCommand(
                        "task-1", "revision-1", "step-1", activationId,
                        "empty-replan", transitionId, NOW));
        assertEquals(ChainStepStatus.SUPERSEDED_BY_REPLAN,
                machine.derive("task-1", "revision-1")
                        .steps().get(0).status());
    }

    @Test
    void stepMutationsExposeOnlyTheChainStepRuntimePublicEntry() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isPublic(
                ChainStepStateMachine.class.getDeclaredMethod(
                        "activateNext", String.class, String.class,
                        String.class, String.class, Instant.class)
                        .getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(
                ChainStepStateMachine.class.getDeclaredMethod(
                        "completeAcceptedStep",
                        ChainStepStateMachine.StepTerminalCommand.class)
                        .getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(
                ChainStepStateMachine.class.getDeclaredMethod(
                        "supersedeForReplan",
                        ChainStepStateMachine.StepTerminalCommand.class)
                        .getModifiers()));

        assertTrue(java.lang.reflect.Modifier.isPublic(
                ChainStepRuntime.class.getMethod(
                        "activateNext", String.class, String.class,
                        String.class, String.class, Instant.class)
                        .getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(
                ChainStepRuntime.class.getMethod(
                        "completeAcceptedStep",
                        ChainStepStateMachine.StepTerminalCommand.class)
                        .getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(
                ChainStepRuntime.class.getMethod(
                        "supersedeForReplan",
                        ChainStepStateMachine.StepTerminalCommand.class)
                        .getModifiers()));
    }

    private static ChainStepTestStore store() {
        ChainStepTestStore store = new ChainStepTestStore();
        store.plan = new ChainStepAuthorityPort.PlanSnapshot(
                "task-1", "frame-1", "plan-1", "revision-1",
                ChainIdentity.NONE, "instruction-1", List.of(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of()),
                new ChainStepAuthorityPort.StepDefinition(
                        "step-2", 2, Set.of("step-1"))));
        return store;
    }

    private static ChainStepAuthorityPort.PlanSnapshot planWithSteps(
            ChainStepAuthorityPort.StepDefinition... steps) {
        return new ChainStepAuthorityPort.PlanSnapshot(
                "task-1", "frame-1", "plan-1", "revision-1",
                ChainIdentity.NONE, "instruction-1", List.of(steps));
    }

    private static ChainStepStateMachine machine(
            ChainStepTestStore store) {
        return new ChainStepStateMachine(
                store, store, store, store, store);
    }

    private static void bindOpenGap(
            ChainStepTestStore store, String activationId) {
        store.proposals.put("proposal-gap", new ModelProposalRecord(
                "proposal-gap", "task-1", "invocation-gap", 1,
                ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_NEED_USER_INPUT,
                canonical("{}"), canonical("{}"), null, null, NOW));
        store.invocations.put("invocation-gap", new ModelInvocationRecord(
                "invocation-gap", "task-1", "context-gap", "token-gap",
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "gap", "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), NOW));
        store.contexts.put("context-gap", completeContext(
                "context-gap", "step-1", activationId));
        store.openPending.add(new PendingItemRecord(
                "gap-1", "task-1", "event-gap", "proposal-gap",
                ChainPendingItemType.USER_INFORMATION, HASH,
                canonical("{\"field\":\"missing\"}"), null,
                "question", "text", ChainRole.EXECUTOR,
                ChainRole.EXECUTOR, canonical("{\"stepId\":\"step-1\"}"),
                HASH, NOW));
    }

    private static ContextRevisionRecord completeContext(
            String contextId, String stepId, String activationId) {
        return new ContextRevisionRecord(
                contextId, "task-1", null, ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, "review",
                "instruction-1", "frame-1", "plan-1", "revision-1",
                1L, stepId, activationId, null, null, null,
                null, null, null, null, null,
                "projectors-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new FormattedJson(1, "{}"), HASH, "token-gap",
                null, null, NOW, NOW);
    }

    private static ContextRevisionRecord completeExecutorContext() {
        return new ContextRevisionRecord(
                "context-result", "task-1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "step-result",
                "instruction-1", "frame-1", "plan-1", "revision-1",
                1L, "step-1", "activation-1", null, null, null,
                null, null, null, null, null,
                "projectors-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new FormattedJson(1, "{}"), HASH, "token-result",
                null, null, NOW, NOW);
    }

    private static CandidateStepResultRecord candidate(
            String id, String eventId, String proposalId, String contentId,
            String stepId, String activationId) {
        return new CandidateStepResultRecord(
                id, "task-1", eventId, proposalId, contentId,
                "instruction-1", "frame-1", "plan-1", "revision-1",
                1L, stepId, activationId, null, null, null,
                canonical("{\"receipts\":[]}"), null, null, null,
                canonical("{\"evidence\":[]}"), HASH, NOW);
    }

    private static ReviewDecisionRecord review(
            String id, String eventId, String candidateId,
            ChainProposalKind kind) {
        return new ReviewDecisionRecord(
                id, "task-1", eventId, "proposal-review-" + id,
                "CANDIDATE_STEP_RESULT", candidateId, kind,
                "formal review", canonical("{\"facts\":[]}"), HASH, NOW);
    }

    private static CanonicalJson canonical(String json) {
        return new CanonicalJson(1, sha256(json), json);
    }

    private static ChainReadinessAuthorityPort.VerifiedReadinessMaterial
            readinessMaterial(
                    String activationId,
                    CanonicalJson acceptedSet,
                    CanonicalJson coverage,
                    long applicabilityCut) {
        return new ChainReadinessAuthorityPort.VerifiedReadinessMaterial(
                "frame-1", "plan-1", "revision-1", 1L,
                "step-1", activationId, List.of("accepted-final"),
                acceptedSet, applicabilityCut, null, ChainIdentity.NONE,
                ChainIdentity.NONE, ChainIdentity.NONE, null, null,
                coverage, ChainPublishRequirement.NOT_REQUIRED,
                sha256("publish-not-required"), "instruction-1",
                ChainIdentity.NONE);
    }

    private static TransitionStageRecord stage(
            String transitionId,
            ChainTransitionStage stage,
            int ordinal,
            String successorType,
            String successorRef) {
        return new TransitionStageRecord(
                transitionId, stage, "task-1",
                "event-stage-" + ordinal, ordinal,
                null, null, successorType, successorRef, NOW);
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
}
