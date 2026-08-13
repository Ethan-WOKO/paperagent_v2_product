package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductTaskAndPlanContractContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void initialPlannerGetsProvableTaskAndPlanEmptyCuts() {
        var fixture = fixture();
        ContextRevisionRecord building = building(
                ChainRole.PLANNER, null, null, null, null, null);
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(3L);
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of());

        var task = fixture.task.read(request(building));
        var plan = fixture.plan.read(request(building));

        assertEquals(ChainContextModuleStatus.EMPTY, task.presenceKind());
        assertEquals("taskFrame=NONE@instructionVersion",
                task.emptyWatermark());
        assertEquals(ChainContextModuleStatus.EMPTY, plan.presenceKind());
        assertEquals("plan=NONE,revision=0,v2EventSequence=0",
                plan.emptyWatermark());
        assertEquals(3, number(plan.readBoundaryComponents(),
                "chainAuthorityEventCut"));
        verify(fixture.bootstraps, never()).find(new PlanId("plan.1"));
    }

    @Test
    void directProjectAnswerUsesCanonicalEmptyTaskAndPlanWatermarks() {
        var fixture = fixture();
        ContextRevisionRecord building = directAnswerBuilding();
        when(fixture.workflow.findRouteDecisions("task.1"))
                .thenReturn(List.of(directRoute()));

        var task = fixture.task.read(request(building));
        var plan = fixture.plan.read(request(building));

        assertEquals(ChainContextModuleStatus.EMPTY, task.presenceKind());
        assertEquals("taskFrame=NONE@instructionVersion",
                task.emptyWatermark());
        assertEquals(ChainContextModuleStatus.EMPTY, plan.presenceKind());
        assertEquals("plan=NONE,revision=0,v2EventSequence=0",
                plan.emptyWatermark());
        var validatedPlan = new ProductChainContextModuleSource(
                ChainContextModule.PLAN_AND_STEP_CONTRACT,
                fixture.plan).project(request(building));
        assertEquals(ChainContextModuleStatus.EMPTY,
                validatedPlan.presenceKind());
        assertTrue(validatedPlan.readBoundary().json()
                .contains("\"stableV2PlanCut\""));
        verify(fixture.bootstraps, never()).find(new PlanId("plan.1"));
    }

    @Test
    void missingTaskFrameCannotHideAnExistingFormalBindingAsEmpty() {
        var fixture = fixture();
        ContextRevisionRecord building = building(
                ChainRole.PLANNER, null, null, null, null, null);
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(4L);
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding("revision.1", 1)));

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> fixture.task.read(request(building)));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void taskProjectionUsesExactBootstrapDigestAndProjectVersion() {
        var fixture = fixture();
        PersistedPlanBootstrap bootstrap = bootstrap(revisionOne());
        PlanBindingRecord binding = binding("revision.1", 1);
        ContextRevisionRecord building = building(
                ChainRole.EXECUTOR, "frame.1", "plan.1", "revision.1",
                1L, "step.current");
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding));
        when(fixture.bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(19L);
        when(fixture.foundations.findAuthorityEvents("task.1", 19L))
                .thenReturn(List.of(authority("binding.event", 4)));

        var projection = fixture.task.read(request(building));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        assertEquals(ProductChainContractProjectionCodec.taskFrame(
                        bootstrap.taskFrame()).sha256(),
                text(projection.sourceVersionComponents(),
                        "taskFramePayloadHash"));
        assertTrue(projection.projectionFields().keySet().containsAll(
                ChainContextInputMatrix.requiredProjectionFields(
                        ChainRole.EXECUTOR, ChainContextModule.TASK_CONTRACT)));
    }

    @Test
    void everyRoleObservesTheSameFrozenTypedTaskRequirements() {
        var fixture = fixture();
        PersistedPlanBootstrap bootstrap = bootstrap(revisionOne());
        PlanBindingRecord binding = binding("revision.1", 1);
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding));
        when(fixture.bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(19L);
        when(fixture.foundations.findAuthorityEvents("task.1", 19L))
                .thenReturn(List.of(authority("binding.event", 4)));

        Map<ChainRole, String> completeField = Map.of(
                ChainRole.PLANNER, "taskFrame.completeOrExplicitEmpty",
                ChainRole.EXECUTOR, "taskFrame.complete",
                ChainRole.REFLECTOR, "taskFrame.complete",
                ChainRole.ANSWER, "taskFrame.persistentCompleteOrDirectEmpty");
        for (ChainRole role : ChainRole.values()) {
            ContextRevisionRecord building = building(
                    role, "frame.1", "plan.1", "revision.1",
                    1L, "step.current");
            var projection = fixture.task.read(request(building));
            var wrapper = (ChainContextValue.ObjectValue)
                    projection.projectionFields().get(completeField.get(role));
            var contract = (ChainContextValue.ObjectValue)
                    wrapper.values().get("contract");
            var requirements = (ChainContextValue.ObjectValue)
                    contract.values().get("requirements");

            assertEquals("EXPLICIT", ((ChainContextValue.Text)
                    requirements.values().get("declarationMode")).value());
            assertEquals("FINAL_DELIVERY_REQUIRED",
                    ((ChainContextValue.Text) requirements.values().get(
                            "deliveryRequirement")).value());
            assertEquals("NOT_REQUIRED", ((ChainContextValue.Text)
                    requirements.values().get("publishRequirement")).value());
            assertEquals(1, ((ChainContextValue.ArrayValue)
                    requirements.values().get("validationRequirements"))
                    .values().size());
        }

        ContextRevisionRecord reflector = building(
                ChainRole.REFLECTOR, "frame.1", "plan.1", "revision.1",
                1L, "step.current");
        var validation = (ChainContextValue.ObjectValue) fixture.task
                .read(request(reflector)).projectionFields()
                .get("taskFrame.validationRequirements");
        assertTrue(validation.values().containsKey("requirements"));
        assertTrue(!validation.values().containsKey("constraints"));
    }

    @Test
    void sparseStepSequencesDrivePlanVersionAndActivationCut() {
        var fixture = fixture();
        PersistedPlanBootstrap bootstrap = bootstrap(revisionOne());
        PlanBindingRecord binding = binding("revision.1", 1);
        ContextRevisionRecord building = building(
                ChainRole.EXECUTOR, "frame.1", "plan.1", "revision.1",
                1L, "step.current");
        List<ChainStepAuthorityPort.StepEvent> events = List.of(
                activation("dep.activation", "step.dep", 7),
                terminal("dep.complete", "dep.activation", "step.dep", 11),
                activation("current.activation", "step.current", 19),
                terminal("future.complete", "current.activation",
                        "step.current", 27));
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding));
        when(fixture.bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(fixture.steps.findPlanRevision("task.1", "revision.1"))
                .thenReturn(Optional.of(revisionOne()));
        when(fixture.steps.findStepEvents("task.1", "revision.1"))
                .thenReturn(events);
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(120L);
        when(fixture.workflow.findTransitionStages("transition.1"))
                .thenReturn(List.of(
                        stage("stage.dep.activation", "dep.activation", 0),
                        stage("stage.dep.complete", "dep.complete", 1),
                        stage("stage.current.activation",
                                "current.activation", 2),
                        stage("stage.future.complete", "future.complete", 3)));
        when(fixture.foundations.findAuthorityEvents("task.1", 120L))
                .thenReturn(List.of(
                        authority("binding.event", 4),
                        authority("stage.dep.activation", 71),
                        authority("stage.dep.complete", 83),
                        authority("stage.current.activation", 103),
                        authority("stage.future.complete", 111)));

        var projection = fixture.plan.read(request(building));

        assertEquals(19, number(projection.sourceVersionComponents(),
                "v2EventSequence"));
        assertEquals(103, number(projection.readBoundaryComponents(),
                "chainAuthorityEventCut"));
        var checkpoint = (ChainContextValue.ObjectValue)
                projection.sourceVersionComponents().get("checkpoint");
        var observed = (ChainContextValue.ArrayValue)
                checkpoint.values().get("observedStepEventRefs");
        assertEquals(3, observed.values().size());
        assertTrue(projection.projectionFields().keySet().containsAll(
                ChainContextInputMatrix.requiredProjectionFields(
                        ChainRole.EXECUTOR,
                        ChainContextModule.PLAN_AND_STEP_CONTRACT)));
    }

    @Test
    void recoveredRevisionMustStillMatchBuildingRevisionExactly() {
        var fixture = fixture();
        PersistedPlanBootstrap bootstrap = bootstrap(revisionOne());
        PlanRevision wrongLatest = revision(
                "revision.3", 3, Optional.of(new PlanRevisionId("revision.2")));
        ContextRevisionRecord building = building(
                ChainRole.PLANNER, "frame.1", "plan.1", "revision.2",
                2L, null);
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding("revision.2", 2)));
        when(fixture.bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(fixture.steps.findPlanRevision("task.1", "revision.2"))
                .thenReturn(Optional.of(wrongLatest));

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> fixture.plan.read(request(building)));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void executorReadsExactReplannedRevisionAndActivation() {
        var fixture = fixture();
        PersistedPlanBootstrap bootstrap = bootstrap(revisionOne());
        PlanRevision replanned = revision(
                "revision.2", 2, Optional.of(new PlanRevisionId("revision.1")));
        ContextRevisionRecord building = building(
                ChainRole.EXECUTOR, "frame.1", "plan.1", "revision.2",
                2L, "step.current");
        var activation = stepEvent("current.activation",
                "current.activation", "step.current",
                ChainStepAuthorityPort.StepEventKind.ACTIVATED, 21,
                "revision.2");
        when(fixture.workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding("revision.2", 2)));
        when(fixture.bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(fixture.steps.findPlanRevision("task.1", "revision.2"))
                .thenReturn(Optional.of(replanned));
        when(fixture.steps.findStepEvents("task.1", "revision.2"))
                .thenReturn(List.of(activation));
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(30L);
        when(fixture.workflow.findTransitionStages("transition.1"))
                .thenReturn(List.of(stage(
                        "stage.current.activation", "current.activation", 0)));
        when(fixture.foundations.findAuthorityEvents("task.1", 30L))
                .thenReturn(List.of(authority("binding.event", 20),
                        authority("stage.current.activation", 21)));

        var projection = fixture.plan.read(request(building));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        assertTrue(projection.projectionFields().keySet().containsAll(
                ChainContextInputMatrix.requiredProjectionFields(
                        ChainRole.EXECUTOR,
                        ChainContextModule.PLAN_AND_STEP_CONTRACT)));
    }

    private static Fixture fixture() {
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var bootstraps = mock(ProductPlanBootstrapRepositoryAdapter.class);
        var steps = mock(ProductChainStepAuthorityAdapter.class);
        var foundations = mock(ChainFoundationRepository.class);
        return new Fixture(workflow, bootstraps, steps, foundations,
                new ProductTaskContractContextProjector(
                        workflow, bootstraps, foundations),
                new ProductPlanStepContractContextProjector(
                        workflow, bootstraps, steps, foundations));
    }

    private static ChainContextProjectionRequest request(
            ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static ContextRevisionRecord building(
            ChainRole role, String frame, String plan, String revision,
            Long number, String step) {
        return new ContextRevisionRecord(
                "context.1", "task.1", null, role,
                role == ChainRole.PLANNER ? ChainWorkState.PLANNING
                        : ChainWorkState.EXECUTING,
                "TEST", "instruction.1", frame, plan, revision, number, step,
                step == null ? null : "current.activation",
                9L, "version.1", step == null ? null : "workspace.1",
                null, null, null, null, null, "projectors.v1", "pages.v1",
                "policy.v1", io.paperagent.v2.chain
                        .ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static ContextRevisionRecord directAnswerBuilding() {
        return new ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.ANSWER,
                ChainWorkState.DIRECT_ANSWERING, "DIRECT_ROUTE",
                "instruction.1", null, null, null, null, null, null,
                9L, "version.1", null, null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static io.paperagent.v2.chain.ChainPersistenceRecords
            .RouteDecisionRecord directRoute() {
        var object = new io.paperagent.v2.chain.ChainPersistenceRecords
                .CanonicalJson(1, HASH, "{}");
        var array = new io.paperagent.v2.chain.ChainPersistenceRecords
                .CanonicalJson(1, HASH, "[]");
        return new io.paperagent.v2.chain.ChainPersistenceRecords
                .RouteDecisionRecord(
                "route.1", "task.1", "route.event", "instruction.1",
                "proposal.route",
                io.paperagent.v2.chain.ChainPersistenceRecords
                        .RouteDecisionType.INITIAL,
                0, io.paperagent.v2.chain.ChainExecutionMode.DIRECT,
                "plain answer", object, array, array,
                false, false, false, false, null, null, null, NOW);
    }

    private static PersistedPlanBootstrap bootstrap(PlanRevision revision) {
        TaskFrame frame = frame();
        Plan plan = new Plan(new PlanId("plan.1"), frame.id(), List.of(revision));
        Map<PlanStepId, StepExecutionState> states = revision.steps().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PlanStep::id, ignored -> StepExecutionState.NOT_STARTED));
        Checkpoint checkpoint = new Checkpoint(
                frame.id(), plan.id(), revision.id(), revision.number(), 0,
                PlanExecutionState.NOT_STARTED, states, List.of(), NOW);
        return new PersistedPlanBootstrap(
                frame, plan, new VersionedCheckpoint(1, checkpoint));
    }

    private static TaskFrame frame() {
        return new TaskFrame(new TaskFrameId("frame.1"), "Execute exactly.",
                List.of("src"), List.of("verified output"),
                List.of("preserve unrelated content"),
                TaskRequirements.explicit(List.of(new ValidationRequirement(
                                "validation.output",
                                ValidationSubject.ACTION_RECEIPT,
                                "verification passes")),
                        PublishRequirement.NOT_REQUIRED),
                Optional.of(new ProjectVersionRef("9", "version.1")),
                new ExecutionProfile(ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT), NetworkPolicy.DENY_ALL,
                        List.of(), new ResourceLimits(Duration.ofMinutes(1),
                        Duration.ofSeconds(30), 1024, 1024, 1), Set.of()), NOW);
    }

    private static PlanRevision revisionOne() {
        return revision("revision.1", 1, Optional.empty());
    }

    private static PlanRevision revision(
            String id, long number, Optional<PlanRevisionId> parent) {
        PlanStep dependency = new PlanStep(new PlanStepId("step.dep"),
                "Prepare input", "Input prepared", Set.of(),
                List.of("input exists"), new BoundedExecutionHints(
                2, Duration.ofSeconds(30)));
        PlanStep current = new PlanStep(new PlanStepId("step.current"),
                "Produce output", "Output verified", Set.of(dependency.id()),
                List.of("output exists", "verification passes"),
                new BoundedExecutionHints(2, Duration.ofSeconds(30)),
                List.of("preserve unrelated content"), false, null,
                List.of("validation.output"));
        return new PlanRevision(new PlanRevisionId(id),
                new TaskFrameId("frame.1"), number, parent, "Plan revision",
                NOW, List.of(dependency, current), Map.of());
    }

    private static PlanBindingRecord binding(String revision, long number) {
        return new PlanBindingRecord(
                "binding.1", "task.1", "binding.event", "instruction.1",
                "route.1", "frame.1", "plan.1", revision, number,
                "PLAN_BOOTSTRAP", "plan.1", HASH, "transition.1", NOW);
    }

    private static AuthorityEventRecord authority(String id, long sequence) {
        return new AuthorityEventRecord(id, "task.1", sequence,
                "PLAN_BINDING", "transition.1", HASH, NOW);
    }

    private static TransitionStageRecord stage(
            String eventId, String successorRef, int ordinal) {
        return new TransitionStageRecord(
                "transition.1", ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                "task.1", eventId, ordinal, null, null,
                "STEP_EVENT", successorRef, NOW);
    }

    private static ChainStepAuthorityPort.StepEvent activation(
            String event, String step, long sequence) {
        return stepEvent(event, event, step,
                ChainStepAuthorityPort.StepEventKind.ACTIVATED, sequence);
    }

    private static ChainStepAuthorityPort.StepEvent terminal(
            String event, String activation, String step, long sequence) {
        return stepEvent(event, activation, step,
                ChainStepAuthorityPort.StepEventKind.COMPLETED, sequence);
    }

    private static ChainStepAuthorityPort.StepEvent stepEvent(
            String event, String activation, String step,
            ChainStepAuthorityPort.StepEventKind kind, long sequence) {
        return stepEvent(event, activation, step, kind, sequence,
                "revision.1");
    }

    private static ChainStepAuthorityPort.StepEvent stepEvent(
            String event, String activation, String step,
            ChainStepAuthorityPort.StepEventKind kind, long sequence,
            String revision) {
        return new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        event, "task.1", revision, step, activation, kind,
                        "decision.1", "transition.1", NOW), sequence);
    }

    private static long number(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private static String text(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private record Fixture(
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainStepAuthorityAdapter steps,
            ChainFoundationRepository foundations,
            ProductTaskContractContextProjector task,
            ProductPlanStepContractContextProjector plan) {
    }
}
