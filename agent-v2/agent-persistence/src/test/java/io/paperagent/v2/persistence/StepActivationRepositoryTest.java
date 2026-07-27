package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepActivationRepositoryTest {
    private static final String OWNER = "worker-a";
    private static final String TOKEN = "lease-token-a";

    @Test
    void publicValuesEnforceStructureConsistencyAndOpaqueText() {
        Plan plan = PersistenceFixtures.plan();
        StepActivationRequest request =
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, 1, "activation-secret-id");

        assertNullMessage(
                () -> new StepActivationRequest(
                        null, TOKEN, 1, request.expectedRevisionId(), 1, 2, 1,
                        request.stepId(), request.activationEvent(),
                        request.activatedCheckpoint()),
                "planId");
        assertNullMessage(
                () -> new StepActivationRequest(
                        plan.id(), null, 1, request.expectedRevisionId(), 1, 2, 1,
                        request.stepId(), request.activationEvent(),
                        request.activatedCheckpoint()),
                "leaseToken");
        assertThrows(
                IllegalArgumentException.class,
                () -> new StepActivationRequest(
                        plan.id(), " ", 1, request.expectedRevisionId(), 1, 2, 1,
                        request.stepId(), request.activationEvent(),
                        request.activatedCheckpoint()));
        for (long invalid : List.of(0L, -1L)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StepActivationRequest(
                            plan.id(), TOKEN, invalid, request.expectedRevisionId(),
                            1, 2, 1, request.stepId(), request.activationEvent(),
                            request.activatedCheckpoint()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StepActivationRequest(
                            plan.id(), TOKEN, 1, request.expectedRevisionId(),
                            invalid, 2, 1, request.stepId(),
                            request.activationEvent(),
                            request.activatedCheckpoint()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StepActivationRequest(
                            plan.id(), TOKEN, 1, request.expectedRevisionId(),
                            1, 2, invalid, request.stepId(),
                            request.activationEvent(),
                            request.activatedCheckpoint()));
        }
        for (long invalid : List.of(0L, 1L, Long.MAX_VALUE)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StepActivationRequest(
                            plan.id(), TOKEN, 1, request.expectedRevisionId(),
                            1, invalid, 1, request.stepId(),
                            request.activationEvent(),
                            request.activatedCheckpoint()));
        }
        assertNullMessage(
                () -> new StepActivationRequest(
                        plan.id(), TOKEN, 1, null, 1, 2, 1, request.stepId(),
                        request.activationEvent(), request.activatedCheckpoint()),
                "expectedRevisionId");
        assertNullMessage(
                () -> new StepActivationRequest(
                        plan.id(), TOKEN, 1, request.expectedRevisionId(), 1, 2, 1,
                        null, request.activationEvent(),
                        request.activatedCheckpoint()),
                "stepId");
        assertNullMessage(
                () -> new StepActivationRequest(
                        plan.id(), TOKEN, 1, request.expectedRevisionId(), 1, 2, 1,
                        request.stepId(), null, request.activatedCheckpoint()),
                "activationEvent");
        assertNullMessage(
                () -> new StepActivationRequest(
                        plan.id(), TOKEN, 1, request.expectedRevisionId(), 1, 2, 1,
                        request.stepId(), request.activationEvent(), null),
                "activatedCheckpoint");

        PersistedStepActivation result = new PersistedStepActivation(
                plan.id(),
                request.stepId(),
                OWNER,
                1,
                request.activationEvent(),
                new VersionedCheckpoint(3, request.activatedCheckpoint()));
        assertNotNull(result);
        assertNullMessage(
                () -> new PersistedStepActivation(
                        null, request.stepId(), OWNER, 1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())),
                "planId");
        assertNullMessage(
                () -> new PersistedStepActivation(
                        plan.id(), null, OWNER, 1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())),
                "stepId");
        assertNullMessage(
                () -> new PersistedStepActivation(
                        plan.id(), request.stepId(), null, 1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())),
                "leaseOwnerId");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedStepActivation(
                        plan.id(), request.stepId(), " ", 1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedStepActivation(
                        plan.id(), request.stepId(), OWNER, 0,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())));
        assertNullMessage(
                () -> new PersistedStepActivation(
                        plan.id(), request.stepId(), OWNER, 1, null,
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())),
                "activationEvent");
        assertNullMessage(
                () -> new PersistedStepActivation(
                        plan.id(), request.stepId(), OWNER, 1,
                        request.activationEvent(), null),
                "activatedCheckpoint");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedStepActivation(
                        plan.id(), request.stepId(), OWNER, 1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                2, request.activatedCheckpoint())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedStepActivation(
                        new PlanId("other-plan"), request.stepId(), OWNER, 1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedStepActivation(
                        plan.id(),
                        PersistenceFixtures.STEP_2,
                        OWNER,
                        1,
                        request.activationEvent(),
                        new VersionedCheckpoint(
                                3, request.activatedCheckpoint())));

        String requestText = request.toString();
        String resultText = result.toString();
        for (String secret : List.of(
                TOKEN,
                plan.id().value(),
                request.stepId().value(),
                request.activationEvent().id().value(),
                request.activationEvent().correlationId(),
                "event-3",
                "1",
                "2",
                "3",
                OWNER)) {
            assertFalse(requestText.contains(secret), requestText);
            assertFalse(resultText.contains(secret), resultText);
        }
        assertTrue(requestText.contains("leaseToken=<provided>"));
        assertTrue(resultText.contains("leaseOwnerId=<provided>"));
    }

    @Test
    void internalAuthorityValuesKeepNestedFactsOpaque() {
        Harness harness = startedHarness("opaque-activation-event");
        StepActivationRequest request =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "opaque-activation-event");
        requireApplied(harness.activations().activate(request));

        InMemoryState.ExecutionStartMarker start =
                harness.state().executionStarts.get(PersistenceFixtures.PLAN_ID);
        InMemoryState.ExecutionMutationHead head =
                harness.state().executionMutationHeads.get(
                        PersistenceFixtures.PLAN_ID);
        InMemoryState.ExecutionMutationLink link =
                harness.state().executionMutationLinks
                        .get(PersistenceFixtures.PLAN_ID)
                        .get(0);
        InMemoryState.StepActivationMarker marker =
                harness.state().stepActivations
                        .get(PersistenceFixtures.PLAN_ID)
                        .get(request.activationEvent().id());
        InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                InMemoryExecutionMutationAuthority.validateAuthoritativeSource(
                        harness.state(), PersistenceFixtures.PLAN_ID);

        for (Object authority : List.of(
                start,
                head,
                link.markerIdentity(),
                link,
                marker,
                source)) {
            String text = authority.toString();
            for (String secret : List.of(
                    TOKEN,
                    OWNER,
                    PersistenceFixtures.PLAN_ID.value(),
                    PersistenceFixtures.STEP_1.value(),
                    request.activationEvent().id().value(),
                    request.activationEvent().correlationId(),
                    "event-1",
                    "event-3")) {
                assertFalse(text.contains(secret), text);
            }
            assertTrue(text.contains("<provided>"), text);
        }
    }

    @Test
    void atomicallyAppliesAndPermanentMarkerReplaysWithoutClock() {
        Scenario scenario = startedScenario("activation-applied");

        PersistenceResult<PersistedStepActivation> applied =
                scenario.persistence().stepActivations().activate(
                        scenario.activationRequest());

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        PersistedStepActivation fact = applied.value().orElseThrow();
        assertEquals(OWNER, fact.leaseOwnerId());
        assertEquals(scenario.lease().fencingToken(), fact.fencingToken());
        assertEquals(
                new VersionedCheckpoint(
                        3,
                        scenario.activationRequest().activatedCheckpoint()),
                fact.activatedCheckpoint());
        assertEquals(
                List.of(
                        scenario.start().startEvent(),
                        scenario.activationRequest().activationEvent()),
                scenario.persistence().events()
                        .readAfter(scenario.plan().id(), 0)
                        .value().orElseThrow());
        assertEquals(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                scenario.persistence().executionStartRecovery()
                        .inspect(scenario.plan().id())
                        .failure().orElseThrow().code());

        requireApplied(scenario.persistence().leases().release(
                scenario.plan().id(), TOKEN));
        scenario.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        requireApplied(scenario.persistence().leases().acquire(
                scenario.plan().id(),
                "worker-b",
                "lease-token-b",
                PersistenceFixtures.T0.plusSeconds(40)));
        int observations = scenario.clock().observationCount();
        scenario.clock().failOnObservation();

        PersistenceResult<PersistedStepActivation> replay =
                scenario.persistence().stepActivations().activate(
                        scenario.activationRequest());
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(fact, replay.value().orElseThrow());
        assertEquals(observations, scenario.clock().observationCount());
    }

    @Test
    void nullRequestIsInvalidWithoutClockObservation() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryPersistence persistence = new InMemoryPersistence(clock);

        assertFailure(
                persistence.stepActivations().activate(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request");
        assertEquals(0, clock.observationCount());
    }

    @Test
    void permanentMarkerUsesEveryRequestComponentAsIdentity() {
        Scenario scenario = startedScenario("activation-marker");
        requireApplied(scenario.persistence().stepActivations().activate(
                scenario.activationRequest()));
        scenario.clock().failOnObservation();
        StepActivationRequest original = scenario.activationRequest();
        EventEnvelope event = original.activationEvent();
        Checkpoint checkpoint = original.activatedCheckpoint();

        List<StepActivationRequest> conflicts = List.of(
                copy(original, "other-token", original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.stepId(), event, checkpoint),
                copy(original, original.leaseToken(), 2,
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.stepId(), event, checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        new PlanRevisionId("other-revision"),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.stepId(), event, checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        original.expectedRevisionId(), 2,
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.stepId(), event, checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(), 3,
                        original.expectedEventHeadSequence(),
                        original.stepId(), event, checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(), 2,
                        original.stepId(), event, checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        PersistenceFixtures.STEP_2, event, checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.stepId(),
                        eventWith(event, event.occurredAt().plusSeconds(1)),
                        checkpoint),
                copy(original, original.leaseToken(), original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.stepId(), event,
                        checkpointWith(
                                checkpoint,
                                checkpoint.createdAt().plusSeconds(1),
                                checkpoint.stepStates(),
                                checkpoint.lastEventSequence())));
        for (StepActivationRequest conflict : conflicts) {
            assertFailure(
                    scenario.persistence().stepActivations().activate(conflict),
                    PersistenceErrorCode.CONFLICTING_REPLAY,
                    "request.activationEvent.id");
        }
    }

    @Test
    void malformedExactMarkersFailClosedBeforeClockObservation() {
        for (String corruption : List.of(
                "null-marker",
                "null-result",
                "rewritten-result",
                "rewritten-link",
                "null-head-field")) {
            Harness harness = startedHarness(
                    "malformed-marker-" + corruption);
            StepActivationRequest request =
                    PersistenceFixtures.stepActivationRequest(
                            PersistenceFixtures.plan(),
                            TOKEN,
                            1,
                            "malformed-marker-event-" + corruption);
            requireApplied(harness.activations().activate(request));
            Map<EventId, InMemoryState.StepActivationMarker> markers =
                    harness.state().stepActivations.get(
                            PersistenceFixtures.PLAN_ID);
            InMemoryState.StepActivationMarker marker =
                    markers.get(request.activationEvent().id());
            InMemoryState.ExecutionMutationLink link =
                    marker.provenanceLink();

            InMemoryState.StepActivationMarker corrupted;
            switch (corruption) {
                case "null-marker" -> corrupted = null;
                case "null-result" -> corrupted =
                        new InMemoryState.StepActivationMarker(
                                marker.request(),
                                null,
                                link);
                case "rewritten-result" -> {
                    EventEnvelope rewritten = PersistenceFixtures.event(
                            "rewritten-result-event",
                            request.activationEvent().sequence());
                    PersistedStepActivation rewrittenResult =
                            new PersistedStepActivation(
                                    request.planId(),
                                    request.stepId(),
                                    OWNER,
                                    request.fencingToken(),
                                    rewritten,
                                    marker.result().activatedCheckpoint());
                    corrupted = new InMemoryState.StepActivationMarker(
                            marker.request(),
                            rewrittenResult,
                            link);
                }
                case "rewritten-link" -> {
                    InMemoryState.ExecutionMutationHead result =
                            link.resultHead();
                    InMemoryState.ExecutionMutationHead rewritten =
                            new InMemoryState.ExecutionMutationHead(
                                    result.revisionId(),
                                    result.revisionNumber(),
                                    result.checkpointVersion(),
                                    result.eventHeadSequence(),
                                    new EventId("rewritten-link-event"));
                    InMemoryState.ExecutionMutationLink rewrittenLink =
                            new InMemoryState.ExecutionMutationLink(
                                    link.previousHead(),
                                    rewritten,
                                    link.markerIdentity());
                    corrupted = new InMemoryState.StepActivationMarker(
                            marker.request(),
                            marker.result(),
                            rewrittenLink);
                }
                case "null-head-field" -> {
                    InMemoryState.ExecutionMutationHead result =
                            link.resultHead();
                    InMemoryState.ExecutionMutationHead malformed =
                            new InMemoryState.ExecutionMutationHead(
                                    null,
                                    result.revisionNumber(),
                                    result.checkpointVersion(),
                                    result.eventHeadSequence(),
                                    result.mutationEventId());
                    InMemoryState.ExecutionMutationLink malformedLink =
                            new InMemoryState.ExecutionMutationLink(
                                    link.previousHead(),
                                    malformed,
                                    link.markerIdentity());
                    corrupted = new InMemoryState.StepActivationMarker(
                            marker.request(),
                            marker.result(),
                            malformedLink);
                }
                default -> throw new AssertionError(corruption);
            }
            markers.put(request.activationEvent().id(), corrupted);

            AuthoritySnapshot authority = authoritySnapshot(harness.state());
            PersistenceFixtures.MutableCountingClock clock =
                    (PersistenceFixtures.MutableCountingClock)
                            harness.state().leaseClock;
            int before = clock.observationCount();
            clock.failOnObservation();
            assertFailure(
                    harness.activations().activate(request),
                    PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                    "stepActivation");
            assertEquals(before, clock.observationCount(), corruption);
            assertAuthorityUnchanged(authority, harness.state(), corruption);
        }
    }

    @Test
    void malformedCurrentHeadFieldsArePartialInsteadOfThrowing() {
        for (String field : List.of("revision-id", "mutation-event-id")) {
            Harness harness = startedHarness("malformed-current-" + field);
            StepActivationRequest first =
                    PersistenceFixtures.stepActivationRequest(
                            PersistenceFixtures.plan(),
                            TOKEN,
                            1,
                            "malformed-current-event-" + field);
            requireApplied(harness.activations().activate(first));
            InMemoryState.ExecutionMutationHead head =
                    harness.state().executionMutationHeads.get(
                            PersistenceFixtures.PLAN_ID);
            harness.state().executionMutationHeads.put(
                    PersistenceFixtures.PLAN_ID,
                    new InMemoryState.ExecutionMutationHead(
                            field.equals("revision-id")
                                    ? null
                                    : head.revisionId(),
                            head.revisionNumber(),
                            head.checkpointVersion(),
                            head.eventHeadSequence(),
                            field.equals("mutation-event-id")
                                    ? null
                                    : head.mutationEventId()));

            AuthoritySnapshot before = authoritySnapshot(harness.state());
            assertFailure(
                    harness.activations().activate(
                            differentActivation(
                                    first,
                                    "after-malformed-current-" + field)),
                    PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                    "stepActivation");
            assertAuthorityUnchanged(before, harness.state(), field);
            assertFailure(
                    harness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                    PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                    "executionRecovery");
            assertAuthorityUnchanged(before, harness.state(), field);
        }
    }

    @Test
    void firstAttemptUsesFrozenFailurePriority() {
        StepActivationRequest unknown =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(), TOKEN, 1, "unknown");
        InMemoryState unknownState = new InMemoryState(
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0));
        AuthoritySnapshot unknownBefore = authoritySnapshot(unknownState);
        assertFailure(
                new InMemoryStepActivationRepository(unknownState)
                        .activate(unknown),
                PersistenceErrorCode.NOT_FOUND,
                "request.planId");
        assertAuthorityUnchanged(
                unknownBefore, unknownState, "unknown-plan");

        Scenario scenario = startedScenario("activation-priority");
        AuthoritySnapshot before = authoritySnapshot(scenario.state());
        StepActivationRequest original = scenario.activationRequest();
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, "wrong-token", original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), original.activationEvent(),
                                original.activatedCheckpoint())),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(), 99,
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), original.activationEvent(),
                                original.activatedCheckpoint())),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                new PlanRevisionId("stale-revision"),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), original.activationEvent(),
                                original.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionId");
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(), 2,
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), original.activationEvent(),
                                original.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionNumber");
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(), 3,
                                original.expectedEventHeadSequence(),
                                original.stepId(), original.activationEvent(),
                                original.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(), 2,
                                original.stepId(), original.activationEvent(),
                                original.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedEventHeadSequence");
        assertAuthorityUnchanged(
                before, scenario.state(), "failure-priority");
    }

    @Test
    void eligibilityAndCanonicalTargetFailClosed() {
        Scenario scenario = startedScenario("activation-eligibility");
        AuthoritySnapshot before = authoritySnapshot(scenario.state());
        StepActivationRequest first = scenario.activationRequest();
        StepActivationRequest missingTarget = copy(
                first,
                first.leaseToken(),
                first.fencingToken(),
                first.expectedRevisionId(),
                first.expectedRevisionNumber(),
                first.expectedCheckpointVersion(),
                first.expectedEventHeadSequence(),
                new PlanStepId("missing-target"),
                first.activationEvent(),
                first.activatedCheckpoint());
        assertFailure(
                scenario.persistence().stepActivations().activate(missingTarget),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");

        StepActivationRequest blockedDependency =
                PersistenceFixtures.stepActivationRequest(
                        scenario.plan(),
                        first.leaseToken(),
                        first.fencingToken(),
                        "blocked-dependency");
        blockedDependency = copy(
                blockedDependency,
                blockedDependency.leaseToken(),
                blockedDependency.fencingToken(),
                blockedDependency.expectedRevisionId(),
                blockedDependency.expectedRevisionNumber(),
                blockedDependency.expectedCheckpointVersion(),
                blockedDependency.expectedEventHeadSequence(),
                PersistenceFixtures.STEP_2,
                blockedDependency.activationEvent(),
                checkpointWith(
                        blockedDependency.activatedCheckpoint(),
                        blockedDependency.activatedCheckpoint().createdAt(),
                        Map.of(
                                PersistenceFixtures.STEP_1,
                                StepExecutionState.NOT_STARTED,
                                PersistenceFixtures.STEP_2,
                                StepExecutionState.ACTIVE),
                        blockedDependency.activationEvent().sequence()));
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        blockedDependency),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");

        StepActivationRequest wrongCursor = copy(
                first,
                first.leaseToken(),
                first.fencingToken(),
                first.expectedRevisionId(),
                first.expectedRevisionNumber(),
                first.expectedCheckpointVersion(),
                first.expectedEventHeadSequence(),
                first.stepId(),
                first.activationEvent(),
                checkpointWith(
                        first.activatedCheckpoint(),
                        first.activatedCheckpoint().createdAt(),
                        first.activatedCheckpoint().stepStates(),
                        first.activationEvent().sequence() + 1));
        assertFailure(
                scenario.persistence().stepActivations().activate(wrongCursor),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.activatedCheckpoint.lastEventSequence");

        Map<PlanStepId, StepExecutionState> illegal =
                new LinkedHashMap<>(
                        first.activatedCheckpoint().stepStates());
        illegal.put(PersistenceFixtures.STEP_2, StepExecutionState.ACTIVE);
        StepActivationRequest twoActive = copy(
                first,
                first.leaseToken(),
                first.fencingToken(),
                first.expectedRevisionId(),
                first.expectedRevisionNumber(),
                first.expectedCheckpointVersion(),
                first.expectedEventHeadSequence(),
                first.stepId(),
                eventWith(
                        first.activationEvent(),
                        first.activationEvent().occurredAt().plusSeconds(1)),
                checkpointWith(
                        first.activatedCheckpoint(),
                        first.activatedCheckpoint().createdAt(),
                        illegal,
                        first.activationEvent().sequence()));
        assertFailure(
                scenario.persistence().stepActivations().activate(twoActive),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.activatedCheckpoint");

        Checkpoint receiptDelta = new Checkpoint(
                first.activatedCheckpoint().taskFrameId(),
                first.activatedCheckpoint().planId(),
                first.activatedCheckpoint().revisionId(),
                first.activatedCheckpoint().revisionNumber(),
                first.activatedCheckpoint().lastEventSequence(),
                first.activatedCheckpoint().planState(),
                first.activatedCheckpoint().stepStates(),
                List.of(new ReceiptId("uncommitted-receipt")),
                first.activatedCheckpoint().createdAt());
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(
                                first,
                                first.leaseToken(),
                                first.fencingToken(),
                                first.expectedRevisionId(),
                                first.expectedRevisionNumber(),
                                first.expectedCheckpointVersion(),
                                first.expectedEventHeadSequence(),
                                first.stepId(),
                                eventWith(
                                        first.activationEvent(),
                                        first.activationEvent()
                                                .occurredAt()
                                                .plusSeconds(2)),
                                receiptDelta)),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.activatedCheckpoint");
        assertAuthorityUnchanged(
                before, scenario.state(), "eligibility-target");
    }

    @Test
    void pureEligibilityRejectsTerminalFactsAndNonRunnablePeerStates() {
        Plan baseline = PersistenceFixtures.plan();
        Checkpoint started = PersistenceFixtures.startedCheckpoint(baseline);
        for (StepExecutionState peerState : List.of(
                StepExecutionState.ACTIVE,
                StepExecutionState.PAUSED,
                StepExecutionState.FAILED,
                StepExecutionState.CANCELLED)) {
            Checkpoint checkpoint = new Checkpoint(
                    started.taskFrameId(),
                    started.planId(),
                    started.revisionId(),
                    started.revisionNumber(),
                    started.lastEventSequence(),
                    PlanExecutionState.ACTIVE,
                    Map.of(
                            PersistenceFixtures.STEP_1,
                            StepExecutionState.NOT_STARTED,
                            PersistenceFixtures.STEP_2,
                            peerState),
                    List.of(),
                    started.createdAt());
            assertFalse(
                    InMemoryStepActivationRepository.isEligible(
                            baseline,
                            checkpoint,
                            PersistenceFixtures.STEP_1),
                    peerState.name());
        }

        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "completed-outcome",
                PersistenceFixtures.T0.plusSeconds(1),
                List.of());
        PlanRevision base = baseline.latestRevision();
        PlanRevision withFact = new PlanRevision(
                base.id(),
                base.taskFrameId(),
                base.number(),
                Optional.empty(),
                base.reason(),
                base.createdAt(),
                base.steps(),
                Map.of(PersistenceFixtures.STEP_1, fact));
        Plan planWithFact = new Plan(
                baseline.id(),
                baseline.taskFrameId(),
                List.of(withFact));
        assertFalse(InMemoryStepActivationRepository.isEligible(
                planWithFact,
                started,
                PersistenceFixtures.STEP_1));
    }

    @Test
    void eventValidationPrecedesTargetValidation() {
        Scenario scenario = startedScenario("activation-event-validation");
        AuthoritySnapshot before = authoritySnapshot(scenario.state());
        StepActivationRequest original = scenario.activationRequest();
        EventEnvelope wrongPlan = new EventEnvelope(
                original.activationEvent().id(),
                original.activationEvent().taskFrameId(),
                new PlanId("other-plan"),
                original.activationEvent().sequence(),
                original.activationEvent().occurredAt(),
                original.activationEvent().type(),
                original.activationEvent().causationId(),
                original.activationEvent().correlationId(),
                original.activationEvent().payload());
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), wrongPlan,
                                original.activatedCheckpoint())),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request.activationEvent.planId");

        EventEnvelope staleSequence = new EventEnvelope(
                new EventId("stale-event"),
                original.activationEvent().taskFrameId(),
                original.activationEvent().planId(),
                1,
                original.activationEvent().occurredAt(),
                original.activationEvent().type(),
                original.activationEvent().causationId(),
                original.activationEvent().correlationId(),
                original.activationEvent().payload());
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), staleSequence,
                                original.activatedCheckpoint())),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                "request.activationEvent.sequence");

        EventEnvelope wrongTask = new EventEnvelope(
                new EventId("wrong-task-event"),
                new TaskFrameId("other-task"),
                original.activationEvent().planId(),
                original.activationEvent().sequence(),
                original.activationEvent().occurredAt(),
                original.activationEvent().type(),
                original.activationEvent().causationId(),
                original.activationEvent().correlationId(),
                original.activationEvent().payload());
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), wrongTask,
                                original.activatedCheckpoint())),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "request.activationEvent.taskFrameId");

        EventEnvelope occupied = new EventEnvelope(
                scenario.start().startEvent().id(),
                original.activationEvent().taskFrameId(),
                original.activationEvent().planId(),
                original.activationEvent().sequence(),
                original.activationEvent().occurredAt(),
                original.activationEvent().type(),
                original.activationEvent().causationId(),
                original.activationEvent().correlationId(),
                original.activationEvent().payload());
        assertFailure(
                scenario.persistence().stepActivations().activate(
                        copy(original, original.leaseToken(),
                                original.fencingToken(),
                                original.expectedRevisionId(),
                                original.expectedRevisionNumber(),
                                original.expectedCheckpointVersion(),
                                original.expectedEventHeadSequence(),
                                original.stepId(), occupied,
                                original.activatedCheckpoint())),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.activationEvent.id");
        assertAuthorityUnchanged(
                before, scenario.state(), "event-validation");
    }

    @Test
    void missingAndInclusivelyExpiredLeaseCannotActivate() {
        Scenario released = startedScenario("activation-no-lease");
        requireApplied(released.persistence().leases().release(
                released.plan().id(), TOKEN));
        AuthoritySnapshot releasedBefore =
                authoritySnapshot(released.state());
        assertFailure(
                released.persistence().stepActivations().activate(
                        released.activationRequest()),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "request.planId");
        assertAuthorityUnchanged(
                releasedBefore, released.state(), "missing-lease");

        Scenario expired = startedScenario("activation-expired");
        expired.clock().set(PersistenceFixtures.T0.plus(Duration.ofMinutes(1)));
        AuthoritySnapshot expiredBefore =
                authoritySnapshot(expired.state());
        assertFailure(
                expired.persistence().stepActivations().activate(
                        expired.activationRequest()),
                PersistenceErrorCode.LEASE_EXPIRED,
                "request.planId");
        assertAuthorityUnchanged(
                expiredBefore, expired.state(), "expired-lease");
    }

    @Test
    void partialSourceWinsBeforeLeaseCasAndEligibility() {
        Harness harness = startedHarness("activation-partial");
        harness.state().executionMutationHeads.remove(PersistenceFixtures.PLAN_ID);
        StepActivationRequest request =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        "wrong-token",
                        99,
                        "partial-request");

        AuthoritySnapshot before = authoritySnapshot(harness.state());
        assertFailure(
                harness.activations().activate(request),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertAuthorityUnchanged(
                before, harness.state(), "partial-source");
    }

    @Test
    void markerEventAndTipCheckpointProjectionCorruptionArePartial() {
        Harness eventHarness = startedHarness("activation-corrupt-event");
        StepActivationRequest eventRequest =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "activation-corrupt-event");
        requireApplied(eventHarness.activations().activate(eventRequest));
        eventHarness.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(
                        eventRequest.activationEvent().sequence(),
                        PersistenceFixtures.event(
                                "replacement-event",
                                eventRequest.activationEvent().sequence()));
        eventHarness.state().eventsById.remove(
                eventRequest.activationEvent().id());
        eventHarness.state().eventsById.put(
                new EventId("replacement-event"),
                PersistenceFixtures.event(
                        "replacement-event",
                        eventRequest.activationEvent().sequence()));
        assertFailure(
                eventHarness.activations().activate(
                        differentActivation(eventRequest, "after-corruption")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertFailure(
                eventHarness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");

        Harness checkpointHarness = startedHarness(
                "activation-corrupt-checkpoint");
        StepActivationRequest checkpointRequest =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "activation-corrupt-checkpoint");
        requireApplied(checkpointHarness.activations().activate(
                checkpointRequest));
        Map<PlanStepId, StepExecutionState> rewritten =
                new LinkedHashMap<>(
                        checkpointRequest.activatedCheckpoint().stepStates());
        rewritten.put(
                PersistenceFixtures.STEP_1,
                StepExecutionState.PAUSED);
        checkpointHarness.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                new VersionedCheckpoint(
                        3,
                        checkpointWith(
                                checkpointRequest.activatedCheckpoint(),
                                checkpointRequest.activatedCheckpoint().createdAt(),
                                rewritten,
                                checkpointRequest.activationEvent().sequence())));
        assertFailure(
                checkpointHarness.activations().activate(
                        differentActivation(
                                checkpointRequest,
                                "after-checkpoint-corruption")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertFailure(
                checkpointHarness.recovery().inspect(
                        PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    @Test
    void unbackedEventInsideASequenceGapIsPartial() {
        Harness harness = startedHarness("unbacked-gap");
        StepActivationRequest request =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "gap-activation");
        requireApplied(harness.activations().activate(request));
        EventEnvelope unbacked = PersistenceFixtures.event(
                "unbacked-event", 2);
        harness.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(unbacked.sequence(), unbacked);
        harness.state().eventsById.put(unbacked.id(), unbacked);

        assertFailure(
                harness.activations().activate(
                        differentActivation(request, "after-unbacked")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertFailure(
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    @Test
    void nullGlobalEventIndexKeyIsPartialAndCannotWriteAuthority() {
        Harness harness = startedHarness("null-index-key");
        StepActivationRequest first =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "null-index-activation");
        requireApplied(harness.activations().activate(first));
        harness.state().eventsById.put(
                null,
                PersistenceFixtures.event(
                        "null-key-value",
                        first.activationEvent().sequence()));

        AuthoritySnapshot before = authoritySnapshot(harness.state());
        assertFailure(
                harness.activations().activate(
                        differentActivation(first, "after-null-index")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertAuthorityUnchanged(before, harness.state(), "null-index");
        assertFailure(
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
        assertAuthorityUnchanged(before, harness.state(), "null-index");
    }

    @Test
    void corruptedChainTopologyIsPartialAndCannotWriteBusinessAuthority() {
        for (String corruption : List.of(
                "orphan-marker",
                "unbacked-link",
                "fork",
                "cycle",
                "duplicate",
                "gap")) {
            Harness harness = startedHarness("chain-" + corruption);
            StepActivationRequest request =
                    PersistenceFixtures.stepActivationRequest(
                            PersistenceFixtures.plan(),
                            TOKEN,
                            1,
                            "chain-event-" + corruption);
            requireApplied(harness.activations().activate(request));

            List<InMemoryState.ExecutionMutationLink> links =
                    harness.state().executionMutationLinks.get(
                            PersistenceFixtures.PLAN_ID);
            Map<EventId, InMemoryState.StepActivationMarker> markers =
                    harness.state().stepActivations.get(
                            PersistenceFixtures.PLAN_ID);
            InMemoryState.ExecutionMutationLink link = links.get(0);
            InMemoryState.StepActivationMarker marker =
                    markers.get(request.activationEvent().id());
            EventId syntheticId = new EventId(
                    "synthetic-" + corruption);

            switch (corruption) {
                case "orphan-marker" ->
                        markers.put(syntheticId, marker);
                case "unbacked-link" -> links.add(
                        new InMemoryState.ExecutionMutationLink(
                                link.resultHead(),
                                successorHead(link.resultHead(), syntheticId),
                                InMemoryState.ExecutionMutationMarkerIdentity
                                        .stepActivation(syntheticId)));
                case "fork" -> {
                    links.add(new InMemoryState.ExecutionMutationLink(
                            link.previousHead(),
                            successorHead(link.resultHead(), syntheticId),
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .stepActivation(syntheticId)));
                    markers.put(syntheticId, marker);
                }
                case "cycle" -> {
                    InMemoryState.ExecutionMutationLink cycle =
                            new InMemoryState.ExecutionMutationLink(
                                    link.previousHead(),
                                    link.previousHead(),
                                    link.markerIdentity());
                    links.set(0, cycle);
                    markers.put(
                            request.activationEvent().id(),
                            new InMemoryState.StepActivationMarker(
                                    marker.request(),
                                    marker.result(),
                                    cycle));
                }
                case "duplicate" -> {
                    links.add(new InMemoryState.ExecutionMutationLink(
                            link.resultHead(),
                            successorHead(
                                    link.resultHead(),
                                    request.activationEvent().id()),
                            link.markerIdentity()));
                    markers.put(syntheticId, marker);
                }
                case "gap" -> {
                    InMemoryState.ExecutionMutationHead gapPrevious =
                            new InMemoryState.ExecutionMutationHead(
                                    link.previousHead().revisionId(),
                                    link.previousHead().revisionNumber(),
                                    link.previousHead().checkpointVersion(),
                                    link.previousHead().eventHeadSequence() + 1,
                                    link.previousHead().mutationEventId());
                    InMemoryState.ExecutionMutationLink gap =
                            new InMemoryState.ExecutionMutationLink(
                                    gapPrevious,
                                    link.resultHead(),
                                    link.markerIdentity());
                    links.set(0, gap);
                    markers.put(
                            request.activationEvent().id(),
                            new InMemoryState.StepActivationMarker(
                                    marker.request(),
                                    marker.result(),
                                    gap));
                }
                default -> throw new AssertionError(corruption);
            }

            AuthoritySnapshot before = authoritySnapshot(harness.state());
            assertFailure(
                    harness.activations().activate(
                            differentActivation(
                                    request,
                                    "after-" + corruption)),
                    PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                    "stepActivation");
            assertAuthorityUnchanged(before, harness.state(), corruption);
            assertFailure(
                    harness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                    PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                    "executionRecovery");
            assertAuthorityUnchanged(before, harness.state(), corruption);
        }
    }

    @Test
    void danglingReceiptCorruptionCannotBeWashedCleanByObjectAppend() {
        Harness harness = startedHarness("dangling-receipt");
        VersionedCheckpoint root =
                harness.state().checkpoints.get(PersistenceFixtures.PLAN_ID);
        ReceiptId missing = new ReceiptId("dangling-receipt");
        Checkpoint corrupted = new Checkpoint(
                root.checkpoint().taskFrameId(),
                root.checkpoint().planId(),
                root.checkpoint().revisionId(),
                root.checkpoint().revisionNumber(),
                root.checkpoint().lastEventSequence(),
                root.checkpoint().planState(),
                root.checkpoint().stepStates(),
                List.of(missing),
                root.checkpoint().createdAt());
        harness.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                new VersionedCheckpoint(root.version(), corrupted));
        StepActivationRequest request =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "after-dangling-receipt");
        assertFailure(
                harness.activations().activate(request),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");

        requireApplied(new InMemoryReceiptRepository(harness.state()).append(
                PersistenceFixtures.receipt(
                        missing.value(), "dangling-tool-call")));

        assertFailure(
                harness.activations().activate(request),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertFailure(
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    @Test
    void rootCheckpointRewriteAndActivationRevisionJumpArePartial() {
        Harness rootHarness = startedHarness("root-rewrite");
        Checkpoint started =
                rootHarness.state().checkpoints
                        .get(PersistenceFixtures.PLAN_ID)
                        .checkpoint();
        rootHarness.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                new VersionedCheckpoint(
                        2,
                        checkpointWith(
                                started,
                                started.createdAt().plusSeconds(1),
                                started.stepStates(),
                                started.lastEventSequence())));
        assertFailure(
                rootHarness.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                PersistenceFixtures.plan(),
                                TOKEN,
                                1,
                                "after-root-rewrite")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertFailure(
                rootHarness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");

        Harness revisionHarness = startedHarness("revision-jump");
        Plan revisionTwoPlan = new Plan(
                PersistenceFixtures.PLAN_ID,
                PersistenceFixtures.TASK_ID,
                List.of(
                        PersistenceFixtures.revision1(),
                        PersistenceFixtures.revision2(
                                "revision-jump-2", "corrupt activation jump")));
        revisionHarness.state().plans.put(
                PersistenceFixtures.PLAN_ID, revisionTwoPlan);
        InMemoryState.ExecutionMutationHead root =
                revisionHarness.state().executionMutationHeads.get(
                        PersistenceFixtures.PLAN_ID);
        EventEnvelope event = PersistenceFixtures.event(
                "revision-jump-activation", 3);
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                started.stepStates());
        states.put(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE);
        Checkpoint jumped = new Checkpoint(
                started.taskFrameId(),
                started.planId(),
                revisionTwoPlan.latestRevision().id(),
                revisionTwoPlan.latestRevision().number(),
                event.sequence(),
                started.planState(),
                states,
                started.receiptReferences(),
                started.createdAt().plusSeconds(1));
        StepActivationRequest corruptRequest = new StepActivationRequest(
                PersistenceFixtures.PLAN_ID,
                TOKEN,
                1,
                root.revisionId(),
                root.revisionNumber(),
                root.checkpointVersion(),
                root.eventHeadSequence(),
                PersistenceFixtures.STEP_1,
                event,
                jumped);
        PersistedStepActivation corruptResult =
                new PersistedStepActivation(
                        PersistenceFixtures.PLAN_ID,
                        PersistenceFixtures.STEP_1,
                        OWNER,
                        1,
                        event,
                        new VersionedCheckpoint(3, jumped));
        InMemoryState.ExecutionMutationHead corruptHead =
                new InMemoryState.ExecutionMutationHead(
                        jumped.revisionId(),
                        jumped.revisionNumber(),
                        3,
                        event.sequence(),
                        event.id());
        InMemoryState.ExecutionMutationLink corruptLink =
                new InMemoryState.ExecutionMutationLink(
                        root,
                        corruptHead,
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .stepActivation(event.id()));
        InMemoryState.StepActivationMarker corruptMarker =
                new InMemoryState.StepActivationMarker(
                        corruptRequest, corruptResult, corruptLink);
        revisionHarness.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(event.sequence(), event);
        revisionHarness.state().eventsById.put(event.id(), event);
        revisionHarness.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                corruptResult.activatedCheckpoint());
        revisionHarness.state().stepActivations
                .get(PersistenceFixtures.PLAN_ID)
                .put(event.id(), corruptMarker);
        revisionHarness.state().executionMutationLinks.put(
                PersistenceFixtures.PLAN_ID, List.of(corruptLink));
        revisionHarness.state().executionMutationHeads.put(
                PersistenceFixtures.PLAN_ID, corruptHead);

        assertFailure(
                revisionHarness.activations().activate(
                        differentActivation(
                                corruptRequest,
                                "after-revision-jump")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertFailure(
                revisionHarness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    @Test
    void postStartOrdinaryMutationRequiresFenceWithIdentityPriority() {
        Scenario scenario = startedScenario("activation-guard");
        Plan plan = scenario.plan();
        assertFailure(
                scenario.persistence().plans().appendRevision(
                        plan.id(),
                        1,
                        PersistenceFixtures.revision2(
                                "guard-revision", "guarded")),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "planId");
        assertEquals(
                PersistenceOutcome.REPLAYED,
                scenario.persistence().plans().appendRevision(
                        plan.id(),
                        1,
                        plan.latestRevision())
                        .outcome());

        EventEnvelope startEvent = scenario.start().startEvent();
        assertEquals(
                PersistenceOutcome.REPLAYED,
                scenario.persistence().events().append(startEvent).outcome());
        EventEnvelope conflict = eventWith(
                startEvent, startEvent.occurredAt().plusSeconds(1));
        assertFailure(
                scenario.persistence().events().append(conflict),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "event.id");
        assertFailure(
                scenario.persistence().events().append(
                        PersistenceFixtures.event("guard-event", 3)),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "event.planId");
        assertFailure(
                scenario.persistence().checkpoints().save(
                        0, scenario.start().startedCheckpoint().checkpoint()),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "checkpoint.planId");
    }

    @Test
    void orphanPermanentStartMarkerStillGuardsOrdinaryMutation() {
        Harness harness = startedHarness("activation-orphan-guard");
        harness.state().plans.remove(PersistenceFixtures.PLAN_ID);
        assertFailure(
                harness.plans().appendRevision(
                        PersistenceFixtures.PLAN_ID,
                        1,
                        PersistenceFixtures.revision2(
                                "orphan-revision", "must remain guarded")),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "planId");
        assertFailure(
                harness.events().append(
                        PersistenceFixtures.event("orphan-event", 3)),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "event.planId");
        assertFailure(
                harness.checkpoints().save(
                        2,
                        PersistenceFixtures.startedCheckpoint(
                                PersistenceFixtures.plan())),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "checkpoint.planId");
    }

    @Test
    void ordinaryReceiptStorageDoesNotChangeExecutionAuthority() {
        Scenario scenario = startedScenario("activation-receipt");
        VersionedCheckpoint before = scenario.persistence().checkpoints()
                .find(scenario.plan().id()).value().orElseThrow();
        List<EventEnvelope> events = scenario.persistence().events()
                .readAfter(scenario.plan().id(), 0).value().orElseThrow();

        requireApplied(scenario.persistence().receipts().append(
                PersistenceFixtures.receipt("ordinary-receipt", "tool-1")));

        assertEquals(
                before,
                scenario.persistence().checkpoints()
                        .find(scenario.plan().id()).value().orElseThrow());
        assertEquals(
                events,
                scenario.persistence().events()
                        .readAfter(scenario.plan().id(), 0)
                        .value().orElseThrow());
        assertEquals(
                PersistenceOutcome.FOUND,
                scenario.persistence().executionStartRecovery()
                        .inspect(scenario.plan().id()).outcome());
    }

    private static Scenario startedScenario(String activationEventId) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        ScenarioPersistence persistence = new ScenarioPersistence(
                new InMemoryPlanRepository(state),
                new InMemoryEventRepository(state),
                new InMemoryReceiptRepository(state),
                new InMemoryCheckpointRepository(state),
                new InMemoryLeaseRepository(state),
                new InMemoryExecutionStartRepository(state),
                new InMemoryStepActivationRepository(state),
                new InMemoryExecutionStartRecoveryRepository(state));
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                PersistenceFixtures.plan(),
                PersistenceFixtures.initialCheckpoint(
                        PersistenceFixtures.plan())));
        Plan plan = PersistenceFixtures.plan();
        LeaseRecord lease = requireApplied(persistence.leases().acquire(
                plan.id(),
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        PersistedExecutionStart start = requireApplied(
                persistence.executionStarts().start(
                        PersistenceFixtures.executionStartRequest(
                                plan, TOKEN, lease.fencingToken(),
                                "start-" + activationEventId)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec(activationEventId));
        return new Scenario(
                persistence,
                state,
                clock,
                plan,
                lease,
                start,
                PersistenceFixtures.stepActivationRequest(
                        plan,
                        TOKEN,
                        lease.fencingToken(),
                        activationEventId));
    }

    private static Harness startedHarness(String startEventId) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Harness harness = new Harness(
                state,
                new InMemoryPlanRepository(state),
                new InMemoryEventRepository(state),
                new InMemoryCheckpointRepository(state),
                new InMemoryStepActivationRepository(state),
                new InMemoryExecutionStartRecoveryRepository(state));
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                PersistenceFixtures.plan(),
                PersistenceFixtures.initialCheckpoint(
                        PersistenceFixtures.plan())));
        requireApplied(new InMemoryLeaseRepository(state).acquire(
                PersistenceFixtures.PLAN_ID,
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        1,
                        "start-" + startEventId)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                PersistenceFixtures.plan(),
                TOKEN,
                1,
                PersistenceFixtures.workspaceSpec(startEventId));
        return harness;
    }

    private static StepActivationRequest differentActivation(
            StepActivationRequest source,
            String eventId) {
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId),
                source.activationEvent().taskFrameId(),
                source.activationEvent().planId(),
                source.activationEvent().sequence() + 1,
                source.activationEvent().occurredAt().plusSeconds(1),
                source.activationEvent().type(),
                source.activationEvent().causationId(),
                source.activationEvent().correlationId(),
                source.activationEvent().payload());
        Checkpoint target = checkpointWith(
                source.activatedCheckpoint(),
                source.activatedCheckpoint().createdAt().plusSeconds(1),
                source.activatedCheckpoint().stepStates(),
                event.sequence());
        return new StepActivationRequest(
                source.planId(),
                source.leaseToken(),
                source.fencingToken(),
                source.expectedRevisionId(),
                source.expectedRevisionNumber(),
                source.expectedCheckpointVersion() + 1,
                source.activationEvent().sequence(),
                PersistenceFixtures.STEP_2,
                event,
                target);
    }

    private static StepActivationRequest copy(
            StepActivationRequest source,
            String leaseToken,
            long fencingToken,
            PlanRevisionId revisionId,
            long revisionNumber,
            long checkpointVersion,
            long eventHead,
            PlanStepId stepId,
            EventEnvelope event,
            Checkpoint checkpoint) {
        return new StepActivationRequest(
                source.planId(),
                leaseToken,
                fencingToken,
                revisionId,
                revisionNumber,
                checkpointVersion,
                eventHead,
                stepId,
                event,
                checkpoint);
    }

    private static EventEnvelope eventWith(
            EventEnvelope source,
            Instant occurredAt) {
        return new EventEnvelope(
                source.id(),
                source.taskFrameId(),
                source.planId(),
                source.sequence(),
                occurredAt,
                source.type(),
                source.causationId(),
                source.correlationId(),
                source.payload());
    }

    private static Checkpoint checkpointWith(
            Checkpoint source,
            Instant createdAt,
            Map<PlanStepId, StepExecutionState> states,
            long cursor) {
        return new Checkpoint(
                source.taskFrameId(),
                source.planId(),
                source.revisionId(),
                source.revisionNumber(),
                cursor,
                source.planState(),
                states,
                source.receiptReferences(),
                createdAt);
    }

    private static InMemoryState.ExecutionMutationHead successorHead(
            InMemoryState.ExecutionMutationHead source,
            EventId eventId) {
        return new InMemoryState.ExecutionMutationHead(
                source.revisionId(),
                source.revisionNumber(),
                source.checkpointVersion() + 1,
                source.eventHeadSequence() + 1,
                eventId);
    }

    private static AuthoritySnapshot authoritySnapshot(InMemoryState state) {
        Map<Long, EventEnvelope> stream =
                state.eventStreams.get(PersistenceFixtures.PLAN_ID);
        Map<EventId, InMemoryState.StepActivationMarker> markers =
                state.stepActivations.get(PersistenceFixtures.PLAN_ID);
        List<InMemoryState.ExecutionMutationLink> links =
                state.executionMutationLinks.get(PersistenceFixtures.PLAN_ID);
        return new AuthoritySnapshot(
                new LinkedHashMap<>(state.plans),
                new LinkedHashMap<>(state.receipts),
                new LinkedHashMap<>(state.leases),
                new LinkedHashMap<>(state.planBootstraps),
                new LinkedHashMap<>(state.executionStarts),
                new LinkedHashMap<>(state.eventsById),
                stream == null ? null : new LinkedHashMap<>(stream),
                state.checkpoints.get(PersistenceFixtures.PLAN_ID),
                markers == null ? null : new LinkedHashMap<>(markers),
                links == null ? null : List.copyOf(links),
                state.executionMutationHeads.get(PersistenceFixtures.PLAN_ID),
                new LinkedHashMap<>(
                        state.planExecutionContextReservations),
                new LinkedHashMap<>(
                        state.planExecutionContextConfirmations),
                new LinkedHashMap<>(state.workspaceOwners));
    }

    private static void assertAuthorityUnchanged(
            AuthoritySnapshot expected,
            InMemoryState state,
            String corruption) {
        assertEquals(
                expected.plans(),
                state.plans,
                corruption + " plans");
        assertEquals(
                expected.receipts(),
                state.receipts,
                corruption + " receipts");
        assertEquals(
                expected.leases(),
                state.leases,
                corruption + " leases");
        assertEquals(
                expected.bootstraps(),
                state.planBootstraps,
                corruption + " bootstraps");
        assertEquals(
                expected.starts(),
                state.executionStarts,
                corruption + " starts");
        assertEquals(
                expected.eventsById(),
                state.eventsById,
                corruption + " eventsById");
        assertEquals(
                expected.eventStream(),
                state.eventStreams.get(PersistenceFixtures.PLAN_ID),
                corruption + " eventStream");
        assertEquals(
                expected.checkpoint(),
                state.checkpoints.get(PersistenceFixtures.PLAN_ID),
                corruption + " checkpoint");
        assertEquals(
                expected.markers(),
                state.stepActivations.get(PersistenceFixtures.PLAN_ID),
                corruption + " markers");
        assertEquals(
                expected.links(),
                state.executionMutationLinks.get(PersistenceFixtures.PLAN_ID),
                corruption + " links");
        assertEquals(
                expected.head(),
                state.executionMutationHeads.get(PersistenceFixtures.PLAN_ID),
                corruption + " head");
        assertEquals(
                expected.contextReservations(),
                state.planExecutionContextReservations,
                corruption + " context reservations");
        assertEquals(
                expected.contextConfirmations(),
                state.planExecutionContextConfirmations,
                corruption + " context confirmations");
        assertEquals(
                expected.workspaceOwners(),
                state.workspaceOwners,
                corruption + " workspace owners");
    }

    private static void assertNullMessage(
            Runnable constructor,
            String path) {
        NullPointerException failure =
                assertThrows(NullPointerException.class, constructor::run);
        assertEquals(path, failure.getMessage());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
        assertTrue(result.value().isEmpty());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private record Scenario(
            ScenarioPersistence persistence,
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            LeaseRecord lease,
            PersistedExecutionStart start,
            StepActivationRequest activationRequest) {
    }

    private record ScenarioPersistence(
            PlanRepository plans,
            EventRepository events,
            ReceiptRepository receipts,
            CheckpointRepository checkpoints,
            LeaseRepository leases,
            ExecutionStartRepository executionStarts,
            StepActivationRepository stepActivations,
            ExecutionStartRecoveryRepository executionStartRecovery) {
    }

    private record Harness(
            InMemoryState state,
            PlanRepository plans,
            EventRepository events,
            CheckpointRepository checkpoints,
            StepActivationRepository activations,
            ExecutionStartRecoveryRepository recovery) {
    }

    private record AuthoritySnapshot(
            Map<PlanId, Plan> plans,
            Map<ReceiptId, ExecutionReceipt> receipts,
            Map<PlanId, LeaseRecord> leases,
            Map<PlanId, PersistedPlanBootstrap> bootstraps,
            Map<PlanId, InMemoryState.ExecutionStartMarker> starts,
            Map<EventId, EventEnvelope> eventsById,
            Map<Long, EventEnvelope> eventStream,
            VersionedCheckpoint checkpoint,
            Map<EventId, InMemoryState.StepActivationMarker> markers,
            List<InMemoryState.ExecutionMutationLink> links,
            InMemoryState.ExecutionMutationHead head,
            Map<PlanId, InMemoryState.PlanExecutionContextReservationMarker>
                    contextReservations,
            Map<PlanId, InMemoryState.PlanExecutionContextConfirmationMarker>
                    contextConfirmations,
            Map<io.paperagent.v2.contracts.WorkspaceId,
                    InMemoryState.WorkspaceOwner> workspaceOwners) {
    }
}
