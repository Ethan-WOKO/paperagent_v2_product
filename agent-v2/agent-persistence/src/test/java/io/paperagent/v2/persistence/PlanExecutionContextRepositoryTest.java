package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionContextRepositoryTest {
    private static final String OWNER = "context-owner";
    private static final String TOKEN = "context-token";

    @Test
    void publicValuesValidateStructureAndKeepTextOpaque() {
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec("values");
        Plan plan = PersistenceFixtures.plan();
        var reservation =
                PersistenceFixtures.contextReservationRequest(
                        plan, TOKEN, 1, spec);
        var reserved = new PersistedPlanExecutionContextReserved(
                plan.id(), spec, OWNER, 1);
        var confirmation =
                PersistenceFixtures.contextConfirmationRequest(
                        plan, TOKEN, 1, spec);
        var confirmed = new PersistedPlanExecutionContextConfirmed(
                reserved,
                OWNER,
                1,
                PersistenceFixtures.SOURCE_FINGERPRINT);

        for (Object value : List.of(
                reservation, reserved, confirmation, confirmed)) {
            String text = value.toString();
            assertFalse(text.contains(TOKEN), text);
            assertFalse(text.contains(OWNER), text);
            assertFalse(text.contains(spec.workspaceId().value()), text);
            assertFalse(text.contains(
                    PersistenceFixtures.SOURCE_FINGERPRINT.value()), text);
        }
        assertEquals(plan.id(), confirmed.planId());
        assertEquals(spec, confirmed.materializationSpec());

        assertThrows(NullPointerException.class, () ->
                new PlanExecutionContextReservationRequest(
                        null, TOKEN, 1,
                        plan.latestRevision().id(), 1, 2, 1, spec));
        assertThrows(IllegalArgumentException.class, () ->
                new PlanExecutionContextReservationRequest(
                        plan.id(), " ", 1,
                        plan.latestRevision().id(), 1, 2, 1, spec));
        assertThrows(IllegalArgumentException.class, () ->
                new PlanExecutionContextConfirmationRequest(
                        plan.id(), TOKEN, 0, spec,
                        PersistenceFixtures.SOURCE_FINGERPRINT));
        assertThrows(NullPointerException.class, () ->
                new PersistedPlanExecutionContextConfirmed(
                        reserved, OWNER, 1, null));
    }

    @Test
    void reserveConfirmAndInspectKeepH0Unchanged() {
        Setup setup = started(true, "happy");
        var request = reservationRequest(setup, "happy");
        var h0 = setup.state().executionMutationHeads.get(setup.plan().id());
        var checkpoint = setup.state().checkpoints.get(setup.plan().id());
        var events = setup.state().eventStreams.get(setup.plan().id());

        PersistenceResult<PersistedPlanExecutionContextReserved> reserved =
                setup.contexts().reserve(request);
        assertEquals(PersistenceOutcome.APPLIED, reserved.outcome());
        assertInstanceOf(
                PersistedPlanExecutionContextReserved.class,
                setup.contexts().inspect(setup.plan().id())
                        .value().orElseThrow());

        var confirmation = new PlanExecutionContextConfirmationRequest(
                setup.plan().id(),
                TOKEN,
                setup.lease().fencingToken(),
                request.materializationSpec(),
                PersistenceFixtures.SOURCE_FINGERPRINT);
        PersistenceResult<PersistedPlanExecutionContextConfirmed> confirmed =
                setup.contexts().confirm(confirmation);
        assertEquals(PersistenceOutcome.APPLIED, confirmed.outcome());
        assertEquals(
                confirmed.value().orElseThrow(),
                setup.contexts().inspect(setup.plan().id())
                        .value().orElseThrow());
        assertEquals(h0,
                setup.state().executionMutationHeads.get(setup.plan().id()));
        assertEquals(checkpoint,
                setup.state().checkpoints.get(setup.plan().id()));
        assertEquals(events,
                setup.state().eventStreams.get(setup.plan().id()));
        assertTrue(setup.state().executionMutationLinks
                .get(setup.plan().id()).isEmpty());
    }

    @Test
    void exactReplaySurvivesConfirmationActivationAndLeaseChangesWithoutClock() {
        Setup setup = started(true, "replay");
        var reserveRequest = reservationRequest(setup, "replay");
        PersistedPlanExecutionContextReserved reserved =
                requireApplied(setup.contexts().reserve(reserveRequest));
        var confirmRequest = new PlanExecutionContextConfirmationRequest(
                setup.plan().id(),
                TOKEN,
                setup.lease().fencingToken(),
                reserveRequest.materializationSpec(),
                PersistenceFixtures.SOURCE_FINGERPRINT);
        PersistedPlanExecutionContextConfirmed confirmed =
                requireApplied(setup.contexts().confirm(confirmRequest));
        requireApplied(setup.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        "context-replay-activation")));
        requireApplied(setup.leases().release(setup.plan().id(), TOKEN));
        requireApplied(setup.leases().acquire(
                setup.plan().id(),
                "takeover-owner",
                "takeover-token",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));

        int observations = setup.clock().observationCount();
        setup.clock().failOnObservation();
        PersistenceResult<PersistedPlanExecutionContextReserved>
                reserveReplay = setup.contexts().reserve(reserveRequest);
        PersistenceResult<PersistedPlanExecutionContextConfirmed>
                confirmReplay = setup.contexts().confirm(confirmRequest);
        assertEquals(PersistenceOutcome.REPLAYED, reserveReplay.outcome());
        assertEquals(reserved, reserveReplay.value().orElseThrow());
        assertEquals(PersistenceOutcome.REPLAYED, confirmReplay.outcome());
        assertEquals(confirmed, confirmReplay.value().orElseThrow());
        assertEquals(observations, setup.clock().observationCount());
    }

    @Test
    void changedPermanentRequestsConflictBeforeClock() {
        Setup setup = started(true, "permanent-conflict");
        var reserveRequest =
                reservationRequest(setup, "permanent-conflict");
        requireApplied(setup.contexts().reserve(reserveRequest));
        var confirmRequest =
                PersistenceFixtures.contextConfirmationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        reserveRequest.materializationSpec());
        requireApplied(setup.contexts().confirm(confirmRequest));
        int observations = setup.clock().observationCount();
        setup.clock().failOnObservation();

        assertFailure(
                setup.contexts().reserve(
                        new PlanExecutionContextReservationRequest(
                                reserveRequest.planId(),
                                "changed-token",
                                reserveRequest.fencingToken(),
                                reserveRequest.expectedRevisionId(),
                                reserveRequest.expectedRevisionNumber(),
                                reserveRequest.expectedCheckpointVersion(),
                                reserveRequest.expectedEventHeadSequence(),
                                reserveRequest.materializationSpec())),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.planId");
        assertFailure(
                setup.contexts().confirm(
                        new PlanExecutionContextConfirmationRequest(
                                confirmRequest.planId(),
                                confirmRequest.leaseToken(),
                                confirmRequest.fencingToken(),
                                confirmRequest.materializationSpec(),
                                new ContentHash(
                                        "sha256", "b".repeat(64)))),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.planId");
        assertEquals(observations, setup.clock().observationCount());
    }

    @Test
    void takeoverMayConfirmWithoutRewritingReservationAuthority() {
        Setup setup = started(true, "takeover");
        var reserveRequest = reservationRequest(setup, "takeover");
        PersistedPlanExecutionContextReserved reserved =
                requireApplied(setup.contexts().reserve(reserveRequest));
        requireApplied(setup.leases().release(setup.plan().id(), TOKEN));
        LeaseRecord takeover = requireApplied(setup.leases().acquire(
                setup.plan().id(),
                "takeover-owner",
                "takeover-token",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));
        var confirmRequest = new PlanExecutionContextConfirmationRequest(
                setup.plan().id(),
                "takeover-token",
                takeover.fencingToken(),
                reserveRequest.materializationSpec(),
                PersistenceFixtures.SOURCE_FINGERPRINT);

        PersistedPlanExecutionContextConfirmed confirmed =
                requireApplied(setup.contexts().confirm(confirmRequest));
        assertEquals(reserved, confirmed.reservation());
        assertEquals("takeover-owner", confirmed.leaseOwnerId());
        assertEquals(2, confirmed.fencingToken());
        assertEquals(OWNER, reserved.leaseOwnerId());
        assertEquals(1, reserved.fencingToken());
    }

    @Test
    void sourceLessPlansHaveNoContextButMayActivate() {
        Setup setup = started(false, "source-less");
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec("source-less");
        assertFailure(
                setup.contexts().reserve(
                        PersistenceFixtures.contextReservationRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                spec)),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                "planExecutionContext.source");
        assertFailure(
                setup.contexts().confirm(
                        PersistenceFixtures.contextConfirmationRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                spec)),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                "planExecutionContext.source");
        assertFailure(
                setup.contexts().inspect(setup.plan().id()),
                PersistenceErrorCode.NOT_FOUND,
                "planExecutionContext");
        assertEquals(
                PersistenceOutcome.APPLIED,
                setup.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                "source-less-activation"))
                        .outcome());

        Setup corrupt = started(false, "source-less-corrupt");
        WorkspaceMaterializationSpec corruptSpec =
                PersistenceFixtures.workspaceSpec(
                        "source-less-corrupt");
        corrupt.state().workspaceOwners.put(
                corruptSpec.workspaceId(),
                new InMemoryState.WorkspaceOwner(
                        corrupt.plan().id(), corruptSpec));
        assertFailure(
                corrupt.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                corrupt.plan(),
                                TOKEN,
                                corrupt.lease().fencingToken(),
                                "source-less-corrupt")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
    }

    @Test
    void projectBackedActivationRequiresConfirmedContext() {
        Setup absent = started(true, "gate-absent");
        assertFailure(
                absent.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                absent.plan(),
                                TOKEN,
                                absent.lease().fencingToken(),
                                "gate-absent")),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");

        Setup reserved = started(true, "gate-reserved");
        requireApplied(reserved.contexts().reserve(
                reservationRequest(reserved, "gate-reserved")));
        assertFailure(
                reserved.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                reserved.plan(),
                                TOKEN,
                                reserved.lease().fencingToken(),
                                "gate-reserved")),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");

        Setup confirmed = started(true, "gate-confirmed");
        WorkspaceMaterializationSpec spec =
                reservationRequest(confirmed, "gate-confirmed")
                        .materializationSpec();
        PersistenceFixtures.confirmExecutionContext(
                confirmed.contexts(),
                confirmed.plan(),
                TOKEN,
                confirmed.lease().fencingToken(),
                spec);
        assertEquals(
                PersistenceOutcome.APPLIED,
                confirmed.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                confirmed.plan(),
                                TOKEN,
                                confirmed.lease().fencingToken(),
                                "gate-confirmed"))
                        .outcome());
    }

    @Test
    void exactActivationReplayPrecedesLaterContextCorruption() {
        Setup setup = started(true, "activation-replay-priority");
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec(
                        "activation-replay-priority");
        PersistenceFixtures.confirmExecutionContext(
                setup.contexts(),
                setup.plan(),
                TOKEN,
                setup.lease().fencingToken(),
                spec);
        var activation =
                PersistenceFixtures.stepActivationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        "activation-replay-priority");
        PersistedStepActivation original =
                requireApplied(setup.activations().activate(activation));
        setup.state().workspaceOwners.remove(spec.workspaceId());
        int observations = setup.clock().observationCount();
        setup.clock().failOnObservation();

        PersistenceResult<PersistedStepActivation> replay =
                setup.activations().activate(activation);
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(original, replay.value().orElseThrow());
        assertEquals(observations, setup.clock().observationCount());
        assertFailure(
                setup.contexts().inspect(setup.plan().id()),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
    }

    @Test
    void staleH0SourceMismatchAndAdvancedFirstReserveFailClosed() {
        Setup stale = started(true, "stale");
        var original = reservationRequest(stale, "stale");
        for (PlanExecutionContextReservationRequest request : List.of(
                new PlanExecutionContextReservationRequest(
                        original.planId(), original.leaseToken(),
                        original.fencingToken(),
                        new io.paperagent.v2.contracts.PlanRevisionId("other"),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.materializationSpec()),
                new PlanExecutionContextReservationRequest(
                        original.planId(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber() + 1,
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.materializationSpec()),
                new PlanExecutionContextReservationRequest(
                        original.planId(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion() + 1,
                        original.expectedEventHeadSequence(),
                        original.materializationSpec()),
                new PlanExecutionContextReservationRequest(
                        original.planId(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence() + 1,
                        original.materializationSpec()))) {
            assertFailure(
                    stale.contexts().reserve(request),
                    PersistenceErrorCode.STALE_VERSION,
                    request.expectedRevisionId()
                                    .equals(original.expectedRevisionId())
                            ? request.expectedRevisionNumber()
                                            != original.expectedRevisionNumber()
                                    ? "request.expectedRevisionNumber"
                                    : request.expectedCheckpointVersion()
                                                    != original.expectedCheckpointVersion()
                                            ? "request.expectedCheckpointVersion"
                                            : "request.expectedEventHeadSequence"
                            : "request.expectedRevisionId");
        }

        Setup mismatch = started(true, "mismatch");
        var mismatchRequest = reservationRequest(mismatch, "mismatch");
        WorkspaceMaterializationSpec wrongSource =
                PersistenceFixtures.workspaceSpec(
                        "wrong-source",
                        new ProjectVersionRef("other-project", "other-version"));
        assertFailure(
                mismatch.contexts().reserve(
                        new PlanExecutionContextReservationRequest(
                                mismatchRequest.planId(),
                                mismatchRequest.leaseToken(),
                                mismatchRequest.fencingToken(),
                                mismatchRequest.expectedRevisionId(),
                                mismatchRequest.expectedRevisionNumber(),
                                mismatchRequest.expectedCheckpointVersion(),
                                mismatchRequest.expectedEventHeadSequence(),
                                wrongSource)),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                "request.materializationSpec.sourceProjectVersion");

        Setup advanced = started(true, "advanced-first-reserve");
        WorkspaceMaterializationSpec advancedSpec =
                PersistenceFixtures.workspaceSpec(
                        "advanced-first-reserve");
        PersistenceFixtures.confirmExecutionContext(
                advanced.contexts(),
                advanced.plan(),
                TOKEN,
                advanced.lease().fencingToken(),
                advancedSpec);
        requireApplied(advanced.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        advanced.plan(),
                        TOKEN,
                        advanced.lease().fencingToken(),
                        "advanced-first-reserve")));
        advanced.state().planExecutionContextConfirmations.remove(
                advanced.plan().id());
        advanced.state().planExecutionContextReservations.remove(
                advanced.plan().id());
        advanced.state().workspaceOwners.remove(
                advancedSpec.workspaceId());
        ContextBusinessState advancedBefore =
                ContextBusinessState.capture(advanced.state());
        int observations = advanced.clock().observationCount();
        assertFailure(
                advanced.contexts().reserve(
                        PersistenceFixtures.contextReservationRequest(
                                advanced.plan(),
                                TOKEN,
                                advanced.lease().fencingToken(),
                                PersistenceFixtures.workspaceSpec(
                                        "advanced-new"))),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                "planExecutionContext.source");
        assertEquals(observations + 1,
                advanced.clock().observationCount());
        assertEquals(advancedBefore,
                ContextBusinessState.capture(advanced.state()));
        assertFailure(
                advanced.contexts().confirm(
                        PersistenceFixtures
                                .contextConfirmationRequest(
                                        advanced.plan(),
                                        TOKEN,
                                        advanced.lease().fencingToken(),
                                        PersistenceFixtures.workspaceSpec(
                                                "advanced-new"))),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations + 2,
                advanced.clock().observationCount());
        assertEquals(advancedBefore,
                ContextBusinessState.capture(advanced.state()));
        assertFailure(
                advanced.contexts().inspect(advanced.plan().id()),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations + 2,
                advanced.clock().observationCount());
        assertEquals(advancedBefore,
                ContextBusinessState.capture(advanced.state()));
    }

    @Test
    void everyLeaseFailureIsStableAndWritesNoContextState() {
        for (LeaseFailure failure : LeaseFailure.values()) {
            assertReserveLeaseFailure(failure);
            assertConfirmLeaseFailure(failure);
        }
    }

    @Test
    void reservationAndConfirmationCorruptionFailClosedBeforeClock() {
        assertReservedCorruption(
                "owner-key-spec-mismatch",
                setup -> {
                    var marker = reservationMarker(setup);
                    WorkspaceMaterializationSpec spec =
                            marker.request().materializationSpec();
                    setup.state().workspaceOwners.remove(
                            spec.workspaceId());
                    setup.state().workspaceOwners.put(
                            PersistenceFixtures.workspaceSpec(
                                    "wrong-owner-key")
                                    .workspaceId(),
                            new InMemoryState.WorkspaceOwner(
                                    setup.plan().id(), spec));
                });
        assertReservedCorruption(
                "owner-wrong-plan",
                setup -> {
                    var marker = reservationMarker(setup);
                    WorkspaceMaterializationSpec spec =
                            marker.request().materializationSpec();
                    setup.state().workspaceOwners.put(
                            spec.workspaceId(),
                            new InMemoryState.WorkspaceOwner(
                                    new PlanId("wrong-owner-plan"),
                                    spec));
                });
        assertReservedCorruption(
                "same-plan-two-owners",
                setup -> {
                    WorkspaceMaterializationSpec second =
                            PersistenceFixtures.workspaceSpec(
                                    "second-owner");
                    setup.state().workspaceOwners.put(
                            second.workspaceId(),
                            new InMemoryState.WorkspaceOwner(
                                    setup.plan().id(), second));
                });
        assertReservedCorruption(
                "same-workspace-contradictory-owner",
                setup -> {
                    var marker = reservationMarker(setup);
                    WorkspaceMaterializationSpec spec =
                            marker.request().materializationSpec();
                    setup.state().workspaceOwners.put(
                            spec.workspaceId(),
                            new InMemoryState.WorkspaceOwner(
                                    new PlanId("contradictory-owner"),
                                    spec));
                });
        assertReservedCorruption(
                "reservation-request",
                setup -> {
                    var marker = reservationMarker(setup);
                    var request = marker.request();
                    setup.state().planExecutionContextReservations.put(
                            setup.plan().id(),
                            new InMemoryState
                                    .PlanExecutionContextReservationMarker(
                                            new PlanExecutionContextReservationRequest(
                                                    request.planId(),
                                                    request.leaseToken(),
                                                    request.fencingToken(),
                                                    request.expectedRevisionId(),
                                                    request.expectedRevisionNumber()
                                                            + 1,
                                                    request.expectedCheckpointVersion(),
                                                    request.expectedEventHeadSequence(),
                                                    request.materializationSpec()),
                                            marker.result(),
                                            marker.frozenH0()));
                });
        assertReservedCorruption(
                "reservation-result",
                setup -> {
                    var marker = reservationMarker(setup);
                    setup.state().planExecutionContextReservations.put(
                            setup.plan().id(),
                            new InMemoryState
                                    .PlanExecutionContextReservationMarker(
                                            marker.request(),
                                            new PersistedPlanExecutionContextReserved(
                                                    setup.plan().id(),
                                                    marker.result()
                                                            .materializationSpec(),
                                                    marker.result()
                                                            .leaseOwnerId(),
                                                    marker.result()
                                                            .fencingToken()
                                                            + 1),
                                            marker.frozenH0()));
                });
        assertReservedCorruption(
                "frozen-h0",
                setup -> {
                    var marker = reservationMarker(setup);
                    var h0 = marker.frozenH0();
                    setup.state().planExecutionContextReservations.put(
                            setup.plan().id(),
                            new InMemoryState
                                    .PlanExecutionContextReservationMarker(
                                            marker.request(),
                                            marker.result(),
                                            new InMemoryState
                                                    .ExecutionMutationHead(
                                                            h0.revisionId(),
                                                            h0.revisionNumber(),
                                                            h0.checkpointVersion(),
                                                            h0.eventHeadSequence(),
                                                            new io.paperagent.v2
                                                                    .contracts
                                                                    .EventId(
                                                                            "wrong-h0-event"))));
                });
        assertConfirmedCorruption(
                "confirmation-request",
                setup -> {
                    var marker = confirmationMarker(setup);
                    setup.state().planExecutionContextConfirmations.put(
                            setup.plan().id(),
                            new InMemoryState
                                    .PlanExecutionContextConfirmationMarker(
                                            new PlanExecutionContextConfirmationRequest(
                                                    setup.plan().id(),
                                                    marker.request()
                                                            .leaseToken(),
                                                    marker.request()
                                                            .fencingToken(),
                                                    marker.request()
                                                            .materializationSpec(),
                                                    new ContentHash(
                                                            "sha256",
                                                            "c".repeat(64))),
                                            marker.result()));
                });
        assertConfirmedCorruption(
                "confirmation-result-fingerprint",
                setup -> {
                    var marker = confirmationMarker(setup);
                    setup.state().planExecutionContextConfirmations.put(
                            setup.plan().id(),
                            new InMemoryState
                                    .PlanExecutionContextConfirmationMarker(
                                            marker.request(),
                                            new PersistedPlanExecutionContextConfirmed(
                                                    marker.result()
                                                            .reservation(),
                                                    marker.result()
                                                            .leaseOwnerId(),
                                                    marker.result()
                                                            .fencingToken(),
                                                    new ContentHash(
                                                            "sha256",
                                                            "d".repeat(64)))));
                });
        assertConfirmationOnlyCorruption();

        Setup reservedSuccessor =
                started(true, "reserved-successor");
        PlanExecutionContextReservationRequest reservation =
                reservationRequest(
                        reservedSuccessor, "reserved-successor");
        requireApplied(reservedSuccessor.contexts().reserve(
                reservation));
        PlanExecutionContextConfirmationRequest confirmation =
                PersistenceFixtures.contextConfirmationRequest(
                        reservedSuccessor.plan(),
                        TOKEN,
                        reservedSuccessor.lease().fencingToken(),
                        reservation.materializationSpec());
        requireApplied(reservedSuccessor.contexts().confirm(
                confirmation));
        requireApplied(reservedSuccessor.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        reservedSuccessor.plan(),
                        TOKEN,
                        reservedSuccessor.lease().fencingToken(),
                        "activation-reserved-successor")));
        reservedSuccessor.state()
                .planExecutionContextConfirmations
                .remove(reservedSuccessor.plan().id());
        assertCorruptContextCut(
                reservedSuccessor,
                reservation,
                confirmation);
    }

    @Test
    void markerOrOwnerCorruptionIsPartialBeforeReplay() {
        Setup setup = started(true, "corrupt");
        var request = reservationRequest(setup, "corrupt");
        requireApplied(setup.contexts().reserve(request));
        setup.state().workspaceOwners.remove(
                request.materializationSpec().workspaceId());
        int observations = setup.clock().observationCount();
        assertFailure(
                setup.contexts().reserve(request),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertFailure(
                setup.contexts().inspect(setup.plan().id()),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations, setup.clock().observationCount());
    }

    @Test
    void globalReservationOwnerBreakBlocksSameAndDifferentWorkspaceReserve() {
        Setup first = started(true, "global-owner-first");
        WorkspaceMaterializationSpec firstSpec =
                PersistenceFixtures.workspaceSpec("global-owner-shared");
        requireApplied(first.contexts().reserve(
                PersistenceFixtures.contextReservationRequest(
                        first.plan(),
                        TOKEN,
                        first.lease().fencingToken(),
                        firstSpec)));

        TaskFrameIdAndPlan second = addStartedSecondPlan(
                first.state(), "global-owner-second");
        first.state().workspaceOwners.remove(firstSpec.workspaceId());
        int observations = first.clock().observationCount();
        int reservationCount =
                first.state().planExecutionContextReservations.size();
        int ownerCount = first.state().workspaceOwners.size();

        for (WorkspaceMaterializationSpec attempted : List.of(
                firstSpec,
                PersistenceFixtures.workspaceSpec(
                        "global-owner-different"))) {
            assertFailure(
                    first.contexts().reserve(
                            PersistenceFixtures.contextReservationRequest(
                                    second.plan(),
                                    second.leaseToken(),
                                    second.fencingToken(),
                                    attempted)),
                    PersistenceErrorCode
                            .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                    "planExecutionContext");
            assertEquals(observations,
                    first.clock().observationCount());
            assertEquals(reservationCount,
                    first.state()
                            .planExecutionContextReservations.size());
            assertEquals(ownerCount,
                    first.state().workspaceOwners.size());
            assertFalse(first.state()
                    .planExecutionContextReservations
                    .containsKey(second.plan().id()));
        }
    }

    @Test
    void destroyedStartThatLeavesActiveCheckpointIsAlwaysPartial() {
        Setup reserve = started(true, "destroyed-reserve");
        var reserveRequest =
                reservationRequest(reserve, "destroyed-reserve");
        destroyStartAuthority(reserve.state());
        ContextBusinessState reserveBefore =
                ContextBusinessState.capture(reserve.state());
        int reserveClock = reserve.clock().observationCount();
        assertFailure(
                reserve.contexts().reserve(reserveRequest),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(reserveClock + 1,
                reserve.clock().observationCount());
        assertEquals(reserveBefore,
                ContextBusinessState.capture(reserve.state()));

        Setup confirm = started(true, "destroyed-confirm");
        var confirmRequest =
                PersistenceFixtures.contextConfirmationRequest(
                        confirm.plan(),
                        TOKEN,
                        confirm.lease().fencingToken(),
                        PersistenceFixtures.workspaceSpec(
                                "destroyed-confirm"));
        destroyStartAuthority(confirm.state());
        ContextBusinessState confirmBefore =
                ContextBusinessState.capture(confirm.state());
        int confirmClock = confirm.clock().observationCount();
        assertFailure(
                confirm.contexts().confirm(confirmRequest),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(confirmClock + 1,
                confirm.clock().observationCount());
        assertEquals(confirmBefore,
                ContextBusinessState.capture(confirm.state()));

        Setup inspect = started(true, "destroyed-inspect");
        destroyStartAuthority(inspect.state());
        ContextBusinessState inspectBefore =
                ContextBusinessState.capture(inspect.state());
        int inspectClock = inspect.clock().observationCount();
        assertFailure(
                inspect.contexts().inspect(inspect.plan().id()),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(inspectClock,
                inspect.clock().observationCount());
        assertEquals(inspectBefore,
                ContextBusinessState.capture(inspect.state()));
    }

    @Test
    void firstReserveObservesTrustedTimeBeforeSourceClassification() {
        MutationOnObservationClock clock =
                new MutationOnObservationClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        LeaseRecord lease = requireApplied(
                new InMemoryLeaseRepository(state).acquire(
                        plan.id(),
                        OWNER,
                        TOKEN,
                        PersistenceFixtures.T0.plus(
                                Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan,
                        TOKEN,
                        lease.fencingToken(),
                        "clock-before-source")));
        PlanExecutionContextRepository contexts =
                new InMemoryPlanExecutionContextRepository(state);
        clock.arm(() -> state.executionStarts.remove(plan.id()));
        int observations = clock.observationCount();

        assertFailure(
                contexts.reserve(
                        PersistenceFixtures.contextReservationRequest(
                                plan,
                                TOKEN,
                                lease.fencingToken(),
                                PersistenceFixtures.workspaceSpec(
                                        "clock-before-source"))),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");

        assertEquals(observations + 1,
                clock.observationCount());
        assertTrue(state.planExecutionContextReservations.isEmpty());
        assertTrue(state.planExecutionContextConfirmations.isEmpty());
        assertTrue(state.workspaceOwners.isEmpty());
    }

    @Test
    void reentrantReserveCannotOverwritePermanentContextState() {
        for (boolean exact : List.of(false, true)) {
            ReentrantSetup setup =
                    reentrantStarted("reentrant-reserve-" + exact);
            WorkspaceMaterializationSpec outerSpec =
                    PersistenceFixtures.workspaceSpec(
                            "reentrant-reserve-outer-" + exact);
            WorkspaceMaterializationSpec innerSpec = exact
                    ? outerSpec
                    : PersistenceFixtures.workspaceSpec(
                            "reentrant-reserve-inner");
            PlanExecutionContextReservationRequest outerRequest =
                    PersistenceFixtures.contextReservationRequest(
                            setup.plan(),
                            TOKEN,
                            setup.lease().fencingToken(),
                            outerSpec);
            PlanExecutionContextReservationRequest innerRequest =
                    PersistenceFixtures.contextReservationRequest(
                            setup.plan(),
                            TOKEN,
                            setup.lease().fencingToken(),
                            innerSpec);
            AtomicReference<PersistenceResult<
                    PersistedPlanExecutionContextReserved>> innerResult =
                    new AtomicReference<>();
            setup.clock().arm(() -> innerResult.set(
                    setup.contexts().reserve(innerRequest)));

            PersistenceResult<PersistedPlanExecutionContextReserved>
                    outerResult =
                    setup.contexts().reserve(outerRequest);

            assertEquals(
                    PersistenceOutcome.APPLIED,
                    innerResult.get().outcome());
            if (exact) {
                assertEquals(
                        PersistenceOutcome.REPLAYED,
                        outerResult.outcome());
                assertEquals(
                        innerResult.get().value().orElseThrow(),
                        outerResult.value().orElseThrow());
            } else {
                assertFailure(
                        outerResult,
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId");
            }
            assertEquals(1, setup.state()
                    .planExecutionContextReservations.size());
            assertEquals(innerRequest,
                    setup.state().planExecutionContextReservations
                            .get(setup.plan().id()).request());
            assertEquals(1,
                    setup.state().workspaceOwners.size());
            assertEquals(
                    Set.of(innerSpec.workspaceId()),
                    setup.state().workspaceOwners.keySet());
            assertEquals(0, setup.state()
                    .planExecutionContextConfirmations.size());
            assertEquals(
                    innerResult.get().value().orElseThrow(),
                    setup.contexts().inspect(setup.plan().id())
                            .value().orElseThrow());
        }
    }

    @Test
    void reentrantConfirmCannotOverwritePermanentContextState() {
        for (boolean exact : List.of(false, true)) {
            ReentrantSetup setup =
                    reentrantStarted("reentrant-confirm-" + exact);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "reentrant-confirm-" + exact);
            requireApplied(setup.contexts().reserve(
                    PersistenceFixtures.contextReservationRequest(
                            setup.plan(),
                            TOKEN,
                            setup.lease().fencingToken(),
                            spec)));
            PlanExecutionContextConfirmationRequest outerRequest =
                    PersistenceFixtures.contextConfirmationRequest(
                            setup.plan(),
                            TOKEN,
                            setup.lease().fencingToken(),
                            spec);
            PlanExecutionContextConfirmationRequest innerRequest =
                    exact
                            ? outerRequest
                            : new PlanExecutionContextConfirmationRequest(
                                    setup.plan().id(),
                                    TOKEN,
                                    setup.lease().fencingToken(),
                                    spec,
                                    new ContentHash(
                                            "sha256",
                                            "e".repeat(64)));
            AtomicReference<PersistenceResult<
                    PersistedPlanExecutionContextConfirmed>> innerResult =
                    new AtomicReference<>();
            setup.clock().arm(() -> innerResult.set(
                    setup.contexts().confirm(innerRequest)));

            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    outerResult =
                    setup.contexts().confirm(outerRequest);

            assertEquals(
                    PersistenceOutcome.APPLIED,
                    innerResult.get().outcome());
            if (exact) {
                assertEquals(
                        PersistenceOutcome.REPLAYED,
                        outerResult.outcome());
                assertEquals(
                        innerResult.get().value().orElseThrow(),
                        outerResult.value().orElseThrow());
            } else {
                assertFailure(
                        outerResult,
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId");
            }
            assertEquals(1, setup.state()
                    .planExecutionContextReservations.size());
            assertEquals(1,
                    setup.state().workspaceOwners.size());
            assertEquals(1, setup.state()
                    .planExecutionContextConfirmations.size());
            assertEquals(innerRequest,
                    setup.state().planExecutionContextConfirmations
                            .get(setup.plan().id()).request());
            assertEquals(
                    innerResult.get().value().orElseThrow(),
                    setup.contexts().inspect(setup.plan().id())
                            .value().orElseThrow());
        }
    }

    @Test
    void completedFactsAndNullStreamOccupancyArePartialPreStart() {
        Setup completedFacts =
                bootstrapped(true, "pre-start-completed-facts");
        PlanRevision previous = completedFacts.plan().latestRevision();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "pre-start-outcome",
                PersistenceFixtures.T0.plusSeconds(5),
                List.of());
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("pre-start-revision-2"),
                completedFacts.plan().taskFrameId(),
                2,
                Optional.of(previous.id()),
                "invalid pre-start completed fact",
                PersistenceFixtures.T0.plusSeconds(5),
                previous.steps(),
                Map.of(PersistenceFixtures.STEP_1, fact));
        completedFacts.state().plans.put(
                completedFacts.plan().id(),
                new Plan(
                        completedFacts.plan().id(),
                        completedFacts.plan().taskFrameId(),
                        List.of(previous, revision)));
        assertPreStartPartial(completedFacts, "completed-facts");

        Setup nullStream =
                bootstrapped(true, "pre-start-null-stream");
        nullStream.state().eventStreams.put(
                nullStream.plan().id(), null);
        assertPreStartPartial(nullStream, "null-stream");
    }

    @Test
    void brokenCurrentCheckpointAndEventCannotLookLikeNoContext() {
        Setup checkpoint = started(true, "broken-checkpoint");
        checkpoint.state().checkpoints.remove(checkpoint.plan().id());
        assertFailure(
                checkpoint.contexts().inspect(checkpoint.plan().id()),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");

        Setup event = started(true, "broken-event");
        event.state().eventsById.clear();
        assertFailure(
                event.contexts().inspect(event.plan().id()),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
    }

    @Test
    void unknownAndMissingReservationUseStableNotFoundPaths() {
        Setup setup = started(true, "missing");
        assertFailure(
                setup.contexts().inspect(new PlanId("unknown-plan")),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertFailure(
                setup.contexts().confirm(
                        PersistenceFixtures.contextConfirmationRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                PersistenceFixtures.workspaceSpec("missing"))),
                PersistenceErrorCode.NOT_FOUND,
                "planExecutionContext");
    }

    private static void assertReserveLeaseFailure(
            LeaseFailure failure) {
        Setup setup = started(true, "reserve-lease-" + failure);
        PlanExecutionContextReservationRequest request =
                reservationRequest(setup, "reserve-lease-" + failure);
        if (failure == LeaseFailure.NOT_HELD) {
            requireApplied(setup.leases().release(
                    setup.plan().id(), TOKEN));
        } else if (failure == LeaseFailure.TOKEN) {
            request = copyReservationAuthority(
                    request, "wrong-token", request.fencingToken());
        } else if (failure == LeaseFailure.FENCE) {
            request = copyReservationAuthority(
                    request,
                    request.leaseToken(),
                    request.fencingToken() + 1);
        } else if (failure == LeaseFailure.EXPIRED) {
            setup.clock().set(setup.lease().expiresAt());
        } else {
            requireApplied(setup.leases().release(
                    setup.plan().id(), TOKEN));
            LeaseRecord takeover = requireApplied(
                    setup.leases().acquire(
                            setup.plan().id(),
                            "takeover-owner",
                            "takeover-token",
                            PersistenceFixtures.T0.plus(
                                    Duration.ofMinutes(2))));
            request = copyReservationAuthority(
                    request,
                    takeover.leaseToken(),
                    setup.lease().fencingToken());
        }
        ContextBusinessState before =
                ContextBusinessState.capture(setup.state());
        int observations = setup.clock().observationCount();

        assertFailure(
                setup.contexts().reserve(request),
                failure.code(),
                failure.path());
        assertEquals(observations + 1,
                setup.clock().observationCount());
        assertEquals(before,
                ContextBusinessState.capture(setup.state()));
    }

    private static void assertConfirmLeaseFailure(
            LeaseFailure failure) {
        Setup setup = started(true, "confirm-lease-" + failure);
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec(
                        "confirm-lease-" + failure);
        requireApplied(setup.contexts().reserve(
                PersistenceFixtures.contextReservationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        spec)));
        PlanExecutionContextConfirmationRequest request =
                PersistenceFixtures.contextConfirmationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        spec);
        if (failure == LeaseFailure.NOT_HELD) {
            requireApplied(setup.leases().release(
                    setup.plan().id(), TOKEN));
        } else if (failure == LeaseFailure.TOKEN) {
            request = copyConfirmationAuthority(
                    request, "wrong-token", request.fencingToken());
        } else if (failure == LeaseFailure.FENCE) {
            request = copyConfirmationAuthority(
                    request,
                    request.leaseToken(),
                    request.fencingToken() + 1);
        } else if (failure == LeaseFailure.EXPIRED) {
            setup.clock().set(setup.lease().expiresAt());
        } else {
            requireApplied(setup.leases().release(
                    setup.plan().id(), TOKEN));
            LeaseRecord takeover = requireApplied(
                    setup.leases().acquire(
                            setup.plan().id(),
                            "takeover-owner",
                            "takeover-token",
                            PersistenceFixtures.T0.plus(
                                    Duration.ofMinutes(2))));
            request = copyConfirmationAuthority(
                    request,
                    takeover.leaseToken(),
                    setup.lease().fencingToken());
        }
        ContextBusinessState before =
                ContextBusinessState.capture(setup.state());
        int observations = setup.clock().observationCount();

        assertFailure(
                setup.contexts().confirm(request),
                failure.code(),
                failure.path());
        assertEquals(observations + 1,
                setup.clock().observationCount());
        assertEquals(before,
                ContextBusinessState.capture(setup.state()));
    }

    private static PlanExecutionContextReservationRequest
            copyReservationAuthority(
                    PlanExecutionContextReservationRequest source,
                    String leaseToken,
                    long fencingToken) {
        return new PlanExecutionContextReservationRequest(
                source.planId(),
                leaseToken,
                fencingToken,
                source.expectedRevisionId(),
                source.expectedRevisionNumber(),
                source.expectedCheckpointVersion(),
                source.expectedEventHeadSequence(),
                source.materializationSpec());
    }

    private static PlanExecutionContextConfirmationRequest
            copyConfirmationAuthority(
                    PlanExecutionContextConfirmationRequest source,
                    String leaseToken,
                    long fencingToken) {
        return new PlanExecutionContextConfirmationRequest(
                source.planId(),
                leaseToken,
                fencingToken,
                source.materializationSpec(),
                source.sourceManifestFingerprint());
    }

    private static void assertReservedCorruption(
            String suffix,
            Consumer<Setup> corruption) {
        Setup setup = started(true, "corruption-" + suffix);
        PlanExecutionContextReservationRequest reservation =
                reservationRequest(setup, "corruption-" + suffix);
        requireApplied(setup.contexts().reserve(reservation));
        PlanExecutionContextConfirmationRequest confirmation =
                PersistenceFixtures.contextConfirmationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        reservation.materializationSpec());
        corruption.accept(setup);
        assertCorruptContextCut(
                setup, reservation, confirmation);
    }

    private static void assertConfirmedCorruption(
            String suffix,
            Consumer<Setup> corruption) {
        Setup setup = started(true, "corruption-" + suffix);
        PlanExecutionContextReservationRequest reservation =
                reservationRequest(setup, "corruption-" + suffix);
        requireApplied(setup.contexts().reserve(reservation));
        PlanExecutionContextConfirmationRequest confirmation =
                PersistenceFixtures.contextConfirmationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        reservation.materializationSpec());
        requireApplied(setup.contexts().confirm(confirmation));
        corruption.accept(setup);
        assertCorruptContextCut(
                setup, reservation, confirmation);
    }

    private static void assertConfirmationOnlyCorruption() {
        Setup setup = started(true, "confirmation-only");
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec("confirmation-only");
        PersistedPlanExecutionContextReserved reservation =
                new PersistedPlanExecutionContextReserved(
                        setup.plan().id(),
                        spec,
                        OWNER,
                        setup.lease().fencingToken());
        PlanExecutionContextConfirmationRequest request =
                PersistenceFixtures.contextConfirmationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        spec);
        setup.state().planExecutionContextConfirmations.put(
                setup.plan().id(),
                new InMemoryState
                        .PlanExecutionContextConfirmationMarker(
                                request,
                                new PersistedPlanExecutionContextConfirmed(
                                        reservation,
                                        OWNER,
                                        setup.lease().fencingToken(),
                                        request.sourceManifestFingerprint())));
        assertCorruptContextCut(
                setup,
                PersistenceFixtures.contextReservationRequest(
                        setup.plan(),
                        TOKEN,
                        setup.lease().fencingToken(),
                        spec),
                request);
    }

    private static void assertCorruptContextCut(
            Setup setup,
            PlanExecutionContextReservationRequest reservation,
            PlanExecutionContextConfirmationRequest confirmation) {
        ContextBusinessState before =
                ContextBusinessState.capture(setup.state());
        int observations = setup.clock().observationCount();
        assertFailure(
                setup.contexts().reserve(reservation),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertFailure(
                setup.contexts().confirm(confirmation),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertFailure(
                setup.contexts().inspect(setup.plan().id()),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations,
                setup.clock().observationCount());
        assertEquals(before,
                ContextBusinessState.capture(setup.state()));
    }

    private static InMemoryState.PlanExecutionContextReservationMarker
            reservationMarker(Setup setup) {
        return setup.state().planExecutionContextReservations
                .get(setup.plan().id());
    }

    private static InMemoryState.PlanExecutionContextConfirmationMarker
            confirmationMarker(Setup setup) {
        return setup.state().planExecutionContextConfirmations
                .get(setup.plan().id());
    }

    private static PlanExecutionContextReservationRequest reservationRequest(
            Setup setup,
            String suffix) {
        ProjectVersionRef source = setup.taskFrame()
                .sourceProjectVersion()
                .orElseGet(() ->
                        PersistenceFixtures.taskFrame()
                                .sourceProjectVersion()
                                .orElseThrow());
        return PersistenceFixtures.contextReservationRequest(
                setup.plan(),
                TOKEN,
                setup.lease().fencingToken(),
                PersistenceFixtures.workspaceSpec(suffix, source));
    }

    private static Setup started(
            boolean sourceBacked,
            String suffix) {
        Setup setup = bootstrapped(sourceBacked, suffix);
        requireApplied(new InMemoryExecutionStartRepository(
                setup.state()).start(
                        PersistenceFixtures.executionStartRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                "context-start-" + suffix)));
        return setup;
    }

    private static ReentrantSetup reentrantStarted(
            String suffix) {
        MutationOnObservationClock clock =
                new MutationOnObservationClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        LeaseRecord lease = requireApplied(
                new InMemoryLeaseRepository(state).acquire(
                        plan.id(),
                        OWNER,
                        TOKEN,
                        PersistenceFixtures.T0.plus(
                                Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan,
                        TOKEN,
                        lease.fencingToken(),
                        "start-" + suffix)));
        return new ReentrantSetup(
                state,
                clock,
                plan,
                lease,
                new InMemoryPlanExecutionContextRepository(state));
    }

    private static Setup bootstrapped(
            boolean sourceBacked,
            String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        TaskFrame taskFrame = sourceBacked
                ? PersistenceFixtures.taskFrame()
                : PersistenceFixtures.sourceLessTaskFrame(
                        PersistenceFixtures.TASK_ID,
                        "Source-less " + suffix);
        Plan plan = PersistenceFixtures.plan();
        var bootstraps = new InMemoryPlanBootstrapRepository(state);
        var leases = new InMemoryLeaseRepository(state);
        var contexts = new InMemoryPlanExecutionContextRepository(state);
        var activations = new InMemoryStepActivationRepository(state);
        requireApplied(bootstraps.bootstrap(
                taskFrame,
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(),
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        return new Setup(
                state,
                clock,
                taskFrame,
                plan,
                lease,
                leases,
                contexts,
                activations);
    }

    private static void assertPreStartPartial(
            Setup setup,
            String suffix) {
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec("partial-" + suffix);
        ContextBusinessState before =
                ContextBusinessState.capture(setup.state());
        int observations = setup.clock().observationCount();

        assertFailure(
                setup.contexts().reserve(
                        PersistenceFixtures.contextReservationRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                spec)),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations + 1,
                setup.clock().observationCount());
        assertEquals(before,
                ContextBusinessState.capture(setup.state()));

        assertFailure(
                setup.contexts().confirm(
                        PersistenceFixtures.contextConfirmationRequest(
                                setup.plan(),
                                TOKEN,
                                setup.lease().fencingToken(),
                                spec)),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations + 2,
                setup.clock().observationCount());
        assertEquals(before,
                ContextBusinessState.capture(setup.state()));

        assertFailure(
                setup.contexts().inspect(setup.plan().id()),
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        assertEquals(observations + 2,
                setup.clock().observationCount());
        assertEquals(before,
                ContextBusinessState.capture(setup.state()));
    }

    private static TaskFrameIdAndPlan addStartedSecondPlan(
            InMemoryState state,
            String suffix) {
        var taskFrameId =
                new io.paperagent.v2.contracts.TaskFrameId(
                        "task-" + suffix);
        var planId = new PlanId("plan-" + suffix);
        TaskFrame taskFrame = PersistenceFixtures.taskFrame(
                taskFrameId, "Second " + suffix);
        Plan plan = PersistenceFixtures.plan(
                planId, taskFrameId, suffix);
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                taskFrame,
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        String token = "token-" + suffix;
        LeaseRecord lease = requireApplied(
                new InMemoryLeaseRepository(state).acquire(
                        plan.id(),
                        "owner-" + suffix,
                        token,
                        PersistenceFixtures.T0.plus(
                                Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan,
                        token,
                        lease.fencingToken(),
                        "start-" + suffix)));
        return new TaskFrameIdAndPlan(
                plan, token, lease.fencingToken());
    }

    private static void destroyStartAuthority(InMemoryState state) {
        PlanId planId = PersistenceFixtures.PLAN_ID;
        state.executionStarts.remove(planId);
        state.executionMutationHeads.remove(planId);
        state.executionMutationLinks.remove(planId);
        state.stepActivations.remove(planId);
        state.eventStreams.remove(planId);
        state.eventsById.entrySet().removeIf(entry ->
                entry.getValue() != null
                        && planId.equals(entry.getValue().planId()));
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(),
                result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
        assertTrue(result.value().isEmpty());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(),
                result.toString());
        return result.value().orElseThrow();
    }

    private record Setup(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            TaskFrame taskFrame,
            Plan plan,
            LeaseRecord lease,
            LeaseRepository leases,
            PlanExecutionContextRepository contexts,
            StepActivationRepository activations) {
    }

    private record TaskFrameIdAndPlan(
            Plan plan,
            String leaseToken,
            long fencingToken) {
    }

    private record ReentrantSetup(
            InMemoryState state,
            MutationOnObservationClock clock,
            Plan plan,
            LeaseRecord lease,
            PlanExecutionContextRepository contexts) {
    }

    private enum LeaseFailure {
        NOT_HELD(
                PersistenceErrorCode.LEASE_NOT_HELD,
                "request.planId"),
        TOKEN(
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken"),
        FENCE(
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken"),
        EXPIRED(
                PersistenceErrorCode.LEASE_EXPIRED,
                "request.planId"),
        TAKEOVER_OLD_FENCE(
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");

        private final PersistenceErrorCode code;
        private final String path;

        LeaseFailure(
                PersistenceErrorCode code,
                String path) {
            this.code = code;
            this.path = path;
        }

        PersistenceErrorCode code() {
            return code;
        }

        String path() {
            return path;
        }
    }

    private static final class MutationOnObservationClock
            extends Clock {
        private final Instant current;
        private final AtomicInteger observations =
                new AtomicInteger();
        private final AtomicReference<Runnable> mutation =
                new AtomicReference<>();

        private MutationOnObservationClock(Instant current) {
            this.current = current;
        }

        void arm(Runnable action) {
            mutation.set(action);
        }

        int observationCount() {
            return observations.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            observations.incrementAndGet();
            Runnable action = mutation.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return current;
        }
    }

    private record ContextBusinessState(
            Map<?, ?> reservations,
            Map<?, ?> confirmations,
            Map<?, ?> owners,
            Map<?, ?> events,
            Map<?, ?> eventStreams,
            Map<?, ?> checkpoints,
            Map<?, ?> heads,
            Map<?, ?> links,
            Map<?, ?> activations) {

        static ContextBusinessState capture(InMemoryState state) {
            return new ContextBusinessState(
                    new LinkedHashMap<>(
                            state.planExecutionContextReservations),
                    new LinkedHashMap<>(
                            state.planExecutionContextConfirmations),
                    new LinkedHashMap<>(state.workspaceOwners),
                    new LinkedHashMap<>(state.eventsById),
                    new LinkedHashMap<>(state.eventStreams),
                    new LinkedHashMap<>(state.checkpoints),
                    new LinkedHashMap<>(
                            state.executionMutationHeads),
                    new LinkedHashMap<>(
                            state.executionMutationLinks),
                    new LinkedHashMap<>(state.stepActivations));
        }
    }
}
