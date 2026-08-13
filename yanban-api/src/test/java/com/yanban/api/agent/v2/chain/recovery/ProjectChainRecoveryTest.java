package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.context.ProductChainContextIdentity;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainWorkspaceCandidateWriter;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String FENCE = "b".repeat(64);

    @Test
    void productSourceBuildsExactlyTenFormalCutsAndTransitionRef() {
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.GAP_RESOLUTION, "task-1",
                "decision-1", HASH).transitionId();
        ChainPersistenceRecords.TransitionRecord transition =
                new ChainPersistenceRecords.TransitionRecord(
                        transitionId, "task-1", "event-transition-1",
                        ChainTransitionType.GAP_RESOLUTION,
                        "decision-1", HASH, NOW);
        ChainPersistenceRecords.AuthorityEventRecord authority =
                new ChainPersistenceRecords.AuthorityEventRecord(
                        transition.eventId(), "task-1", 1L,
                        "TRANSITION", transitionId, HASH, NOW);
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 1L,
                        "findAuthorityEvents", ignored -> List.of(authority)));
        Store workflow = new Store();
        workflow.transitions.add(transition);
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, empty(ChainContextRepository.class),
                empty(ChainModelRepository.class), workflow,
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(),
                                request.chainAuthorityCut()));

        ChainRecoveryRuntime.RecoverySnapshot snapshot = source.load("task-1");

        assertEquals(10, snapshot.factCuts().size());
        assertEquals(List.of(ChainRecoveryRuntime.RecoveryFactKind.values()),
                snapshot.factCuts().stream().map(
                        ChainRecoveryRuntime.FactCut::kind).toList());
        assertTrue(snapshot.factCuts().stream().allMatch(value ->
                value.sourceVersion().equals("agent-v2-chain-v70-v80")
                        && value.readBoundary().startsWith(
                        "authority-event-sequence=1;invocation-ordinal=0;")));
        assertEquals(1, snapshot.incompleteTransitions().size());
        assertEquals(transitionId,
                snapshot.incompleteTransitions().get(0).transitionId());
        assertEquals(ChainTransitionStage.OPEN,
                snapshot.incompleteTransitions().get(0).persistedStage());
        assertEquals(1L,
                snapshot.incompleteTransitions().get(0).authoritySequence());
    }

    @Test
    void sourceRejectsAnAuthorityCutWithAMissingMiddleEvent() {
        var first = route("route-1", "event-route-1");
        var third = route("route-3", "event-route-3");
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 3L,
                        "findAuthorityEvents", ignored -> List.of(
                                authority(first, 1L), authority(third, 3L))));
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, empty(ChainContextRepository.class),
                empty(ChainModelRepository.class),
                empty(ChainWorkflowRepository.class),
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(),
                                request.chainAuthorityCut()));

        assertThrows(IllegalStateException.class,
                () -> source.load("task-1"));
    }

    @Test
    void sourceFreezesContextBuildFailureAtItsAuthorityEventSequence() {
        var revision = contextRevision(
                "context-building", ChainRole.EXECUTOR,
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING);
        var failure = new ChainPersistenceRecords.ContextBuildFailureRecord(
                "context-build-failure-1", "task-1",
                "event-context-build-failure-1", revision.contextRevisionId(),
                revision.role(), revision.workState(), revision.callReason(),
                revision.instructionId(),
                io.paperagent.v2.chain.ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "CONTEXT_INPUT_BLOCKED", revision.projectorSetVersion(),
                revision.paginationVersion(), revision.runtimePolicyVersion(),
                NOW);
        var event = authority(failure, 1L);
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 1L,
                        "findAuthorityEvents", ignored -> List.of(event)));
        ChainContextRepository contexts = proxy(
                ChainContextRepository.class, Map.of(
                        "findContextRevisions", ignored -> List.of(revision)));
        io.paperagent.v2.chain.ChainContextBuildFailureRepository failures =
                contextRevisionId -> Optional.of(failure);
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, contexts, failures,
                empty(ChainModelRepository.class),
                empty(ChainWorkflowRepository.class),
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(), request.chainAuthorityCut()));

        var snapshot = source.load("task-1");
        var projection = (ProductChainRecoverySource.RoleProjection)
                snapshot.roleProjection();

        assertEquals(1, projection.contextFailures().size());
        var projected = projection.contextFailures().get(0);
        assertEquals("CONTEXT_BUILD_FAILURE",
                projected.sourceAuthorityType());
        assertEquals(failure.contextBuildFailureId(),
                projected.sourceAuthorityRef());
        assertEquals(1L, projected.authoritySequence());
        assertFalse(projected.successorContextPresent());
        assertTrue(snapshot.factCuts().get(1).authorityRefs().contains(
                "CONTEXT_BUILD_FAILURE:"
                        + failure.contextBuildFailureId()));
        assertTrue(snapshot.factCuts().get(1).authorityRefs().contains(
                "CONTEXT_BUILD_FAILURE_EVENT:" + failure.eventId() + ":1"));
        assertTrue(snapshot.factCuts().get(1).readBoundary().contains(
                "context-failure-set="));
    }

    @Test
    void sourceAlsoFreezesFullInputBlockedContextWithoutInventingAnEvent() {
        var blocked = contextRevision(
                "context-input-blocked", ChainRole.PLANNER,
                io.paperagent.v2.chain.ChainContextRevisionStatus.INPUT_BLOCKED);
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 0L,
                        "findAuthorityEvents", ignored -> List.of()));
        ChainContextRepository contexts = proxy(
                ChainContextRepository.class, Map.of(
                        "findContextRevisions", ignored -> List.of(blocked)));
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, contexts, ignored -> Optional.empty(),
                empty(ChainModelRepository.class),
                empty(ChainWorkflowRepository.class),
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(), request.chainAuthorityCut()));

        var snapshot = source.load("task-1");
        var projection = (ProductChainRecoverySource.RoleProjection)
                snapshot.roleProjection();
        var projected = projection.contextFailures().get(0);

        assertEquals("CONTEXT_REVISION", projected.sourceAuthorityType());
        assertEquals(blocked.contextRevisionId(),
                projected.sourceAuthorityRef());
        assertEquals(0L, projected.authoritySequence());
        assertFalse(projected.successorContextPresent());
    }

    @Test
    void sourceRetriesWhenContextTerminalStateChangesDuringTheRead() {
        var building = contextRevision(
                "context-changing", ChainRole.PLANNER,
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING);
        var blocked = contextRevision(
                "context-changing", ChainRole.PLANNER,
                io.paperagent.v2.chain.ChainContextRevisionStatus.INPUT_BLOCKED);
        AtomicInteger contextReads = new AtomicInteger();
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 0L,
                        "findAuthorityEvents", ignored -> List.of()));
        ChainContextRepository contexts = proxy(
                ChainContextRepository.class, Map.of(
                        "findContextRevisions", ignored ->
                                contextReads.getAndIncrement() == 0
                                        ? List.of(building)
                                        : List.of(blocked)));
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, contexts, ignored -> Optional.empty(),
                empty(ChainModelRepository.class),
                empty(ChainWorkflowRepository.class),
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(), request.chainAuthorityCut()));

        var snapshot = source.load("task-1");
        var projection = (ProductChainRecoverySource.RoleProjection)
                snapshot.roleProjection();

        assertTrue(contextReads.get() >= 4);
        assertEquals("CONTEXT_REVISION",
                projection.contextFailures().get(0).sourceAuthorityType());
    }

    @Test
    void sourceRejectsBuildFailureBoundToAnotherContextIdentity() {
        var revision = contextRevision(
                "context-building", ChainRole.EXECUTOR,
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING);
        var failure = new ChainPersistenceRecords.ContextBuildFailureRecord(
                "context-build-failure-1", "task-1",
                "event-context-build-failure-1", revision.contextRevisionId(),
                ChainRole.PLANNER, revision.workState(), revision.callReason(),
                revision.instructionId(),
                io.paperagent.v2.chain.ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "CONTEXT_INPUT_BLOCKED", revision.projectorSetVersion(),
                revision.paginationVersion(), revision.runtimePolicyVersion(),
                NOW);
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 1L,
                        "findAuthorityEvents", ignored ->
                                List.of(authority(failure, 1L))));
        ChainContextRepository contexts = proxy(
                ChainContextRepository.class, Map.of(
                        "findContextRevisions", ignored -> List.of(revision)));
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, contexts, ignored -> Optional.of(failure),
                empty(ChainModelRepository.class),
                empty(ChainWorkflowRepository.class),
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(), request.chainAuthorityCut()));

        assertThrows(IllegalStateException.class,
                () -> source.load("task-1"));
    }

    @Test
    void sourceRejectsBuildFailureWithoutAuthorityEventInStableCut() {
        var revision = contextRevision(
                "context-building", ChainRole.REFLECTOR,
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING);
        var failure = new ChainPersistenceRecords.ContextBuildFailureRecord(
                "context-build-failure-1", "task-1",
                "event-context-build-failure-1", revision.contextRevisionId(),
                revision.role(), revision.workState(), revision.callReason(),
                revision.instructionId(),
                io.paperagent.v2.chain.ChainContextModule.TASK_CONTRACT,
                "CONTEXT_INPUT_BLOCKED", revision.projectorSetVersion(),
                revision.paginationVersion(), revision.runtimePolicyVersion(),
                NOW);
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 0L,
                        "findAuthorityEvents", ignored -> List.of()));
        ChainContextRepository contexts = proxy(
                ChainContextRepository.class, Map.of(
                        "findContextRevisions", ignored -> List.of(revision)));
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, contexts, ignored -> Optional.of(failure),
                empty(ChainModelRepository.class),
                empty(ChainWorkflowRepository.class),
                empty(ChainFinalizationRepository.class),
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(), request.chainAuthorityCut()));

        assertThrows(IllegalStateException.class,
                () -> source.load("task-1"));
    }

    @Test
    void sourceRejectsProposalStateIdentityAndSequenceCorruption() {
        Scenario accepted = Scenario.acceptedProposal();
        var crossProposal = new ChainPersistenceRecords
                .ProposalStateEventRecord(
                "proposal-other", 1L, "task-1",
                "event-proposal-cross", ChainProposalState.ACCEPTED,
                null, null, NOW);
        assertThrows(IllegalStateException.class,
                () -> snapshot(
                        withProposalStates(accepted, List.of(crossProposal)),
                        List.of(), Map.of()));

        var misplacedSequence = new ChainPersistenceRecords
                .ProposalStateEventRecord(
                "proposal-1", 2L, "task-1",
                "event-proposal-sequence-2", ChainProposalState.ACCEPTED,
                null, null, NOW);
        assertThrows(IllegalStateException.class,
                () -> snapshot(
                        withProposalStates(
                                accepted, List.of(misplacedSequence)),
                        List.of(), Map.of()));
    }

    @Test
    void sourceRejectsCorruptPendingItemEventPrefixes() {
        var response = pendingEvent(
                "gap-1", 1, ChainPendingItemStatus.RESPONSE_RECEIVED,
                "event-response-1", "instruction-answer-1", null, null);
        var resolved = pendingEvent(
                "gap-1", 1, ChainPendingItemStatus.RESOLVED,
                "event-resolved-1", "instruction-answer-1",
                "invocation-validation-1", GapValidation.Outcome.RESOLVED);
        List<List<ChainPersistenceRecords.PendingItemEventRecord>> cases =
                List.of(
                        List.of(pendingEvent(
                                "gap-other", 1,
                                ChainPendingItemStatus.RESPONSE_RECEIVED,
                                "event-cross-gap", "instruction-answer-1",
                                null, null)),
                        List.of(response, resolved, pendingEvent(
                                "gap-1", 2,
                                ChainPendingItemStatus.RESPONSE_RECEIVED,
                                "event-after-resolved", "instruction-answer-2",
                                null, null)),
                        List.of(pendingEvent(
                                "gap-1", 1,
                                ChainPendingItemStatus.RESPONSE_RECEIVED,
                                "event-missing-answer", null, null, null)),
                        List.of(pendingEvent(
                                "gap-1", 2,
                                ChainPendingItemStatus.RESPONSE_RECEIVED,
                                "event-round-gap", "instruction-answer-2",
                                null, null)));
        for (List<ChainPersistenceRecords.PendingItemEventRecord> events
                : cases) {
            assertThrows(IllegalStateException.class,
                    () -> snapshot(
                            withPendingEvents(events), List.of(), Map.of()));
        }
    }

    @Test
    void sourceRetriesWhenAuthorityAdvancesDuringTheRead() {
        var first = route("route-1", "event-route-1");
        var appended = route("route-2", "event-route-2");
        var firstEvent = authority(first, 1L);
        var appendedEvent = authority(appended, 2L);
        AtomicInteger highestReads = new AtomicInteger();
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored ->
                                highestReads.getAndIncrement() == 0 ? 1L : 2L,
                        "findAuthorityEvents", arguments ->
                                ((Number) arguments[1]).longValue() == 1L
                                        ? List.of(firstEvent)
                                        : List.of(firstEvent, appendedEvent)));
        ChainWorkflowRepository workflow = proxy(
                ChainWorkflowRepository.class, Map.of(
                        "findRouteDecisions", ignored ->
                                List.of(first, appended)));
        List<Long> frozenCuts = new ArrayList<>();
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, empty(ChainContextRepository.class),
                empty(ChainModelRepository.class), workflow,
                empty(ChainFinalizationRepository.class),
                request -> {
                    frozenCuts.add(request.chainAuthorityCut());
                    return ProductChainRecoverySource.StableAuthoritySnapshot
                            .empty(request.taskId(),
                                    request.chainAuthorityCut());
                });

        var snapshot = source.load("task-1");

        assertEquals(List.of(1L, 2L), frozenCuts,
                "the stale pass must be discarded and read again");
        assertTrue(snapshot.factCuts().stream().allMatch(value ->
                value.readBoundary().startsWith(
                        "authority-event-sequence=2;")));
        assertTrue(snapshot.factCuts().get(1).authorityRefs()
                .contains("ROUTE_DECISION:route-2"));
    }

    @Test
    void retainedAuthoritySourceReadsExactEffectIntentAndReceipt() {
        var action = action();
        var toolCallId = new io.paperagent.v2.contracts.ToolCallId(
                action.actionId());
        var intent = new io.paperagent.v2.persistence.PersistedEffectIntent(
                new io.paperagent.v2.contracts.EffectIntent(
                        toolCallId,
                        new io.paperagent.v2.contracts.PlanId(action.planId()),
                        new io.paperagent.v2.contracts.PlanStepId(
                                action.stepId()),
                        "project_tool",
                        new io.paperagent.v2.contracts.ObjectValue(Map.of())),
                "lease-owner", 1L,
                new io.paperagent.v2.contracts.EventId(
                        action.activationEventId()));
        var receipt = new io.paperagent.v2.contracts.ExecutionReceipt(
                new io.paperagent.v2.contracts.ReceiptId("receipt-1"),
                toolCallId, io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                NOW, NOW.plusSeconds(1), Optional.empty(), Optional.empty(),
                io.paperagent.v2.contracts.OutputCapture.empty(),
                io.paperagent.v2.contracts.OutputCapture.empty(), List.of(),
                Optional.empty(), List.of());
        var result = new io.paperagent.v2.persistence.PersistedEffectResult(
                receipt, "lease-owner", 1L);
        var stepRepository = empty(
                io.paperagent.v2.persistence.StepRecoveryRepository.class);
        var intentRepository = proxy(
                io.paperagent.v2.persistence.EffectIntentRepository.class,
                Map.of("find", ignored -> io.paperagent.v2.persistence
                        .PersistenceResult.found(intent)));
        var outcomeRepository = proxy(
                io.paperagent.v2.persistence.EffectOutcomeRepository.class,
                Map.of("findResult", ignored -> io.paperagent.v2.persistence
                        .PersistenceResult.found(result)));
        ProductChainRetainedAuthoritySource source =
                new ProductChainRetainedAuthoritySource(
                        stepRepository, intentRepository, outcomeRepository,
                        empty(ChainWorkflowRepository.class),
                        ignored -> {
                            throw new IllegalStateException(
                                    "finalization is forbidden");
                        }, ignored -> Optional.empty());
        var request = new ProductChainRecoverySource.StableAuthorityRequest(
                task(), 1L, List.of(), List.of(action), List.of(), List.of(),
                Map.of());

        var snapshot = source.freeze(request);

        assertTrue(snapshot.facts().stream().anyMatch(value ->
                value.authorityType().equals("EFFECT_INTENT")
                        && value.authorityRef().equals(action.actionId())
                        && value.status().equals("COMMITTED")));
        assertTrue(snapshot.facts().stream().anyMatch(value ->
                value.authorityType().equals("RECEIPT")
                        && value.authorityRef().equals("receipt-1")
                        && value.status().equals("SUCCESS")));
        assertTrue(snapshot.facts().stream().anyMatch(value ->
                value.kind() == ProductChainRecoverySource.StableFactKind
                        .IN_FLIGHT_ACTION
                        && value.status().equals("RESOLVED_SUCCESS")));
        assertTrue(snapshot.readBoundary().startsWith(
                "retained-v2-authority-sha256="));
    }

    @Test
    void retainedAuthoritySourceReadsValidationAndPublishAuthorities() {
        FinalizationFixture fixture = finalizationFixture();
        var passed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.PASSED);
        var source = new ProductChainRetainedAuthoritySource(
                empty(io.paperagent.v2.persistence.StepRecoveryRepository.class),
                empty(io.paperagent.v2.persistence.EffectIntentRepository.class),
                empty(io.paperagent.v2.persistence.EffectOutcomeRepository.class),
                empty(ChainWorkflowRepository.class),
                readiness -> new io.paperagent.v2.chain.finalization
                        .ChainFinalizationAuthorityPort.Available(
                        readiness.taskId(), readiness.instructionId(),
                        readiness.taskFrameId(), readiness.finalPlanId(),
                        readiness.finalPlanRevisionId(),
                        readiness.finalPlanRevisionNumber(),
                        readiness.finalStepId(), readiness.reviewDecisionId(),
                        readiness.acceptedSet().sha256(),
                        readiness.applicabilityCutEventSequence(), true,
                        readiness.coverage().sha256(), null, false, null,
                        readiness.publishRequirement(),
                        readiness.publishRequirementDigest(),
                        readiness.projectVersion()),
                ignored -> Optional.of(
                        new ProductChainRetainedAuthoritySource.PublishAttempt(
                                "publish-operation-1", HASH, "FAILED")));
        var request = new ProductChainRecoverySource.StableAuthorityRequest(
                task(), 2L, List.of(), List.of(), List.of(),
                List.of(fixture.readiness()),
                Map.of(fixture.readiness().readinessId(), List.of(passed)));

        var snapshot = source.freeze(request);

        assertTrue(snapshot.facts().stream().anyMatch(value ->
                value.authorityType().equals("PUBLISH_REQUIREMENT")
                        && value.status().equals("NOT_REQUIRED")));
        assertTrue(snapshot.facts().stream().anyMatch(value ->
                value.authorityType().equals("PUBLISH_ATTEMPT")
                        && value.authorityRef().equals("publish-operation-1")
                        && value.status().equals("FAILED")));
    }

    @Test
    void roleSelectionIsAStableTypedMechanicalProjection() {
        var classify = selection(Scenario.withInstruction(
                ChainInstructionRelation.SUPPLEMENT));
        var validatePending = selection(Scenario.pendingResponse());
        var acceptedPendingValidation = selection(
                Scenario.pendingResponseValidation(false));
        var boundPendingValidation = selection(
                Scenario.pendingResponseValidation(true));
        var acceptedProposal = selection(Scenario.acceptedProposal());
        var acceptedStepBlocked = selection(
                Scenario.acceptedStepBlocked());
        var activeStep = selection(Scenario.activeStep());
        var readiness = selection(Scenario.finalizationReady());
        var planWithoutStep = selection(Scenario.planWithoutStep());
        var superseded = selection(Scenario.superseded());

        List<RoleCase> cases = List.of(
                new RoleCase("supplement", classify,
                        ProductChainNextRoleSelector.Model.class,
                        ChainRole.PLANNER,
                        ChainWorkState.CLASSIFYING_INSTRUCTION),
                new RoleCase("pending response", validatePending,
                        ProductChainNextRoleSelector.Model.class,
                        ChainRole.EXECUTOR,
                        ChainWorkState.VALIDATING_PENDING_ITEM),
                new RoleCase("accepted pending validation",
                        acceptedPendingValidation,
                        ProductChainNextRoleSelector.MechanicalProposal.class,
                        null, null),
                new RoleCase("bound pending validation before resolved event",
                        boundPendingValidation,
                        ProductChainNextRoleSelector.MechanicalProposal.class,
                        null, null),
                new RoleCase("accepted proposal", acceptedProposal,
                        ProductChainNextRoleSelector.MechanicalProposal.class,
                        null, null),
                new RoleCase("accepted Executor Step block",
                        acceptedStepBlocked,
                        ProductChainNextRoleSelector.Model.class,
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW),
                new RoleCase("active step", activeStep,
                        ProductChainNextRoleSelector.Model.class,
                        ChainRole.EXECUTOR, ChainWorkState.EXECUTING),
                new RoleCase("finalization readiness", readiness,
                        ProductChainNextRoleSelector
                                .MechanicalFinalization.class,
                        null, null),
                new RoleCase("plan lacks step authority", planWithoutStep,
                        ProductChainNextRoleSelector.ControlWait.class,
                        null, null),
                new RoleCase("superseded outcome", superseded,
                        ProductChainNextRoleSelector.Model.class,
                        ChainRole.ANSWER, ChainWorkState.TERMINAL));
        for (RoleCase value : cases) {
            assertTrue(value.expectedType().isInstance(value.selection()),
                    value.name());
            if (value.selection()
                    instanceof ProductChainNextRoleSelector.Model model) {
                assertEquals(value.role(), model.directive().role(),
                        value.name());
                assertEquals(value.state(), model.directive().workState(),
                        value.name());
            }
        }
        var mechanical = assertInstanceOf(
                ProductChainNextRoleSelector.MechanicalFinalization.class,
                readiness);
        assertEquals("readiness-1", mechanical.readinessId());
        var wait = assertInstanceOf(
                ProductChainNextRoleSelector.ControlWait.class,
                planWithoutStep);
        assertEquals(ProductChainNextRoleSelector.WaitKind
                        .STEP_AUTHORITY_REQUIRED,
                wait.kind());
        var stepBlock = assertInstanceOf(
                ProductChainNextRoleSelector.Model.class,
                acceptedStepBlocked).directive();
        assertEquals("PROPOSAL_STATE", stepBlock.sourceAuthorityType());
        assertEquals("event-step-block-accepted",
                stepBlock.sourceAuthorityRef());
    }

    @Test
    void permissionResponseNeverUsesModelAsPermissionAuthority() {
        var waiting = assertInstanceOf(
                ProductChainNextRoleSelector.ControlWait.class,
                permissionResponseSelection(false));
        assertEquals(ProductChainNextRoleSelector.WaitKind
                .PERMISSION_DECISION_REQUIRED, waiting.kind());

        var mechanical = assertInstanceOf(
                ProductChainNextRoleSelector.MechanicalPermission.class,
                permissionResponseSelection(true));
        assertEquals("permission-1", mechanical.permissionDecisionId());
    }

    @Test
    void selectorRejectsAProjectionThatWasNotFrozenByTheProductSource() {
        ChainRecoveryRuntime.RecoverySnapshot snapshot = snapshot(
                Scenario.withInstruction(ChainInstructionRelation.INITIAL),
                List.of(), Map.of());
        var fake = new ChainRecoveryRuntime.RecoverySnapshot(
                snapshot.taskId(), snapshot.factCuts(),
                snapshot.incompleteTransitions(),
                new FakeRoleProjection(snapshot.taskId(),
                        snapshot.roleProjection().authorityCut(),
                        snapshot.roleProjection().readBoundary()));

        assertThrows(IllegalStateException.class, () ->
                new ProductChainNextRoleSelector().decide(fake));
    }

    @Test
    void frozenModelInputAcceptsFinalizationAndPublishFailureFactsFromExactCut() {
        ChainRecoveryRuntime.RecoverySnapshot base = snapshot(
                Scenario.withInstruction(ChainInstructionRelation.INITIAL),
                List.of(), Map.of());
        List<ChainRecoveryRuntime.FactCut> cuts = base.factCuts().stream()
                .map(value -> value.kind() == ChainRecoveryRuntime
                        .RecoveryFactKind.VALIDATION_FINALIZATION_AND_PUBLISH
                        ? new ChainRecoveryRuntime.FactCut(
                        value.kind(), value.sourceVersion(), value.readBoundary(),
                        List.of("FINALIZATION_CHECK:check-1:FAILED",
                                "PUBLISH_FAILURE:project-revision-operation:41:"
                                        + HASH + ":FAILED"))
                        : value)
                .toList();
        var frozen = new ChainRecoveryRuntime.RecoverySnapshot(
                base.taskId(), cuts, base.incompleteTransitions(),
                base.roleProjection());

        for (var directive : List.of(
                new ChainRecoveryRuntime.NextDirective(
                        ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                        "FINALIZATION_CHECK", "check-1"),
                new ChainRecoveryRuntime.NextDirective(
                        ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                        "PUBLISH_FAILURE", "project-revision-operation:41"))) {
            assertEquals("instruction-1", ProductChainRecoverySource
                    .frozenModelInput(frozen, directive)
                    .instruction().instructionId());
        }
        assertThrows(IllegalStateException.class, () ->
                ProductChainRecoverySource.frozenModelInput(
                        frozen, new ChainRecoveryRuntime.NextDirective(
                                ChainRole.REFLECTOR,
                                ChainWorkState.AWAITING_REVIEW,
                                "PUBLISH_FAILURE",
                                "project-revision-operation")));
    }

    @Test
    void selectorRejectsTwoUnresolvedFormalStepBlocks() {
        var modelBlock = new ChainPersistenceRecords
                .ModelFailureStepBlockRecord(
                "model-block-1", "task-1", "event-model-block-1",
                "invocation-1", "context-1", "instruction-1", "frame-1",
                "plan-1", "revision-1", 1L, "step-1", "activation-1",
                "invocation-1#3", "MODEL", "MODEL_CALL_FAILED", HASH, NOW);
        var actionBlock = new ChainPersistenceRecords
                .ActionReceiptStepBlockRecord(
                "action-block-1", "task-1", "event-action-block-1",
                "action-1", "RECEIPT", "receipt-1", "receipt-1", HASH,
                "instruction-1", "frame-1",
                "plan-1", "revision-1", 1L, "step-1", "activation-1",
                "repair-proposal-1", "repair-context-1", HASH, 2L, HASH,
                3, "FAILURE", "EXECUTION", "TOOL_FAILED",
                "NO_PROGRESS_THRESHOLD_REACHED",
                ChainRuntimePolicy.V1.policyVersion(), FENCE, HASH, NOW);
        String boundary = "authority-event-sequence=4;block-conflict";
        var projection = new ProductChainRecoverySource.RoleProjection(
                "task-1", "instruction-1", 4L, boundary,
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new ProductChainRecoverySource.Sequenced<>(
                        modelBlock, 3L)),
                List.of(new ProductChainRecoverySource.Sequenced<>(
                        actionBlock, 4L)),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), Optional.empty(), List.of(), Map.of(), List.of(),
                Optional.empty());
        var snapshot = new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", java.util.Arrays.stream(
                ChainRecoveryRuntime.RecoveryFactKind.values())
                .map(value -> new ChainRecoveryRuntime.FactCut(
                        value, "test", boundary, List.of()))
                .toList(), List.of(), projection);

        var conflict = assertThrows(IllegalStateException.class,
                () -> new ProductChainNextRoleSelector().decide(snapshot));
        assertEquals("CHAIN_MULTIPLE_UNRESOLVED_STEP_BLOCKS",
                conflict.getMessage());
    }

    @Test
    void actionReceiptBlockSelectsReflectorOnlyUntilReviewIsCommitted() {
        var actionBlock = new ChainPersistenceRecords
                .ActionReceiptStepBlockRecord(
                "action-block-1", "task-1", "event-action-block-1",
                "action-1", "RECEIPT", "receipt-1", "receipt-1", HASH,
                "instruction-1", "frame-1",
                "plan-1", "revision-1", 1L, "step-1", "activation-1",
                "repair-proposal-1", "repair-context-1", HASH, 2L, HASH,
                3, "FAILURE", "EXECUTION", "TOOL_FAILED",
                "NO_PROGRESS_THRESHOLD_REACHED",
                ChainRuntimePolicy.V1.policyVersion(), FENCE, HASH, NOW);
        var unresolved = actionBlockSnapshot(actionBlock, List.of(),
                Optional.empty(), 4L);

        var reflector = assertInstanceOf(
                ProductChainNextRoleSelector.Model.class,
                new ProductChainNextRoleSelector().decide(unresolved))
                .directive();
        assertEquals(ChainRole.REFLECTOR, reflector.role());
        assertEquals(ChainWorkState.AWAITING_REVIEW,
                reflector.workState());
        assertEquals("ACTION_RECEIPT_STEP_BLOCK",
                reflector.sourceAuthorityType());
        assertEquals(actionBlock.stepBlockId(),
                reflector.sourceAuthorityRef());

        var review = new ChainPersistenceRecords.ReviewDecisionRecord(
                "review-action-block-1", "task-1",
                "event-review-action-block-1", "proposal-review-1",
                "ACTION_RECEIPT_STEP_BLOCK", actionBlock.stepBlockId(),
                ChainProposalKind.REFLECTOR_CONTINUE_STEP,
                "continue after action failure review", json("[]"), FENCE,
                NOW);
        var reviewed = actionBlockSnapshot(actionBlock,
                List.of(new ProductChainRecoverySource.Sequenced<>(
                        review, 5L)),
                Optional.of(new ProductChainRecoverySource.Sequenced<>(
                        terminalOutcome(ChainTaskOutcomeStatus.COMPLETED),
                        6L)), 6L);

        var answer = assertInstanceOf(
                ProductChainNextRoleSelector.Model.class,
                new ProductChainNextRoleSelector().decide(reviewed))
                .directive();
        assertEquals(ChainRole.ANSWER, answer.role());
        assertEquals("TASK_OUTCOME", answer.sourceAuthorityType());
    }

    @Test
    void candidateFailureActionBlockUsesTheSameReflectorSuccessor() {
        var actionBlock = new ChainPersistenceRecords
                .ActionReceiptStepBlockRecord(
                "candidate-block-1", "task-1", "event-candidate-block-1",
                "action-1", "CANDIDATE_MATERIALIZATION_FAILURE",
                "candidate-failure-1", null, null,
                "instruction-1", "frame-1",
                "plan-1", "revision-1", 1L, "step-1", "activation-1",
                "repair-proposal-1", "repair-context-1", HASH, 2L, HASH,
                3, null, "CANDIDATE", "CANDIDATE_NO_ACTUAL_CHANGE",
                "NO_PROGRESS_THRESHOLD_REACHED",
                ChainRuntimePolicy.V1.policyVersion(), FENCE, HASH, NOW);

        var directive = assertInstanceOf(
                ProductChainNextRoleSelector.Model.class,
                new ProductChainNextRoleSelector().decide(
                        actionBlockSnapshot(actionBlock, List.of(),
                                Optional.empty(), 4L)))
                .directive();
        assertEquals(ChainRole.REFLECTOR, directive.role());
        assertEquals(ChainWorkState.AWAITING_REVIEW,
                directive.workState());
        assertEquals("ACTION_RECEIPT_STEP_BLOCK",
                directive.sourceAuthorityType());
        assertEquals(actionBlock.stepBlockId(),
                directive.sourceAuthorityRef());
    }

    private static ChainRecoveryRuntime.RecoverySnapshot actionBlockSnapshot(
            ChainPersistenceRecords.ActionReceiptStepBlockRecord actionBlock,
            List<ProductChainRecoverySource.Sequenced<
                    ChainPersistenceRecords.ReviewDecisionRecord>> reviews,
            Optional<ProductChainRecoverySource.Sequenced<
                    ChainPersistenceRecords.TaskOutcomeRecord>> outcome,
            long authorityCut) {
        String boundary = "authority-event-sequence=" + authorityCut
                + ";action-receipt-block";
        var projection = new ProductChainRecoverySource.RoleProjection(
                "task-1", "instruction-1", authorityCut, boundary,
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new ProductChainRecoverySource.Sequenced<>(
                        actionBlock, 4L)),
                reviews, List.of(), List.of(), List.of(), List.of(),
                List.of(), outcome, List.of(), Map.of(), List.of(),
                Optional.empty());
        return new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", java.util.Arrays.stream(
                ChainRecoveryRuntime.RecoveryFactKind.values())
                .map(value -> new ChainRecoveryRuntime.FactCut(
                        value, "test", boundary, List.of()))
                .toList(), List.of(), projection);
    }

    @Test
    void terminalDeliveryWaitsWithoutSelectingAnswerAgain() {
        for (ChainTaskOutcomeStatus status : List.of(
                ChainTaskOutcomeStatus.COMPLETED,
                ChainTaskOutcomeStatus.SUPERSEDED)) {
            for (ChainDeliveryStatus deliveryStatus : List.of(
                    ChainDeliveryStatus.SUCCEEDED,
                    ChainDeliveryStatus.DELIVERY_FAILED)) {
                var outcome = terminalOutcome(status);
                var delivery = delivery(outcome);
                var events = deliveryPrefix(delivery, deliveryStatus);
                var selected = selection(
                        Scenario.completed(outcome, delivery, events),
                        List.of(delivery),
                        Map.of(delivery.deliveryId(), events));

                var wait = assertInstanceOf(
                        ProductChainNextRoleSelector.ControlWait.class,
                        selected, status + "/" + deliveryStatus);
                assertEquals(ProductChainNextRoleSelector.WaitKind
                                .DELIVERY_TERMINAL,
                        wait.kind(), status + "/" + deliveryStatus);
                assertEquals("DELIVERY", wait.authorityType(), status.name());
                assertEquals(delivery.deliveryId(), wait.authorityRef(),
                        status + "/" + deliveryStatus);
            }
        }
    }

    @Test
    void acceptedTaskOutcomeAnswerRecoversMechanicallyBeforeAnotherModelCall() {
        for (ChainTaskOutcomeStatus status : List.of(
                ChainTaskOutcomeStatus.COMPLETED,
                ChainTaskOutcomeStatus.FAILED)) {
            ProductChainNextRoleSelector.MechanicalProposal selected =
                    assertInstanceOf(
                            ProductChainNextRoleSelector.MechanicalProposal.class,
                            acceptedTaskOutcomeAnswerSelection(status));
            assertEquals("answer-proposal-recovery", selected.proposalId());
            assertEquals("answer-accepted-recovery",
                    selected.acceptedStateEventId());
        }
    }

    @Test
    void acceptedPendingItemAnswerRecoversBeforeAnotherModelCall() {
        for (ChainPendingItemType type : ChainPendingItemType.values()) {
            ProductChainNextRoleSelector.MechanicalProposal selected =
                    assertInstanceOf(
                            ProductChainNextRoleSelector.MechanicalProposal.class,
                            acceptedPendingAnswerSelection(type));
            assertEquals("pending-answer-proposal", selected.proposalId());
            assertEquals("pending-answer-accepted",
                    selected.acceptedStateEventId());
        }
    }

    @Test
    void reservedDeliveryUsesOnlyTypedMechanicalRecovery() {
        for (ChainTaskOutcomeStatus status : List.of(
                ChainTaskOutcomeStatus.COMPLETED,
                ChainTaskOutcomeStatus.SUPERSEDED)) {
            var outcome = terminalOutcome(status);
            var delivery = delivery(outcome);
            var noPending = selection(
                    Scenario.completed(outcome, delivery, List.of()),
                    List.of(delivery), Map.of(delivery.deliveryId(), List.of()));
            assertInstanceOf(ProductChainNextRoleSelector
                    .MechanicalProposal.class, noPending,
                    "a Delivery-without-PENDING crash resumes begin");

            for (ChainDeliveryStatus inFlight : List.of(
                    ChainDeliveryStatus.PENDING,
                    ChainDeliveryStatus.RETRYING)) {
                var events = deliveryPrefix(delivery, inFlight);
                var selected = selection(
                        Scenario.completed(outcome, delivery, events),
                        List.of(delivery),
                        Map.of(delivery.deliveryId(), events));
                var mechanical = assertInstanceOf(
                        ProductChainNextRoleSelector.MechanicalDelivery.class,
                        selected, status + "/" + inFlight);
                assertEquals(delivery.deliveryId(), mechanical.deliveryId());
            }

            var pending = deliveryPrefix(
                    delivery, ChainDeliveryStatus.PENDING);
            Scenario acceptedOnly = withProposalStates(
                    Scenario.completed(outcome, delivery, pending),
                    List.of(Scenario.answerAccepted(outcome)));
            assertInstanceOf(ProductChainNextRoleSelector
                            .MechanicalProposal.class,
                    selection(acceptedOnly, List.of(delivery),
                            Map.of(delivery.deliveryId(), pending)),
                    "a PENDING-before-binding crash resumes proposal commit");
        }
    }

    @Test
    void everyDeliverySourceRecoversMechanicallyWithoutCallingAnswerAgain() {
        for (DeliverySourceCase source : DeliverySourceCase.values()) {
            for (ChainDeliveryStatus status : List.of(
                    ChainDeliveryStatus.PENDING,
                    ChainDeliveryStatus.RETRYING)) {
                var selected = directDeliverySelection(
                        source, status, true, true);
                assertInstanceOf(
                        ProductChainNextRoleSelector.MechanicalDelivery.class,
                        selected, source + "/" + status);
            }
            var terminal = directDeliverySelection(
                    source, ChainDeliveryStatus.SUCCEEDED, true, true);
            assertFalse(terminal instanceof ProductChainNextRoleSelector.Model,
                    source + " terminal Delivery cannot select Answer again");
        }
        assertInstanceOf(ProductChainNextRoleSelector.MechanicalProposal.class,
                directDeliverySelection(DeliverySourceCase.ROUTE,
                        ChainDeliveryStatus.PENDING, true, false),
                "Delivery without PENDING must replay begin");
        assertInstanceOf(ProductChainNextRoleSelector.MechanicalProposal.class,
                directDeliverySelection(DeliverySourceCase.GAP,
                        ChainDeliveryStatus.PENDING, false, true),
                "PENDING without official binding must finish begin");
    }

    @Test
    void gapAndDecisionDeliveriesRejectAStaleInstructionSourceCommand() {
        for (DeliverySourceCase source : List.of(
                DeliverySourceCase.GAP, DeliverySourceCase.DECISION)) {
            assertThrows(IllegalStateException.class, () ->
                    directDeliverySelection(source,
                            ChainDeliveryStatus.PENDING, true, true, true),
                    source + " must bind the source-current instruction");
        }
    }

    @Test
    void malformedFrozenDeliveryEventPrefixIsRejected() {
        var outcome = terminalOutcome(ChainTaskOutcomeStatus.COMPLETED);
        var delivery = delivery(outcome);
        List<List<ChainPersistenceRecords.DeliveryEventRecord>> malformed =
                List.of(
                        List.of(deliveryEvent(
                                delivery, "another-delivery", 1L,
                                ChainDeliveryStatus.PENDING, 0)),
                        List.of(deliveryEvent(
                                delivery, delivery.deliveryId(), 2L,
                                ChainDeliveryStatus.PENDING, 0)),
                        List.of(
                                deliveryEvent(delivery, delivery.deliveryId(),
                                        1L, ChainDeliveryStatus.PENDING, 0),
                                deliveryEvent(delivery, delivery.deliveryId(),
                                        2L, ChainDeliveryStatus.RETRYING, 2)));
        for (List<ChainPersistenceRecords.DeliveryEventRecord> events
                : malformed) {
            assertThrows(IllegalStateException.class, () -> selection(
                    Scenario.completed(outcome, delivery, events),
                    List.of(delivery),
                    Map.of(delivery.deliveryId(), events)));
        }
    }

    @Test
    void compositeRecoveryContinuesTheSamePersistedTransition() {
        Store store = gapAtPendingResolved();
        ChainCompositeTransitionRuntime runtime =
                new ChainCompositeTransitionRuntime(
                        store, store, ignored ->
                        ChainCompositeTransitionRuntime.AuthorityVerification
                                .verified());
        ProductChainFinalizationRecoverySource finalization =
                new ProductChainFinalizationRecoverySource(
                        empty(ChainFoundationRepository.class),
                        store, empty(ChainFinalizationRepository.class),
                        (transition, readiness, check) -> Optional.empty(),
                        ignoredReadiness -> { });
        AtomicInteger continuationCalls = new AtomicInteger();
        ProductChainCompositeTransitionRecovery recovery =
                new ProductChainCompositeTransitionRecovery(
                        store, runtime, command -> {
                    continuationCalls.incrementAndGet();
                    throw new IllegalStateException(
                            "only COMPLETE remains; no successor is allowed");
                }, finalization, (readinessId, committedAt) -> {
                    throw new IllegalStateException(
                            "finalization is forbidden");
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        ChainPersistenceRecords.TransitionRecord transition =
                store.transitions.get(0);

        ChainRecoveryRuntime.TransitionRecoveryResult result = recovery.resume(
                new ChainRecoveryRuntime.TransitionRef(
                        transition.transitionId(), transition.taskId(),
                        transition.transitionType(),
                        ChainTransitionStage.PENDING_RESOLVED, 1L));

        assertEquals(ChainTransitionStage.COMPLETE, result.lastStage());
        assertEquals(transition.transitionId(), result.transitionId());
        assertEquals(0, continuationCalls.get());
        assertEquals(1, store.transitions.size(),
                "recovery must not create another transition identity");
        assertEquals(ChainTransitionStage.COMPLETE,
                store.stages.get(store.stages.size() - 1).stageCode());
    }

    @Test
    void finalizationRecoveryRunsTheMechanicBeforeSelectingFailureBranch() {
        FinalizationFixture fixture = finalizationFixture();
        Store store = new Store();
        addReadinessPredecessor(store, fixture.readiness());
        store.transitions.add(fixture.transition());
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                fixture.transition().transitionId(), ChainTransitionStage.OPEN,
                "task-1", "event-finalization-open", 0,
                null, null, null, null, NOW));
        List<ChainPersistenceRecords.FinalizationCheckRecord> checks =
                new ArrayList<>();
        ChainFinalizationRepository finalization = proxy(
                ChainFinalizationRepository.class, Map.of(
                        "findReadiness", ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> checks));
        ProductChainFinalizationRecoverySource finalizationRecovery =
                new ProductChainFinalizationRecoverySource(
                        empty(ChainFoundationRepository.class),
                        store, finalization,
                        (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        AtomicInteger mechanicalCalls = new AtomicInteger();
        AtomicInteger stageContinuationCalls = new AtomicInteger();
        ProductChainCompositeTransitionRecovery recovery =
                new ProductChainCompositeTransitionRecovery(
                        store,
                        new ChainCompositeTransitionRuntime(
                                store, store, ignored ->
                                ChainCompositeTransitionRuntime
                                        .AuthorityVerification.verified()),
                        command -> {
                            stageContinuationCalls.incrementAndGet();
                            throw new IllegalStateException(
                                    "generic continuation is forbidden");
                        }, finalizationRecovery,
                        (readinessId, committedAt) -> {
                            mechanicalCalls.incrementAndGet();
                            var failed = check(fixture.transition(),
                                    fixture.readiness(),
                                    ChainFinalization.Outcome.FAILED);
                            checks.add(failed);
                            store.stages.add(new ChainPersistenceRecords
                                    .TransitionStageRecord(
                                    fixture.transition().transitionId(),
                                    ChainTransitionStage.READINESS_VERIFIED,
                                    "task-1", "event-readiness-verified", 1,
                                    null, null, "FINALIZATION_READINESS",
                                    readinessId, committedAt));
                            store.stages.add(new ChainPersistenceRecords
                                    .TransitionStageRecord(
                                    fixture.transition().transitionId(),
                                    ChainTransitionStage
                                            .FINALIZATION_CHECK_COMMITTED,
                                    "task-1", "event-check-committed", 2,
                                    null, null, "FINALIZATION_CHECK",
                                    failed.finalizationCheckId(), committedAt));
                            return new io.paperagent.v2.chain.finalization
                                    .ChainFinalizationRuntime.CheckFailed(failed);
                        }, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = recovery.resume(new ChainRecoveryRuntime.TransitionRef(
                fixture.transition().transitionId(), "task-1",
                ChainTransitionType.FINALIZATION, ChainTransitionStage.OPEN,
                1L));

        assertEquals(ChainRecoveryRuntime.TransitionRecoveryDisposition
                        .WAITING_FORMAL_SUCCESSOR,
                result.disposition());
        assertEquals(ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                result.lastStage());
        assertEquals(1, mechanicalCalls.get());
        assertEquals(0, stageContinuationCalls.get());
        assertEquals(fixture.transition().transitionId(),
                result.transitionId());
    }

    @Test
    void finalizationRecoveryRequiresExactReadinessBundleBeforeContinuing() {
        FinalizationFixture fixture = finalizationFixture();
        AtomicInteger verified = new AtomicInteger();
        var source = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                finalizationWorkflow(fixture, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> List.of())),
                (transition, readiness, check) -> Optional.empty(),
                readiness -> verified.incrementAndGet());

        assertInstanceOf(ProductChainFinalizationRecoverySource
                        .RequiresMechanicalFinalization.class,
                source.inspect(fixture.transition()));
        assertEquals(1, verified.get());

        var rejected = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                finalizationWorkflow(fixture, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()))),
                (transition, readiness, check) -> Optional.empty(),
                readiness -> {
                    throw new IllegalStateException(
                            "ValidationBundle identity drift");
                });
        assertThrows(IllegalStateException.class,
                () -> rejected.inspect(fixture.transition()));
    }

    @Test
    void finalizationRecoveryValidatesReadinessAndCheckAuthorityPrefixes() {
        FinalizationFixture fixture = finalizationFixture();
        var readiness = fixture.readiness();
        var retryable = retryableCheck(
                fixture.transition(), readiness, 1);

        var crashWindow = finalizationRecoveryWithChecks(
                fixture, List.of(retryable));
        assertInstanceOf(ProductChainFinalizationRecoverySource
                        .RequiresMechanicalFinalization.class,
                crashWindow.inspect(fixture.transition()));

        var passedAfterRetry = check(
                fixture.transition(), readiness, 2,
                ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                readiness.readinessId(), readiness.taskFrameId());
        var legalPrefix = finalizationRecoveryWithChecks(
                fixture, List.of(retryable, passedAfterRetry));
        assertInstanceOf(ProductChainFinalizationRecoverySource
                        .RequiresMechanicalFinalization.class,
                legalPrefix.inspect(fixture.transition()));

        var retryableAtLimit = retryableCheck(
                fixture.transition(), readiness,
                ChainRuntimePolicy.V1.finalizationMechanicalAttemptsTotal());
        assertThrows(IllegalStateException.class,
                () -> finalizationRecoveryWithChecks(
                        fixture, List.of(retryable, retryableAtLimit))
                        .inspect(fixture.transition()));

        var gap = finalizationRecoveryWithChecks(
                fixture, List.of(passedAfterRetry));
        assertThrows(IllegalStateException.class,
                () -> gap.inspect(fixture.transition()));

        var passed = check(
                fixture.transition(), readiness,
                ChainFinalization.Outcome.PASSED);
        var afterPassed = finalizationRecoveryWithChecks(
                fixture, List.of(passed, passedAfterRetry));
        assertThrows(IllegalStateException.class,
                () -> afterPassed.inspect(fixture.transition()));

        var failed = check(
                fixture.transition(), readiness,
                ChainFinalization.Outcome.FAILED);
        var afterFailedTerminal = finalizationRecoveryWithChecks(
                fixture, List.of(failed, passedAfterRetry));
        assertThrows(IllegalStateException.class,
                () -> afterFailedTerminal.inspect(fixture.transition()));

        var wrongReadinessId = check(
                fixture.transition(), readiness, 1,
                ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                "readiness-other", readiness.taskFrameId());
        assertThrows(IllegalStateException.class,
                () -> finalizationRecoveryWithChecks(
                        fixture, List.of(wrongReadinessId))
                        .inspect(fixture.transition()));

        var wrongFrozenField = check(
                fixture.transition(), readiness, 1,
                ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                readiness.readinessId(), "frame-other");
        assertThrows(IllegalStateException.class,
                () -> finalizationRecoveryWithChecks(
                        fixture, List.of(wrongFrozenField))
                        .inspect(fixture.transition()));

        var wrongPolicy = check(
                fixture.transition(), readiness, 1,
                ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                readiness.readinessId(), readiness.taskFrameId(),
                "chain-runtime-policy-other");
        assertThrows(IllegalStateException.class,
                () -> finalizationRecoveryWithChecks(
                        fixture, List.of(wrongPolicy))
                        .inspect(fixture.transition()));
    }

    @Test
    void finalizationRecoveryRejectsCrossTaskReadiness() {
        FinalizationFixture fixture = finalizationFixture();
        var crossTask = copyReadiness(
                fixture.readiness(), "task-other",
                fixture.readiness().transitionId(),
                fixture.readiness().taskFrameId());
        var crossTaskTransition = transitionWithReadinessTarget(
                fixture.transition(), crossTask);
        assertThrows(IllegalStateException.class,
                () -> finalizationRecoveryWithReadiness(crossTask)
                        .inspect(crossTaskTransition));
    }

    @Test
    void finalizationRecoveryRejectsMissingWrongAndIncompletePredecessors() {
        FinalizationFixture fixture = finalizationFixture();
        ChainFinalizationRepository finalization = proxy(
                ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> List.of()));
        var missing = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                empty(ChainWorkflowRepository.class), finalization,
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> missing.inspect(fixture.transition()));

        String wrongId = new ChainIdentity.Transition(
                ChainTransitionType.GAP_RESOLUTION, "task-1",
                "review-1", HASH).transitionId();
        var wrongPredecessor = new ChainPersistenceRecords.TransitionRecord(
                wrongId, "task-1", "wrong-predecessor-event",
                ChainTransitionType.GAP_RESOLUTION, "review-1", HASH, NOW);
        var wrong = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                proxy(ChainWorkflowRepository.class, Map.of(
                        "findTransition",
                        ignored -> Optional.of(wrongPredecessor))),
                finalization,
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> wrong.inspect(fixture.transition()));

        var predecessor = readinessTransition();
        var incomplete = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                proxy(ChainWorkflowRepository.class, Map.of(
                        "findTransition", ignored -> Optional.of(predecessor),
                        "findTransitionStages", ignored -> List.of(
                                readinessPredecessorStages(predecessor).get(0)))),
                finalization,
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> incomplete.inspect(fixture.transition()));

        List<ChainPersistenceRecords.TransitionStageRecord> complete =
                readinessPredecessorStages(predecessor);
        var missingAccepted = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                proxy(ChainWorkflowRepository.class, Map.of(
                        "findTransition", ignored -> Optional.of(predecessor),
                        "findTransitionStages", ignored -> complete,
                        "findAcceptedResults", ignored -> List.of(),
                        "findApplicabilityDecisions", ignored -> List.of())),
                finalization,
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> missingAccepted.inspect(fixture.transition()),
                "a complete stage list cannot replace AcceptedResult authority");

        var wrongReadinessStage = new ArrayList<>(complete);
        var committed = wrongReadinessStage.get(4);
        wrongReadinessStage.set(4,
                new ChainPersistenceRecords.TransitionStageRecord(
                        committed.transitionId(), committed.stageCode(),
                        committed.taskId(), committed.eventId(),
                        committed.stageOrdinal(), null, null,
                        "FINALIZATION_READINESS", "readiness-other",
                        committed.committedAt()));
        var wrongReadiness = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                proxy(ChainWorkflowRepository.class, Map.of(
                        "findTransition", ignored -> Optional.of(predecessor),
                        "findTransitionStages", ignored -> wrongReadinessStage,
                        "findCandidateStepResults", ignored -> List.of(
                                readinessCandidateResult()),
                        "findAcceptedResults", ignored -> List.of(
                                readinessAcceptedResult()),
                        "findApplicabilityDecisions", ignored -> List.of(),
                        "findReviewDecisions", ignored -> List.of(
                                readinessReviewDecision()))),
                finalization,
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> wrongReadiness.inspect(fixture.transition()),
                "READINESS_COMMITTED must bind the exact readiness fact");

        var wrongStepStages = new ArrayList<>(complete);
        var step = wrongStepStages.get(3);
        wrongStepStages.set(3,
                new ChainPersistenceRecords.TransitionStageRecord(
                        step.transitionId(), step.stageCode(), step.taskId(),
                        step.eventId(), step.stageOrdinal(), null, null,
                        "STEP_EVENT", "step.completed.wrong",
                        step.committedAt()));
        var wrongStep = new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                proxy(ChainWorkflowRepository.class, Map.of(
                        "findTransition", ignored -> Optional.of(predecessor),
                        "findTransitionStages", ignored -> wrongStepStages,
                        "findCandidateStepResults", ignored -> List.of(
                                readinessCandidateResult()),
                        "findAcceptedResults", ignored -> List.of(
                                readinessAcceptedResult()),
                        "findApplicabilityDecisions", ignored -> List.of(),
                        "findReviewDecisions", ignored -> List.of(
                                readinessReviewDecision()))),
                finalization,
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> wrongStep.inspect(fixture.transition()),
                "STEP_EVENT must bind the exact final completion event");

        var wrongApplicabilityStages = new ArrayList<>(complete);
        var applicabilityStage = wrongApplicabilityStages.get(2);
        wrongApplicabilityStages.set(2,
                new ChainPersistenceRecords.TransitionStageRecord(
                        applicabilityStage.transitionId(),
                        applicabilityStage.stageCode(),
                        applicabilityStage.taskId(),
                        applicabilityStage.eventId(),
                        applicabilityStage.stageOrdinal(), null, null,
                        "RESULT_APPLICABILITY", "applicability-1",
                        applicabilityStage.committedAt()));
        var wrongApplicability =
                new ChainPersistenceRecords.ResultApplicabilityRecord(
                        "applicability-1", "task-1", "applicability-event-1",
                        "accepted-1",
                        io.paperagent.v2.chain.ChainApplicability.SourceType
                                .PLAN_REVISION,
                        predecessor.transitionId(), "frame-1", "plan-1",
                        "revision-1", ChainIdentity.NONE, "instruction-1",
                        io.paperagent.v2.chain.ChainApplicability.Outcome
                                .APPLICABLE,
                        "wrong source kind", NOW);
        var wrongApplicabilitySource =
                new ProductChainFinalizationRecoverySource(
                        empty(ChainFoundationRepository.class),
                        proxy(ChainWorkflowRepository.class, Map.of(
                                "findTransition",
                                ignored -> Optional.of(predecessor),
                                "findTransitionStages",
                                ignored -> wrongApplicabilityStages,
                                "findCandidateStepResults", ignored -> List.of(
                                        readinessCandidateResult()),
                                "findAcceptedResults", ignored -> List.of(
                                        readinessAcceptedResult()),
                                "findApplicabilityDecisions", ignored -> List.of(
                                        wrongApplicability),
                                "findReviewDecisions", ignored -> List.of(
                                        readinessReviewDecision()))),
                        finalization,
                        (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        assertThrows(IllegalStateException.class,
                () -> wrongApplicabilitySource.inspect(fixture.transition()),
                "readiness applicability must originate from ACCEPT_STEP");
    }

    @Test
    void finalizationFailuresRemainTypedFormalSuccessorWaits() {
        FinalizationFixture fixture = finalizationFixture();
        ChainPersistenceRecords.TransitionRecord transition =
                fixture.transition();
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                fixture.readiness();
        ChainWorkflowRepository workflow = finalizationWorkflow(
                fixture, List.of());

        ProductChainFinalizationRecoverySource checkFailure =
                new ProductChainFinalizationRecoverySource(
                        empty(ChainFoundationRepository.class),
                        workflow,
                        finalization(readiness, check(
                                transition, readiness,
                                ChainFinalization.Outcome.FAILED)),
                        (ignoredTransition, ignoredReadiness, ignoredCheck) ->
                                Optional.empty(), ignoredReadiness -> { });
        var failedCheck = assertInstanceOf(
                ProductChainFinalizationRecoverySource.CheckFailure.class,
                checkFailure.inspect(transition));
        assertEquals("finalization-check-1",
                failedCheck.reason().finalizationCheckId());
        assertEquals(ChainFinalization.ErrorCode.VALIDATION_NOT_SUCCESSFUL,
                failedCheck.reason().errorCode());

        ProductChainFinalizationRecoverySource publishFailure =
                new ProductChainFinalizationRecoverySource(
                        empty(ChainFoundationRepository.class),
                        workflow,
                        finalization(readiness, check(
                                transition, readiness,
                                ChainFinalization.Outcome.PASSED)),
                        (ignoredTransition, ignoredReadiness, ignoredCheck) ->
                                Optional.of(new ProductChainFinalizationRecoverySource
                                        .PublishFailure(
                                        "publish-failure-1",
                                        ChainProjectPublishPort.ErrorCode
                                                .VERSION_CONFLICT,
                                        false)), ignoredReadiness -> { });
        var failedPublish = assertInstanceOf(
                ProductChainFinalizationRecoverySource.PublishFailureState.class,
                publishFailure.inspect(transition));
        assertEquals("publish-failure-1",
                failedPublish.reason().formalFailureRef());
        assertEquals(ChainProjectPublishPort.ErrorCode.VERSION_CONFLICT,
                failedPublish.reason().errorCode());
    }

    @Test
    void impreciseFailedFinalizationHandoffsRemainFormalSuccessorWaits() {
        FinalizationFixture fixture = finalizationFixture();
        var failed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.FAILED);
        var wrongKind = failedCheckReview(
                failed, ChainProposalKind.REFLECTOR_CONTINUE_STEP);
        var wrongOutcome = failedFinalizationOutcome(
                failed, fixture.transition().transitionId());

        record InvalidHandoff(
                String name,
                List<ChainPersistenceRecords.ReviewDecisionRecord> reviews,
                Optional<ChainPersistenceRecords.TaskOutcomeRecord> outcome) {
        }
        List<InvalidHandoff> cases = List.of(
                new InvalidHandoff("wrong review kind",
                        List.of(wrongKind), Optional.empty()),
                new InvalidHandoff("review has another object type",
                        List.of(failedCheckReview(
                                failed, "PUBLISH_FAILURE",
                                failed.finalizationCheckId(),
                                ChainProposalKind.REFLECTOR_TASK_FAILED)),
                        Optional.empty()),
                new InvalidHandoff("review has another object id",
                        List.of(failedCheckReview(
                                failed, "FINALIZATION_CHECK",
                                "finalization-check-other",
                                ChainProposalKind.REFLECTOR_TASK_FAILED)),
                        Optional.empty()),
                new InvalidHandoff("outcome bound to transition",
                        List.of(), Optional.of(wrongOutcome)),
                new InvalidHandoff("outcome bound to another command",
                        List.of(), Optional.of(failedFinalizationOutcome(
                        failed, failed.finalizationCheckId(),
                        "command-other", "FINALIZATION",
                        failed.errorCode().name()))),
                new InvalidHandoff("outcome has another failure category",
                        List.of(), Optional.of(failedFinalizationOutcome(
                        failed, failed.finalizationCheckId(), "command-1",
                        "EXECUTION", failed.errorCode().name()))),
                new InvalidHandoff("outcome has another failure code",
                        List.of(), Optional.of(failedFinalizationOutcome(
                        failed, failed.finalizationCheckId(), "command-1",
                        "FINALIZATION", "OTHER_FAILURE"))));
        for (InvalidHandoff value : cases) {
            Store store = failedFinalizationStore(fixture, failed);
            store.reviews.addAll(value.reviews());
            ChainFinalizationRepository finalization = proxy(
                    ChainFinalizationRepository.class, Map.of(
                            "findReadiness",
                            ignored -> List.of(fixture.readiness()),
                            "findFinalizationChecks",
                            ignored -> List.of(failed),
                            "findTaskOutcome", ignored -> value.outcome()));
            var source = new ProductChainFinalizationRecoverySource(
                    finalizationFoundations(),
                    store, finalization,
                    (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
            var recovery = new ProductChainCompositeTransitionRecovery(
                    store,
                    new ChainCompositeTransitionRuntime(
                            store, store, ignored ->
                            ChainCompositeTransitionRuntime
                                    .AuthorityVerification.verified()),
                    command -> {
                        throw new IllegalStateException(
                                "invalid handoff cannot continue");
                    }, source, (readinessId, committedAt) -> {
                throw new IllegalStateException(
                        "committed check cannot rerun finalization");
            }, Clock.fixed(NOW, ZoneOffset.UTC));

            var result = recovery.resume(
                    new ChainRecoveryRuntime.TransitionRef(
                            fixture.transition().transitionId(), "task-1",
                            ChainTransitionType.FINALIZATION,
                            ChainTransitionStage
                                    .FINALIZATION_CHECK_COMMITTED,
                            1L));

            assertEquals(ChainRecoveryRuntime
                            .TransitionRecoveryDisposition
                            .WAITING_FORMAL_SUCCESSOR,
                    result.disposition(), value.name());
            assertInstanceOf(ChainRecoveryRuntime.CheckFailureWait.class,
                    result.formalSuccessorWait(), value.name());
        }
    }

    @Test
    void exactFailedFinalizationHandoffsSelectTheFailureBranch() {
        FinalizationFixture fixture = finalizationFixture();
        var failed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.FAILED);
        var foundations = finalizationFoundations();

        for (ChainProposalKind kind : List.of(
                ChainProposalKind.REFLECTOR_REPLAN_REQUIRED,
                ChainProposalKind.REFLECTOR_NEED_PERMISSION,
                ChainProposalKind.REFLECTOR_TASK_FAILED)) {
            var reviewSource = new ProductChainFinalizationRecoverySource(
                    foundations,
                    finalizationWorkflow(fixture, List.of(
                            failedCheckReview(failed, kind))),
                    finalization(fixture.readiness(), failed),
                    (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
            var reviewContinue = assertInstanceOf(
                    ProductChainFinalizationRecoverySource.Continue.class,
                    reviewSource.inspect(fixture.transition()));
            assertEquals(
                    ChainCompositeTransitionRuntime.Branch.FINALIZATION_FAILED,
                    reviewContinue.branch(), kind.name());
        }

        var exactOutcome = failedFinalizationOutcome(
                failed, failed.finalizationCheckId());
        var outcomeSource = new ProductChainFinalizationRecoverySource(
                foundations, finalizationWorkflow(fixture, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> List.of(failed),
                        "findTaskOutcome",
                        ignored -> Optional.of(exactOutcome))),
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        var outcomeContinue = assertInstanceOf(
                ProductChainFinalizationRecoverySource.Continue.class,
                outcomeSource.inspect(fixture.transition()));
        assertEquals(ChainCompositeTransitionRuntime.Branch.FINALIZATION_FAILED,
                outcomeContinue.branch());
    }

    @Test
    void exactPublishFailureHandoffsSelectTheFailureBranch() {
        FinalizationFixture fixture = finalizationFixture();
        var passed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.PASSED);
        var failure = publishFailure();
        var foundations = finalizationFoundations();

        var reviewSource = new ProductChainFinalizationRecoverySource(
                foundations,
                finalizationWorkflow(fixture, List.of(
                        publishFailureReview(
                                failure.formalFailureRef(),
                                ChainProposalKind
                                        .REFLECTOR_NEED_PERMISSION))),
                finalization(fixture.readiness(), passed),
                (transition, readiness, check) -> Optional.of(failure), ignoredReadiness -> { });
        var reviewContinue = assertInstanceOf(
                ProductChainFinalizationRecoverySource.Continue.class,
                reviewSource.inspect(fixture.transition()));
        assertEquals(ChainCompositeTransitionRuntime.Branch.FINALIZATION_FAILED,
                reviewContinue.branch());

        var exactOutcome = failedPublishOutcome(
                failure.formalFailureRef(), "command-1", "PUBLISH",
                failure.errorCode().name());
        var outcomeSource = new ProductChainFinalizationRecoverySource(
                foundations, finalizationWorkflow(fixture, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> List.of(passed),
                        "findTaskOutcome",
                        ignored -> Optional.of(exactOutcome))),
                (transition, readiness, check) -> Optional.of(failure), ignoredReadiness -> { });
        var outcomeContinue = assertInstanceOf(
                ProductChainFinalizationRecoverySource.Continue.class,
                outcomeSource.inspect(fixture.transition()));
        assertEquals(ChainCompositeTransitionRuntime.Branch.FINALIZATION_FAILED,
                outcomeContinue.branch());
    }

    @Test
    void imprecisePublishFailureHandoffsRemainFormalSuccessorWaits() {
        FinalizationFixture fixture = finalizationFixture();
        var passed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.PASSED);
        var failure = publishFailure();

        record InvalidHandoff(
                String name,
                List<ChainPersistenceRecords.ReviewDecisionRecord> reviews,
                Optional<ChainPersistenceRecords.TaskOutcomeRecord> outcome) {
        }
        List<InvalidHandoff> cases = List.of(
                new InvalidHandoff("wrong review kind",
                        List.of(publishFailureReview(
                                failure.formalFailureRef(),
                                ChainProposalKind.REFLECTOR_CONTINUE_STEP)),
                        Optional.empty()),
                new InvalidHandoff("review has another object type",
                        List.of(failedCheckReview(
                                passed, "FINALIZATION_CHECK",
                                failure.formalFailureRef(),
                                ChainProposalKind.REFLECTOR_TASK_FAILED)),
                        Optional.empty()),
                new InvalidHandoff("review has another object id",
                        List.of(publishFailureReview(
                                "publish-failure-other",
                                ChainProposalKind.REFLECTOR_TASK_FAILED)),
                        Optional.empty()),
                new InvalidHandoff("outcome has another source",
                        List.of(), Optional.of(failedPublishOutcome(
                        "publish-failure-other", "command-1", "PUBLISH",
                        failure.errorCode().name()))),
                new InvalidHandoff("outcome has another command",
                        List.of(), Optional.of(failedPublishOutcome(
                        failure.formalFailureRef(), "command-other", "PUBLISH",
                        failure.errorCode().name()))),
                new InvalidHandoff("outcome has another category",
                        List.of(), Optional.of(failedPublishOutcome(
                        failure.formalFailureRef(), "command-1", "FINALIZATION",
                        failure.errorCode().name()))),
                new InvalidHandoff("outcome has another code",
                        List.of(), Optional.of(failedPublishOutcome(
                        failure.formalFailureRef(), "command-1", "PUBLISH",
                        ChainProjectPublishPort.ErrorCode
                                .AUTHORITY_TEMPORARILY_UNAVAILABLE.name()))));
        for (InvalidHandoff value : cases) {
            var source = new ProductChainFinalizationRecoverySource(
                    finalizationFoundations(),
                    finalizationWorkflow(fixture, value.reviews()),
                    proxy(ChainFinalizationRepository.class, Map.of(
                            "findReadiness",
                            ignored -> List.of(fixture.readiness()),
                            "findFinalizationChecks", ignored -> List.of(passed),
                            "findTaskOutcome", ignored -> value.outcome())),
                    (transition, readiness, check) -> Optional.of(failure), ignoredReadiness -> { });

            var result = assertInstanceOf(
                    ProductChainFinalizationRecoverySource
                            .PublishFailureState.class,
                    source.inspect(fixture.transition()), value.name());
            assertEquals(failure.formalFailureRef(),
                    result.reason().formalFailureRef(), value.name());
        }
    }

    @Test
    void onlyExactCompletedOutcomeSelectsFinalizationSuccess() {
        FinalizationFixture fixture = finalizationFixture();
        var passed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.PASSED);
        var foundations = finalizationFoundations();

        var exactSource = finalizationRecoveryWithOutcome(
                fixture, passed, foundations,
                completedOutcome(fixture, ChainTaskOutcomeStatus.COMPLETED,
                        fixture.transition().transitionId(), "command-1",
                        fixture.readiness().taskFrameId(), false));
        var exact = assertInstanceOf(
                ProductChainFinalizationRecoverySource.Continue.class,
                exactSource.inspect(fixture.transition()));
        assertEquals(ChainCompositeTransitionRuntime.Branch.FINALIZATION_SUCCESS,
                exact.branch());

        record InvalidOutcome(
                String name,
                ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        }
        List<InvalidOutcome> invalid = List.of(
                new InvalidOutcome("wrong status", completedOutcome(
                        fixture, ChainTaskOutcomeStatus.CANCELLED,
                        fixture.transition().transitionId(), "command-1",
                        fixture.readiness().taskFrameId(), false)),
                new InvalidOutcome("wrong source", completedOutcome(
                        fixture, ChainTaskOutcomeStatus.COMPLETED,
                        "transition-other", "command-1",
                        fixture.readiness().taskFrameId(), false)),
                new InvalidOutcome("wrong root command", completedOutcome(
                        fixture, ChainTaskOutcomeStatus.COMPLETED,
                        fixture.transition().transitionId(), "command-other",
                        fixture.readiness().taskFrameId(), false)),
                new InvalidOutcome("wrong frozen identity", completedOutcome(
                        fixture, ChainTaskOutcomeStatus.COMPLETED,
                        fixture.transition().transitionId(), "command-1",
                        "frame-other", false)),
                new InvalidOutcome("unexpected publish identity",
                        completedOutcome(
                                fixture, ChainTaskOutcomeStatus.COMPLETED,
                                fixture.transition().transitionId(),
                                "command-1",
                                fixture.readiness().taskFrameId(), true)));
        for (InvalidOutcome value : invalid) {
            var source = finalizationRecoveryWithOutcome(
                    fixture, passed, foundations, value.outcome());
            assertInstanceOf(ProductChainFinalizationRecoverySource
                            .RequiresMechanicalFinalization.class,
                    source.inspect(fixture.transition()), value.name());
        }

        FinalizationFixture required = finalizationFixture(
                ChainPublishRequirement.REQUIRED);
        var requiredPassed = check(
                required.transition(), required.readiness(),
                ChainFinalization.Outcome.PASSED);
        var missingPublish = finalizationRecoveryWithOutcome(
                required, requiredPassed, foundations,
                completedOutcome(
                        required, ChainTaskOutcomeStatus.COMPLETED,
                        required.transition().transitionId(), "command-1",
                        required.readiness().taskFrameId(), false));
        assertInstanceOf(ProductChainFinalizationRecoverySource
                        .RequiresMechanicalFinalization.class,
                missingPublish.inspect(required.transition()));

        var exactPublished = finalizationRecoveryWithOutcome(
                required, requiredPassed, foundations,
                completedOutcome(
                        required, ChainTaskOutcomeStatus.COMPLETED,
                        required.transition().transitionId(), "command-1",
                        required.readiness().taskFrameId(), true));
        var published = assertInstanceOf(
                ProductChainFinalizationRecoverySource.Continue.class,
                exactPublished.inspect(required.transition()));
        assertEquals(ChainCompositeTransitionRuntime.Branch.FINALIZATION_SUCCESS,
                published.branch());
    }

    @Test
    void completedOutcomeFollowsTheReadinessInstructionCommand() {
        FinalizationFixture fixture = finalizationFixture();
        var passed = check(fixture.transition(), fixture.readiness(),
                ChainFinalization.Outcome.PASSED);
        var currentFoundations = finalizationFoundations("command-current");
        var currentOutcome = completedOutcome(
                fixture, ChainTaskOutcomeStatus.COMPLETED,
                fixture.transition().transitionId(), "command-current",
                fixture.readiness().taskFrameId(), false);
        assertInstanceOf(ProductChainFinalizationRecoverySource.Continue.class,
                finalizationRecoveryWithOutcome(
                        fixture, passed, currentFoundations, currentOutcome)
                        .inspect(fixture.transition()));

        var rootOutcome = completedOutcome(
                fixture, ChainTaskOutcomeStatus.COMPLETED,
                fixture.transition().transitionId(), "command-1",
                fixture.readiness().taskFrameId(), false);
        assertInstanceOf(ProductChainFinalizationRecoverySource
                        .RequiresMechanicalFinalization.class,
                finalizationRecoveryWithOutcome(
                        fixture, passed, currentFoundations, rootOutcome)
                        .inspect(fixture.transition()));
    }

    @Test
    void coordinatorReconcilesUnknownInFlightActionWithoutDispatch() {
        Store store = new Store();
        ChainPersistenceRecords.ActionBindingRecord action = action();
        store.actions.add(action);
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, Map.of(
                        "findTask", ignored -> Optional.of(task()),
                        "highestAuthorityEventSequence", ignored -> 0L));
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        action.proposalId(), action.taskId(), "invocation-1", 1,
                        ChainRole.EXECUTOR,
                        ChainProposalKind.EXECUTOR_TOOL_ACTION,
                        new ChainPersistenceRecords.CanonicalJson(1, HASH, "{}"),
                        new ChainPersistenceRecords.CanonicalJson(1, HASH, "[]"),
                        null, null, NOW);
        ChainModelRepository models = proxy(
                ChainModelRepository.class, Map.of(
                        "findProposal", ignored -> Optional.of(proposal)));
        ChainFinalizationRepository finalization =
                empty(ChainFinalizationRepository.class);
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, empty(ChainContextRepository.class), models,
                store, finalization,
                request -> ProductChainRecoverySource
                        .StableAuthoritySnapshot.empty(
                                request.taskId(),
                                request.chainAuthorityCut()));
        ProductChainFinalizationRecoverySource finalizationRecovery =
                new ProductChainFinalizationRecoverySource(
                        empty(ChainFoundationRepository.class),
                        store, finalization,
                        (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
        ProductChainCompositeTransitionRecovery transitions =
                new ProductChainCompositeTransitionRecovery(
                        store,
                        new ChainCompositeTransitionRuntime(
                                store, store, ignored ->
                                ChainCompositeTransitionRuntime
                                        .AuthorityVerification.verified()),
                        command -> ChainCompositeTransitionRuntime
                                .StageCommitResult.none(),
                        finalizationRecovery,
                        (readinessId, committedAt) -> {
                            throw new IllegalStateException(
                                    "finalization is forbidden");
                        },
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger prepares = new AtomicInteger();
        AtomicInteger dispatches = new AtomicInteger();
        ChainEffectRuntime effects = new ChainEffectRuntime(
                store, models, new ChainEffectRuntime.EffectAuthority() {
                    @Override
                    public ChainEffectRuntime.EffectReconciliation reconcile(
                            ChainEffectRuntime.FrozenMutation frozen) {
                        return new ChainEffectRuntime.EffectReconciliation(
                                frozen.actionId(), frozen.idempotencyKey(),
                                ChainEffectRuntime.EffectStatus.UNKNOWN,
                                null, null, "chain-effect-intent."
                                + frozen.actionId(), null);
                    }

                    @Override
                    public ChainEffectRuntime.PreparedEffect prepare(
                            ChainEffectRuntime.FrozenMutation frozen) {
                        prepares.incrementAndGet();
                        return new ChainEffectRuntime.PreparedEffect(
                                "intent", frozen.actionId(),
                                frozen.idempotencyKey(),
                                frozen.versionFenceSha256(), "permit");
                    }

                    @Override
                    public ChainEffectRuntime.EffectReconciliation dispatch(
                            ChainEffectRuntime.PreparedEffect prepared) {
                        dispatches.incrementAndGet();
                        throw new IllegalStateException("dispatch is forbidden");
                    }
                },
                (taskId, actionId) -> {
                    throw new IllegalStateException("workspace source is forbidden");
                },
                new ChainEffectRuntime.WorkspaceCandidateAuthority() {
                    @Override
                    public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
                            ChainEffectRuntime.CandidateMutation mutation) {
                        return Optional.empty();
                    }

                    @Override
                    public ChainEffectRuntime.MaterializedCandidate materialize(
                            ChainEffectRuntime.CandidateMutation mutation,
                            ChainEffectRuntime.CandidateBindingPort binding) {
                        throw new IllegalStateException("Candidate is forbidden");
                    }
                }, store, ignored -> ChainEffectRuntime.GateStatus.CURRENT);
        ProductChainRecoveryCoordinator coordinator =
                new ProductChainRecoveryCoordinator(
                        source, transitions, store, effects,
                        new ProductChainNextRoleSelector(),
                        (readinessId, committedAt) -> {
                            throw new IllegalStateException(
                                    "finalization is forbidden");
                        });

        var recovered = assertInstanceOf(
                ProductChainRecoveryCoordinator.RuntimeOutcome.class,
                coordinator.recover("task-1", NOW));
        ChainRecoveryRuntime.RecoveryOutcome outcome = recovered.outcome();

        assertEquals(ChainRecoveryRuntime.RecoveryDisposition.WAITING_IN_FLIGHT,
                outcome.disposition());
        assertEquals("chain-effect-intent.action-1",
                outcome.inFlightRecovery().actions().get(0).uncertaintyRef());
        assertEquals(0, prepares.get());
        assertEquals(0, dispatches.get());
        assertFalse(outcome.inFlightRecovery().actions().isEmpty());
    }

    private static ProductChainNextRoleSelector.Selection selection(
            Scenario scenario) {
        return selection(scenario, List.of(), Map.of());
    }

    private static ProductChainNextRoleSelector.Selection
            directDeliverySelection(
            DeliverySourceCase source,
            ChainDeliveryStatus status,
            boolean bound,
            boolean includePending) {
        return directDeliverySelection(
                source, status, bound, includePending, false);
    }

    private static ProductChainNextRoleSelector.Selection
            directDeliverySelection(
            DeliverySourceCase source,
            ChainDeliveryStatus status,
            boolean bound,
            boolean includePending,
            boolean staleSourceCommand) {
        var binding = new ChainPersistenceRecords
                .TaskInstructionBindingRecord(
                "task-1", "binding-event", "instruction-1", 1L,
                ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
        var instruction = new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 1L, "task-1", 1L,
                HASH, "message-1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
        var currentBinding = new ChainPersistenceRecords
                .TaskInstructionBindingRecord(
                "task-1", "binding-event-current", "instruction-current", 2L,
                ChainPersistenceRecords.BindingRole.INHERITED_ROOT, NOW);
        var currentInstruction = new ChainPersistenceRecords.InstructionRecord(
                "instruction-current", "command-current", 1L, "task-1", 2L,
                HASH, "message-2", ChainInstructionRelation.SUPPLEMENT,
                "instruction-1", null, HASH, NOW);
        var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                "answer-invocation", "task-1", "answer-context",
                 "answer-completion", ChainRole.ANSWER,
                 ChainWorkState.DELIVERING, "delivery", "provider", "model",
                 1, ChainRuntimePolicy.V1.policyVersion(), NOW);
        ChainProposalKind kind = switch (source) {
            case ROUTE -> ChainProposalKind.ANSWER_DIRECT_ANSWER;
            case GAP -> ChainProposalKind.ANSWER_USER_QUESTION;
            case DECISION, OUTCOME ->
                    ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        };
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "answer-proposal", "task-1", invocation.invocationId(), 1,
                ChainRole.ANSWER, kind, json("{}"), json("[]"),
                "ANSWER_BODY", "answer-content", NOW);
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1L, "task-1", "proposal-accepted",
                ChainProposalState.ACCEPTED, null, null, NOW);
        var delivery = new ChainPersistenceRecords.DeliveryRecord(
                "delivery-1", "task-1", "delivery-event", "command-1",
                source == DeliverySourceCase.ROUTE ? "route-1" : null,
                source == DeliverySourceCase.OUTCOME ? "outcome-1" : null,
                source == DeliverySourceCase.GAP ? "gap-1" : null,
                source == DeliverySourceCase.DECISION ? "review-1" : null,
                "answer-content", 41L, NOW.plusSeconds(2));
        var official = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 2L, "task-1", "proposal-bound",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "DELIVERY", delivery.deliveryId(), NOW.plusSeconds(1));
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = bound
                ? List.of(accepted, official) : List.of(accepted);
        var proposalProjection = new ProductChainRecoverySource
                .ProposalProjection(null, invocation, proposal, states,
                states.get(states.size() - 1), 4L);

        List<ProductChainRecoverySource.Sequenced<
                ChainPersistenceRecords.RouteDecisionRecord>> routes =
                List.of();
        List<ProductChainRecoverySource.PendingProjection> pending = List.of();
        List<ProductChainRecoverySource.Sequenced<
                ChainPersistenceRecords.ReviewDecisionRecord>> reviews =
                List.of();
        Optional<ProductChainRecoverySource.Sequenced<
                ChainPersistenceRecords.TaskOutcomeRecord>> outcome =
                Optional.empty();
        long sourceAuthoritySequence = staleSourceCommand ? 3L : 2L;
        if (source == DeliverySourceCase.ROUTE) {
            var route = new ChainPersistenceRecords.RouteDecisionRecord(
                    "route-1", "task-1", "route-event", "instruction-1",
                    "planner-proposal", ChainPersistenceRecords
                    .RouteDecisionType.INITIAL, 0,
                    io.paperagent.v2.chain.ChainExecutionMode.DIRECT,
                    "direct", json("{}"), json("{}"), json("[]"),
                    false, false, false, false,
                    null, null, null, NOW);
            routes = List.of(new ProductChainRecoverySource.Sequenced<>(
                    route, sourceAuthoritySequence));
        } else if (source == DeliverySourceCase.GAP) {
            var gap = new ChainPersistenceRecords.PendingItemRecord(
                    "gap-1", "task-1", "gap-event", "planner-proposal",
                    ChainPendingItemType.USER_INFORMATION, HASH, json("[]"),
                    null, "question", "text", ChainRole.EXECUTOR,
                    ChainRole.EXECUTOR, json("{}"), FENCE, NOW);
            pending = List.of(new ProductChainRecoverySource.PendingProjection(
                    gap, ChainPendingItemStatus.PENDING, gap.gapId(),
                    sourceAuthoritySequence, sourceAuthoritySequence));
        } else if (source == DeliverySourceCase.DECISION) {
            var review = new ChainPersistenceRecords.ReviewDecisionRecord(
                    "review-1", "task-1", "review-event",
                    "reflector-proposal", "CANDIDATE_STEP_RESULT",
                    "candidate-1",
                    ChainProposalKind.REFLECTOR_NEED_USER_INPUT,
                    "need user", json("[]"), FENCE, NOW);
            reviews = List.of(new ProductChainRecoverySource.Sequenced<>(
                    review, sourceAuthoritySequence));
        } else {
            outcome = Optional.of(new ProductChainRecoverySource.Sequenced<>(
                    terminalOutcome(ChainTaskOutcomeStatus.SUPERSEDED),
                    sourceAuthoritySequence));
        }
        List<ChainPersistenceRecords.DeliveryEventRecord> events =
                includePending ? deliveryPrefix(delivery, status) : List.of();
        String boundary = "authority-event-sequence=10;test";
        List<ProductChainRecoverySource.Sequenced<
                ChainPersistenceRecords.TaskInstructionBindingRecord>>
                instructionBindings = staleSourceCommand ? List.of(
                new ProductChainRecoverySource.Sequenced<>(binding, 1L),
                new ProductChainRecoverySource.Sequenced<>(
                        currentBinding, 2L)) : List.of(
                new ProductChainRecoverySource.Sequenced<>(binding, 1L));
        Map<String, ChainPersistenceRecords.InstructionRecord>
                instructionValues = staleSourceCommand ? Map.of(
                instruction.instructionId(), instruction,
                currentInstruction.instructionId(), currentInstruction)
                : Map.of(instruction.instructionId(), instruction);
        var projection = new ProductChainRecoverySource.RoleProjection(
                "task-1", staleSourceCommand
                ? currentInstruction.instructionId()
                : instruction.instructionId(), 10L, boundary,
                instructionBindings,
                instructionValues, List.of(), routes,
                List.of(), List.of(), List.of(), reviews, List.of(), pending,
                List.of(), List.of(proposalProjection), List.of(), outcome,
                List.of(new ProductChainRecoverySource.Sequenced<>(
                        delivery, 5L)),
                Map.of(delivery.deliveryId(), events), List.of(),
                Optional.empty());
        return new ProductChainNextRoleSelector().decide(
                new ChainRecoveryRuntime.RecoverySnapshot(
                        "task-1", java.util.Arrays.stream(
                        ChainRecoveryRuntime.RecoveryFactKind.values())
                        .map(value -> new ChainRecoveryRuntime.FactCut(
                                value, "test", boundary, List.of()))
                        .toList(), List.of(), projection));
    }

    private static ProductChainNextRoleSelector.Selection selection(
            Scenario scenario,
            List<ChainPersistenceRecords.DeliveryRecord> deliveries,
            Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                    deliveryEvents) {
        return new ProductChainNextRoleSelector().decide(
                snapshot(scenario, deliveries, deliveryEvents));
    }

    private static ProductChainNextRoleSelector.Selection
            acceptedTaskOutcomeAnswerSelection(
                    ChainTaskOutcomeStatus status) {
        ChainWorkState state = status == ChainTaskOutcomeStatus.COMPLETED
                ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL;
        ChainProposalKind kind = status == ChainTaskOutcomeStatus.COMPLETED
                ? ChainProposalKind.ANSWER_FINAL_DELIVERY
                : ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        var outcome = new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-recovery", "task-1", "event-outcome-recovery",
                "command-1", status, "instruction-1", "frame-1",
                "plan-1", "revision-1", json("[]"), json("[]"),
                null, ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null, json("[]"), json("[]"),
                json("[]"), status == ChainTaskOutcomeStatus.FAILED
                ? "EXECUTION" : null,
                status == ChainTaskOutcomeStatus.FAILED
                ? "FAILED" : null, "review-1", NOW);
        var context = new ChainPersistenceRecords.ContextRevisionRecord(
                ProductChainContextIdentity.taskOutcomeAnswer(
                        "task-1", outcome.outcomeId()), "task-1", null,
                ChainRole.ANSWER, state, "TASK_OUTCOME", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L,
                "step-1", "activation-1", 1L, "project-version-1",
                "workspace-1", null, null, null, null, null,
                "projectors", "pagination", "policy",
                io.paperagent.v2.chain.ChainContextRevisionStatus.COMPLETE,
                13, new ChainPersistenceRecords.FormattedJson(1, "{}"),
                HASH, "completion-token", null, null, NOW, NOW);
        var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                "invocation-answer-recovery", "task-1",
                context.contextRevisionId(), "completion-token",
                 ChainRole.ANSWER, state, "TASK_OUTCOME", "provider",
                 "model", 1, ChainRuntimePolicy.V1.policyVersion(), NOW);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "answer-proposal-recovery", "task-1",
                invocation.invocationId(), 1, ChainRole.ANSWER, kind,
                json("{}"), json("[]"), "ANSWER_BODY",
                "answer-content-recovery", NOW);
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1L, "task-1",
                "answer-accepted-recovery", ChainProposalState.ACCEPTED,
                null, null, NOW);
        var projected = new ProductChainRecoverySource.ProposalProjection(
                context, invocation, proposal, List.of(accepted), accepted,
                2L);
        String boundary = "authority-event-sequence=2;answer-recovery";
        var role = new ProductChainRecoverySource.RoleProjection(
                "task-1", "instruction-1", 2L, boundary, List.of(), Map.of(),
                List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(projected), List.of(),
                Optional.of(new ProductChainRecoverySource.Sequenced<>(
                        outcome, 1L)), List.of(), Map.of(), List.of(),
                Optional.empty());
        var snapshot = new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", java.util.Arrays.stream(
                ChainRecoveryRuntime.RecoveryFactKind.values())
                .map(value -> new ChainRecoveryRuntime.FactCut(
                        value, "test", boundary, List.of()))
                .toList(), List.of(), role);
        return new ProductChainNextRoleSelector().decide(snapshot);
    }

    private static ProductChainNextRoleSelector.Selection
            permissionResponseSelection(boolean withDecision) {
        var pending = new ChainPersistenceRecords.PendingItemRecord(
                "gap-permission", "task-1", "event-gap-permission",
                "proposal-gap", ChainPendingItemType.PERMISSION, HASH,
                json("[]"), "scope-1", "allow?", "grant-or-deny",
                ChainRole.PLANNER, ChainRole.PLANNER, json("{}"), FENCE, NOW);
        var binding = new ChainPersistenceRecords
                .TaskInstructionBindingRecord(
                "task-1", "event-binding-permission", "instruction-1", 1L,
                ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
        var instruction = new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 1L, "task-1", 1L,
                HASH, "message-1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
        List<ProductChainRecoverySource.Sequenced<
                ChainPersistenceRecords.PermissionDecisionRecord>> decisions =
                withDecision ? List.of(new ProductChainRecoverySource
                .Sequenced<>(new ChainPersistenceRecords
                .PermissionDecisionRecord(
                "permission-1", "task-1", "event-permission-1",
                pending.gapId(), pending.permissionScope(), "USER_CONSENT",
                "consent-1", io.paperagent.v2.chain.ChainPermissionDecision
                .GRANTED, "approved", NOW), 4L)) : List.of();
        String boundary = "authority-event-sequence=4;permission-test";
        var role = new ProductChainRecoverySource.RoleProjection(
                "task-1", "instruction-1", 4L, boundary,
                List.of(new ProductChainRecoverySource.Sequenced<>(binding, 1L)),
                Map.of(instruction.instructionId(), instruction),
                List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(new ProductChainRecoverySource
                .PendingProjection(pending,
                ChainPendingItemStatus.RESPONSE_RECEIVED,
                "event-response-permission", 3L, 2L)),
                decisions, List.of(), List.of(), Optional.empty(), List.of(),
                Map.of(), List.of(), Optional.empty());
        var snapshot = new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", java.util.Arrays.stream(
                ChainRecoveryRuntime.RecoveryFactKind.values())
                .map(value -> new ChainRecoveryRuntime.FactCut(
                        value, "test", boundary, List.of()))
                .toList(), List.of(), role);
        return new ProductChainNextRoleSelector().decide(snapshot);
    }

    private static ProductChainNextRoleSelector.Selection
            acceptedPendingAnswerSelection(ChainPendingItemType type) {
        ChainWorkState state = type == ChainPendingItemType.PERMISSION
                ? ChainWorkState.WAITING_PERMISSION
                : ChainWorkState.WAITING_USER;
        var pending = new ChainPersistenceRecords.PendingItemRecord(
                "gap-1", "task-1", "event-gap-1", "review-proposal-1",
                type, HASH, json("[\"field\"]"),
                type == ChainPendingItemType.PERMISSION ? "scope" : null,
                "question", "text", ChainRole.PLANNER,
                ChainRole.PLANNER, json("{}"), FENCE, NOW);
        var context = new ChainPersistenceRecords.ContextRevisionRecord(
                ProductChainContextIdentity.pendingItemAnswer(
                        "task-1", "gap-1", state.name()),
                "task-1", null, ChainRole.ANSWER, state,
                "PENDING_ITEM", "instruction-1", null, null, null, null,
                null, null, 1L, "project-version-1", null, null, null,
                null, null, null, "projectors", "pagination", "policy",
                io.paperagent.v2.chain.ChainContextRevisionStatus.COMPLETE,
                13, new ChainPersistenceRecords.FormattedJson(1, "{}"),
                HASH, "completion", null, null, NOW, NOW);
        var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                "pending-answer-invocation", "task-1",
                 context.contextRevisionId(), "completion", ChainRole.ANSWER,
                 state, "PENDING_ITEM", "provider", "model", 1,
                 ChainRuntimePolicy.V1.policyVersion(), NOW);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "pending-answer-proposal", "task-1",
                invocation.invocationId(), 1, ChainRole.ANSWER,
                ChainProposalKind.ANSWER_USER_QUESTION, json("{}"),
                json("[]"), "ANSWER_BODY", "pending-answer-content", NOW);
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1L, "task-1",
                "pending-answer-accepted", ChainProposalState.ACCEPTED,
                null, null, NOW);
        var projected = new ProductChainRecoverySource.ProposalProjection(
                context, invocation, proposal, List.of(accepted), accepted,
                3L);
        var binding = new ChainPersistenceRecords
                .TaskInstructionBindingRecord(
                "task-1", "event-binding-1", "instruction-1", 1L,
                ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
        var instruction = new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 1L, "task-1", 1L,
                HASH, "message-1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
        String boundary = "authority-event-sequence=3;pending-answer";
        var role = new ProductChainRecoverySource.RoleProjection(
                "task-1", "instruction-1", 3L, boundary,
                List.of(new ProductChainRecoverySource.Sequenced<>(
                        binding, 1L)),
                Map.of(instruction.instructionId(), instruction),
                List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(new ProductChainRecoverySource
                        .PendingProjection(pending,
                        ChainPendingItemStatus.PENDING, pending.gapId(),
                        2L, 2L)),
                List.of(), List.of(projected), List.of(), Optional.empty(),
                List.of(), Map.of(), List.of(), Optional.empty());
        var snapshot = new ChainRecoveryRuntime.RecoverySnapshot(
                "task-1", java.util.Arrays.stream(
                ChainRecoveryRuntime.RecoveryFactKind.values())
                .map(value -> new ChainRecoveryRuntime.FactCut(
                        value, "test", boundary, List.of()))
                .toList(), List.of(), role);
        return new ProductChainNextRoleSelector().decide(snapshot);
    }

    private static Scenario withProposalStates(
            Scenario source,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states) {
        List<ChainPersistenceRecords.TaskAuthorityFact> facts = new ArrayList<>(
                source.orderedFacts().stream().filter(value ->
                        !(value instanceof ChainPersistenceRecords
                                .ProposalStateEventRecord)).toList());
        facts.addAll(states);
        return new Scenario(
                facts, source.bindings(), source.instructions(),
                source.routes(), source.plans(), source.candidates(),
                source.reviews(), source.accepted(), source.pending(),
                source.pendingEvents(), source.invocations(),
                source.proposals(),
                Map.of(source.proposals().get(0).proposalId(), states),
                source.readiness(), source.outcome(), source.stable());
    }

    private static Scenario withPendingEvents(
            List<ChainPersistenceRecords.PendingItemEventRecord> events) {
        Scenario source = Scenario.pendingResponse();
        List<ChainPersistenceRecords.TaskAuthorityFact> facts = new ArrayList<>(
                source.orderedFacts().stream().filter(value ->
                        !(value instanceof ChainPersistenceRecords
                                .PendingItemEventRecord)).toList());
        facts.addAll(events);
        String gapId = source.pending().get(0).gapId();
        return new Scenario(
                facts, source.bindings(), source.instructions(),
                source.routes(), source.plans(), source.candidates(),
                source.reviews(), source.accepted(), source.pending(),
                Map.of(gapId, events), source.invocations(),
                source.proposals(), source.proposalStates(),
                source.readiness(), source.outcome(), source.stable());
    }

    private static ChainPersistenceRecords.PendingItemEventRecord pendingEvent(
            String gapId,
            int responseRound,
            ChainPendingItemStatus status,
            String eventId,
            String answerInstructionId,
            String validationInvocationId,
            GapValidation.Outcome validationOutcome) {
        return new ChainPersistenceRecords.PendingItemEventRecord(
                gapId, responseRound, status, "task-1", eventId,
                answerInstructionId, validationInvocationId,
                validationOutcome, json("{}"), NOW);
    }

    private static ChainRecoveryRuntime.RecoverySnapshot snapshot(
            Scenario scenario,
            List<ChainPersistenceRecords.DeliveryRecord> deliveries,
            Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                    deliveryEvents) {
        List<ChainPersistenceRecords.AuthorityEventRecord> events =
                new ArrayList<>();
        long sequence = 0;
        for (ChainPersistenceRecords.TaskAuthorityFact fact
                : scenario.orderedFacts()) {
            events.add(authority(fact, ++sequence));
        }
        long cut = sequence;
        Map<String, Function<Object[], Object>> foundationAnswers =
                new HashMap<>();
        foundationAnswers.put("findTask", ignored -> Optional.of(task()));
        foundationAnswers.put("highestAuthorityEventSequence",
                ignored -> cut);
        foundationAnswers.put("findAuthorityEvents", ignored -> events);
        foundationAnswers.put("findTaskInstructions",
                ignored -> scenario.bindings());
        foundationAnswers.put("findInstruction", arguments -> Optional.ofNullable(
                scenario.instructions().get((String) arguments[0])));
        ChainFoundationRepository foundations = proxy(
                ChainFoundationRepository.class, foundationAnswers);

        Map<String, Function<Object[], Object>> workflowAnswers =
                new HashMap<>();
        workflowAnswers.put("findRouteDecisions",
                ignored -> scenario.routes());
        workflowAnswers.put("findPlanBindings", ignored -> scenario.plans());
        workflowAnswers.put("findCandidateStepResults",
                ignored -> scenario.candidates());
        workflowAnswers.put("findReviewDecisions",
                ignored -> scenario.reviews());
        workflowAnswers.put("findAcceptedResults",
                ignored -> scenario.accepted());
        workflowAnswers.put("findPendingItems",
                ignored -> scenario.pending());
        workflowAnswers.put("findPendingItemEvents", arguments ->
                scenario.pendingEvents().getOrDefault(
                        (String) arguments[0], List.of()));
        ChainWorkflowRepository workflow = proxy(
                ChainWorkflowRepository.class, workflowAnswers);

        Map<String, Function<Object[], Object>> modelAnswers = new HashMap<>();
        modelAnswers.put("highestProviderAttemptNo", ignored -> 0);
        modelAnswers.put("highestInvocationOrdinal", ignored ->
                scenario.invocations().stream().mapToLong(
                        ChainPersistenceRecords.ModelInvocationRecord
                                ::invocationOrdinal).max().orElse(0));
        modelAnswers.put("findInvocations", arguments ->
                scenario.invocations().stream().filter(value ->
                        value.invocationOrdinal() <= (long) arguments[1])
                        .toList());
        modelAnswers.put("findProposalByInvocation", arguments ->
                scenario.proposals().stream().filter(value ->
                        value.invocationId().equals(arguments[0]))
                        .findFirst());
        modelAnswers.put("findProposalStateEvents", arguments ->
                scenario.proposalStates().getOrDefault(
                        (String) arguments[0], List.of()));

        Map<String, Function<Object[], Object>> finalAnswers = new HashMap<>();
        finalAnswers.put("findReadiness", ignored -> scenario.readiness());
        finalAnswers.put("findTaskOutcome", ignored -> scenario.outcome());
        finalAnswers.put("findDeliveries", ignored -> deliveries);
        finalAnswers.put("findDeliveryEvents", arguments ->
                deliveryEvents.getOrDefault((String) arguments[0], List.of()));
        ChainFinalizationRepository finalization = proxy(
                ChainFinalizationRepository.class, finalAnswers);
        ProductChainRecoverySource source = new ProductChainRecoverySource(
                foundations, empty(ChainContextRepository.class),
                proxy(ChainModelRepository.class, modelAnswers), workflow,
                finalization, request -> scenario.stable()
                .withCut(request.taskId(), request.chainAuthorityCut()));
        return source.load("task-1");
    }

    private static ChainPersistenceRecords.AuthorityEventRecord authority(
            ChainPersistenceRecords.TaskAuthorityFact fact, long sequence) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                fact.eventId(), fact.taskId(), sequence, "TEST_AUTHORITY",
                fact instanceof ChainPersistenceRecords.TransitionRecord value
                        ? value.transitionId() : null,
                HASH, NOW.plusSeconds(sequence));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            contextRevision(
                    String contextRevisionId,
                    ChainRole role,
                    io.paperagent.v2.chain.ChainContextRevisionStatus status) {
        boolean blocked = status
                == io.paperagent.v2.chain.ChainContextRevisionStatus
                .INPUT_BLOCKED;
        return new ChainPersistenceRecords.ContextRevisionRecord(
                contextRevisionId, "task-1", null, role,
                role == ChainRole.EXECUTOR
                        ? ChainWorkState.EXECUTING
                        : ChainWorkState.PLANNING,
                "CONTEXT_TEST", "instruction-1",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors", "pagination", "policy", status,
                blocked ? 13 : 0,
                blocked ? new ChainPersistenceRecords.FormattedJson(1, "{}")
                        : null,
                null, null,
                blocked ? "CONTEXT_INPUT_BLOCKED" : null,
                blocked ? HASH : null,
                NOW, blocked ? NOW : null);
    }

    private static ChainPersistenceRecords.RouteDecisionRecord route(
            String id, String eventId) {
        return new ChainPersistenceRecords.RouteDecisionRecord(
                id, "task-1", eventId, "instruction-1", "proposal-" + id,
                ChainPersistenceRecords.RouteDecisionType.INITIAL, 0,
                io.paperagent.v2.chain.ChainExecutionMode
                        .PERSISTENT_PLAN_EXECUTE,
                "persistent", null, null, null,
                true, false, false, true,
                null, null, null, NOW);
    }

    private record RoleCase(
            String name,
            ProductChainNextRoleSelector.Selection selection,
            Class<? extends ProductChainNextRoleSelector.Selection>
                    expectedType,
            ChainRole role,
            ChainWorkState state) {
    }

    private enum DeliverySourceCase {
        ROUTE,
        GAP,
        DECISION,
        OUTCOME
    }

    private record FakeRoleProjection(
            String taskId, long authorityCut, String readBoundary)
            implements ChainRecoveryRuntime.FrozenRoleProjection {
    }

    private record StableScenario(
            List<ProductChainRecoverySource.StableAuthorityFact> facts,
            Optional<ProductChainRecoverySource.StepState> stepState) {
        StableScenario {
            facts = List.copyOf(facts);
            stepState = Objects.requireNonNull(stepState, "stepState");
        }

        ProductChainRecoverySource.StableAuthoritySnapshot withCut(
                String taskId, long cut) {
            return new ProductChainRecoverySource.StableAuthoritySnapshot(
                    taskId, cut, "scenario-stable-authority",
                    facts, stepState);
        }

        static StableScenario empty() {
            return new StableScenario(List.of(), Optional.empty());
        }
    }

    private record Scenario(
            List<ChainPersistenceRecords.TaskAuthorityFact> orderedFacts,
            List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings,
            Map<String, ChainPersistenceRecords.InstructionRecord> instructions,
            List<ChainPersistenceRecords.RouteDecisionRecord> routes,
            List<ChainPersistenceRecords.PlanBindingRecord> plans,
            List<ChainPersistenceRecords.CandidateStepResultRecord> candidates,
            List<ChainPersistenceRecords.ReviewDecisionRecord> reviews,
            List<ChainPersistenceRecords.AcceptedResultRecord> accepted,
            List<ChainPersistenceRecords.PendingItemRecord> pending,
            Map<String, List<ChainPersistenceRecords.PendingItemEventRecord>>
                    pendingEvents,
            List<ChainPersistenceRecords.ModelInvocationRecord> invocations,
            List<ChainPersistenceRecords.ModelProposalRecord> proposals,
            Map<String, List<ChainPersistenceRecords.ProposalStateEventRecord>>
                    proposalStates,
            List<ChainPersistenceRecords.FinalizationReadinessRecord> readiness,
            Optional<ChainPersistenceRecords.TaskOutcomeRecord> outcome,
            StableScenario stable) {
        Scenario {
            orderedFacts = List.copyOf(orderedFacts);
            bindings = List.copyOf(bindings);
            instructions = Map.copyOf(instructions);
            routes = List.copyOf(routes);
            plans = List.copyOf(plans);
            candidates = List.copyOf(candidates);
            reviews = List.copyOf(reviews);
            accepted = List.copyOf(accepted);
            pending = List.copyOf(pending);
            pendingEvents = Map.copyOf(pendingEvents);
            invocations = List.copyOf(invocations);
            proposals = List.copyOf(proposals);
            proposalStates = Map.copyOf(proposalStates);
            readiness = List.copyOf(readiness);
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(stable, "stable");
        }

        static Scenario withInstruction(ChainInstructionRelation relation) {
            String instructionId = relation == ChainInstructionRelation.INITIAL
                    ? "instruction-1" : "instruction-new";
            var binding = new ChainPersistenceRecords
                    .TaskInstructionBindingRecord(
                    "task-1", "event-binding-1", instructionId, 1L,
                    ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
            var instruction = new ChainPersistenceRecords.InstructionRecord(
                    instructionId, "command-1", 1L, "task-1", 1L,
                    HASH, "message-1", relation,
                    relation == ChainInstructionRelation.INITIAL
                            ? null : "instruction-1",
                    null, HASH, NOW);
            return base(List.of(binding), Map.of(instructionId, instruction),
                    List.of(binding));
        }

        static Scenario pendingResponse() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var pending = new ChainPersistenceRecords.PendingItemRecord(
                    "gap-1", "task-1", "event-gap-1", "proposal-gap-1",
                    ChainPendingItemType.USER_INFORMATION, HASH,
                    json("[\"owner\"]"), null, "owner?", "text",
                    ChainRole.EXECUTOR, ChainRole.EXECUTOR, json("{}"),
                    FENCE, NOW);
            var response = new ChainPersistenceRecords.PendingItemEventRecord(
                    "gap-1", 1, ChainPendingItemStatus.RESPONSE_RECEIVED,
                    "task-1", "event-gap-response-1", "instruction-answer-1",
                    null, null, json("{}"), NOW.plusSeconds(1));
            return new Scenario(
                    List.of(base.bindings().get(0), pending, response),
                    base.bindings(), base.instructions(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(pending),
                    Map.of(pending.gapId(), List.of(response)),
                    List.of(), List.of(), Map.of(), List.of(),
                    Optional.empty(), StableScenario.empty());
        }

        static Scenario pendingResponseValidation(boolean bound) {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var pending = new ChainPersistenceRecords.PendingItemRecord(
                    "gap-1", "task-1", "event-gap-1", "proposal-gap-1",
                    ChainPendingItemType.USER_INFORMATION, HASH,
                    json("[\"owner\"]"), null, "owner?", "text",
                    ChainRole.PLANNER, ChainRole.PLANNER, json("{}"),
                    FENCE, NOW);
            var response = new ChainPersistenceRecords.PendingItemEventRecord(
                    "gap-1", 1, ChainPendingItemStatus.RESPONSE_RECEIVED,
                    "task-1", "event-gap-response-1", "instruction-answer-1",
                    null, null, json("{}"), NOW.plusSeconds(1));
            var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                    "invocation-validation-1", "task-1", "context-validation-1",
                     "completion-validation-1", ChainRole.PLANNER,
                     ChainWorkState.VALIDATING_PENDING_ITEM,
                     "PENDING_ITEM_VALIDATION", "provider", "model", 1,
                     ChainRuntimePolicy.V1.policyVersion(), NOW.plusSeconds(2));
            String payload = "{\"answerRequiredRefs\":[],"
                    + "\"directTaskSpecification\":\"answer\","
                    + "\"gapValidation\":{\"checks\":[{"
                    + "\"closingCondition\":\"owner supplied\","
                    + "\"factRef\":\"instruction-answer-1\","
                    + "\"satisfied\":true}],\"gapId\":\"gap-1\","
                    + "\"outcome\":\"RESOLVED\"},"
                    + "\"needsNetwork\":false,"
                    + "\"needsPersistentProgress\":false,"
                    + "\"needsProject\":false,\"needsTool\":false,"
                    + "\"routeReason\":\"answer available\","
                    + "\"userConstraints\":[]}";
            var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                    "proposal-validation-1", "task-1",
                    invocation.invocationId(), 1, ChainRole.PLANNER,
                    ChainProposalKind.PLANNER_DIRECT_ROUTE,
                    json(payload), json("[]"), null, null,
                    NOW.plusSeconds(2));
            var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                    proposal.proposalId(), 1, "task-1",
                    "event-validation-accepted", ChainProposalState.ACCEPTED,
                    null, null, NOW.plusSeconds(3));
            List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                    bound ? List.of(accepted,
                    new ChainPersistenceRecords.ProposalStateEventRecord(
                            proposal.proposalId(), 2, "task-1",
                            "event-validation-bound",
                            ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                            "ROUTE_DECISION", "route-validation-1",
                            NOW.plusSeconds(4))) : List.of(accepted);
            List<ChainPersistenceRecords.TaskAuthorityFact> authorities =
                    new ArrayList<>();
            authorities.add(base.bindings().get(0));
            authorities.add(pending);
            authorities.add(response);
            authorities.addAll(states);
            return new Scenario(
                    authorities, base.bindings(), base.instructions(),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(pending), Map.of(pending.gapId(), List.of(response)),
                    List.of(invocation), List.of(proposal),
                    Map.of(proposal.proposalId(), states), List.of(),
                    Optional.empty(), StableScenario.empty());
        }

        static Scenario acceptedProposal() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                     "invocation-1", "task-1", "context-1", "completion-1",
                     ChainRole.PLANNER, ChainWorkState.PLANNING,
                     "planning", "provider", "model", 1,
                     ChainRuntimePolicy.V1.policyVersion(), NOW);
            var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                    "proposal-1", "task-1", invocation.invocationId(), 1,
                    ChainRole.PLANNER,
                    ChainProposalKind.PLANNER_PERSISTENT_PLAN,
                    json("{}"), json("[]"), null, null, NOW);
            var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                    proposal.proposalId(), 1L, "task-1",
                    "event-proposal-accepted", ChainProposalState.ACCEPTED,
                    null, null, NOW);
            return new Scenario(
                    List.of(base.bindings().get(0), accepted),
                    base.bindings(), base.instructions(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), Map.of(),
                    List.of(invocation), List.of(proposal),
                    Map.of(proposal.proposalId(), List.of(accepted)), List.of(),
                    Optional.empty(), StableScenario.empty());
        }

        static Scenario acceptedStepBlocked() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                    "step-block-invocation", "task-1", "step-block-context",
                     "step-block-completion", ChainRole.EXECUTOR,
                     ChainWorkState.EXECUTING, "STEP_EXECUTION", "provider",
                     "model", 1, ChainRuntimePolicy.V1.policyVersion(), NOW);
            var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                    "step-block-proposal", "task-1",
                    invocation.invocationId(), 1, ChainRole.EXECUTOR,
                    ChainProposalKind.EXECUTOR_STEP_BLOCKED,
                    json("{}"), json("[]"), null, null, NOW);
            var accepted = new ChainPersistenceRecords
                    .ProposalStateEventRecord(
                    proposal.proposalId(), 1L, "task-1",
                    "event-step-block-accepted", ChainProposalState.ACCEPTED,
                    null, null, NOW);
            return new Scenario(
                    List.of(base.bindings().get(0), accepted),
                    base.bindings(), base.instructions(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), Map.of(),
                    List.of(invocation), List.of(proposal),
                    Map.of(proposal.proposalId(), List.of(accepted)), List.of(),
                    Optional.empty(), StableScenario.empty());
        }

        static Scenario activeStep() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var plan = planBinding();
            var step = new ProductChainRecoverySource.StepState(
                    "revision-1", "step-1", "activation-1",
                    ChainStepStatus.ACTIVE, "STEP_ACTIVATION", "activation-1",
                    1L);
            return new Scenario(
                    List.of(base.bindings().get(0), plan), base.bindings(),
                    base.instructions(), List.of(), List.of(plan), List.of(),
                    List.of(), List.of(), List.of(), Map.of(), List.of(),
                    List.of(), Map.of(), List.of(), Optional.empty(),
                    new StableScenario(List.of(stableFact(
                            ProductChainRecoverySource.StableFactKind
                                    .TASKFRAME_PLAN_STEP,
                            "STEP_ACTIVATION", "activation-1", "ACTIVE")),
                            Optional.of(step)));
        }

        static Scenario finalizationReady() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var value = ProjectChainRecoveryTest.readiness(
                    readinessTransition());
            return new Scenario(
                    List.of(base.bindings().get(0), value), base.bindings(),
                    base.instructions(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), Map.of(), List.of(),
                    List.of(), Map.of(), List.of(value), Optional.empty(),
                    StableScenario.empty());
        }

        static Scenario planWithoutStep() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var plan = planBinding();
            return new Scenario(
                    List.of(base.bindings().get(0), plan), base.bindings(),
                    base.instructions(), List.of(), List.of(plan), List.of(),
                    List.of(), List.of(), List.of(), Map.of(), List.of(),
                    List.of(), Map.of(), List.of(), Optional.empty(),
                    StableScenario.empty());
        }

        private static ChainPersistenceRecords.PlanBindingRecord planBinding() {
            return new ChainPersistenceRecords.PlanBindingRecord(
                    "plan-binding-1", "task-1", "event-plan-1",
                    "instruction-1", "route-1", "frame-1", "plan-1",
                    "revision-1", 1L, "PLAN", "plan-1", HASH,
                    null, NOW);
        }

        static Scenario superseded() {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            var outcome = new ChainPersistenceRecords.TaskOutcomeRecord(
                    "outcome-1", "task-1", "event-outcome-1", "command-1",
                    ChainTaskOutcomeStatus.SUPERSEDED, "instruction-1",
                    null, null, null, json("[]"), json("[]"), null,
                    ChainIdentity.NONE, ChainIdentity.NONE,
                    null, null, null, null,
                    json("[]"), json("[]"), json("[]"),
                    null, null, "instruction-new", NOW);
            return new Scenario(
                    List.of(base.bindings().get(0), outcome), base.bindings(),
                    base.instructions(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), Map.of(), List.of(),
                    List.of(), Map.of(), List.of(), Optional.of(outcome),
                    StableScenario.empty());
        }

        static Scenario completed(
                ChainPersistenceRecords.TaskOutcomeRecord outcome,
                ChainPersistenceRecords.DeliveryRecord delivery,
                List<ChainPersistenceRecords.DeliveryEventRecord> events) {
            Scenario base = withInstruction(ChainInstructionRelation.INITIAL);
            List<ChainPersistenceRecords.TaskAuthorityFact> facts =
                    new ArrayList<>();
            facts.add(base.bindings().get(0));
            facts.add(outcome);
            var invocation = answerInvocation();
            var proposal = answerProposal(outcome, invocation);
            var accepted = answerAccepted(outcome);
            var bound = answerBound(outcome, delivery);
            facts.add(accepted);
            facts.add(bound);
            facts.add(delivery);
            facts.addAll(events);
            return new Scenario(
                    facts,
                    base.bindings(), base.instructions(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), Map.of(),
                    List.of(invocation), List.of(proposal),
                    Map.of(proposal.proposalId(), List.of(accepted, bound)),
                    List.of(),
                    Optional.of(outcome), StableScenario.empty());
        }

        private static ChainPersistenceRecords.ModelInvocationRecord
                answerInvocation() {
            return new ChainPersistenceRecords.ModelInvocationRecord(
                     "answer-invocation-1", "task-1", "context-answer-1",
                     "completion-answer-1", ChainRole.ANSWER,
                     ChainWorkState.DELIVERING, "delivery", "provider",
                     "model", 1, ChainRuntimePolicy.V1.policyVersion(), NOW);
        }

        private static ChainPersistenceRecords.ModelProposalRecord
                answerProposal(
                ChainPersistenceRecords.TaskOutcomeRecord outcome,
                ChainPersistenceRecords.ModelInvocationRecord invocation) {
            ChainProposalKind kind = outcome.outcomeType()
                    == ChainTaskOutcomeStatus.COMPLETED
                    ? ChainProposalKind.ANSWER_FINAL_DELIVERY
                    : ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
            return new ChainPersistenceRecords.ModelProposalRecord(
                    "answer-proposal-1", "task-1",
                    invocation.invocationId(), 1, ChainRole.ANSWER, kind,
                    json("{}"), json("[]"), "ANSWER_BODY",
                    "answer-content-1", NOW);
        }

        private static ChainPersistenceRecords.ProposalStateEventRecord
                answerAccepted(
                ChainPersistenceRecords.TaskOutcomeRecord ignored) {
            return new ChainPersistenceRecords.ProposalStateEventRecord(
                    "answer-proposal-1", 1L, "task-1",
                    "answer-proposal-accepted-1", ChainProposalState.ACCEPTED,
                    null, null, NOW);
        }

        private static ChainPersistenceRecords.ProposalStateEventRecord
                answerBound(
                ChainPersistenceRecords.TaskOutcomeRecord ignored,
                ChainPersistenceRecords.DeliveryRecord delivery) {
            return new ChainPersistenceRecords.ProposalStateEventRecord(
                    "answer-proposal-1", 2L, "task-1",
                    "answer-proposal-bound-1",
                    ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                    "DELIVERY", delivery.deliveryId(), NOW.plusSeconds(1));
        }

        private static Scenario base(
                List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                        bindings,
                Map<String, ChainPersistenceRecords.InstructionRecord>
                        instructions,
                List<ChainPersistenceRecords.TaskAuthorityFact> facts) {
            return new Scenario(facts, bindings, instructions,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), Map.of(), List.of(), List.of(), Map.of(),
                    List.of(), Optional.empty(), StableScenario.empty());
        }

        private Scenario withStable(StableScenario value) {
            return new Scenario(orderedFacts, bindings, instructions, routes,
                    plans, candidates, reviews, accepted, pending,
                    pendingEvents, invocations, proposals, proposalStates,
                    readiness, outcome, value);
        }
    }

    private static ProductChainRecoverySource.StableAuthorityFact stableFact(
            ProductChainRecoverySource.StableFactKind kind,
            String type, String ref, String status) {
        return new ProductChainRecoverySource.StableAuthorityFact(
                kind, type, ref, HASH, status);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                1L, 1L, 1L, 1L, "request-1", HASH,
                1L, "project-version-1", 0L, NOW);
    }

    private static ChainFoundationRepository finalizationFoundations() {
        return finalizationFoundations("command-1");
    }

    private static ChainFoundationRepository finalizationFoundations(
            String sourceCommandId) {
        return proxy(ChainFoundationRepository.class, Map.of(
                "findTask", ignored -> Optional.of(task()),
                "findInstruction", ignored -> Optional.of(
                        new ChainPersistenceRecords.InstructionRecord(
                                "instruction-1", sourceCommandId, 1L,
                                "task-1", 1L, HASH, "message-1",
                                ChainInstructionRelation.INITIAL,
                                null, null, HASH, NOW))));
    }

    private static ChainPersistenceRecords.ActionBindingRecord action() {
        return new ChainPersistenceRecords.ActionBindingRecord(
                "action-1", "task-1", "event-action-1", "proposal-1", 1,
                HASH, "key-1", "instruction-1", "frame-1", "plan-1",
                "revision-1", "step-1", "activation-1", "workspace-1",
                ChainIdentity.NONE, null, null, null, null, FENCE, NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord terminalOutcome(
            ChainTaskOutcomeStatus status) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "event-outcome-1", "command-1",
                status, "instruction-1", null, null, null,
                json("[]"), json("[]"), null,
                ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null,
                json("[]"), json("[]"), json("[]"),
                null, null, "finalization-check-1", NOW);
    }

    private static ChainPersistenceRecords.DeliveryRecord delivery(
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        return new ChainPersistenceRecords.DeliveryRecord(
                "delivery-1", "task-1", "event-delivery-1", "command-1",
                null, outcome.outcomeId(), null, null,
                "answer-content-1", 41L, NOW.plusSeconds(1));
    }

    private static List<ChainPersistenceRecords.DeliveryEventRecord>
            deliveryPrefix(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainDeliveryStatus latest) {
        List<ChainPersistenceRecords.DeliveryEventRecord> events =
                new ArrayList<>();
        events.add(deliveryEvent(delivery, delivery.deliveryId(), 1L,
                ChainDeliveryStatus.PENDING, 0));
        if (latest == ChainDeliveryStatus.PENDING) {
            return List.copyOf(events);
        }
        int terminalAttempt = latest == ChainDeliveryStatus.DELIVERY_FAILED
                ? ChainRuntimePolicy.V1.deliveryAttemptsTotal() : 1;
        for (int attempt = 1; attempt < terminalAttempt; attempt++) {
            events.add(deliveryEvent(delivery, delivery.deliveryId(),
                    attempt + 1L, ChainDeliveryStatus.RETRYING, attempt));
        }
        events.add(deliveryEvent(delivery, delivery.deliveryId(),
                terminalAttempt + 1L, latest, terminalAttempt));
        return List.copyOf(events);
    }

    private static ChainPersistenceRecords.DeliveryEventRecord deliveryEvent(
            ChainPersistenceRecords.DeliveryRecord delivery,
            String deliveryId,
            long sequence,
            ChainDeliveryStatus status,
            int attempt) {
        return new ChainPersistenceRecords.DeliveryEventRecord(
                deliveryId, sequence, delivery.taskId(),
                "event-delivery-" + sequence + "-" + status,
                status, attempt,
                status == ChainDeliveryStatus.RETRYING
                        || status == ChainDeliveryStatus.DELIVERY_FAILED
                        ? "CHAIN_DELIVERY_MESSAGE_WRITE_FAILED" : null,
                ChainRuntimePolicy.V1.policyVersion(),
                NOW.plusSeconds(sequence + 1L));
    }

    private static Store gapAtPendingResolved() {
        Store store = new Store();
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.GAP_RESOLUTION, "task-1",
                "decision-gap-1", HASH).transitionId();
        store.transitions.add(new ChainPersistenceRecords.TransitionRecord(
                transitionId, "task-1", "event-transition-gap",
                ChainTransitionType.GAP_RESOLUTION,
                "decision-gap-1", HASH, NOW));
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                transitionId, ChainTransitionStage.OPEN, "task-1",
                "event-stage-open", 0,
                null, null, null, null, NOW));
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                transitionId,
                ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED, "task-1",
                "event-stage-successor", 1,
                null, null, "MODEL_INVOCATION", "invocation-1", NOW));
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                transitionId, ChainTransitionStage.PENDING_RESOLVED, "task-1",
                "event-stage-pending", 2,
                null, null, "PENDING_ITEM_EVENT", "pending-event-1", NOW));
        return store;
    }

    private static ChainPersistenceRecords.TransitionRecord
            readinessTransition() {
        String id = new ChainIdentity.Transition(
                ChainTransitionType.FINAL_STEP_READINESS, "task-1",
                "review-1", HASH).transitionId();
        return new ChainPersistenceRecords.TransitionRecord(
                id, "task-1", "event-transition-readiness",
                ChainTransitionType.FINAL_STEP_READINESS,
                "review-1", HASH, NOW);
    }

    private static ChainWorkflowRepository finalizationWorkflow(
            FinalizationFixture fixture,
            List<ChainPersistenceRecords.ReviewDecisionRecord> reviews) {
        return finalizationWorkflowForReadiness(
                fixture.readiness(), reviews);
    }

    private static ChainWorkflowRepository finalizationWorkflowForReadiness(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            List<ChainPersistenceRecords.ReviewDecisionRecord> reviews) {
        var predecessor = readinessTransition();
        return proxy(ChainWorkflowRepository.class, Map.of(
                "findTransition", arguments ->
                        readiness.transitionId().equals(arguments[0])
                                ? Optional.of(predecessor) : Optional.empty(),
                "findTransitionStages", arguments ->
                        predecessor.transitionId().equals(arguments[0])
                                ? readinessPredecessorStages(predecessor)
                                : List.of(),
                "findCandidateStepResults", ignored -> List.of(
                        readinessCandidateResult()),
                "findAcceptedResults", ignored -> List.of(
                        readinessAcceptedResult()),
                "findApplicabilityDecisions", ignored -> List.of(),
                "findReviewDecisions", ignored -> {
                    List<ChainPersistenceRecords.ReviewDecisionRecord> result =
                            new ArrayList<>();
                    result.add(readinessReviewDecision());
                    result.addAll(reviews.stream()
                            .filter(value -> !value.reviewDecisionId().equals(
                                    "review-1"))
                            .toList());
                    return List.copyOf(result);
                }));
    }

    private static List<ChainPersistenceRecords.TransitionStageRecord>
            readinessPredecessorStages(
            ChainPersistenceRecords.TransitionRecord predecessor) {
        List<ChainTransitionStage> stages = List.of(
                ChainTransitionStage.OPEN,
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY,
                ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED,
                ChainTransitionStage.READINESS_COMMITTED,
                ChainTransitionStage.COMPLETE);
        List<ChainPersistenceRecords.TransitionStageRecord> result =
                new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            String predecessorType = null;
            String predecessorRef = null;
            String successorType = index == 1
                    ? "ACCEPTED_RESULT"
                    : index == 3
                    ? "STEP_EVENT"
                    : index == 4 ? "FINALIZATION_READINESS" : null;
            String successorRef = index == 1
                    ? "accepted-1"
                    : index == 3
                    ? readinessStepEventId(predecessor)
                    : index == 4 ? "readiness-1" : null;
            result.add(new ChainPersistenceRecords.TransitionStageRecord(
                    predecessor.transitionId(), stages.get(index),
                    predecessor.taskId(),
                    "readiness-predecessor-stage-" + index, index,
                    predecessorType, predecessorRef,
                    successorType, successorRef, NOW.plusSeconds(index)));
        }
        return List.copyOf(result);
    }

    private static ChainPersistenceRecords.AcceptedResultRecord
            readinessAcceptedResult() {
        var predecessor = readinessTransition();
        return new ChainPersistenceRecords.AcceptedResultRecord(
                "accepted-1", "task-1", "accepted-event-1",
                "candidate-result-1", "review-1",
                predecessor.transitionId(), "content-1", HASH, NOW);
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord
            readinessCandidateResult() {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                "candidate-result-1", "task-1", "candidate-event-1",
                "proposal-candidate-1", "content-1", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L, "step-1",
                "activation-1", null, null, null,
                json("[]"), null, null, null, json("[]"), FENCE, NOW);
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord
            readinessReviewDecision() {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                "review-1", "task-1", "review-event-1",
                "proposal-review-readiness-1", "CANDIDATE_STEP_RESULT",
                "candidate-result-1",
                ChainProposalKind.REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE,
                "accept final step and finalize", json("[]"), FENCE, NOW);
    }

    private static String readinessStepEventId(
            ChainPersistenceRecords.TransitionRecord predecessor) {
        return "step.completed." + sha256("task-1\0revision-1\0step-1\0"
                + "activation-1\0" + predecessor.transitionId());
    }

    private static void addReadinessPredecessor(
            Store store,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        var predecessor = readinessTransition();
        if (!predecessor.transitionId().equals(readiness.transitionId())) {
            throw new IllegalStateException(
                    "test readiness does not use the frozen predecessor");
        }
        store.transitions.add(predecessor);
        store.stages.addAll(readinessPredecessorStages(predecessor));
        store.candidates.add(readinessCandidateResult());
        store.accepted.add(readinessAcceptedResult());
        store.reviews.add(readinessReviewDecision());
    }

    private static FinalizationFixture finalizationFixture() {
        return finalizationFixture(ChainPublishRequirement.NOT_REQUIRED);
    }

    private static FinalizationFixture finalizationFixture(
            ChainPublishRequirement publishRequirement) {
        var readiness = readiness(readinessTransition(), publishRequirement);
        String target = ProductChainFinalizationRecoverySource
                .readinessTargetDigest(readiness);
        String id = new ChainIdentity.Transition(
                ChainTransitionType.FINALIZATION, "task-1",
                readiness.reviewDecisionId(), target).transitionId();
        var transition = new ChainPersistenceRecords.TransitionRecord(
                id, "task-1", "event-transition-finalization",
                ChainTransitionType.FINALIZATION,
                readiness.reviewDecisionId(), target, NOW);
        return new FinalizationFixture(transition, readiness);
    }

    private record FinalizationFixture(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
    }

    private static ProductChainFinalizationRecoverySource
            finalizationRecoveryWithChecks(
            FinalizationFixture fixture,
            List<ChainPersistenceRecords.FinalizationCheckRecord> checks) {
        return new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                finalizationWorkflow(fixture, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> checks)),
                (transition, readiness, check) -> Optional.empty(), ignoredReadiness -> { });
    }

    private static ProductChainFinalizationRecoverySource
            finalizationRecoveryWithReadiness(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        return new ProductChainFinalizationRecoverySource(
                empty(ChainFoundationRepository.class),
                finalizationWorkflowForReadiness(readiness, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness", ignored -> List.of(readiness))),
                (transition, boundReadiness, check) -> Optional.empty(), ignoredReadiness -> { });
    }

    private static ChainPersistenceRecords.TransitionRecord
            transitionWithReadinessTarget(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        String target = ProductChainFinalizationRecoverySource
                .readinessTargetDigest(readiness);
        String id = new ChainIdentity.Transition(
                transition.transitionType(), transition.taskId(),
                transition.sourceDecisionId(), target).transitionId();
        return new ChainPersistenceRecords.TransitionRecord(
                id, transition.taskId(),
                transition.eventId(), transition.transitionType(),
                transition.sourceDecisionId(),
                target,
                transition.createdAt());
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            copyReadiness(
            ChainPersistenceRecords.FinalizationReadinessRecord source,
            String taskId,
            String transitionId,
            String taskFrameId) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                source.readinessId(), taskId, source.eventId(), transitionId,
                source.readinessScopeKey(), taskFrameId, source.finalPlanId(),
                source.finalPlanRevisionId(),
                source.finalPlanRevisionNumber(), source.finalStepId(),
                source.reviewDecisionId(), source.acceptedSet(),
                source.applicabilityCutEventSequence(), source.artifactId(),
                source.candidateKey(), source.workspaceId(),
                source.validationId(), source.validationRequestDigest(),
                source.validationReceiptDigest(), source.coverage(),
                source.publishRequirement(),
                source.publishRequirementDigest(), source.instructionId(),
                source.projectVersion(), source.createdAt());
    }

    private static Store failedFinalizationStore(
            FinalizationFixture fixture,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        Store store = new Store();
        addReadinessPredecessor(store, fixture.readiness());
        store.transitions.add(fixture.transition());
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                fixture.transition().transitionId(), ChainTransitionStage.OPEN,
                "task-1", "event-finalization-open", 0,
                null, null, null, null, NOW));
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                fixture.transition().transitionId(),
                ChainTransitionStage.READINESS_VERIFIED,
                "task-1", "event-readiness-verified", 1,
                null, null, "FINALIZATION_READINESS",
                fixture.readiness().readinessId(), NOW));
        store.stages.add(new ChainPersistenceRecords.TransitionStageRecord(
                fixture.transition().transitionId(),
                ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                "task-1", "event-check-committed", 2,
                null, null, "FINALIZATION_CHECK",
                check.finalizationCheckId(), NOW));
        return store;
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord
            failedCheckReview(
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainProposalKind kind) {
        return failedCheckReview(
                check, "FINALIZATION_CHECK", check.finalizationCheckId(), kind);
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord
            failedCheckReview(
            ChainPersistenceRecords.FinalizationCheckRecord check,
            String reviewObjectType,
            String reviewObjectId,
            ChainProposalKind kind) {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                "review-failed-check-1", check.taskId(),
                "event-review-failed-check-1", "proposal-review-1",
                reviewObjectType, reviewObjectId, kind,
                "finalization failure handoff", json("[]"), FENCE, NOW);
    }

    private static ProductChainFinalizationRecoverySource.PublishFailure
            publishFailure() {
        return new ProductChainFinalizationRecoverySource.PublishFailure(
                "publish-failure-1",
                ChainProjectPublishPort.ErrorCode.VERSION_CONFLICT, false);
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord
            publishFailureReview(
            String formalFailureRef,
            ChainProposalKind kind) {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                "review-publish-failure-1", "task-1",
                "event-review-publish-failure-1", "proposal-review-1",
                "PUBLISH_FAILURE", formalFailureRef, kind,
                "publish failure handoff", json("[]"), FENCE, NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord
            failedFinalizationOutcome(
            ChainPersistenceRecords.FinalizationCheckRecord check,
            String sourceDecisionId) {
        return failedFinalizationOutcome(
                check, sourceDecisionId, "command-1", "FINALIZATION",
                check.errorCode().name());
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord
            failedFinalizationOutcome(
            ChainPersistenceRecords.FinalizationCheckRecord check,
            String sourceDecisionId,
            String sourceCommandId,
            String failureCategory,
            String failureCode) {
        return failedOutcome(
                check.taskId(), sourceDecisionId, sourceCommandId,
                failureCategory, failureCode);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord
            failedPublishOutcome(
            String sourceDecisionId,
            String sourceCommandId,
            String failureCategory,
            String failureCode) {
        return failedOutcome(
                "task-1", sourceDecisionId, sourceCommandId,
                failureCategory, failureCode);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord failedOutcome(
            String taskId,
            String sourceDecisionId,
            String sourceCommandId,
            String failureCategory,
            String failureCode) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-failed-1", taskId,
                "event-outcome-failed-1", sourceCommandId,
                ChainTaskOutcomeStatus.FAILED, "instruction-1",
                null, null, null, json("[]"), json("[]"), null,
                ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null,
                json("[]"), json("[]"), json("[]"),
                failureCategory, failureCode,
                sourceDecisionId, NOW);
    }

    private static ProductChainFinalizationRecoverySource
            finalizationRecoveryWithOutcome(
            FinalizationFixture fixture,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainFoundationRepository foundations,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        return new ProductChainFinalizationRecoverySource(
                foundations, finalizationWorkflow(fixture, List.of()),
                proxy(ChainFinalizationRepository.class, Map.of(
                        "findReadiness",
                        ignored -> List.of(fixture.readiness()),
                        "findFinalizationChecks", ignored -> List.of(check),
                        "findTaskOutcome", ignored -> Optional.of(outcome))),
                (transition, readiness, committedCheck) -> Optional.empty(), ignoredReadiness -> { });
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord completedOutcome(
            FinalizationFixture fixture,
            ChainTaskOutcomeStatus status,
            String sourceDecisionId,
            String sourceCommandId,
            String taskFrameId,
            boolean includePublishIdentity) {
        var readiness = fixture.readiness();
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-completed-1", readiness.taskId(),
                "event-outcome-completed-1", sourceCommandId,
                status, readiness.instructionId(), taskFrameId,
                readiness.finalPlanId(), readiness.finalPlanRevisionId(),
                readiness.coverage(), readiness.acceptedSet(),
                readiness.artifactId(), readiness.candidateKey(),
                readiness.validationId(),
                includePublishIdentity ? "publish-operation-1" : null,
                includePublishIdentity ? "project-version-2" : null,
                includePublishIdentity ? 2L : null,
                includePublishIdentity ? "publish-receipt-1" : null,
                json("[]"), json("[]"), json("[]"),
                null, null, sourceDecisionId, NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord readiness(
            ChainPersistenceRecords.TransitionRecord transition) {
        return readiness(transition, ChainPublishRequirement.NOT_REQUIRED);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord readiness(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPublishRequirement publishRequirement) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", transition.taskId(), "event-readiness-1",
                transition.transitionId(), HASH, "frame-1", "plan-1",
                "revision-1", 1L, "step-1", "review-1",
                new ChainPersistenceRecords.CanonicalJson(1, HASH, "[]"), 1L,
                null, ChainIdentity.NONE, "workspace-1", ChainIdentity.NONE,
                null, null,
                new ChainPersistenceRecords.CanonicalJson(1, HASH, "[]"),
                publishRequirement, HASH,
                "instruction-1", "project-version-1", NOW);
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord check(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainFinalization.Outcome outcome) {
        return check(
                transition, readiness, 1, outcome,
                outcome == ChainFinalization.Outcome.FAILED
                        ? ChainFinalization.ErrorCode
                        .VALIDATION_NOT_SUCCESSFUL
                        : null,
                outcome == ChainFinalization.Outcome.FAILED
                        ? ChainFinalization.FailureHandling.REFLECTOR_REQUIRED
                        : ChainFinalization.FailureHandling.NONE,
                readiness.readinessId(), readiness.taskFrameId());
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord
            retryableCheck(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            int attemptNo) {
        return check(
                transition, readiness, attemptNo,
                ChainFinalization.Outcome.FAILED,
                ChainFinalization.ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
                ChainFinalization.FailureHandling.RETRYABLE,
                readiness.readinessId(), readiness.taskFrameId());
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord check(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            int attemptNo,
            ChainFinalization.Outcome outcome,
            ChainFinalization.ErrorCode errorCode,
            ChainFinalization.FailureHandling failureHandling,
            String readinessId,
            String taskFrameId) {
        return check(
                transition, readiness, attemptNo, outcome, errorCode,
                failureHandling, readinessId, taskFrameId,
                ChainRuntimePolicy.V1.policyVersion());
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord check(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            int attemptNo,
            ChainFinalization.Outcome outcome,
            ChainFinalization.ErrorCode errorCode,
            ChainFinalization.FailureHandling failureHandling,
            String readinessId,
            String taskFrameId,
            String runtimePolicyVersion) {
        return new ChainPersistenceRecords.FinalizationCheckRecord(
                "finalization-check-" + attemptNo, transition.taskId(),
                "event-finalization-check-" + attemptNo, readinessId,
                transition.transitionId(), attemptNo, taskFrameId,
                readiness.finalPlanRevisionId(),
                readiness.acceptedSet().sha256(), readiness.candidateKey(),
                readiness.workspaceId(), readiness.validationId(),
                readiness.validationRequestDigest(),
                readiness.validationReceiptDigest(),
                readiness.publishRequirementDigest(), readiness.instructionId(),
                readiness.projectVersion(), HASH, HASH, HASH, outcome,
                errorCode, failureHandling,
                runtimePolicyVersion, NOW);
    }

    private static ChainFinalizationRepository finalization(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        return proxy(ChainFinalizationRepository.class, Map.of(
                "findReadiness", ignored -> List.of(readiness),
                "findFinalizationChecks", ignored -> List.of(check)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> type, Map<String, Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "RecoveryTestProxy(" + type.getName() + ")";
                            case "hashCode" -> System.identityHashCode(ignored);
                            case "equals" -> ignored == arguments[0];
                            default -> throw new UnsupportedOperationException();
                        };
                    }
                    Function<Object[], Object> answer = answers.get(method.getName());
                    if (answer != null) {
                        return answer.apply(arguments == null ? new Object[0] : arguments);
                    }
                    if (method.getReturnType() == Optional.class) return Optional.empty();
                    if (method.getReturnType() == List.class) return List.of();
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    throw new UnsupportedOperationException(method.toString());
                });
    }

    private static <T> T empty(Class<T> type) {
        return proxy(type, Map.of());
    }

    private static final class Store implements
            ChainWorkflowRepository,
            ChainTransitionWriter,
            ChainWorkspaceCandidateWriter {
        private final List<ChainPersistenceRecords.TransitionRecord> transitions =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.TransitionStageRecord> stages =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.ActionBindingRecord> actions =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.CandidateStepResultRecord>
                candidates = new ArrayList<>();
        private final List<ChainPersistenceRecords.ReviewDecisionRecord> reviews =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.AcceptedResultRecord> accepted =
                new ArrayList<>();
        private long eventSequence;

        @Override
        public Optional<ChainPersistenceRecords.TransitionRecord> findTransition(
                String transitionId) {
            return transitions.stream().filter(value -> value.transitionId()
                    .equals(transitionId)).findFirst();
        }

        @Override
        public List<ChainPersistenceRecords.TransitionStageRecord>
                findTransitionStages(String transitionId) {
            return stages.stream().filter(value -> value.transitionId()
                    .equals(transitionId)).toList();
        }

        @Override
        public List<ChainPersistenceRecords.InstructionDispositionRecord>
                findInstructionDispositions(String taskId) {
            return List.of();
        }

        @Override
        public List<ChainPersistenceRecords.TransitionRecord>
                findIncompleteTransitions(String taskId) {
            return transitions.stream().filter(value -> value.taskId().equals(taskId)
                    && stages.stream().noneMatch(stage ->
                    stage.transitionId().equals(value.transitionId())
                            && stage.stageCode() == ChainTransitionStage.COMPLETE))
                    .toList();
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TransitionRecord> appendTransition(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.TransitionRecord> value) {
            var existing = findTransition(value.fact().transitionId());
            if (existing.isEmpty()) transitions.add(value.fact());
            return result(value.event(), existing.orElse(value.fact()),
                    existing.isPresent());
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TransitionStageRecord>
                appendTransitionStage(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.TransitionStageRecord> value) {
            var existing = stages.stream().filter(stage ->
                    stage.transitionId().equals(value.fact().transitionId())
                            && stage.stageCode() == value.fact().stageCode())
                    .findFirst();
            if (existing.isEmpty()) stages.add(value.fact());
            return result(value.event(), existing.orElse(value.fact()),
                    existing.isPresent());
        }

        private <T extends ChainPersistenceRecords.TaskAuthorityFact>
                ChainPersistenceRecords.AuthoritativeAppendResult<T> result(
                ChainPersistenceRecords.AuthorityEventRequest request,
                T fact, boolean replayed) {
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            request.eventId(), request.taskId(), ++eventSequence,
                            request.eventType(), request.transitionId(),
                            request.sourceIdentitySha256(), request.committedAt()),
                    fact, replayed);
        }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String taskId) { return actions.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String taskId) { return findActionBindings(taskId); }
        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.WorkspaceCandidateRecord> appendWorkspaceCandidate(ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.WorkspaceCandidateRecord> value) { throw new IllegalStateException("Candidate not expected"); }
        @Override public List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String taskId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String taskId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String taskId) { return candidates.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String taskId) { return reviews.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
        @Override public List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String taskId) { return accepted.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String taskId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String taskId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String taskId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String gapId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String taskId) { return List.of(); }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String taskId) { return List.of(); }
    }
}
