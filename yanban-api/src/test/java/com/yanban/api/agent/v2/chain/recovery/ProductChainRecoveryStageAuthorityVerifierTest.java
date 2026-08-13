package com.yanban.api.agent.v2.chain.recovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.chain.validation.ChainValidationBundleIdentity;
import io.paperagent.v2.chain.validation.ChainValidationIdentity;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductChainRecoveryStageAuthorityVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "0".repeat(64);

    @Test
    void gapStagesBindTypedValidationExactRoundAndOfficialSuccessor() {
        String target = hash("task-1\0gap-1\0" + 1
                + "\0validation-invocation");
        Fixture fixture = fixture(ChainTransitionType.GAP_RESOLUTION,
                "validation-invocation", target);
        var item = pendingItem();
        var response = pendingEvent("response-1", 1,
                ChainPendingItemStatus.RESPONSE_RECEIVED,
                "answer-1", null, null, canonical("{}"));
        when(fixture.workflow.findPendingItems("task-1"))
                .thenReturn(List.of(item));
        when(fixture.workflow.findPendingItemEvents("gap-1"))
                .thenReturn(List.of(response));
        var route = mock(ChainPersistenceRecords.RouteDecisionRecord.class);
        when(route.taskId()).thenReturn("task-1");
        when(route.routeDecisionId()).thenReturn("route-1");
        when(fixture.workflow.findRouteDecisions("task-1"))
                .thenReturn(List.of(route));
        var payload = new PlannerPayload.DirectRoute(
                "resolved", "continue", List.of(), List.of(),
                false, false, false, false,
                new GapValidation("gap-1", List.of(
                        new GapValidation.Check(
                                "answer supplied", true, "answer-1")),
                        GapValidation.Outcome.RESOLVED));
        String payloadJson = json(payload);
        var invocation = invocation("validation-invocation",
                ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM);
        var proposal = proposal("validation-proposal",
                "validation-invocation", payload.kind(),
                canonical(payloadJson));
        var accepted = proposalState("validation-proposal", 1,
                ChainProposalState.ACCEPTED, null, null);
        when(fixture.models.findInvocation("validation-invocation"))
                .thenReturn(Optional.of(invocation));
        when(fixture.models.findProposalByInvocation(
                "validation-invocation")).thenReturn(Optional.of(proposal));
        when(fixture.models.findProposalStateEvents("validation-proposal"))
                .thenReturn(List.of(accepted));
        var normal = fixture.stage(
                ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED, 1,
                null, null, "ROUTE_DECISION", "route-1");
        assertDoesNotThrow(() -> fixture.verify(normal));

        var disposition = mock(ChainPersistenceRecords
                .InstructionDispositionRecord.class);
        when(disposition.taskId()).thenReturn("task-1");
        when(disposition.dispositionId()).thenReturn("disposition-1");
        when(fixture.workflow.findInstructionDispositions("task-1"))
                .thenReturn(List.of(disposition));
        var dispositionSuccessor = fixture.stage(
                ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED, 1,
                null, null, "INSTRUCTION_DISPOSITION", "disposition-1");
        assertDoesNotThrow(() -> fixture.verify(dispositionSuccessor));

        var bound = proposalState("validation-proposal", 2,
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "ROUTE_DECISION", "route-1");
        var resolved = pendingEvent("resolved-1", 1,
                ChainPendingItemStatus.RESOLVED, "answer-1",
                "validation-invocation", GapValidation.Outcome.RESOLVED,
                canonical("{\"successorAuthorityRef\":\"route-1\"}"));
        when(fixture.models.findProposalStateEvents("validation-proposal"))
                .thenReturn(List.of(accepted, bound));
        when(fixture.workflow.findPendingItemEvents("gap-1"))
                .thenReturn(List.of(response, resolved));
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId()))
                .thenReturn(List.of(normal));
        var resolvedStage = fixture.stage(
                ChainTransitionStage.PENDING_RESOLVED, 2,
                null, null, "PENDING_ITEM_EVENT", "resolved-1");
        assertDoesNotThrow(() -> fixture.verify(resolvedStage));

        var roundDrift = pendingEvent("resolved-drift", 2,
                ChainPendingItemStatus.RESOLVED, "answer-1",
                "validation-invocation", GapValidation.Outcome.RESOLVED,
                canonical("{\"successorAuthorityRef\":\"route-1\"}"));
        when(fixture.workflow.findPendingItemEvents("gap-1"))
                .thenReturn(List.of(response, roundDrift));
        assertThrows(IllegalStateException.class, () -> fixture.verify(
                fixture.stage(ChainTransitionStage.PENDING_RESOLVED, 2,
                        null, null, "PENDING_ITEM_EVENT",
                        "resolved-drift")));
    }

    @Test
    void acceptStepActivatesOnlyTheFormalNextStep() {
        String digest = acceptedDigest();
        Fixture fixture = fixture(
                ChainTransitionType.ACCEPT_STEP, "review-1", digest);
        var candidate = candidate();
        var review = review(ChainProposalKind.REFLECTOR_ACCEPT_STEP);
        var accepted = accepted(fixture.transition.transitionId(), digest);
        when(fixture.workflow.findCandidateStepResults("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task-1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findAcceptedResults("task-1"))
                .thenReturn(List.of(accepted));
        var acceptedStage = fixture.stage(
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED, 1,
                null, null, "ACCEPTED_RESULT", "accepted-1");
        var completionStage = fixture.stage(
                ChainTransitionStage.STEP_COMPLETED, 3,
                null, null, "STEP_EVENT", "completion-1");
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId()))
                .thenReturn(List.of(acceptedStage, completionStage));
        var plan = plan(List.of(
                new ChainStepAuthorityPort.StepDefinition(
                        "step-1", 1, Set.of()),
                new ChainStepAuthorityPort.StepDefinition(
                        "step-2", 2, Set.of("step-1")),
                new ChainStepAuthorityPort.StepDefinition(
                        "step-3", 3, Set.of("step-2"))));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(plan));
        String activation2 = "step.activation." + hash(
                "task-1\0revision-1\0step-2\0"
                        + fixture.transition.transitionId());
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(
                        stepEvent("activation-1", "step-1", "activation-1",
                                ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                                "initial-source", "initial-transition", 1),
                        stepEvent("completion-1", "step-1", "activation-1",
                                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                                "review-1", fixture.transition.transitionId(), 2),
                        stepEvent(activation2, "step-2", activation2,
                                ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                                "review-1", fixture.transition.transitionId(), 3),
                        stepEvent("completion-2", "step-2", activation2,
                                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                                "review-2", "later-transition", 4),
                        stepEvent("activation-3", "step-3", "activation-3",
                                ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                                "review-2", "later-transition", 5)));
        var next = fixture.stage(
                ChainTransitionStage.NEXT_STEP_ACTIVATED_OR_NONE, 4,
                null, null, "STEP_EVENT", activation2);
        assertDoesNotThrow(() -> fixture.verify(next));
        assertThrows(IllegalStateException.class, () -> fixture.verify(
                fixture.stage(
                        ChainTransitionStage.NEXT_STEP_ACTIVATED_OR_NONE, 4,
                        null, null, "STEP_EVENT", "another-event")));
    }

    @Test
    void planChangeBindsPlannerPlanAndFormalFirstStep() {
        Fixture fixture = fixture(
                ChainTransitionType.PLAN_CHANGE, "route-1", HASH);
        String proposalId = "planner-proposal";
        String bindingId = "plan-binding." + hash(
                "task-1\0instruction-1\0" + proposalId
                        + "\0frame-1\0plan-1\0revision-1\0"
                        + fixture.transition.transitionId());
        var binding = new ChainPersistenceRecords.PlanBindingRecord(
                bindingId, "task-1", "binding-event", "instruction-1",
                "route-1", "frame-1", "plan-1", "revision-1", 1,
                "STABLE_V2_PLAN", "revision-1", HASH,
                fixture.transition.transitionId(), NOW);
        when(fixture.workflow.findPlanBindings("task-1"))
                .thenReturn(List.of(binding));
        var snapshot = plan(List.of(new ChainStepAuthorityPort.StepDefinition(
                "step-1", 1, Set.of())));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(snapshot));
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        TaskFrame frame = mock(TaskFrame.class);
        Plan stablePlan = mock(Plan.class);
        PlanRevision revision = mock(PlanRevision.class);
        when(frame.id()).thenReturn(new TaskFrameId("frame-1"));
        when(stablePlan.id()).thenReturn(new PlanId("plan-1"));
        when(stablePlan.revisions()).thenReturn(List.of(revision));
        when(revision.id()).thenReturn(new PlanRevisionId("revision-1"));
        when(revision.number()).thenReturn(1L);
        when(bootstrap.taskFrame()).thenReturn(frame);
        when(bootstrap.plan()).thenReturn(stablePlan);
        when(fixture.bootstraps.find(new PlanId("plan-1")))
                .thenReturn(Optional.of(bootstrap));
        when(fixture.codec.encode(bootstrap)).thenReturn(
                new ProductPlanBootstrapCodec.EncodedPayload(1, HASH, "{}"));
        var route = mock(ChainPersistenceRecords.RouteDecisionRecord.class);
        when(route.taskId()).thenReturn("task-1");
        when(route.routeDecisionId()).thenReturn("route-1");
        when(route.proposalId()).thenReturn(proposalId);
        when(route.route()).thenReturn(
                ChainExecutionMode.PERSISTENT_PLAN_EXECUTE);
        when(route.decisionKind()).thenReturn(
                ChainPersistenceRecords.RouteDecisionType.INITIAL);
        when(fixture.workflow.findRouteDecisions("task-1"))
                .thenReturn(List.of(route));
        var proposal = proposal(proposalId, "planner-invocation",
                ChainProposalKind.PLANNER_PERSISTENT_PLAN, canonical("{}"));
        when(fixture.models.findProposal(proposalId))
                .thenReturn(Optional.of(proposal));
        when(fixture.models.findInvocation("planner-invocation"))
                .thenReturn(Optional.of(invocation("planner-invocation",
                        ChainRole.PLANNER, ChainWorkState.PLANNING)));
        when(fixture.models.findProposalStateEvents(proposalId)).thenReturn(
                List.of(proposalState(proposalId, 1,
                                ChainProposalState.ACCEPTED, null, null),
                        proposalState(proposalId, 2,
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "ROUTE_DECISION", "route-1")));
        var event = new ChainPersistenceRecords.AuthorityEventRecord(
                "binding-event", "task-1", 7, "PLAN_BINDING",
                fixture.transition.transitionId(), HASH, NOW);
        when(fixture.foundations.highestAuthorityEventSequence("task-1"))
                .thenReturn(7L);
        when(fixture.foundations.findAuthorityEvents("task-1", 7L))
                .thenReturn(List.of(event));
        var planStage = fixture.stage(
                ChainTransitionStage.TASKFRAME_PLAN_COMMITTED, 1,
                null, null, "PLAN_BINDING", bindingId);
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId()))
                .thenReturn(List.of(planStage));
        assertDoesNotThrow(() -> fixture.verify(planStage));

        String activation = "step.activation." + hash(
                "task-1\0revision-1\0step-1\0"
                        + fixture.transition.transitionId());
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(stepEvent(activation, "step-1", activation,
                        ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                        "route-1", fixture.transition.transitionId(), 1)));
        var newStep = fixture.stage(ChainTransitionStage.NEW_STEP_ACTIVATED, 4,
                null, null, "STEP_EVENT", activation);
        assertDoesNotThrow(() -> fixture.verify(newStep));

        when(fixture.codec.encode(bootstrap)).thenReturn(
                new ProductPlanBootstrapCodec.EncodedPayload(
                        1, "1".repeat(64), "{}"));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(planStage));
    }

    @Test
    void readinessCommitBindsCompletedPlanAcceptedSetAndApplicabilityCut() {
        String digest = acceptedDigest();
        Fixture fixture = fixture(ChainTransitionType.FINAL_STEP_READINESS,
                "review-1", digest);
        fixture.requirements(TaskRequirements.explicit(List.of(),
                io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        var candidate = candidate();
        var review = review(ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE);
        var accepted = accepted(fixture.transition.transitionId(), digest);
        when(fixture.workflow.findCandidateStepResults("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task-1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findAcceptedResults("task-1"))
                .thenReturn(List.of(accepted));
        var applicability = applicability(fixture.transition.transitionId());
        when(fixture.workflow.findApplicabilityDecisions("task-1"))
                .thenReturn(List.of(applicability));
        var acceptedStage = fixture.stage(
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED_OR_VERIFIED, 1,
                null, null, "ACCEPTED_RESULT", "accepted-1");
        var applicabilityStage = fixture.stage(
                ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY, 2,
                null, null, "RESULT_APPLICABILITY", "applicability-1");
        var completionStage = fixture.stage(
                ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED, 3,
                null, null, "STEP_EVENT", "completion-1");
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId())).thenReturn(
                List.of(acceptedStage, applicabilityStage, completionStage));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(plan(List.of(
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-1", 1, Set.of())))));
        var activationEvent = stepEvent(
                "activation-1", "step-1", "activation-1",
                ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "initial", "initial-transition", 1);
        var completedEvent = stepEvent(
                "completion-1", "step-1", "activation-1",
                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                "review-1", fixture.transition.transitionId(), 2);
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(activationEvent, completedEvent));
        var applicabilityEvent = new ChainPersistenceRecords
                .AuthorityEventRecord("applicability-event", "task-1", 9,
                "RESULT_APPLICABILITY", fixture.transition.transitionId(),
                hash("accepted-1\0ACCEPT_STEP\0"
                        + fixture.transition.transitionId()
                        + "\0frame-1\0plan-1\0revision-1"
                        + "\0candidate-key\0instruction-1"), NOW);
        when(fixture.foundations.highestAuthorityEventSequence("task-1"))
                .thenReturn(10L);
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(applicabilityEvent));
        var readiness = readiness(
                fixture.transition.transitionId(), 9, canonical(
                        "[\"accepted-1\"]"));
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(readiness));
        var committed = fixture.stage(
                ChainTransitionStage.READINESS_COMMITTED, 4,
                null, null, "FINALIZATION_READINESS", "readiness-1");
        assertDoesNotThrow(() -> fixture.verify(committed));
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId())).thenReturn(
                List.of(acceptedStage, applicabilityStage, completionStage,
                        committed));
        assertDoesNotThrow(() -> fixture.verifier.requireExact(readiness));

        var drift = readiness(fixture.transition.transitionId(), 9,
                canonical("[]"));
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(drift));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
    }

    @Test
    void readinessRecoveryRequiresExactPlanValidationBundleAuthority() {
        String digest = acceptedDigest();
        Fixture fixture = fixture(ChainTransitionType.FINAL_STEP_READINESS,
                "review-1", digest);
        fixture.projectlessTask();
        var requirement = new ValidationRequirement("run-proof",
                ValidationSubject.ACTION_RECEIPT, "run verified");
        fixture.requirements(TaskRequirements.explicit(List.of(requirement),
                io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        var candidate = candidate();
        var review = review(ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE);
        var accepted = accepted(fixture.transition.transitionId(), digest);
        when(fixture.workflow.findCandidateStepResults("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task-1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findAcceptedResults("task-1"))
                .thenReturn(List.of(accepted));
        when(fixture.workflow.findApplicabilityDecisions("task-1"))
                .thenReturn(List.of());
        var acceptedStage = fixture.stage(
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED_OR_VERIFIED, 1,
                null, null, "ACCEPTED_RESULT", "accepted-1");
        var applicabilityStage = fixture.stage(
                ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY, 2,
                null, null, null, null);
        var completionStage = fixture.stage(
                ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED, 3,
                null, null, "STEP_EVENT", "completion-1");
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId())).thenReturn(
                List.of(acceptedStage, applicabilityStage, completionStage));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(plan(List.of(
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-1", 1, Set.of())))));
        var memberActivation = stepEvent(
                "activation-1", "step-1", "activation-1",
                ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "initial", "initial-transition", 1);
        var memberCompletion = stepEvent(
                "completion-1", "step-1", "activation-1",
                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                "review-1", fixture.transition.transitionId(), 2);
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(memberActivation, memberCompletion));
        String requirementDigest = ChainValidationIdentity.requirementDigest(
                requirement);
        var actionItem = new ChainPersistenceRecords
                .ActionReceiptValidationItemRecord(
                "validation-set-1", "run-proof", "task-1",
                requirementDigest, "action-1", "receipt-1",
                "8".repeat(64), "9".repeat(64),
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED);
        var setScope = new ChainValidationIdentity.SetScope(
                "task-1", "frame-1", "plan-1", "revision-1", 1,
                "step-1", "activation-1");
        String setRequest = ChainValidationIdentity.requestDigest(
                setScope, List.of(new ChainValidationIdentity.RequestIdentity(
                        "run-proof", requirementDigest,
                        ValidationSubject.ACTION_RECEIPT,
                        ChainValidationIdentity.actionSubject(actionItem))));
        String setReceipts = ChainValidationIdentity.receiptSetDigest(List.of(
                new ChainValidationIdentity.ReceiptIdentity(
                        "run-proof", "receipt-1", "8".repeat(64))));
        String setConclusion = ChainValidationIdentity.conclusionDigest(List.of(
                new ChainValidationIdentity.ConclusionIdentity(
                        "run-proof", io.paperagent.v2.chain
                        .ChainValidationConclusion.PASSED)));
        var scope = new ChainValidationBundleIdentity.Scope(
                "task-1", "frame-1", "plan-1", "revision-1", 1,
                "instruction-1", "step-1");
        var memberIdentity = new ChainValidationBundleIdentity.Member(
                "step-1", "validation-set-1", setRequest,
                setReceipts, setConclusion);
        var aggregate = ChainValidationBundleIdentity.aggregate(
                scope, List.of(memberIdentity));
        String request = aggregate.requestDigest();
        String receipts = aggregate.receiptSetDigest();
        String conclusion = aggregate.conclusionDigest();
        String bundleId = ChainValidationBundleIdentity.bundleId(
                scope, aggregate);
        var bundle = validationBundle(bundleId, "frame-1", request, receipts,
                conclusion);
        var member = validationBundleMember(bundleId, "task-1",
                setRequest, setReceipts, setConclusion);
        var validation = validationSet(
                setRequest, setReceipts, setConclusion);
        var event = new ChainPersistenceRecords.AuthorityEventRecord(
                bundle.eventId(), "task-1", 10, "VALIDATION_BUNDLE", null,
                ChainValidationBundleIdentity.eventSourceIdentity(
                        bundleId, aggregate), NOW);
        var validationEvent = new ChainPersistenceRecords.AuthorityEventRecord(
                validation.eventId(), "task-1", 9, "VALIDATION", null,
                hash(validation.validationId() + "\0"
                        + validation.requestDigest() + "\0"
                        + validation.receiptSetDigest() + "\0"
                        + validation.conclusionDigest()), NOW);
        when(fixture.foundations.highestAuthorityEventSequence("task-1"))
                .thenReturn(10L);
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(validationEvent, event));
        when(fixture.validationBundles.findBundle(bundleId))
                .thenReturn(Optional.of(bundle));
        when(fixture.validationBundles.findBundleSets(bundleId))
                .thenReturn(List.of(member));
        when(fixture.validations.findValidation("validation-set-1"))
                .thenReturn(Optional.of(validation));
        when(fixture.validations.findCandidateItems("validation-set-1"))
                .thenReturn(List.of());
        when(fixture.validations.findActionReceiptItems("validation-set-1"))
                .thenReturn(List.of(actionItem));
        var revision = mock(PlanRevision.class);
        var revisionStep = mock(io.paperagent.v2.contracts.PlanStep.class);
        when(revision.id()).thenReturn(new PlanRevisionId("revision-1"));
        when(revision.taskFrameId()).thenReturn(new TaskFrameId("frame-1"));
        when(revision.number()).thenReturn(1L);
        when(revisionStep.id()).thenReturn(
                new io.paperagent.v2.contracts.PlanStepId("step-1"));
        when(revisionStep.validationRequirementIds())
                .thenReturn(List.of("run-proof"));
        when(revision.steps()).thenReturn(List.of(revisionStep));
        when(fixture.steps.findPlanRevision("task-1", "revision-1"))
                .thenReturn(Optional.of(revision));
        var readiness = readinessBundle(fixture.transition.transitionId(),
                bundleId, request, receipts);
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(readiness));
        var committed = fixture.stage(
                ChainTransitionStage.READINESS_COMMITTED, 4,
                null, null, "FINALIZATION_READINESS", "readiness-1");
        assertDoesNotThrow(() -> fixture.verify(committed));
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId())).thenReturn(
                List.of(acceptedStage, applicabilityStage, completionStage,
                        committed));
        assertDoesNotThrow(() -> fixture.verifier.requireExact(readiness));

        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(memberActivation, memberCompletion,
                        stepEvent("completion-duplicate", "step-1",
                                "activation-1", ChainStepAuthorityPort
                                .StepEventKind.COMPLETED, "review-1",
                                fixture.transition.transitionId(), 3)));
        assertThrows(io.paperagent.v2.chain.step.ChainStepException.class,
                () -> fixture.verify(committed));
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(memberActivation, memberCompletion,
                        stepEvent("superseded-1", "step-1", "activation-1",
                                ChainStepAuthorityPort.StepEventKind
                                        .SUPERSEDED_BY_REPLAN,
                                "review-1", fixture.transition.transitionId(),
                                3)));
        assertThrows(io.paperagent.v2.chain.step.ChainStepException.class,
                () -> fixture.verify(committed));
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(memberActivation, memberCompletion));

        when(fixture.validationBundles.findBundle(bundleId))
                .thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        assertThrows(IllegalStateException.class,
                () -> fixture.verifier.requireExact(readiness));
        when(fixture.validationBundles.findBundle(bundleId))
                .thenReturn(Optional.of(bundle));
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(validationEvent));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(validationEvent, event));
        when(fixture.validationBundles.findBundleSets(bundleId))
                .thenReturn(List.of());
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.validationBundles.findBundleSets(bundleId))
                .thenReturn(List.of(validationBundleMember(
                        bundleId, "task-other", setRequest,
                        setReceipts, setConclusion)));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.validationBundles.findBundleSets(bundleId))
                .thenReturn(List.of(member));
        when(fixture.validationBundles.findBundle(bundleId))
                .thenReturn(Optional.of(validationBundle(
                        bundleId, "frame-other", request, receipts,
                        conclusion)));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.validationBundles.findBundle(bundleId))
                .thenReturn(Optional.of(bundle));
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(readinessBundle(
                        fixture.transition.transitionId(), bundleId,
                        "4".repeat(64), receipts)));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(readiness));
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(event));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(validationEvent, event));
        when(fixture.validationBundles.findBundleSets(bundleId))
                .thenReturn(List.of(new ChainPersistenceRecords
                        .ValidationBundleSetRecord(
                        bundleId, "task-1", "step-1", "activation-1",
                        "validation-set-1", "a".repeat(64), setReceipts,
                        setConclusion)));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.validationBundles.findBundleSets(bundleId))
                .thenReturn(List.of(member));
        var payloadDrift = new ChainPersistenceRecords
                .ActionReceiptValidationItemRecord(
                "validation-set-1", "run-proof", "task-1",
                requirementDigest, "action-1", "receipt-1",
                "a".repeat(64), "9".repeat(64),
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED);
        when(fixture.validations.findActionReceiptItems("validation-set-1"))
                .thenReturn(List.of(payloadDrift));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        var requirementDrift = new ChainPersistenceRecords
                .ActionReceiptValidationItemRecord(
                "validation-set-1", "run-proof", "task-1",
                "b".repeat(64), "action-1", "receipt-1",
                "8".repeat(64), "9".repeat(64),
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED);
        when(fixture.validations.findActionReceiptItems("validation-set-1"))
                .thenReturn(List.of(requirementDrift));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
        when(fixture.validations.findActionReceiptItems("validation-set-1"))
                .thenReturn(List.of(actionItem));
        var previousRequirement = new ValidationRequirement(
                "previous-proof", ValidationSubject.ACTION_RECEIPT,
                "previous verified");
        fixture.requirements(TaskRequirements.explicit(
                List.of(previousRequirement, requirement),
                io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        var previousStep = mock(io.paperagent.v2.contracts.PlanStep.class);
        when(previousStep.id()).thenReturn(
                new io.paperagent.v2.contracts.PlanStepId("step-0"));
        when(previousStep.validationRequirementIds())
                .thenReturn(List.of("previous-proof"));
        when(revision.steps()).thenReturn(List.of(previousStep, revisionStep));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));

        var candidateRequirement = new ValidationRequirement(
                "run-proof", ValidationSubject.CANDIDATE,
                "run verified");
        fixture.requirements(TaskRequirements.explicit(
                List.of(candidateRequirement),
                io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        when(revision.steps()).thenReturn(List.of(revisionStep));
        fixture.projectTask();
        when(candidate.artifactId()).thenReturn(101L);
        when(candidate.candidateFingerprint()).thenReturn("c".repeat(64));
        var workspace = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "candidate-key", "task-1", "workspace-event-1",
                "candidate-action-1", "workspace-1", "project-version-1",
                101, "c".repeat(64), "d".repeat(64), "e".repeat(64), NOW);
        when(fixture.workflow.findWorkspaceCandidates("task-1"))
                .thenReturn(List.of(workspace));
        String candidateRequirementDigest = ChainValidationIdentity
                .requirementDigest(candidateRequirement);
        var candidateItem = candidateValidationItem(
                candidateRequirementDigest, "candidate-key", "c".repeat(64));
        String candidateSetRequest = ChainValidationIdentity.requestDigest(
                setScope, List.of(new ChainValidationIdentity.RequestIdentity(
                        "run-proof", candidateRequirementDigest,
                        ValidationSubject.CANDIDATE,
                        ChainValidationIdentity.candidateSubject(
                                candidateItem))));
        String candidateSetReceipts = ChainValidationIdentity
                .receiptSetDigest(List.of(
                        new ChainValidationIdentity.ReceiptIdentity(
                                "run-proof", "receipt-1", "8".repeat(64))));
        String candidateSetConclusion = ChainValidationIdentity
                .conclusionDigest(List.of(
                        new ChainValidationIdentity.ConclusionIdentity(
                                "run-proof", io.paperagent.v2.chain
                                .ChainValidationConclusion.PASSED)));
        var candidateMemberIdentity = new ChainValidationBundleIdentity.Member(
                "step-1", "validation-set-1", candidateSetRequest,
                candidateSetReceipts, candidateSetConclusion);
        var candidateAggregate = ChainValidationBundleIdentity.aggregate(
                scope, List.of(candidateMemberIdentity));
        String candidateBundleId = ChainValidationBundleIdentity.bundleId(
                scope, candidateAggregate);
        var candidateBundle = validationBundle(candidateBundleId, "frame-1",
                candidateAggregate.requestDigest(),
                candidateAggregate.receiptSetDigest(),
                candidateAggregate.conclusionDigest());
        var candidateMember = validationBundleMember(
                candidateBundleId, "task-1", candidateSetRequest,
                candidateSetReceipts, candidateSetConclusion);
        var candidateSet = validationSet(candidateSetRequest,
                candidateSetReceipts, candidateSetConclusion);
        var candidateBundleEvent = new ChainPersistenceRecords
                .AuthorityEventRecord(
                candidateBundle.eventId(), "task-1", 10,
                "VALIDATION_BUNDLE", null,
                ChainValidationBundleIdentity.eventSourceIdentity(
                        candidateBundleId, candidateAggregate), NOW);
        var candidateSetEvent = new ChainPersistenceRecords
                .AuthorityEventRecord(
                candidateSet.eventId(), "task-1", 9, "VALIDATION", null,
                hash(candidateSet.validationId() + "\0"
                        + candidateSet.requestDigest() + "\0"
                        + candidateSet.receiptSetDigest() + "\0"
                        + candidateSet.conclusionDigest()), NOW);
        when(fixture.foundations.findAuthorityEvents("task-1", 10L))
                .thenReturn(List.of(candidateSetEvent, candidateBundleEvent));
        when(fixture.validationBundles.findBundle(candidateBundleId))
                .thenReturn(Optional.of(candidateBundle));
        when(fixture.validationBundles.findBundleSets(candidateBundleId))
                .thenReturn(List.of(candidateMember));
        when(fixture.validations.findValidation("validation-set-1"))
                .thenReturn(Optional.of(candidateSet));
        when(fixture.validations.findActionReceiptItems("validation-set-1"))
                .thenReturn(List.of());
        when(fixture.validations.findCandidateItems("validation-set-1"))
                .thenReturn(List.of(candidateItem));
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(readinessCandidateBundle(
                        fixture.transition.transitionId(), candidateBundleId,
                        candidateAggregate.requestDigest(),
                        candidateAggregate.receiptSetDigest())));
        assertDoesNotThrow(() -> fixture.verify(committed));
        when(fixture.validations.findCandidateItems("validation-set-1"))
                .thenReturn(List.of(candidateValidationItem(
                        candidateRequirementDigest, "candidate-key",
                        "f".repeat(64))));
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(committed));
    }

    @Test
    void finalizationCheckAndNoPublishReuseExactRecoveryBinding() {
        String acceptedDigest = acceptedDigest();
        var predecessor = transition(ChainTransitionType.FINAL_STEP_READINESS,
                "review-1", acceptedDigest);
        var readiness = readiness(predecessor.transitionId(), 0,
                canonical("[\"accepted-1\"]"));
        String target = ProductChainFinalizationRecoverySource
                .readinessTargetDigest(readiness);
        Fixture fixture = fixture(
                ChainTransitionType.FINALIZATION, "review-1", target);
        var candidate = candidate();
        var review = review(ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE);
        when(fixture.workflow.findTransition(predecessor.transitionId()))
                .thenReturn(Optional.of(predecessor));
        var accepted = accepted(predecessor.transitionId(), acceptedDigest);
        when(fixture.workflow.findAcceptedResults("task-1"))
                .thenReturn(List.of(accepted));
        when(fixture.workflow.findCandidateStepResults("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task-1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findApplicabilityDecisions("task-1"))
                .thenReturn(List.of());
        fixture.requirements(TaskRequirements.explicit(List.of(),
                io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(plan(List.of(
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-1", 1, Set.of())))));
        String completionId = "step.completed." + hash(
                "task-1\0revision-1\0step-1\0activation-1\0"
                        + predecessor.transitionId());
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(
                        stepEvent("activation-1", "step-1", "activation-1",
                                ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                                "initial", "initial-transition", 1),
                        stepEvent(completionId, "step-1", "activation-1",
                                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                                "review-1", predecessor.transitionId(), 2)));
        when(fixture.workflow.findTransitionStages(
                predecessor.transitionId())).thenReturn(
                readinessPrefix(predecessor, readiness));
        when(fixture.finalization.findReadiness("task-1"))
                .thenReturn(List.of(readiness));
        when(fixture.finalization.findReadinessById("readiness-1"))
                .thenReturn(Optional.of(readiness));
        var check = finalizationCheck(
                fixture.transition.transitionId(), readiness);
        when(fixture.finalization.findFinalizationChecks("readiness-1"))
                .thenReturn(List.of(check));
        var checkStage = fixture.stage(
                ChainTransitionStage.FINALIZATION_CHECK_COMMITTED, 2,
                null, null, "FINALIZATION_CHECK", "check-1");
        when(fixture.workflow.findTransitionStages(
                fixture.transition.transitionId()))
                .thenReturn(List.of(checkStage));
        assertDoesNotThrow(() -> fixture.verify(checkStage));
        var noPublish = fixture.stage(
                ChainTransitionStage.PUBLISH_COMMITTED_OR_NOT_REQUIRED, 3,
                null, null, null, null);
        assertDoesNotThrow(() -> fixture.verify(noPublish));

        when(check.instructionId()).thenReturn("wrong-instruction");
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(noPublish));
    }

    @Test
    void crossTaskAndUnknownAuthorityFailClosed() {
        Fixture fixture = fixture(
                ChainTransitionType.ACCEPT_STEP, "review-1", HASH);
        var crossTask = new ChainPersistenceRecords.TransitionStageRecord(
                fixture.transition.transitionId(),
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED,
                "foreign-task", "stage-event", 1,
                null, null, "ACCEPTED_RESULT", "accepted-1", NOW);
        assertThrows(IllegalStateException.class,
                () -> fixture.verify(crossTask));
        assertThrows(IllegalStateException.class, () -> fixture.verify(
                fixture.stage(ChainTransitionStage.ACCEPTED_RESULT_COMMITTED,
                        1, null, null, "UNKNOWN", "unknown")));
    }

    private static ChainPersistenceRecords.PendingItemRecord pendingItem() {
        return new ChainPersistenceRecords.PendingItemRecord(
                "gap-1", "task-1", "gap-event", "source-proposal",
                ChainPendingItemType.USER_INFORMATION, HASH,
                canonical("[\"answer\"]"), null, "question", "text",
                ChainRole.PLANNER, ChainRole.EXECUTOR,
                canonical("{}"), HASH, NOW);
    }

    private static ChainPersistenceRecords.PendingItemEventRecord pendingEvent(
            String eventId, int round, ChainPendingItemStatus kind,
            String answer, String invocation, GapValidation.Outcome outcome,
            ChainPersistenceRecords.CanonicalJson detail) {
        return new ChainPersistenceRecords.PendingItemEventRecord(
                "gap-1", round, kind, "task-1", eventId,
                answer, invocation, outcome, detail, NOW);
    }

    private static ChainPersistenceRecords.ModelInvocationRecord invocation(
            String id, ChainRole role, ChainWorkState state) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                id, "task-1", "context-1", "token-1", role, state,
                "reason", "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), NOW);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            String id, String invocationId, ChainProposalKind kind,
            ChainPersistenceRecords.CanonicalJson payload) {
        return new ChainPersistenceRecords.ModelProposalRecord(
                id, "task-1", invocationId, 1, kind.role(), kind,
                payload, canonical("[]"), null, null, NOW);
    }

    private static ChainPersistenceRecords.ProposalStateEventRecord
            proposalState(String proposalId, long sequence,
                    ChainProposalState state, String type, String ref) {
        return new ChainPersistenceRecords.ProposalStateEventRecord(
                proposalId, sequence, "task-1",
                "state-" + proposalId + "-" + sequence,
                state, type, ref, NOW);
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidate() {
        var value = mock(
                ChainPersistenceRecords.CandidateStepResultRecord.class);
        when(value.taskId()).thenReturn("task-1");
        when(value.candidateResultId()).thenReturn("candidate-1");
        when(value.contentId()).thenReturn("content-1");
        when(value.instructionId()).thenReturn("instruction-1");
        when(value.taskFrameId()).thenReturn("frame-1");
        when(value.planId()).thenReturn("plan-1");
        when(value.planRevisionId()).thenReturn("revision-1");
        when(value.planRevisionNumber()).thenReturn(1L);
        when(value.stepId()).thenReturn("step-1");
        when(value.activationEventId()).thenReturn("activation-1");
        // This fixture intentionally represents an authority-free result.
        // Mockito supplies boxed numeric defaults, so make the null identity
        // explicit rather than accidentally modeling a workspace candidate.
        when(value.artifactId()).thenReturn(null);
        when(value.validationId()).thenReturn(null);
        when(value.validationRequestDigest()).thenReturn(null);
        when(value.validationReceiptDigest()).thenReturn(null);
        when(value.evidenceRefs()).thenReturn(canonical("[]"));
        return value;
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord review(
            ChainProposalKind kind) {
        var value = mock(ChainPersistenceRecords.ReviewDecisionRecord.class);
        when(value.taskId()).thenReturn("task-1");
        when(value.reviewDecisionId()).thenReturn("review-1");
        when(value.reviewObjectType()).thenReturn("CANDIDATE_STEP_RESULT");
        when(value.reviewObjectId()).thenReturn("candidate-1");
        when(value.decisionKind()).thenReturn(kind);
        when(value.factRefs()).thenReturn(canonical("{}"));
        return value;
    }

    private static ChainPersistenceRecords.AcceptedResultRecord accepted(
            String transitionId, String digest) {
        var value = mock(ChainPersistenceRecords.AcceptedResultRecord.class);
        when(value.taskId()).thenReturn("task-1");
        when(value.acceptedResultId()).thenReturn("accepted-1");
        when(value.candidateResultId()).thenReturn("candidate-1");
        when(value.reviewDecisionId()).thenReturn("review-1");
        when(value.transitionId()).thenReturn(transitionId);
        when(value.contentId()).thenReturn("content-1");
        when(value.acceptedIdentitySha256()).thenReturn(digest);
        return value;
    }

    private static ChainPersistenceRecords.ResultApplicabilityRecord
            applicability(String transitionId) {
        var value = mock(
                ChainPersistenceRecords.ResultApplicabilityRecord.class);
        when(value.applicabilityId()).thenReturn("applicability-1");
        when(value.taskId()).thenReturn("task-1");
        when(value.eventId()).thenReturn("applicability-event");
        when(value.acceptedResultId()).thenReturn("accepted-1");
        when(value.sourceType()).thenReturn(
                ChainApplicability.SourceType.ACCEPT_STEP);
        when(value.sourceDecisionId()).thenReturn(transitionId);
        when(value.targetTaskFrameId()).thenReturn("frame-1");
        when(value.targetPlanId()).thenReturn("plan-1");
        when(value.targetPlanRevisionId()).thenReturn("revision-1");
        when(value.targetCandidateKey()).thenReturn("candidate-key");
        when(value.targetInstructionVersionId()).thenReturn("instruction-1");
        when(value.conclusion()).thenReturn(
                ChainApplicability.Outcome.APPLICABLE);
        return value;
    }

    private static ChainStepAuthorityPort.PlanSnapshot plan(
            List<ChainStepAuthorityPort.StepDefinition> definitions) {
        return new ChainStepAuthorityPort.PlanSnapshot(
                "task-1", "frame-1", "plan-1", "revision-1",
                "candidate-key", "instruction-1", definitions);
    }

    private static ChainStepAuthorityPort.StepEvent stepEvent(
            String eventId, String stepId, String activationId,
            ChainStepAuthorityPort.StepEventKind kind, String source,
            String transitionId, long sequence) {
        return new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        eventId, "task-1", "revision-1", stepId,
                        activationId, kind, source, transitionId, NOW),
                sequence);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            readiness(String predecessorTransitionId, long cut,
                    ChainPersistenceRecords.CanonicalJson acceptedSet) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "readiness-event",
                predecessorTransitionId, HASH, "frame-1", "plan-1",
                "revision-1", 1, "step-1", "review-1", acceptedSet, cut,
                null, ChainIdentity.NONE, ChainIdentity.NONE,
                ChainIdentity.NONE, null, null, canonical("[]"),
                ChainPublishRequirement.NOT_REQUIRED,
                hash("publish\0NOT_REQUIRED"),
                "instruction-1", "project-version-1", NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            readinessBundle(String predecessorTransitionId, String bundleId,
                    String request, String receipts) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "readiness-event",
                predecessorTransitionId, HASH, "frame-1", "plan-1",
                "revision-1", 1, "step-1", "review-1",
                canonical("[\"accepted-1\"]"), 0,
                null, ChainIdentity.NONE, ChainIdentity.NONE,
                bundleId, request, receipts, canonical("[]"),
                ChainPublishRequirement.NOT_REQUIRED,
                hash("publish\0NOT_REQUIRED"),
                "instruction-1", ChainIdentity.NONE, NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            readinessCandidateBundle(
                    String predecessorTransitionId, String bundleId,
                    String request, String receipts) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "readiness-event",
                predecessorTransitionId, HASH, "frame-1", "plan-1",
                "revision-1", 1, "step-1", "review-1",
                canonical("[\"accepted-1\"]"), 0,
                101L, "candidate-key", "workspace-1", bundleId,
                request, receipts, canonical("[]"),
                ChainPublishRequirement.NOT_REQUIRED,
                hash("publish\0NOT_REQUIRED"), "instruction-1",
                "project-version-1", NOW);
    }

    private static ChainPersistenceRecords.ValidationBundleRecord
            validationBundle(String bundleId, String frameId, String request,
                    String receipts, String conclusion) {
        return new ChainPersistenceRecords.ValidationBundleRecord(
                bundleId, "task-1", "bundle-event-1", frameId,
                "plan-1", "revision-1", 1, "instruction-1", "step-1",
                request, receipts, conclusion,
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED,
                "bundle-key", NOW);
    }

    private static ChainPersistenceRecords.ValidationBundleSetRecord
            validationBundleMember(
                    String bundleId, String taskId, String request,
                    String receipts, String conclusion) {
        return new ChainPersistenceRecords.ValidationBundleSetRecord(
                bundleId, taskId, "step-1", "activation-1",
                "validation-set-1", request, receipts, conclusion);
    }

    private static ChainPersistenceRecords.ValidationSetRecord
            validationSet(
                    String request, String receipts, String conclusion) {
        return new ChainPersistenceRecords.ValidationSetRecord(
                "validation-set-1", "task-1", "validation-event-1",
                "frame-1", "plan-1", "revision-1", 1, "step-1",
                "activation-1", request, receipts, conclusion,
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED,
                "validation-key", NOW);
    }

    private static ChainPersistenceRecords.CandidateValidationItemRecord
            candidateValidationItem(
                    String requirementDigest, String workspaceCandidateId,
                    String fingerprint) {
        return new ChainPersistenceRecords.CandidateValidationItemRecord(
                "validation-set-1", "run-proof", "task-1",
                requirementDigest, "candidate-action-1",
                "validation-action-1", "receipt-1", "8".repeat(64),
                "9".repeat(64), workspaceCandidateId, "workspace-1", 101,
                fingerprint, "project-version-1",
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED);
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord
            finalizationCheck(String transitionId,
                    ChainPersistenceRecords.FinalizationReadinessRecord ready) {
        var value = mock(
                ChainPersistenceRecords.FinalizationCheckRecord.class);
        when(value.finalizationCheckId()).thenReturn("check-1");
        when(value.taskId()).thenReturn("task-1");
        when(value.readinessId()).thenReturn(ready.readinessId());
        when(value.transitionId()).thenReturn(transitionId);
        when(value.attemptNo()).thenReturn(1);
        when(value.taskFrameId()).thenReturn(ready.taskFrameId());
        when(value.finalPlanRevisionId()).thenReturn(
                ready.finalPlanRevisionId());
        when(value.acceptedSetSha256()).thenReturn(
                ready.acceptedSet().sha256());
        when(value.candidateKey()).thenReturn(ready.candidateKey());
        when(value.workspaceId()).thenReturn(ready.workspaceId());
        when(value.validationId()).thenReturn(ready.validationId());
        when(value.validationRequestDigest()).thenReturn(
                ready.validationRequestDigest());
        when(value.validationReceiptDigest()).thenReturn(
                ready.validationReceiptDigest());
        when(value.publishRequirementDigest()).thenReturn(
                ready.publishRequirementDigest());
        when(value.instructionId()).thenReturn(ready.instructionId());
        when(value.projectVersion()).thenReturn(ready.projectVersion());
        when(value.runtimePolicyVersion()).thenReturn(
                ChainRuntimePolicy.V1.policyVersion());
        when(value.resultStatus()).thenReturn(ChainFinalization.Outcome.PASSED);
        return value;
    }

    private static List<ChainPersistenceRecords.TransitionStageRecord>
            readinessPrefix(
                    ChainPersistenceRecords.TransitionRecord predecessor,
                    ChainPersistenceRecords.FinalizationReadinessRecord ready) {
        String completedId = "step.completed." + hash(
                "task-1\0revision-1\0step-1\0activation-1\0"
                        + predecessor.transitionId());
        return List.of(
                stage(predecessor, ChainTransitionStage.OPEN, 0,
                        null, null, null, null),
                stage(predecessor,
                        ChainTransitionStage
                                .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                        1, null, null, "ACCEPTED_RESULT", "accepted-1"),
                stage(predecessor,
                        ChainTransitionStage
                                .APPLICABILITY_COMMITTED_OR_EMPTY,
                        2, null, null, null, null),
                stage(predecessor,
                        ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED,
                        3, null, null, "STEP_EVENT", completedId),
                stage(predecessor, ChainTransitionStage.READINESS_COMMITTED,
                        4, null, null, "FINALIZATION_READINESS",
                        ready.readinessId()),
                stage(predecessor, ChainTransitionStage.COMPLETE, 5,
                        null, null, null, null));
    }

    private static ChainPersistenceRecords.TransitionRecord transition(
            ChainTransitionType type, String source, String target) {
        return new ChainPersistenceRecords.TransitionRecord(
                new ChainIdentity.Transition(
                        type, "task-1", source, target).transitionId(),
                "task-1", "transition-event", type, source, target, NOW);
    }

    private static ChainPersistenceRecords.TransitionStageRecord stage(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainTransitionStage code, int ordinal, String predecessorType,
            String predecessorRef, String successorType, String successorRef) {
        return new ChainPersistenceRecords.TransitionStageRecord(
                transition.transitionId(), code, transition.taskId(),
                "stage-" + transition.transitionId() + "-" + ordinal,
                ordinal, predecessorType, predecessorRef,
                successorType, successorRef, NOW);
    }

    private static Fixture fixture(
            ChainTransitionType type, String source, String target) {
        return new Fixture(transition(type, source, target));
    }

    private static String acceptedDigest() {
        return hash("candidate-1\0review-1\0content-1");
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(
            String value) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, hash(value), value);
    }

    private static String hash(String value) {
        return ProductChainRecoveryAuthorityLookup.sha256(value);
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return quote(enumValue.name());
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            list.forEach(element -> values.add(json(element)));
            return "[" + String.join(",", values) + "]";
        }
        if (value.getClass().isRecord()) {
            List<RecordComponent> components = List.of(
                            value.getClass().getRecordComponents()).stream()
                    .sorted(Comparator.comparing(RecordComponent::getName))
                    .toList();
            List<String> fields = new ArrayList<>();
            for (RecordComponent component : components) {
                try {
                    fields.add(quote(component.getName()) + ":"
                            + json(component.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Map<?, ?> map) {
            throw new IllegalArgumentException("map not expected: " + map);
        }
        throw new IllegalArgumentException("unsupported fixture value");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static final class Fixture {
        private final ChainPersistenceRecords.TransitionRecord transition;
        private final ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        private final ChainContextRepository contexts = mock(
                ChainContextRepository.class);
        private final ChainWorkflowRepository workflow = mock(
                ChainWorkflowRepository.class);
        private final ChainFinalizationRepository finalization = mock(
                ChainFinalizationRepository.class);
        private final ChainModelRepository models = mock(
                ChainModelRepository.class);
        private final ProductChainStepAuthorityAdapter steps = mock(
                ProductChainStepAuthorityAdapter.class);
        private final ProductPlanBootstrapRepositoryAdapter bootstraps = mock(
                ProductPlanBootstrapRepositoryAdapter.class);
        private final ProductPlanBootstrapCodec codec = mock(
                ProductPlanBootstrapCodec.class);
        private final com.yanban.api.agent.v2.persistence
                .ProductPlanRevisionAuthoritySource revisionAuthorities = mock(
                com.yanban.api.agent.v2.persistence
                        .ProductPlanRevisionAuthoritySource.class);
        private final ProductChainPublishAuthoritySource publishes = mock(
                ProductChainPublishAuthoritySource.class);
        private final ChainValidationBundleRepository validationBundles = mock(
                ChainValidationBundleRepository.class);
        private final ChainValidationRepository validations = mock(
                ChainValidationRepository.class);
        private final ProductChainRecoveryStageAuthorityVerifier verifier;

        private Fixture(
                ChainPersistenceRecords.TransitionRecord transition) {
            this.transition = transition;
            var task = mock(ChainPersistenceRecords.TaskRecord.class);
            when(task.taskId()).thenReturn("task-1");
            when(task.sessionId()).thenReturn(9L);
            when(task.initialProjectVersion()).thenReturn("project-version-1");
            var instruction = mock(
                    ChainPersistenceRecords.InstructionRecord.class);
            when(instruction.instructionId()).thenReturn("instruction-1");
            when(instruction.originTaskId()).thenReturn("task-1");
            when(instruction.sessionId()).thenReturn(9L);
            when(foundations.findTask("task-1"))
                    .thenReturn(Optional.of(task));
            when(foundations.findInstruction("instruction-1"))
                    .thenReturn(Optional.of(instruction));
            when(workflow.findTransition(transition.transitionId()))
                    .thenReturn(Optional.of(transition));
            verifier = new ProductChainRecoveryStageAuthorityVerifier(
                    foundations, contexts, workflow, finalization, models, steps,
                    bootstraps, codec, revisionAuthorities, publishes, validationBundles,
                    validations);
        }

        private void verify(
                ChainPersistenceRecords.TransitionStageRecord value) {
            verifier.verify(
                    new ChainCompositeTransitionRuntime.StageAuthorityQuery(
                            transition, value));
        }

        private void requirements(TaskRequirements requirements) {
            PersistedPlanBootstrap bootstrap = mock(
                    PersistedPlanBootstrap.class);
            TaskFrame frame = mock(TaskFrame.class);
            when(frame.id()).thenReturn(new TaskFrameId("frame-1"));
            when(frame.requirements()).thenReturn(requirements);
            when(bootstrap.taskFrame()).thenReturn(frame);
            when(bootstraps.find(new PlanId("plan-1")))
                    .thenReturn(Optional.of(bootstrap));
        }

        private void projectlessTask() {
            var task = mock(ChainPersistenceRecords.TaskRecord.class);
            when(task.taskId()).thenReturn("task-1");
            when(task.sessionId()).thenReturn(9L);
            when(task.initialProjectVersion()).thenReturn(null);
            when(foundations.findTask("task-1"))
                    .thenReturn(Optional.of(task));
        }

        private void projectTask() {
            var task = mock(ChainPersistenceRecords.TaskRecord.class);
            when(task.taskId()).thenReturn("task-1");
            when(task.sessionId()).thenReturn(9L);
            when(task.initialProjectVersion())
                    .thenReturn("project-version-1");
            when(foundations.findTask("task-1"))
                    .thenReturn(Optional.of(task));
        }

        private ChainPersistenceRecords.TransitionStageRecord stage(
                ChainTransitionStage code, int ordinal,
                String predecessorType, String predecessorRef,
                String successorType, String successorRef) {
            return ProductChainRecoveryStageAuthorityVerifierTest.stage(
                    transition, code, ordinal, predecessorType,
                    predecessorRef, successorType, successorRef);
        }
    }
}
