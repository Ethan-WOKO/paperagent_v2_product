package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRouteDecisionWriter;
import io.paperagent.v2.chain.ChainPlanBindingWriter;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChainRoleRoutingTest {
    private static final Instant NOW = Instant.parse("2026-08-07T05:00:00Z");
    private static final String SHA = "a".repeat(64);

    @Test
    void acceptedProposalDoesNotRouteUntilTheFormalDirectDecisionExists() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRoleRouter router = roleRouter(instructions, store);
        PlannerPayload.DirectRoute payload = directPayload();
        store.accept("proposal-direct", payload);

        assertModel(router.next("task-1"), ChainRole.PLANNER, ChainWorkState.PLANNING);

        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        ChainPersistenceRecords.RouteDecisionRecord direct = runtime.commitDirect(
                initialRequest("proposal-direct", "event-direct", NOW), payload);

        assertEquals(io.paperagent.v2.chain.ChainExecutionMode.DIRECT, direct.route());
        assertEquals("ROUTE_DECISION", store.lastRouteEventType);
        assertModel(router.next("task-1"), ChainRole.ANSWER,
                ChainWorkState.DIRECT_ANSWERING);
    }

    @Test
    void directRouteEscalatesMonotonicallyAndCannotBeReplacedByAnotherInitialRoute() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        ChainRoleRouter router = roleRouter(instructions, store);
        PlannerPayload.DirectRoute directPayload = directPayload();
        store.accept("proposal-direct", directPayload);
        ChainPersistenceRecords.RouteDecisionRecord direct = runtime.commitDirect(
                initialRequest("proposal-direct", "event-direct", NOW), directPayload);

        AnswerPayload.EscalateToPersistent escalation =
                new AnswerPayload.EscalateToPersistent(
                        direct.routeDecisionId(), "requires governed execution",
                        List.of("tool.search"), List.of("project.manifest"), true);
        store.accept("proposal-escalate", escalation);
        ChainPersistenceRecords.RouteDecisionRecord escalated = runtime.escalate(
                escalationRequest("proposal-escalate", "event-escalate", NOW.plusSeconds(1)),
                escalation);

        assertEquals(1, escalated.decisionOrdinal());
        assertEquals(direct.routeDecisionId(), escalated.parentRouteDecisionId());
        assertEquals(io.paperagent.v2.chain.ChainExecutionMode.PERSISTENT_PLAN_EXECUTE,
                escalated.route());
        assertModel(router.next("task-1"), ChainRole.PLANNER, ChainWorkState.PLANNING);

        store.addPlanBinding(planBinding(escalated.routeDecisionId()));
        store.activateStep("revision-1", "step-1", "activation-1");
        assertModel(router.next("task-1"), ChainRole.EXECUTOR, ChainWorkState.EXECUTING);

        PlannerPayload.DirectRoute competingPayload = new PlannerPayload.DirectRoute(
                "competing direct route", "answer inline", List.of(), List.of(),
                false, false, false, false, null);
        store.accept("proposal-competing", competingPayload);
        ChainRouteException conflict = assertThrows(ChainRouteException.class,
                () -> runtime.commitDirect(
                        initialRequest("proposal-competing", "event-competing", NOW.plusSeconds(2)),
                        competingPayload));
        assertEquals(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                conflict.code());

        assertEquals(direct, runtime.commitDirect(
                initialRequest("proposal-direct", "event-direct", NOW), directPayload));
        assertModel(router.next("task-1"), ChainRole.EXECUTOR, ChainWorkState.EXECUTING);
    }

    @Test
    void persistentPlanCreatesTheOnlyInitialPersistentRoute() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);

        ChainPersistenceRecords.RouteDecisionRecord route = runtime.commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);

        assertEquals(io.paperagent.v2.chain.ChainExecutionMode.PERSISTENT_PLAN_EXECUTE,
                route.route());
        assertEquals(ChainPersistenceRecords.RouteDecisionType.INITIAL,
                route.decisionKind());
        assertEquals(true, route.needsTool());
        assertEquals(false, route.needsNetwork());
        assertEquals(true, route.needsProject());
        assertEquals(false, route.needsPersistentProgress());
    }

    @Test
    void planCommitUsesFormalTransitionsAndKeepsPersistentProposalBoundToRoute() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);
        ChainPersistenceRecords.RouteDecisionRecord route = routeRuntime(
                store, instructions).commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);
        ChainPersistenceRecords.TransitionRecord initialTransition = transition(
                ChainTransitionType.PLAN_CHANGE, route.routeDecisionId(),
                "event-transition-initial");
        store.addTransition(initialTransition);
        ChainPlanCommitRuntime runtime = planCommitRuntime(store, instructions);
        ChainPlanCommitRuntime.CommitRequest initialRequest = planCommitRequest(
                "proposal-plan", "event-binding-initial",
                initialTransition.transitionId());

        ChainPersistenceRecords.PlanBindingRecord initial =
                runtime.commitPersistent(initialRequest, payload);
        assertEquals(initialTransition.transitionId(), initial.transitionId());
        assertEquals(1, initial.planRevisionNumber());
        assertEquals("ROUTE_DECISION",
                store.states.get("proposal-plan").get(1).officialAuthorityType());
        assertEquals(initial, runtime.commitPersistent(initialRequest, payload));
        assertEquals(1, store.planBindings.size());

        ChainPersistenceRecords.ReviewDecisionRecord review = reviewDecision(
                "review-replan-commit", "event-review-replan-commit",
                ChainProposalKind.REFLECTOR_REPLAN_REQUIRED);
        store.addReview(review);
        PlannerPayload.PlanRevision revision = revisionPayload(
                review.reviewDecisionId());
        store.accept("proposal-revision", revision);
        ChainPersistenceRecords.TransitionRecord revisionTransition = transition(
                ChainTransitionType.PLAN_CHANGE, review.reviewDecisionId(),
                "event-transition-revision");
        store.addTransition(revisionTransition);
        ChainPlanCommitRuntime.CommitRequest revisionRequest = planCommitRequest(
                "proposal-revision", "event-binding-revision",
                revisionTransition.transitionId());
        store.failNextProposalBind = true;

        assertThrows(IllegalStateException.class,
                () -> runtime.commitRevision(revisionRequest, revision));
        assertEquals(2, store.planBindings.size());
        ChainPersistenceRecords.PlanBindingRecord revised =
                runtime.commitRevision(revisionRequest, revision);
        assertEquals(initial.taskFrameId(), revised.taskFrameId());
        assertEquals(initial.planId(), revised.planId());
        assertEquals(2, revised.planRevisionNumber());
        assertEquals("PLAN_BINDING",
                store.states.get("proposal-revision").get(1)
                        .officialAuthorityType());
        assertEquals(revised.planBindingId(),
                store.states.get("proposal-revision").get(1)
                        .officialAuthorityRef());
        assertEquals(2, store.planBindings.size());
    }

    @Test
    void persistentPlanCommitRejectsMissingWrongSourceAndWrongTypeTransitions() {
        PreparedPersistent missing = preparedPersistent();
        ChainRouteException noTransition = assertThrows(
                ChainRouteException.class,
                () -> planCommitRuntime(missing.store(), missing.instructions())
                        .commitPersistent(planCommitRequest(
                                "proposal-plan", "event-binding-missing", null),
                                missing.payload()));
        assertEquals(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                noTransition.code());

        PreparedPersistent wrongSource = preparedPersistent();
        ChainPersistenceRecords.TransitionRecord sourceTransition = transition(
                ChainTransitionType.PLAN_CHANGE, "another-route",
                "event-transition-wrong-source");
        wrongSource.store().addTransition(sourceTransition);
        ChainRouteException sourceRejected = assertThrows(
                ChainRouteException.class,
                () -> planCommitRuntime(
                        wrongSource.store(), wrongSource.instructions())
                        .commitPersistent(planCommitRequest(
                                "proposal-plan", "event-binding-wrong-source",
                                sourceTransition.transitionId()),
                                wrongSource.payload()));
        assertEquals(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                sourceRejected.code());

        PreparedPersistent wrongType = preparedPersistent();
        ChainPersistenceRecords.TransitionRecord typeTransition = transition(
                ChainTransitionType.ACCEPT_STEP,
                wrongType.route().routeDecisionId(),
                "event-transition-wrong-type");
        wrongType.store().addTransition(typeTransition);
        ChainRouteException typeRejected = assertThrows(
                ChainRouteException.class,
                () -> planCommitRuntime(wrongType.store(), wrongType.instructions())
                        .commitPersistent(planCommitRequest(
                                "proposal-plan", "event-binding-wrong-type",
                                typeTransition.transitionId()),
                                wrongType.payload()));
        assertEquals(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                typeRejected.code());
    }

    @Test
    void phaseFourRouteAuthoritiesExposeNoDeliveryWriterDependency() {
        for (Class<?> authority : List.of(
                ChainRouteRuntime.class,
                ChainRoleRouter.class,
                ChainPlanCommitRuntime.class)) {
            assertFalse(Arrays.stream(authority.getDeclaredFields())
                    .anyMatch(field -> field.getType() == ChainDeliveryWriter.class));
            assertFalse(Arrays.stream(authority.getDeclaredConstructors())
                    .flatMap(constructor -> Arrays.stream(
                            constructor.getParameterTypes()))
                    .anyMatch(type -> type == ChainDeliveryWriter.class));
            assertFalse(Arrays.stream(authority.getDeclaredMethods())
                    .anyMatch(method ->
                            method.getReturnType()
                                    == ChainPersistenceRecords.DeliveryRecord.class
                                    || Arrays.stream(method.getParameterTypes())
                                    .anyMatch(type -> type
                                            == ChainPersistenceRecords
                                            .DeliveryRecord.class)));
        }
    }

    @Test
    void formalCandidateAndTaskOutcomeMechanicallySelectReflectorThenAnswer() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);
        ChainPersistenceRecords.RouteDecisionRecord route = runtime.commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);
        store.addPlanBinding(planBinding(route.routeDecisionId()));
        store.activateStep("revision-1", "step-1", "activation-1");
        ChainRoleRouter router = roleRouter(instructions, store);
        assertModel(router.next("task-1"), ChainRole.EXECUTOR, ChainWorkState.EXECUTING);

        store.addCandidate(candidateResult());
        assertModel(router.next("task-1"), ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW);

        store.addTaskOutcome(failedOutcome());
        assertModel(router.next("task-1"), ChainRole.ANSWER, ChainWorkState.TERMINAL);
    }

    @Test
    void routeRuntimeRejectsNonAcceptedAndPayloadMismatchedProposals() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.DirectRoute payload = directPayload();
        store.proposals.put("proposal-not-accepted", proposal("proposal-not-accepted", payload));

        ChainRouteException notAccepted = assertThrows(ChainRouteException.class,
                () -> runtime.commitDirect(
                        initialRequest("proposal-not-accepted", "event-1", NOW), payload));
        assertEquals(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED, notAccepted.code());

        store.accept("proposal-mismatch", payload);
        PlannerPayload.DirectRoute changed = new PlannerPayload.DirectRoute(
                "changed reason", payload.directTaskSpecification(),
                payload.userConstraints(), payload.answerRequiredRefs(),
                false, false, false, false, null);
        ChainRouteException mismatch = assertThrows(ChainRouteException.class,
                () -> runtime.commitDirect(
                        initialRequest("proposal-mismatch", "event-2", NOW), changed));
        assertEquals(ChainRouteException.Code.PROPOSAL_PAYLOAD_MISMATCH, mismatch.code());
    }

    @Test
    void routeAppendBindsProposalAndReplayRecoversWhileCrossProposalPrefixIsRejected() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.DirectRoute payload = directPayload();
        store.accept("proposal-direct", payload);
        store.failNextProposalBind = true;

        assertThrows(IllegalStateException.class, () -> runtime.commitDirect(
                initialRequest("proposal-direct", "event-direct", NOW), payload));
        assertEquals(1, store.routes.size());
        assertEquals(1, store.states.get("proposal-direct").size());

        ChainPersistenceRecords.RouteDecisionRecord replay = runtime.commitDirect(
                initialRequest("proposal-direct", "event-direct", NOW), payload);
        ChainPersistenceRecords.ProposalStateEventRecord bound =
                store.states.get("proposal-direct").get(1);
        assertEquals("ROUTE_DECISION", bound.officialAuthorityType());
        assertEquals(replay.routeDecisionId(), bound.officialAuthorityRef());
        assertEquals(1, store.routes.size());

        Store crossed = new Store();
        crossed.accept("proposal-crossed", payload);
        crossed.states.put("proposal-crossed", new ArrayList<>(List.of(
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        "another-proposal", 1, "task-1", "event-crossed",
                        ChainProposalState.ACCEPTED, null, null, NOW))));
        ChainRouteException rejected = assertThrows(
                ChainRouteException.class,
                () -> routeRuntime(crossed, stateReader(crossed)).commitDirect(
                        initialRequest("proposal-crossed", "event-crossed-route", NOW),
                        payload));
        assertEquals(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                rejected.code());
    }

    @Test
    void routeAcceptsDatabaseMicrosecondTimestampNormalization() {
        Store store = new Store();
        store.normalizeRouteAuditTimesToMicros = true;
        PlannerPayload.DirectRoute payload = directPayload();
        store.accept("proposal-direct", payload);
        Instant nanosecondTime = Instant.parse(
                "2026-08-07T05:00:00.123456789Z");

        ChainPersistenceRecords.RouteDecisionRecord route = routeRuntime(
                store, stateReader(store)).commitDirect(
                initialRequest("proposal-direct", "event-direct",
                        nanosecondTime), payload);

        assertEquals(nanosecondTime.truncatedTo(ChronoUnit.MICROS),
                route.createdAt());
    }

    @Test
    void acceptReviewWaitsForCompleteSuccessorAndActiveStepWhileReadyWaitsForReadiness() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);
        ChainPersistenceRecords.RouteDecisionRecord route = runtime.commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);
        store.addPlanBinding(planBinding(route.routeDecisionId()));
        store.activateStep("revision-1", "step-1", "activation-1");
        store.addCandidate(candidateResult());
        ChainRoleRouter router = roleRouter(instructions, store);

        store.addReview(reviewDecision(
                "review-other-object", "event-review-other-object",
                ChainProposalKind.REFLECTOR_CONTINUE_STEP, "OTHER_OBJECT"));
        assertModel(router.next("task-1"),
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW);

        ChainPersistenceRecords.ReviewDecisionRecord continueReview = reviewDecision(
                "review-continue", "event-review-continue",
                ChainProposalKind.REFLECTOR_CONTINUE_STEP);
        store.addReview(continueReview);
        assertModel(router.next("task-1"),
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING);

        ChainPersistenceRecords.ReviewDecisionRecord acceptedReview = reviewDecision(
                "review-accept", "event-review-accept",
                ChainProposalKind.REFLECTOR_ACCEPT_STEP);
        store.addReview(acceptedReview);
        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));

        ChainPersistenceRecords.AcceptedResultRecord accepted = acceptedResult(
                acceptedReview, "transition-accept");
        store.addAcceptedResult(accepted);
        store.activateStep("revision-1", "step-2", "activation-2");
        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));

        store.completeTransition(
                "transition-accept", ChainTransitionType.ACCEPT_STEP);
        assertModel(router.next("task-1"),
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING);

        ChainPersistenceRecords.ReviewDecisionRecord readyReview = reviewDecision(
                "review-ready", "event-review-ready",
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE);
        store.addReview(readyReview);
        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));

        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                readiness(readyReview, "transition-ready");
        store.addReadiness(readiness);
        store.completeTransition(
                "transition-ready", ChainTransitionType.FINAL_STEP_READINESS);
        ChainRoleRouter.RoutingResult.ControlOnly finalizing = assertInstanceOf(
                ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));
        assertEquals(readiness.readinessId(), finalizing.authorityRef());
    }

    @Test
    void completedReplanFallsThroughToTheNewPlanAndActiveStep() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);
        ChainPersistenceRecords.RouteDecisionRecord route = runtime.commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);
        store.addPlanBinding(planBinding(route.routeDecisionId()));
        store.activateStep("revision-1", "step-1", "activation-1");
        store.addCandidate(candidateResult());
        store.addReview(reviewDecision(
                "review-replan", "event-review-replan",
                ChainProposalKind.REFLECTOR_REPLAN_REQUIRED));
        ChainRoleRouter router = roleRouter(instructions, store);

        assertModel(router.next("task-1"),
                ChainRole.PLANNER, ChainWorkState.PLANNING);

        store.clearActiveStep();
        store.addPlanBinding(revisedPlanBinding(route.routeDecisionId()));
        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));

        store.completeTransition(
                "transition-plan-change", ChainTransitionType.PLAN_CHANGE);
        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));

        store.activateStep("revision-2", "step-2", "activation-revision-2");
        assertModel(router.next("task-1"),
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING);
    }

    @Test
    void resolvedUserGapReleasesNeedInputReviewButPermissionGapDoesNot() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        ChainRouteRuntime runtime = routeRuntime(store, instructions);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);
        ChainPersistenceRecords.RouteDecisionRecord route = runtime.commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);
        store.addPlanBinding(planBinding(route.routeDecisionId()));
        store.activateStep("revision-1", "step-1", "activation-1");
        store.addCandidate(candidateResult());
        ChainPersistenceRecords.ReviewDecisionRecord needInput = reviewDecision(
                "review-need-input", "event-review-need-input",
                ChainProposalKind.REFLECTOR_NEED_USER_INPUT);
        store.addReview(needInput);
        ChainRoleRouter router = roleRouter(instructions, store);

        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));

        ChainPersistenceRecords.PendingItemRecord userGap = pendingItem(
                "gap-user", needInput.proposalId(),
                ChainPendingItemType.USER_INFORMATION);
        store.addPendingItem(userGap);
        store.addPendingEvent(pendingEvent(
                userGap.gapId(), "event-gap-user-pending",
                ChainPendingItemStatus.PENDING));
        assertModel(router.next("task-1"),
                ChainRole.ANSWER, ChainWorkState.WAITING_USER);

        store.addPendingEvent(pendingEvent(
                userGap.gapId(), "event-gap-user-response",
                ChainPendingItemStatus.RESPONSE_RECEIVED));
        assertModel(router.next("task-1"),
                ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM);

        store.addPendingEvent(pendingEvent(
                userGap.gapId(), "event-gap-user-resolved",
                ChainPendingItemStatus.RESOLVED));
        assertModel(router.next("task-1"),
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING);

        ChainPersistenceRecords.ReviewDecisionRecord needPermission = reviewDecision(
                "review-need-permission", "event-review-need-permission",
                ChainProposalKind.REFLECTOR_NEED_PERMISSION);
        store.addReview(needPermission);
        ChainPersistenceRecords.PendingItemRecord permissionGap = pendingItem(
                "gap-permission", needPermission.proposalId(),
                ChainPendingItemType.PERMISSION);
        store.addPendingItem(permissionGap);
        store.addPendingEvent(pendingEvent(
                permissionGap.gapId(), "event-gap-permission-resolved",
                ChainPendingItemStatus.RESOLVED));
        assertInstanceOf(ChainRoleRouter.RoutingResult.ControlOnly.class,
                router.next("task-1"));
    }

    private static ChainInstructionStateReader stateReader(Store store) {
        return new ChainInstructionStateReader(store, store, store);
    }

    private static ChainRouteRuntime routeRuntime(
            Store store,
            ChainInstructionStateReader instructions) {
        return new ChainRouteRuntime(
                store, store, store, instructions, store);
    }

    private static ChainPlanCommitRuntime planCommitRuntime(
            Store store,
            ChainInstructionStateReader instructions) {
        return new ChainPlanCommitRuntime(
                store, store, store, store, store, instructions, store);
    }

    private static ChainRoleRouter roleRouter(
            ChainInstructionStateReader instructions,
            Store store) {
        return new ChainRoleRouter(
                instructions, store, store, store, store);
    }

    private static PlannerPayload.DirectRoute directPayload() {
        return new PlannerPayload.DirectRoute(
                "no persistent boundary", "answer the question",
                List.of("be concise"), List.of("instruction-1"),
                false, false, false, false, null);
    }

    private static PlannerPayload.PersistentPlan persistentPayload() {
        ProposalFields.TaskFrameDraft frame = new ProposalFields.TaskFrameDraft(
                "perform governed work", List.of("object-1"), List.of("result"),
                List.of("stay scoped"), "NONE", "standard",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(), io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        ProposalFields.StepDraft step = new ProposalFields.StepDraft(
                "step-1", 1, "perform work", List.of(),
                List.of("result exists"), List.of("object-1"), List.of("result"),
                false, null);
        return new PlannerPayload.PersistentPlan(
                frame, new ProposalFields.RoutingBoundary(
                        true, false, true, false),
                List.of(new ProposalFields.RequirementCoverage(
                        "result", ProposalFields.RequirementStatus.PLANNED, List.of())),
                new ProposalFields.PlanDraft(List.of(step)), List.of(), null);
    }

    private static PlannerPayload.PlanRevision revisionPayload(String sourceRef) {
        ProposalFields.StepDraft step = new ProposalFields.StepDraft(
                "step-2", 1, "perform revised work", List.of(),
                List.of("revised result exists"), List.of("object-1"),
                List.of("result"), false, null);
        return new PlannerPayload.PlanRevision(
                sourceRef, "revision-1",
                new ProposalFields.PlanDraft(List.of(step)),
                List.of(new ProposalFields.RequirementCoverage(
                        "result", ProposalFields.RequirementStatus.PLANNED,
                        List.of())),
                List.of(), List.of(), List.of(), List.of(),
                "task-frame-1", null);
    }

    private static PreparedPersistent preparedPersistent() {
        Store store = new Store();
        ChainInstructionStateReader instructions = stateReader(store);
        PlannerPayload.PersistentPlan payload = persistentPayload();
        store.accept("proposal-plan", payload);
        ChainPersistenceRecords.RouteDecisionRecord route = routeRuntime(
                store, instructions).commitPersistent(
                initialRequest("proposal-plan", "event-plan", NOW), payload);
        return new PreparedPersistent(store, instructions, payload, route);
    }

    private static ChainPlanCommitRuntime.CommitRequest planCommitRequest(
            String proposalId,
            String eventId,
            String transitionId) {
        return new ChainPlanCommitRuntime.CommitRequest(
                "task-1", "instruction-1", proposalId, eventId,
                transitionId, NOW.plusSeconds(2));
    }

    private static ChainPersistenceRecords.TransitionRecord transition(
            ChainTransitionType type,
            String sourceRef,
            String eventId) {
        return new ChainPersistenceRecords.TransitionRecord(
                new ChainIdentity.Transition(
                        type, "task-1", sourceRef, SHA).transitionId(),
                "task-1", eventId, type, sourceRef, SHA,
                NOW.plusSeconds(1));
    }

    private static ChainRouteRuntime.InitialRouteRequest initialRequest(
            String proposalId, String eventId, Instant createdAt) {
        return new ChainRouteRuntime.InitialRouteRequest(
                new ChainRouteRuntime.CommonRequest(
                        "task-1", "instruction-1", proposalId, eventId, createdAt));
    }

    private static ChainRouteRuntime.EscalationRequest escalationRequest(
            String proposalId, String eventId, Instant createdAt) {
        return new ChainRouteRuntime.EscalationRequest(
                new ChainRouteRuntime.CommonRequest(
                        "task-1", "instruction-1", proposalId, eventId, createdAt));
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            String proposalId, ChainProposalPayload payload) {
        return new ChainPersistenceRecords.ModelProposalRecord(
                proposalId, "task-1", "invocation-" + proposalId, 1,
                payload.role(), payload.kind(), ChainRouteCanonical.canonical(payload),
                ChainRouteCanonical.canonical(List.of()), null, null, NOW);
    }

    private static ChainPersistenceRecords.PlanBindingRecord planBinding(String routeId) {
        return new ChainPersistenceRecords.PlanBindingRecord(
                "plan-binding-1", "task-1", "event-plan-binding", "instruction-1",
                routeId, "task-frame-1", "plan-1", "revision-1", 1,
                "STABLE_V2_PLAN", "plan-authority", SHA, null, NOW.plusSeconds(3));
    }

    private static ChainPersistenceRecords.PlanBindingRecord revisedPlanBinding(
            String routeId) {
        return new ChainPersistenceRecords.PlanBindingRecord(
                "plan-binding-2", "task-1", "event-plan-binding-2",
                "instruction-1", routeId, "task-frame-1", "plan-1",
                "revision-2", 2, "STABLE_V2_PLAN", "plan-authority-2",
                SHA, "transition-plan-change", NOW.minusSeconds(100));
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidateResult() {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                "candidate-result-1", "task-1", "event-candidate", "proposal-result",
                "content-result", "instruction-1", "task-frame-1", "plan-1",
                "revision-1", 1, "step-1", "activation-1",
                null, null, null, json("[]"), null, null, null,
                json("[]"), SHA, NOW.plusSeconds(4));
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord reviewDecision(
            String reviewId,
            String eventId,
            ChainProposalKind kind) {
        return reviewDecision(
                reviewId, eventId, kind, "CANDIDATE_STEP_RESULT");
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord reviewDecision(
            String reviewId,
            String eventId,
            ChainProposalKind kind,
            String reviewObjectType) {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                reviewId, "task-1", eventId, "proposal-" + reviewId,
                reviewObjectType, "candidate-result-1", kind,
                "formal review", json("[]"), SHA, NOW.minusSeconds(30));
    }

    private static ChainPersistenceRecords.AcceptedResultRecord acceptedResult(
            ChainPersistenceRecords.ReviewDecisionRecord review,
            String transitionId) {
        return new ChainPersistenceRecords.AcceptedResultRecord(
                "accepted-1", "task-1", "event-accepted-result",
                "candidate-result-1", review.reviewDecisionId(), transitionId,
                "content-result", SHA, NOW.minusSeconds(40));
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord readiness(
            ChainPersistenceRecords.ReviewDecisionRecord review,
            String transitionId) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "event-readiness", transitionId,
                SHA, "task-frame-1", "plan-1", "revision-1", 1,
                "step-2", review.reviewDecisionId(), json("[]"), 0,
                null, "NONE", "workspace-1", "NONE", null, null,
                json("[]"),
                io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED,
                SHA, "instruction-1", "NONE", NOW.minusSeconds(50));
    }

    private static ChainPersistenceRecords.PendingItemRecord pendingItem(
            String gapId,
            String sourceProposalId,
            ChainPendingItemType type) {
        return new ChainPersistenceRecords.PendingItemRecord(
                gapId, "task-1", "event-" + gapId, sourceProposalId,
                type, SHA, json("[]"),
                type == ChainPendingItemType.PERMISSION ? "project.write" : null,
                "Provide the missing value", "plain text",
                ChainRole.PLANNER, ChainRole.EXECUTOR,
                json("{}"), SHA, NOW.minusSeconds(70));
    }

    private static ChainPersistenceRecords.PendingItemEventRecord pendingEvent(
            String gapId,
            String eventId,
            ChainPendingItemStatus status) {
        boolean resolved = status == ChainPendingItemStatus.RESOLVED;
        boolean answered = resolved
                || status == ChainPendingItemStatus.RESPONSE_RECEIVED;
        return new ChainPersistenceRecords.PendingItemEventRecord(
                gapId, answered ? 1 : 0, status, "task-1", eventId,
                answered ? "instruction-gap-answer" : null,
                resolved ? "invocation-gap-validation" : null,
                resolved ? io.paperagent.v2.chain.GapValidation.Outcome.RESOLVED : null,
                json("{}"), NOW.minusSeconds(80));
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord failedOutcome() {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "event-outcome", "command-root",
                ChainTaskOutcomeStatus.FAILED, "instruction-1",
                "task-frame-1", "plan-1", "revision-1", json("[]"), json("[]"),
                null, "NONE", "NONE", null, null, null, null,
                json("[\"unfinished\"]"), json("[]"), json("[]"),
                "EXECUTION", "FAILED", "review-1", NOW.plusSeconds(5));
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, ChainRouteCanonical.sha256(value), value);
    }

    private static void assertModel(
            ChainRoleRouter.RoutingResult result,
            ChainRole role,
            ChainWorkState workState) {
        ChainRoleRouter.RoutingResult.ModelCall call = assertInstanceOf(
                ChainRoleRouter.RoutingResult.ModelCall.class, result);
        assertEquals(role, call.role());
        assertEquals(workState, call.workState());
    }

    private record PreparedPersistent(
            Store store,
            ChainInstructionStateReader instructions,
            PlannerPayload.PersistentPlan payload,
            ChainPersistenceRecords.RouteDecisionRecord route) {
    }

    private static final class Store implements
            ChainFoundationRepository, ChainWorkflowRepository,
            ChainFinalizationRepository, ChainModelRepository,
            ChainRouteDecisionWriter, ChainRouteRuntime.ProposalOfficialBinder,
            ChainPlanBindingWriter, ChainPlanCommitPort, StepRoutingAuthority {
        private final ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        "task-1", "command-root", "instruction-1", null,
                        3, 7, 9, 10L, "client-root", SHA,
                        null, null, 0, NOW);
        private final ChainPersistenceRecords.InstructionRecord instruction =
                new ChainPersistenceRecords.InstructionRecord(
                        "instruction-1", "command-root", 7, "task-1", 10L,
                        SHA, "message-1", ChainInstructionRelation.INITIAL,
                        null, null, SHA, NOW);
        private final ChainPersistenceRecords.TaskInstructionBindingRecord binding =
                new ChainPersistenceRecords.TaskInstructionBindingRecord(
                        "task-1", "event-instruction", "instruction-1", 1,
                        ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
        private final List<ChainPersistenceRecords.AuthorityEventRecord> authorityEvents =
                new ArrayList<>(List.of(new ChainPersistenceRecords.AuthorityEventRecord(
                        "event-instruction", "task-1", 1, "INSTRUCTION_BOUND",
                        null, SHA, NOW)));
        private final Map<String, ChainPersistenceRecords.ModelProposalRecord> proposals =
                new LinkedHashMap<>();
        private final Map<String, List<ChainPersistenceRecords.ProposalStateEventRecord>> states =
                new LinkedHashMap<>();
        private final List<ChainPersistenceRecords.RouteDecisionRecord> routes = new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.TransitionRecord> transitions =
                new LinkedHashMap<>();
        private final List<ChainPersistenceRecords.PlanBindingRecord> planBindings = new ArrayList<>();
        private final List<ChainPersistenceRecords.CandidateStepResultRecord> candidates = new ArrayList<>();
        private final List<ChainPersistenceRecords.ReviewDecisionRecord> reviews =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.AcceptedResultRecord> acceptedResults =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.PendingItemRecord> pendingItems =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.PendingItemEventRecord> pendingEvents =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.FinalizationReadinessRecord> readiness =
                new ArrayList<>();
        private final Map<String, ChainTransitionType> completeTransitions =
                new LinkedHashMap<>();
        private ChainPersistenceRecords.TaskOutcomeRecord taskOutcome;
        private StepRoutingAuthority.ActiveStep activeStep;
        private boolean failNextProposalBind;
        private boolean normalizeRouteAuditTimesToMicros;
        private String lastRouteEventType;

        void accept(String proposalId, ChainProposalPayload payload) {
            proposals.put(proposalId, proposal(proposalId, payload));
            states.put(proposalId, new ArrayList<>(List.of(
                    new ChainPersistenceRecords.ProposalStateEventRecord(
                            proposalId, 1, "task-1", "event-accepted-" + proposalId,
                            ChainProposalState.ACCEPTED, null, null, NOW))));
            addAuthority("event-accepted-" + proposalId,
                    "PROPOSAL_ACCEPTED", NOW);
        }

        void addPlanBinding(ChainPersistenceRecords.PlanBindingRecord value) {
            planBindings.add(value);
            addAuthority(value.eventId(), "PLAN_BINDING", value.createdAt());
        }

        void addTransition(ChainPersistenceRecords.TransitionRecord value) {
            transitions.put(value.transitionId(), value);
            addAuthority(value.eventId(), "TRANSITION", value.createdAt());
        }

        void addCandidate(ChainPersistenceRecords.CandidateStepResultRecord value) {
            candidates.add(value);
            addAuthority(value.eventId(), "CANDIDATE_STEP_RESULT", value.createdAt());
        }

        void addReview(ChainPersistenceRecords.ReviewDecisionRecord value) {
            reviews.add(value);
            addAuthority(value.eventId(), "REVIEW_DECISION", value.createdAt());
        }

        void addAcceptedResult(ChainPersistenceRecords.AcceptedResultRecord value) {
            acceptedResults.add(value);
            addAuthority(value.eventId(), "ACCEPTED_RESULT", value.createdAt());
        }

        void addReadiness(ChainPersistenceRecords.FinalizationReadinessRecord value) {
            readiness.add(value);
            addAuthority(value.eventId(), "FINALIZATION_READINESS", value.createdAt());
        }

        void addPendingItem(ChainPersistenceRecords.PendingItemRecord value) {
            pendingItems.add(value);
            addAuthority(value.eventId(), "PENDING_ITEM", value.createdAt());
        }

        void addPendingEvent(ChainPersistenceRecords.PendingItemEventRecord value) {
            pendingEvents.add(value);
            addAuthority(value.eventId(),
                    "PENDING_ITEM_" + value.eventKind().name(),
                    value.committedAt());
        }

        void addTaskOutcome(ChainPersistenceRecords.TaskOutcomeRecord value) {
            taskOutcome = value;
            addAuthority(value.eventId(), "TASK_OUTCOME", value.createdAt());
        }

        void activateStep(
                String planRevisionId,
                String stepId,
                String activationEventId) {
            addAuthority(activationEventId, "STEP_ACTIVATED", NOW.minusSeconds(60));
            activeStep = new StepRoutingAuthority.ActiveStep(
                    "task-1", planRevisionId, stepId, activationEventId,
                    authorityEvents.size());
        }

        void clearActiveStep() {
            activeStep = null;
        }

        void completeTransition(
                String transitionId,
                ChainTransitionType type) {
            completeTransitions.put(transitionId, type);
        }

        private void addAuthority(
                String eventId,
                String eventType,
                Instant committedAt) {
            authorityEvents.add(new ChainPersistenceRecords.AuthorityEventRecord(
                    eventId, "task-1", authorityEvents.size() + 1L,
                    eventType, null, SHA, committedAt));
        }

        @Override public Optional<ChainPersistenceRecords.CommandRecord> findCommand(long a, long b, String c) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.CommandRecord> findCommand(String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.TaskRecord> findTask(String id) { return task.taskId().equals(id) ? Optional.of(task) : Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.InstructionRecord> findInstruction(String id) { return instruction.instructionId().equals(id) ? Optional.of(instruction) : Optional.empty(); }
        @Override public List<ChainPersistenceRecords.TaskInstructionBindingRecord> findTaskInstructions(String id, long cut) { return cut >= 1 ? List.of(binding) : List.of(); }
        @Override public List<ChainPersistenceRecords.AuthorityEventRecord> findAuthorityEvents(String id, long cut) { return authorityEvents.stream().filter(value -> value.eventSequence() <= cut).toList(); }
        @Override public long highestAuthorityEventSequence(String id) { return authorityEvents.size(); }

        @Override public Optional<ChainPersistenceRecords.ModelInvocationRecord> findInvocation(String id) { return Optional.empty(); }
        @Override public long highestInvocationOrdinal(String taskId) { return 0; }
        @Override public List<ChainPersistenceRecords.ModelInvocationRecord> findInvocations(String id, long cut) { return List.of(); }
        @Override public int highestProviderAttemptNo(String invocationId) { return 0; }
        @Override public List<ChainPersistenceRecords.ProviderAttemptRecord> findProviderAttempts(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ContentRecord> findContents(String id) { return List.of(); }
        @Override public Optional<ChainPersistenceRecords.ContentRecord> findContent(String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposal(String id) { return Optional.ofNullable(proposals.get(id)); }
        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposalByInvocation(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.ProposalStateEventRecord> findProposalStateEvents(String id) { return states.getOrDefault(id, List.of()); }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.RouteDecisionRecord> appendRouteDecision(ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.RouteDecisionRecord> requested) {
            lastRouteEventType = requested.event().eventType();
            ChainPersistenceRecords.RouteDecisionRecord existing = routes.stream()
                    .filter(value -> value.routeDecisionId().equals(requested.fact().routeDecisionId()))
                    .findFirst().orElse(null);
            boolean replayed = existing != null;
            if (existing == null) {
                ChainPersistenceRecords.RouteDecisionRecord fact =
                        normalizeRouteAuditTimesToMicros
                                ? normalizeRoute(requested.fact())
                                : requested.fact();
                routes.add(fact);
                addAuthority(requested.event().eventId(),
                        requested.event().eventType(), requested.event().committedAt());
                existing = fact;
            }
            ChainPersistenceRecords.RouteDecisionRecord stored = existing;
            ChainPersistenceRecords.AuthorityEventRecord storedEvent = authorityEvents.stream()
                    .filter(value -> value.eventId().equals(stored.eventId()))
                    .findFirst().orElseThrow();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    storedEvent, stored, replayed);
        }

        private ChainPersistenceRecords.RouteDecisionRecord normalizeRoute(
                ChainPersistenceRecords.RouteDecisionRecord value) {
            return new ChainPersistenceRecords.RouteDecisionRecord(
                    value.routeDecisionId(), value.taskId(), value.eventId(),
                    value.instructionId(), value.proposalId(),
                    value.decisionKind(), value.decisionOrdinal(),
                    value.route(), value.routeReason(),
                    value.directTaskSpecification(), value.userConstraints(),
                    value.answerRequiredRefs(), value.needsTool(),
                    value.needsNetwork(), value.needsProject(),
                    value.needsPersistentProgress(),
                    value.parentRouteDecisionId(), value.escalationReason(),
                    value.transitionId(),
                    value.createdAt().truncatedTo(ChronoUnit.MICROS));
        }

        @Override public void bindOfficialResult(String taskId, String proposalId, String authorityType, String authorityRef) {
            if (failNextProposalBind) {
                failNextProposalBind = false;
                throw new IllegalStateException("injected proposal bind failure");
            }
            List<ChainPersistenceRecords.ProposalStateEventRecord> prefix = states.get(proposalId);
            if (prefix == null) throw new IllegalStateException("proposal is missing");
            if (prefix.size() == 2) {
                ChainPersistenceRecords.ProposalStateEventRecord existing = prefix.get(1);
                if (!authorityType.equals(existing.officialAuthorityType())
                        || !authorityRef.equals(existing.officialAuthorityRef())) {
                    throw new IllegalStateException("conflicting proposal bind replay");
                }
                return;
            }
            prefix.add(new ChainPersistenceRecords.ProposalStateEventRecord(
                    proposalId, 2, taskId, "event-bound-" + proposalId,
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    authorityType, authorityRef, NOW));
            addAuthority("event-bound-" + proposalId,
                    "PROPOSAL_REPLACED_BY_OFFICIAL_RESULT", NOW);
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.PlanBindingRecord> appendPlanBinding(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.PlanBindingRecord> requested) {
            ChainPersistenceRecords.PlanBindingRecord existing = planBindings.stream()
                    .filter(value -> value.planBindingId().equals(
                            requested.fact().planBindingId()))
                    .findFirst().orElse(null);
            boolean replayed = existing != null;
            if (existing == null) {
                planBindings.add(requested.fact());
                ChainPersistenceRecords.AuthorityEventRequest requestedEvent =
                        requested.event();
                authorityEvents.add(
                        new ChainPersistenceRecords.AuthorityEventRecord(
                                requestedEvent.eventId(),
                                requestedEvent.taskId(),
                                authorityEvents.size() + 1L,
                                requestedEvent.eventType(),
                                requestedEvent.transitionId(),
                                requestedEvent.sourceIdentitySha256(),
                                requestedEvent.committedAt()));
                existing = requested.fact();
            }
            ChainPersistenceRecords.PlanBindingRecord stored = existing;
            ChainPersistenceRecords.AuthorityEventRecord event = authorityEvents
                    .stream()
                    .filter(value -> stored.eventId().equals(value.eventId()))
                    .findFirst().orElseThrow();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, stored, replayed);
        }

        @Override public ChainPlanCommitPort.CommittedPlan commitPersistent(
                ChainPlanCommitPort.PersistentPlanCommand command) {
            return new ChainPlanCommitPort.CommittedPlan(
                    command.taskId(), "task-frame-1", "plan-1", "revision-1",
                    1, "STABLE_V2_PLAN", "plan-authority-1", SHA);
        }

        @Override public ChainPlanCommitPort.CommittedPlan commitRevision(
                ChainPlanCommitPort.PlanRevisionCommand command) {
            return new ChainPlanCommitPort.CommittedPlan(
                    command.taskId(), command.taskFrameId(), command.planId(),
                    "revision-2", command.oldPlanRevisionNumber() + 1L,
                    "STABLE_V2_PLAN", "plan-authority-2", SHA);
        }

        @Override public Optional<StepRoutingAuthority.ActiveStep> findActiveStep(String taskId) {
            return Optional.ofNullable(activeStep);
        }

        @Override public boolean isTransitionComplete(String taskId, String transitionId, ChainTransitionType expectedType) {
            return expectedType == completeTransitions.get(transitionId);
        }

        @Override public Optional<ChainPersistenceRecords.TransitionRecord> findTransition(String id) { return Optional.ofNullable(transitions.get(id)); }
        @Override public List<ChainPersistenceRecords.TransitionStageRecord> findTransitionStages(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.TransitionRecord> findIncompleteTransitions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String id) { return List.copyOf(routes); }
        @Override public List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String id) { return List.copyOf(planBindings); }
        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String id) { return List.copyOf(candidates); }
        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String id) { return List.copyOf(reviews); }
        @Override public List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String id) { return List.copyOf(acceptedResults); }
        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String id) { return List.copyOf(pendingItems); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String id) {
            return pendingItems.stream().filter(item -> {
                List<ChainPersistenceRecords.PendingItemEventRecord> events = pendingEvents.stream()
                        .filter(event -> item.gapId().equals(event.gapId())).toList();
                ChainPendingItemStatus status = events.isEmpty()
                        ? ChainPendingItemStatus.PENDING
                        : events.get(events.size() - 1).eventKind();
                return status == ChainPendingItemStatus.PENDING
                        || status == ChainPendingItemStatus.RESPONSE_RECEIVED;
            }).toList();
        }
        @Override public List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String id) { return pendingEvents.stream().filter(value -> id.equals(value.gapId())).toList(); }
        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String id) { return List.of(); }

        @Override public Optional<ChainPersistenceRecords.FinalizationReadinessRecord> findReadinessById(String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.FinalizationReadinessRecord> findReadinessByScope(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.FinalizationReadinessRecord> findReadiness(String id) { return List.copyOf(readiness); }
        @Override public List<ChainPersistenceRecords.FinalizationCheckRecord> findFinalizationChecks(String id) { return List.of(); }
        @Override public Optional<ChainPersistenceRecords.TaskOutcomeRecord> findTaskOutcome(String id) { return Optional.ofNullable(taskOutcome); }
        @Override public List<ChainPersistenceRecords.DeliveryRecord> findDeliveries(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.DeliveryRecord> findIncompleteDeliveries(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.DeliveryEventRecord> findDeliveryEvents(String id) { return List.of(); }
    }
}
