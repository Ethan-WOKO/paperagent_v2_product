package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductTaskStepRuntimeContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void directAnswerProjectsEveryRequiredRuntimeFieldThroughSnapshot() {
        var fixture = fixture();
        when(fixture.workflow.findRouteDecisions("task.1"))
                .thenReturn(List.of(directRoute()));
        authority(fixture, List.of(event("route.event", 3)));
        var building = directAnswerBuilding();

        var projection = fixture.projector.read(request(building));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        assertEquals(Set.copyOf(ChainContextInputMatrix
                        .requiredProjectionFields(ChainRole.ANSWER,
                                ChainContextModule
                                        .TASK_AND_STEP_RUNTIME_STATE)),
                projection.projectionFields().keySet());
        var header = (ChainContextValue.ObjectValue) projection
                .projectionFields().get("foundation.stateHeader");
        assertEquals("DIRECT", text(header.values(), "executionMode"));
        assertEquals("ANSWER", text(header.values(), "role"));
        var template = (ChainContextValue.ObjectValue) projection
                .projectionFields().get("runtime.answerPayloadTemplate");
        var root = (ChainContextValue.ObjectValue) template.values()
                .get("root");
        var payload = (ChainContextValue.ObjectValue) root.values()
                .get("payload");
        assertEquals("DIRECT_ANSWER", text(root.values(), "kind"));
        assertEquals("route.1", text(payload.values(),
                "routeDecisionRef"));
        assertEquals("direct answer", text(payload.values(),
                "directTaskSpecification"));
        assertEquals(List.of(), texts((ChainContextValue.ArrayValue)
                payload.values().get("factRefs")));
        assertEquals(Set.of("routeDecisionRef", "directTaskSpecification",
                        "inlineAnswerBody", "factRefs"),
                payload.values().keySet());
        var snapshot = new ProductChainContextModuleSource(
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE,
                fixture.projector).project(request(building));
        assertEquals(ChainContextModuleStatus.PRESENT,
                snapshot.presenceKind());
    }

    @Test
    void initialPlannerKeepsRuntimeCutsEmptyAtNonzeroTaskAuthorityHead() {
        var fixture = fixture();
        authority(fixture, List.of(event("instruction.event", 17)));

        var projection = fixture.projector.read(request(
                initialBuilding()));

        assertEquals(ChainContextModuleStatus.EMPTY, projection.presenceKind());
        assertEquals("allCuts=0", projection.emptyWatermark());
        assertEquals(0, number(projection.sourceVersionComponents(),
                "chainEventCut"));
        assertEquals(17, number(projection.readBoundaryComponents(),
                "taskEventSequence"));
        assertEquals(17, number(projection.projectionParameters(),
                "taskAuthorityHead"));
    }

    @Test
    void plannerUsesRealModeAndStepStatesWithSparseSequenceDomains() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var activated = stepEvent("step.one.activation", "step.one.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "transition.activate", 11);
        var completed = stepEvent("step.one.complete", "step.one.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.COMPLETED,
                "transition.complete", 37);
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(activated, completed));
        formal(fixture, activated, "stage.activate");
        formal(fixture, completed, "stage.complete");
        authority(fixture, List.of(
                event("route.event", 3), event("binding.event", 19),
                event("stage.activate", 73), event("stage.complete", 101)));

        var projection = fixture.projector.read(request(
                building(ChainRole.PLANNER, null, null)));

        assertEquals("PERSISTENT_PLAN_EXECUTE", text(
                projection.projectionFields(), "runtime.executionMode"));
        var steps = array(projection.projectionFields(), "runtime.steps");
        assertEquals("COMPLETED", text(object(steps, 0), "status"));
        assertEquals("READY", text(object(steps, 1), "status"));
        assertEquals(101, number(projection.readBoundaryComponents(),
                "taskEventSequence"));
        assertEquals(37, number(projection.readBoundaryComponents(),
                "checkpointHead"));
    }

    @Test
    void executorReadsOnlyCandidateForExactCurrentActivation() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var activated = stepEvent("current.activation", "current.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "transition.activate", 29);
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(activated));
        formal(fixture, activated, "stage.activate");
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(
                        candidate("candidate.old", "candidate.old.event",
                                "step.one", "old.activation"),
                        candidate("candidate.current", "candidate.current.event",
                                "step.one", "current.activation")));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 7),
                event("stage.activate", 31),
                event("candidate.old.event", 41),
                event("candidate.current.event", 53)));

        var projection = fixture.projector.read(request(building(
                ChainRole.EXECUTOR, "step.one", "current.activation")));

        var candidate = (ChainContextValue.ObjectValue)
                projection.projectionFields().get("runtime.candidateResult");
        assertEquals("candidate.current", text(
                candidate.values(), "candidateResultId"));
        var current = (ChainContextValue.ObjectValue)
                projection.projectionFields().get("runtime.currentStep");
        assertEquals("AWAITING_REVIEW", text(current.values(), "status"));
    }

    @Test
    void reflectorProjectsAcceptedResultsAffectedByCurrentStep() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var activated = stepEvent("current.activation", "current.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "transition.activate", 13);
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(activated));
        formal(fixture, activated, "stage.activate");
        var current = candidate("candidate.current", "candidate.current.event",
                "step.one", "current.activation");
        var downstream = candidate("candidate.downstream",
                "candidate.downstream.event", "step.two", "downstream.act");
        var review = review("review.downstream", "review.event",
                downstream.candidateResultId());
        var accepted = accepted("accepted.downstream", "accepted.event",
                downstream, review);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(current, downstream));
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findAcceptedResults("task.1"))
                .thenReturn(List.of(accepted));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5),
                event("stage.activate", 11),
                event("candidate.current.event", 17),
                event("candidate.downstream.event", 23),
                event("review.event", 31), event("accepted.event", 47)));

        var projection = fixture.projector.read(request(building(
                ChainRole.REFLECTOR, "step.one", "current.activation")));

        var affected = array(projection.projectionFields(),
                "runtime.affectedResults");
        assertEquals(1, affected.values().size());
        assertEquals("accepted.downstream", text(
                object(affected, 0), "acceptedResultId"));
    }

    @Test
    void formalFailureReviewDoesNotRequireCandidateStepResult() {
        for (String callReason : List.of(
                "MODEL_CALL_FAILED_REVIEW",
                "CONTEXT_BUILD_FAILURE_REVIEW",
                "ACTION_FAILURE_REVIEW")) {
            var fixture = fixture();
            persistent(fixture, plan("instruction.1"));
            var activated = stepEvent(
                    "current.activation", "current.activation",
                    "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                    "transition.activate", 13);
            when(fixture.steps.findStepEvents("task.1", "revision.1"))
                    .thenReturn(List.of(activated));
            formal(fixture, activated, "stage.activate");
            authority(fixture, List.of(
                    event("route.event", 2), event("binding.event", 5),
                    event("stage.activate", 11)));

            var projection = fixture.projector.read(request(building(
                    ChainRole.REFLECTOR, "step.one", "current.activation",
                    callReason)));

            assertEquals(ChainContextModuleStatus.PRESENT,
                    projection.presenceKind());
            assertEquals(ChainContextValue.nil(),
                    projection.projectionFields().get(
                            "runtime.candidateResult"));
        }
    }

    @Test
    void ordinaryReflectorStillRequiresCandidateStepResult() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var activated = stepEvent(
                "current.activation", "current.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "transition.activate", 13);
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(activated));
        formal(fixture, activated, "stage.activate");
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5),
                event("stage.activate", 11)));

        var failure = assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.REFLECTOR, "step.one",
                        "current.activation", "CANDIDATE_REVIEW"))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void answerProjectsExactTaskOutcomeTerminalResultsAndDelivery() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var candidate = candidate("candidate.final", "candidate.event",
                "step.two", "final.activation");
        var review = review("review.final", "review.event",
                candidate.candidateResultId());
        var accepted = accepted("accepted.final", "accepted.event",
                candidate, review);
        var outcome = outcome("outcome.event");
        var delivery = new ChainPersistenceRecords.DeliveryRecord(
                "delivery.1", "task.1", "delivery.event", "command.1",
                null, outcome.outcomeId(), null, null,
                "answer.content", 81L, NOW);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findAcceptedResults("task.1"))
                .thenReturn(List.of(accepted));
        when(fixture.finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.of(outcome));
        when(fixture.finalization.findDeliveries("task.1"))
                .thenReturn(List.of(delivery));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5),
                event("candidate.event", 11), event("review.event", 17),
                event("accepted.event", 23), event("outcome.event", 31),
                event("delivery.event", 43)));

        var projection = fixture.projector.read(request(
                building(ChainRole.ANSWER, null, null)));

        var taskOutcome = (ChainContextValue.ObjectValue)
                projection.projectionFields().get("runtime.taskOutcome");
        var terminal = array(projection.projectionFields(),
                "runtime.acceptedResultTerminalProjection");
        var delivered = (ChainContextValue.ObjectValue)
                projection.projectionFields().get("runtime.deliveryRecord");
        var template = (ChainContextValue.ObjectValue)
                projection.projectionFields().get(
                        "runtime.answerPayloadTemplate");
        var templateRoot = (ChainContextValue.ObjectValue)
                template.values().get("root");
        var templatePayload = (ChainContextValue.ObjectValue)
                templateRoot.values().get("payload");
        assertEquals("outcome.1", text(taskOutcome.values(), "outcomeId"));
        assertEquals("candidate-artifact:91", text(taskOutcome.values(),
                "finalArtifactRef"));
        assertEquals("accepted.final", text(object(terminal, 0),
                "acceptedResultId"));
        assertEquals("delivery.1", text(delivered.values(), "deliveryId"));
        assertEquals("FINAL_DELIVERY", text(template.values(),
                "selectedKind"));
        assertEquals("outcome.1", text(templatePayload.values(),
                "taskOutcomeRef"));
        assertEquals(List.of("candidate-artifact:91", "candidate.key"),
                texts((ChainContextValue.ArrayValue) templatePayload.values()
                        .get("artifactAndCandidateRefs")));
        assertEquals("validation.1", text(templatePayload.values(),
                "validationRef"));
    }

    @Test
    void failedAnswerTemplateBindsOutcomeTwiceAndLatestFormalReview() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var earlier = review("review.earlier", "review.earlier.event",
                "candidate.earlier");
        var latest = review("review.latest", "review.latest.event",
                "candidate.latest");
        var outcome = failedOutcome("outcome.event");
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(earlier, latest));
        when(fixture.finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.of(outcome));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5),
                event("review.earlier.event", 11),
                event("review.latest.event", 17),
                event("outcome.event", 23)));

        var projection = fixture.projector.read(request(
                building(ChainRole.ANSWER, null, null)));

        var template = (ChainContextValue.ObjectValue)
                projection.projectionFields().get(
                        "runtime.answerPayloadTemplate");
        var root = (ChainContextValue.ObjectValue)
                template.values().get("root");
        var payload = (ChainContextValue.ObjectValue)
                root.values().get("payload");
        assertEquals("STATUS_OR_FAILURE", text(template.values(),
                "selectedKind"));
        assertEquals("outcome.1", text(payload.values(),
                "taskOrStepStatusRef"));
        assertEquals("outcome.1", text(payload.values(),
                "blockerOrTaskOutcomeRef"));
        assertEquals("review.latest", text(payload.values(),
                "latestDecisionRef"));
    }

    @Test
    void contextAndPlanIdentityConflictIsTypedBlocked() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.other"));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5)));

        var failure = assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(
                        building(ChainRole.PLANNER, null, null))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void businessRecordWithoutAuthorityEventIsTypedBlocked() {
        var fixture = fixture();
        when(fixture.workflow.findRouteDecisions("task.1"))
                .thenReturn(List.of(route()));
        authority(fixture, List.of(event("instruction.event", 7)));

        var failure = assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(
                        initialBuilding())));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void stepEventWithoutFormalStageIsTypedBlocked() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var activated = stepEvent("current.activation", "current.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "transition.activate", 13);
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(activated));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5)));

        var failure = assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.EXECUTOR, "step.one", "current.activation"))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void crossTaskFormalStageCannotAuthorizeStepEvent() {
        var fixture = fixture();
        persistent(fixture, plan("instruction.1"));
        var activated = stepEvent("current.activation", "current.activation",
                "step.one", ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                "transition.activate", 13);
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(List.of(activated));
        when(fixture.workflow.findTransitionStages("transition.activate"))
                .thenReturn(List.of(new ChainPersistenceRecords
                        .TransitionStageRecord(
                        "transition.activate",
                        ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                        "task.other", "stage.activate", 0, null, null,
                        "STEP_EVENT", "current.activation", NOW)));
        authority(fixture, List.of(
                event("route.event", 2), event("binding.event", 5),
                event("stage.activate", 11)));

        var failure = assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.EXECUTOR, "step.one", "current.activation"))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static Fixture fixture() {
        var foundations = mock(ChainFoundationRepository.class);
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var steps = mock(ChainStepAuthorityPort.class);
        var finalization = mock(ChainFinalizationRepository.class);
        when(foundations.findTask("task.1"))
                .thenReturn(Optional.of(mock(
                        ChainPersistenceRecords.TaskRecord.class)));
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.empty());
        return new Fixture(foundations, workflow, steps, finalization,
                new ProductTaskStepRuntimeContextProjector(
                        foundations, workflow, steps, finalization));
    }

    private static void persistent(Fixture fixture,
                                   ChainStepAuthorityPort.PlanSnapshot plan) {
        when(fixture.workflow.findRouteDecisions("task.1"))
                .thenReturn(List.of(route()));
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding()));
        when(fixture.steps.findPlan("task.1", "revision.1"))
                .thenReturn(Optional.of(plan));
    }

    private static void authority(
            Fixture fixture,
            List<ChainPersistenceRecords.AuthorityEventRecord> events) {
        long cut = events.stream().mapToLong(
                ChainPersistenceRecords.AuthorityEventRecord::eventSequence)
                .max().orElse(0);
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(cut);
        when(fixture.foundations.findAuthorityEvents("task.1", cut))
                .thenReturn(events);
    }

    private static void formal(
            Fixture fixture, ChainStepAuthorityPort.StepEvent event,
            String stageEventId) {
        var command = event.command();
        when(fixture.workflow.findTransitionStages(command.transitionId()))
                .thenReturn(List.of(new ChainPersistenceRecords
                        .TransitionStageRecord(
                        command.transitionId(),
                        ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                        "task.1", stageEventId, 0, null, null,
                        "STEP_EVENT", command.eventId(), NOW)));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String stepId, String activationId) {
        return building(role, stepId, activationId, "TEST");
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String stepId, String activationId,
            String callReason) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, role,
                role == ChainRole.PLANNER
                        ? ChainWorkState.PLANNING : ChainWorkState.EXECUTING,
                callReason, "instruction.1", "frame.1", "plan.1",
                "revision.1", 1L, stepId, activationId,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord initialBuilding() {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.PLANNER,
                ChainWorkState.PLANNING, "TEST", "instruction.1",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            directAnswerBuilding() {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.ANSWER,
                ChainWorkState.DIRECT_ANSWERING, "DIRECT_ROUTE",
                "instruction.1", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static ChainContextProjectionRequest request(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static ChainStepAuthorityPort.PlanSnapshot plan(
            String instructionId) {
        return new ChainStepAuthorityPort.PlanSnapshot(
                "task.1", "frame.1", "plan.1", "revision.1",
                "candidate.key", instructionId,
                List.of(new ChainStepAuthorityPort.StepDefinition(
                                "step.one", 1, Set.of()),
                        new ChainStepAuthorityPort.StepDefinition(
                                "step.two", 2, Set.of("step.one"))));
    }

    private static ChainPersistenceRecords.RouteDecisionRecord route() {
        return new ChainPersistenceRecords.RouteDecisionRecord(
                "route.1", "task.1", "route.event", "instruction.1",
                "proposal.route", ChainPersistenceRecords.RouteDecisionType.INITIAL,
                0, ChainExecutionMode.PERSISTENT_PLAN_EXECUTE, "needs work",
                json("{}"), json("[]"), json("[]"), true, false,
                false, true, null, null, "transition.route", NOW);
    }

    private static ChainPersistenceRecords.RouteDecisionRecord directRoute() {
        return new ChainPersistenceRecords.RouteDecisionRecord(
                "route.1", "task.1", "route.event", "instruction.1",
                "proposal.route",
                ChainPersistenceRecords.RouteDecisionType.INITIAL,
                0, ChainExecutionMode.DIRECT, "direct answer",
                json("{\"specification\":\"direct answer\"}"),
                json("[]"), json("[]"), false, false,
                false, false, null, null, "transition.route", NOW);
    }

    private static ChainPersistenceRecords.PlanBindingRecord binding() {
        return new ChainPersistenceRecords.PlanBindingRecord(
                "binding.1", "task.1", "binding.event", "instruction.1",
                "route.1", "frame.1", "plan.1", "revision.1", 1,
                "PLAN_COMMIT", "plan.1", HASH, "transition.binding", NOW);
    }

    private static ChainStepAuthorityPort.StepEvent stepEvent(
            String eventId, String activationId, String stepId,
            ChainStepAuthorityPort.StepEventKind kind, String transition,
            long checkpoint) {
        return new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        eventId, "task.1", "revision.1", stepId,
                        activationId, kind, "decision.1", transition, NOW),
                checkpoint);
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidate(
            String id, String eventId, String stepId, String activationId) {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                id, "task.1", eventId, "proposal." + id,
                "content." + id, "instruction.1", "frame.1", "plan.1",
                "revision.1", 1, stepId, activationId, null, null, null,
                json("[]"), null, null, null, json("[]"), HASH, NOW);
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord review(
            String id, String eventId, String candidateId) {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                id, "task.1", eventId, "proposal." + id,
                "CANDIDATE_STEP_RESULT", candidateId,
                ChainProposalKind.REFLECTOR_ACCEPT_STEP, "accepted",
                json("[]"), HASH, NOW);
    }

    private static ChainPersistenceRecords.AcceptedResultRecord accepted(
            String id, String eventId,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            ChainPersistenceRecords.ReviewDecisionRecord review) {
        return new ChainPersistenceRecords.AcceptedResultRecord(
                id, "task.1", eventId, candidate.candidateResultId(),
                review.reviewDecisionId(), "transition." + id,
                candidate.contentId(), HASH, NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord outcome(
            String eventId) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome.1", "task.1", eventId, "command.1",
                ChainTaskOutcomeStatus.COMPLETED, "instruction.1", "frame.1",
                "plan.1", "revision.1", json("{}"), json("[]"),
                91L, "candidate.key", "validation.1",
                null, null, null, null, json("[]"), json("[]"), json("[]"),
                null, null, "decision.final", NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord failedOutcome(
            String eventId) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome.1", "task.1", eventId, "command.1",
                ChainTaskOutcomeStatus.FAILED, "instruction.1", "frame.1",
                "plan.1", "revision.1", json("{}"), json("[]"),
                null, "NONE", "NONE", null, null, null, null,
                json("[]"), json("[]"), json("[]"),
                "MODEL", "MODEL_CALL_FAILED", "decision.final", NOW);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord event(
            String eventId, long sequence) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                eventId, "task.1", sequence, "TEST", null, HASH, NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, value);
    }

    private static ChainContextValue.ArrayValue array(
            Map<String, ChainContextValue> values, String key) {
        return (ChainContextValue.ArrayValue) values.get(key);
    }

    private static Map<String, ChainContextValue> object(
            ChainContextValue.ArrayValue values, int index) {
        return ((ChainContextValue.ObjectValue) values.values().get(index))
                .values();
    }

    private static long number(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private static String text(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private static List<String> texts(ChainContextValue.ArrayValue values) {
        return values.values().stream().map(value ->
                ((ChainContextValue.Text) value).value()).toList();
    }

    private record Fixture(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainStepAuthorityPort steps,
            ChainFinalizationRepository finalization,
            ProductTaskStepRuntimeContextProjector projector) {
    }
}
