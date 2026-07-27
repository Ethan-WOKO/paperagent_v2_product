package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionContextRepositoryConcurrencyTest {
    @Test
    void thirtyTwoExactReservationsCommitOnceAndReplayOriginal() throws Exception {
        Setup setup = started("reserve-race");
        var request = PersistenceFixtures.contextReservationRequest(
                setup.plan(),
                setup.lease().leaseToken(),
                setup.lease().fencingToken(),
                PersistenceFixtures.workspaceSpec("reserve-race"));
        List<Callable<PersistenceResult<PersistedPlanExecutionContextReserved>>>
                calls = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            calls.add(() -> setup.persistence()
                    .planExecutionContexts().reserve(request));
        }

        List<PersistenceResult<PersistedPlanExecutionContextReserved>>
                results = race(calls);
        assertEquals(1, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(31, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.REPLAYED)
                .count());
        assertEquals(1, results.stream()
                .map(result -> result.value().orElseThrow())
                .distinct()
                .count());
        assertEquals(1, setup.persistence().state()
                .planExecutionContextReservations.size());
        assertEquals(1,
                setup.persistence().state().workspaceOwners.size());
        assertEquals(0, setup.persistence().state()
                .planExecutionContextConfirmations.size());
    }

    @Test
    void thirtyTwoExactConfirmationsCommitOnceAndReplayOriginal()
            throws Exception {
        Setup setup = started("confirm-race");
        WorkspaceMaterializationSpec spec =
                PersistenceFixtures.workspaceSpec("confirm-race");
        requireApplied(setup.persistence().planExecutionContexts().reserve(
                PersistenceFixtures.contextReservationRequest(
                        setup.plan(),
                        setup.lease().leaseToken(),
                        setup.lease().fencingToken(),
                        spec)));
        var request = PersistenceFixtures.contextConfirmationRequest(
                setup.plan(),
                setup.lease().leaseToken(),
                setup.lease().fencingToken(),
                spec);
        List<Callable<PersistenceResult<PersistedPlanExecutionContextConfirmed>>>
                calls = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            calls.add(() -> setup.persistence()
                    .planExecutionContexts().confirm(request));
        }

        List<PersistenceResult<PersistedPlanExecutionContextConfirmed>>
                results = race(calls);
        assertEquals(1, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(31, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.REPLAYED)
                .count());
        assertEquals(1, results.stream()
                .map(result -> result.value().orElseThrow())
                .distinct()
                .count());
        assertEquals(1, setup.persistence().state()
                .planExecutionContextReservations.size());
        assertEquals(1,
                setup.persistence().state().workspaceOwners.size());
        assertEquals(1, setup.persistence().state()
                .planExecutionContextConfirmations.size());
    }

    @Test
    void sameWorkspaceAcrossPlansHasOnePermanentOwner() throws Exception {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        TestPersistence persistence = persistence(clock);
        Plan first = PersistenceFixtures.plan();
        TaskFrameId secondTaskId = new TaskFrameId("task-owner-second");
        PlanId secondPlanId = new PlanId("plan-owner-second");
        TaskFrame secondTask = PersistenceFixtures.taskFrame(
                secondTaskId, "Second owner");
        Plan second = PersistenceFixtures.plan(
                secondPlanId, secondTaskId, "owner-second");
        requireApplied(persistence.planBootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                first,
                PersistenceFixtures.initialCheckpoint(first)));
        requireApplied(persistence.planBootstraps().bootstrap(
                secondTask,
                second,
                PersistenceFixtures.initialCheckpoint(second)));
        LeaseRecord firstLease = start(
                persistence, first, "owner-one", "token-one", "one");
        LeaseRecord secondLease = start(
                persistence, second, "owner-two", "token-two", "two");
        WorkspaceMaterializationSpec shared =
                PersistenceFixtures.workspaceSpec("shared-owner");
        var firstRequest =
                PersistenceFixtures.contextReservationRequest(
                        first, "token-one",
                        firstLease.fencingToken(), shared);
        var secondRequest =
                PersistenceFixtures.contextReservationRequest(
                        second, "token-two",
                        secondLease.fencingToken(), shared);

        List<PersistenceResult<PersistedPlanExecutionContextReserved>>
                results = race(List.of(
                        () -> persistence.planExecutionContexts()
                                .reserve(firstRequest),
                        () -> persistence.planExecutionContexts()
                                .reserve(secondRequest)));
        assertEquals(1, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        PersistenceResult<?> rejected = results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                rejected.failure().orElseThrow().code());
        assertEquals(
                "request.materializationSpec.workspaceId",
                rejected.failure().orElseThrow().path());
        assertEquals(1,
                persistence.state()
                        .planExecutionContextReservations.size());
        assertEquals(1, persistence.state().workspaceOwners.size());
        assertEquals(0,
                persistence.state()
                        .planExecutionContextConfirmations.size());
    }

    @Test
    void changedReservationIdentitiesForSamePlanCommitExactlyOne()
            throws Exception {
        Setup setup = started("changed-identity");
        var first = PersistenceFixtures.contextReservationRequest(
                setup.plan(),
                setup.lease().leaseToken(),
                setup.lease().fencingToken(),
                PersistenceFixtures.workspaceSpec("changed-first"));
        var second = PersistenceFixtures.contextReservationRequest(
                setup.plan(),
                setup.lease().leaseToken(),
                setup.lease().fencingToken(),
                PersistenceFixtures.workspaceSpec("changed-second"));

        List<PersistenceResult<PersistedPlanExecutionContextReserved>>
                results = race(List.of(
                        () -> setup.persistence()
                                .planExecutionContexts().reserve(first),
                        () -> setup.persistence()
                                .planExecutionContexts().reserve(second)));

        assertEquals(1, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        PersistenceResult<?> conflict = results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                conflict.failure().orElseThrow().code());
        assertEquals(
                "request.planId",
                conflict.failure().orElseThrow().path());
        assertEquals(1, setup.persistence().state()
                .planExecutionContextReservations.size());
        assertEquals(1,
                setup.persistence().state().workspaceOwners.size());
        assertEquals(0, setup.persistence().state()
                .planExecutionContextConfirmations.size());
    }

    @Test
    void differentPlansAndWorkspacesReserveIndependently()
            throws Exception {
        TestPersistence persistence = persistence(
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0));
        Plan first = PersistenceFixtures.plan();
        TaskFrameId secondTaskId =
                new TaskFrameId("task-independent-second");
        Plan second = PersistenceFixtures.plan(
                new PlanId("plan-independent-second"),
                secondTaskId,
                "independent-second");
        requireApplied(persistence.planBootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                first,
                PersistenceFixtures.initialCheckpoint(first)));
        requireApplied(persistence.planBootstraps().bootstrap(
                PersistenceFixtures.taskFrame(
                        secondTaskId, "Independent second"),
                second,
                PersistenceFixtures.initialCheckpoint(second)));
        LeaseRecord firstLease = start(
                persistence, first, "owner-first", "token-first", "first");
        LeaseRecord secondLease = start(
                persistence,
                second,
                "owner-second",
                "token-second",
                "second");

        List<PersistenceResult<PersistedPlanExecutionContextReserved>>
                results = race(List.of(
                        () -> persistence.planExecutionContexts().reserve(
                                PersistenceFixtures
                                        .contextReservationRequest(
                                                first,
                                                "token-first",
                                                firstLease.fencingToken(),
                                                PersistenceFixtures
                                                        .workspaceSpec(
                                                                "independent-first"))),
                        () -> persistence.planExecutionContexts().reserve(
                                PersistenceFixtures
                                        .contextReservationRequest(
                                                second,
                                                "token-second",
                                                secondLease.fencingToken(),
                                                PersistenceFixtures
                                                        .workspaceSpec(
                                                                "independent-second")))));

        assertEquals(2, results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(2, persistence.state()
                .planExecutionContextReservations.size());
        assertEquals(2, persistence.state().workspaceOwners.size());
        assertEquals(0, persistence.state()
                .planExecutionContextConfirmations.size());
    }

    @Test
    void reserveAndConfirmSerializeWithRenewal() throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup reserve = started("reserve-renew-" + iteration);
            var reserveRequest =
                    PersistenceFixtures.contextReservationRequest(
                            reserve.plan(),
                            reserve.lease().leaseToken(),
                            reserve.lease().fencingToken(),
                            PersistenceFixtures.workspaceSpec(
                                    "reserve-renew-" + iteration));
            List<Object> reserveResults = raceObjects(List.of(
                    () -> reserve.persistence()
                            .planExecutionContexts()
                            .reserve(reserveRequest),
                    () -> reserve.persistence().leases().renew(
                            reserve.plan().id(),
                            reserve.lease().leaseToken(),
                            PersistenceFixtures.T0.plus(
                                    Duration.ofMinutes(2)))));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) reserveResults.get(0))
                            .outcome());
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) reserveResults.get(1))
                            .outcome());
            assertEquals(1, reserve.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(1,
                    reserve.persistence().state().workspaceOwners.size());

            Setup confirm = started("confirm-renew-" + iteration);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "confirm-renew-" + iteration);
            requireApplied(confirm.persistence()
                    .planExecutionContexts().reserve(
                            PersistenceFixtures
                                    .contextReservationRequest(
                                            confirm.plan(),
                                            confirm.lease().leaseToken(),
                                            confirm.lease().fencingToken(),
                                            spec)));
            List<Object> confirmResults = raceObjects(List.of(
                    () -> confirm.persistence()
                            .planExecutionContexts().confirm(
                                    PersistenceFixtures
                                            .contextConfirmationRequest(
                                                    confirm.plan(),
                                                    confirm.lease()
                                                            .leaseToken(),
                                                    confirm.lease()
                                                            .fencingToken(),
                                                    spec)),
                    () -> confirm.persistence().leases().renew(
                            confirm.plan().id(),
                            confirm.lease().leaseToken(),
                            PersistenceFixtures.T0.plus(
                                    Duration.ofMinutes(2)))));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) confirmResults.get(0))
                            .outcome());
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) confirmResults.get(1))
                            .outcome());
            assertEquals(1, confirm.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(1, confirm.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(1,
                    confirm.persistence().state().workspaceOwners.size());
        }
    }

    @Test
    void reserveExpiryTakeoverRaceNeverLetsOldFenceFirstCommit()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup setup = started(
                    "reserve-expiry-takeover-" + iteration);
            PlanExecutionContextReservationRequest oldRequest =
                    PersistenceFixtures.contextReservationRequest(
                            setup.plan(),
                            setup.lease().leaseToken(),
                            setup.lease().fencingToken(),
                            PersistenceFixtures.workspaceSpec(
                                    "reserve-expiry-takeover-"
                                            + iteration));
            setup.clock().set(setup.lease().expiresAt());
            int suffix = iteration;

            List<Object> results = raceObjects(List.of(
                    () -> setup.persistence()
                            .planExecutionContexts()
                            .reserve(oldRequest),
                    () -> setup.persistence().leases().acquire(
                            setup.plan().id(),
                            "takeover-owner",
                            "takeover-token-" + suffix,
                            PersistenceFixtures.T0.plus(
                                    Duration.ofMinutes(2)))));
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedPlanExecutionContextReserved>
                    reservation =
                    (PersistenceResult<PersistedPlanExecutionContextReserved>)
                            results.get(0);
            @SuppressWarnings("unchecked")
            PersistenceResult<LeaseRecord> takeover =
                    (PersistenceResult<LeaseRecord>) results.get(1);

            assertEquals(
                    PersistenceOutcome.REJECTED,
                    reservation.outcome());
            assertTrue(
                    reservation.failure().orElseThrow().code()
                                    == PersistenceErrorCode.LEASE_EXPIRED
                            || reservation.failure().orElseThrow().code()
                                    == PersistenceErrorCode
                                            .LEASE_TOKEN_INVALID);
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    takeover.outcome());
            assertEquals(
                    2,
                    takeover.value().orElseThrow().fencingToken());
            assertEquals(0, setup.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(0, setup.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(0,
                    setup.persistence().state().workspaceOwners.size());
        }
    }

    @Test
    void confirmExpiryTakeoverRaceNeverLetsOldFenceFirstCommit()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup setup = started(
                    "confirm-expiry-takeover-" + iteration);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "confirm-expiry-takeover-" + iteration);
            requireApplied(setup.persistence()
                    .planExecutionContexts().reserve(
                            PersistenceFixtures
                                    .contextReservationRequest(
                                            setup.plan(),
                                            setup.lease().leaseToken(),
                                            setup.lease().fencingToken(),
                                            spec)));
            PlanExecutionContextConfirmationRequest oldRequest =
                    PersistenceFixtures.contextConfirmationRequest(
                            setup.plan(),
                            setup.lease().leaseToken(),
                            setup.lease().fencingToken(),
                            spec);
            setup.clock().set(setup.lease().expiresAt());
            int suffix = iteration;

            List<Object> results = raceObjects(List.of(
                    () -> setup.persistence()
                            .planExecutionContexts()
                            .confirm(oldRequest),
                    () -> setup.persistence().leases().acquire(
                            setup.plan().id(),
                            "takeover-owner",
                            "takeover-token-" + suffix,
                            PersistenceFixtures.T0.plus(
                                    Duration.ofMinutes(2)))));
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    confirmation =
                    (PersistenceResult<PersistedPlanExecutionContextConfirmed>)
                            results.get(0);
            @SuppressWarnings("unchecked")
            PersistenceResult<LeaseRecord> takeover =
                    (PersistenceResult<LeaseRecord>) results.get(1);

            assertEquals(
                    PersistenceOutcome.REJECTED,
                    confirmation.outcome());
            assertTrue(
                    confirmation.failure().orElseThrow().code()
                                    == PersistenceErrorCode.LEASE_EXPIRED
                            || confirmation.failure().orElseThrow().code()
                                    == PersistenceErrorCode
                                            .LEASE_TOKEN_INVALID);
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    takeover.outcome());
            assertEquals(
                    2,
                    takeover.value().orElseThrow().fencingToken());
            assertEquals(1, setup.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(0, setup.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(1,
                    setup.persistence().state().workspaceOwners.size());
            assertTrue(setup.persistence()
                    .planExecutionContexts()
                    .inspect(setup.plan().id())
                    .value().orElseThrow()
                    instanceof PersistedPlanExecutionContextReserved);
        }
    }

    @Test
    void inspectRacesExposeOnlyCompleteContextCuts() throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup reserve = started("inspect-reserve-" + iteration);
            var request =
                    PersistenceFixtures.contextReservationRequest(
                            reserve.plan(),
                            reserve.lease().leaseToken(),
                            reserve.lease().fencingToken(),
                            PersistenceFixtures.workspaceSpec(
                                    "inspect-reserve-" + iteration));
            List<Object> reserveCut = raceObjects(List.of(
                    () -> reserve.persistence()
                            .planExecutionContexts().reserve(request),
                    () -> reserve.persistence()
                            .planExecutionContexts()
                            .inspect(reserve.plan().id())));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) reserveCut.get(0)).outcome());
            PersistenceResult<?> reserveInspection =
                    (PersistenceResult<?>) reserveCut.get(1);
            assertTrue(
                    reserveInspection.outcome() == PersistenceOutcome.FOUND
                            && reserveInspection.value().orElseThrow()
                                    instanceof PersistedPlanExecutionContextReserved
                            || reserveInspection.outcome()
                                    == PersistenceOutcome.REJECTED
                                    && reserveInspection.failure()
                                            .orElseThrow().code()
                                            == PersistenceErrorCode.NOT_FOUND);

            Setup confirm = started("inspect-confirm-" + iteration);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "inspect-confirm-" + iteration);
            requireApplied(confirm.persistence()
                    .planExecutionContexts().reserve(
                            PersistenceFixtures
                                    .contextReservationRequest(
                                            confirm.plan(),
                                            confirm.lease().leaseToken(),
                                            confirm.lease().fencingToken(),
                                            spec)));
            List<Object> confirmCut = raceObjects(List.of(
                    () -> confirm.persistence()
                            .planExecutionContexts().confirm(
                                    PersistenceFixtures
                                            .contextConfirmationRequest(
                                                    confirm.plan(),
                                                    confirm.lease()
                                                            .leaseToken(),
                                                    confirm.lease()
                                                            .fencingToken(),
                                                    spec)),
                    () -> confirm.persistence()
                            .planExecutionContexts()
                            .inspect(confirm.plan().id())));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) confirmCut.get(0)).outcome());
            Object inspected = ((PersistenceResult<?>) confirmCut.get(1))
                    .value().orElseThrow();
            assertTrue(
                    inspected instanceof PersistedPlanExecutionContextReserved
                            || inspected
                                    instanceof PersistedPlanExecutionContextConfirmed);
            assertEquals(1, confirm.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(1, confirm.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(1,
                    confirm.persistence().state().workspaceOwners.size());
        }
    }

    @Test
    void recoveryRacesObserveOnlyCommittedOrLegallyAdvancedCuts()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup reserveCut =
                    started("recovery-reserve-cut-" + iteration);
            var reserveRequest =
                    PersistenceFixtures.contextReservationRequest(
                            reserveCut.plan(),
                            reserveCut.lease().leaseToken(),
                            reserveCut.lease().fencingToken(),
                            PersistenceFixtures.workspaceSpec(
                                    "recovery-reserve-cut-" + iteration));
            List<Object> reserveResults = raceObjects(List.of(
                    () -> reserveCut.persistence()
                            .planExecutionContexts()
                            .reserve(reserveRequest),
                    () -> reserveCut.persistence()
                            .executionStartRecovery()
                            .inspect(reserveCut.plan().id())));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) reserveResults.get(0))
                            .outcome());
            assertTrue(
                    ((PersistenceResult<?>) reserveResults.get(1))
                                    .value().orElseThrow()
                            instanceof PersistedExecutionStartCommitted);
            assertEquals(1, reserveCut.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(0, reserveCut.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(1, reserveCut.persistence().state()
                    .workspaceOwners.size());

            Setup setup = started("recovery-cut-" + iteration);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "recovery-cut-" + iteration);
            requireApplied(setup.persistence()
                    .planExecutionContexts().reserve(
                            PersistenceFixtures
                                    .contextReservationRequest(
                                            setup.plan(),
                                            setup.lease().leaseToken(),
                                            setup.lease().fencingToken(),
                                            spec)));
            int suffix = iteration;
            List<Object> results = raceObjects(List.of(
                    () -> setup.persistence()
                            .planExecutionContexts().confirm(
                                    PersistenceFixtures
                                            .contextConfirmationRequest(
                                                    setup.plan(),
                                                    setup.lease()
                                                            .leaseToken(),
                                                    setup.lease()
                                                            .fencingToken(),
                                                    spec)),
                    () -> setup.persistence().stepActivations().activate(
                            PersistenceFixtures.stepActivationRequest(
                                    setup.plan(),
                                    setup.lease().leaseToken(),
                                    setup.lease().fencingToken(),
                                    "recovery-cut-" + suffix)),
                    () -> setup.persistence().executionStartRecovery()
                            .inspect(setup.plan().id())));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    ((PersistenceResult<?>) results.get(0)).outcome());
            PersistenceResult<?> activation =
                    (PersistenceResult<?>) results.get(1);
            assertTrue(
                    activation.outcome() == PersistenceOutcome.APPLIED
                            || activation.failure().orElseThrow().code()
                                    == PersistenceErrorCode
                                            .STEP_ACTIVATION_NOT_ELIGIBLE);
            PersistenceResult<?> recovery =
                    (PersistenceResult<?>) results.get(2);
            assertTrue(
                    recovery.outcome() == PersistenceOutcome.FOUND
                            && recovery.value().orElseThrow()
                                    instanceof PersistedExecutionStartCommitted
                            || recovery.outcome()
                                    == PersistenceOutcome.REJECTED
                                    && recovery.failure().orElseThrow()
                                            .code()
                                            == PersistenceErrorCode
                                                    .EXECUTION_RECOVERY_ADVANCED_STATE);
            assertEquals(1, setup.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(1, setup.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(1,
                    setup.persistence().state().workspaceOwners.size());
        }
    }

    @Test
    void confirmAndActivationSerializeWithoutActiveReservedState()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup setup = started("confirm-activation-" + iteration);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "confirm-activation-" + iteration);
            requireApplied(setup.persistence()
                    .planExecutionContexts().reserve(
                            PersistenceFixtures
                                    .contextReservationRequest(
                                            setup.plan(),
                                            setup.lease().leaseToken(),
                                            setup.lease().fencingToken(),
                                            spec)));
            int suffix = iteration;
            List<Object> results = raceObjects(List.of(
                    () -> setup.persistence()
                            .planExecutionContexts().confirm(
                                    PersistenceFixtures
                                            .contextConfirmationRequest(
                                                    setup.plan(),
                                                    setup.lease().leaseToken(),
                                                    setup.lease().fencingToken(),
                                                    spec)),
                    () -> setup.persistence().stepActivations().activate(
                            PersistenceFixtures.stepActivationRequest(
                                    setup.plan(),
                                    setup.lease().leaseToken(),
                                    setup.lease().fencingToken(),
                                    "activation-race-" + suffix))));
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    confirmation =
                    (PersistenceResult<PersistedPlanExecutionContextConfirmed>)
                            results.get(0);
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedStepActivation> activation =
                    (PersistenceResult<PersistedStepActivation>)
                            results.get(1);
            assertEquals(PersistenceOutcome.APPLIED,
                    confirmation.outcome());
            assertTrue(activation.outcome()
                            == PersistenceOutcome.APPLIED
                    || activation.failure().orElseThrow().code()
                            == PersistenceErrorCode
                                    .STEP_ACTIVATION_NOT_ELIGIBLE);
            if (activation.outcome() == PersistenceOutcome.APPLIED) {
                assertTrue(setup.persistence()
                        .planExecutionContexts().inspect(
                                setup.plan().id())
                        .value().orElseThrow()
                        instanceof PersistedPlanExecutionContextConfirmed);
            }
            assertEquals(1, setup.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(1, setup.persistence().state()
                    .planExecutionContextConfirmations.size());
            assertEquals(1,
                    setup.persistence().state().workspaceOwners.size());
        }
    }

    @Test
    void reserveAndLeaseReleaseAreStrictlySerialized()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup setup = started("reserve-release-" + iteration);
            var request =
                    PersistenceFixtures.contextReservationRequest(
                            setup.plan(),
                            setup.lease().leaseToken(),
                            setup.lease().fencingToken(),
                            PersistenceFixtures.workspaceSpec(
                                    "reserve-release-" + iteration));
            List<Object> results = raceObjects(List.of(
                    () -> setup.persistence()
                            .planExecutionContexts().reserve(request),
                    () -> setup.persistence().leases().release(
                            setup.plan().id(),
                            setup.lease().leaseToken())));
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedPlanExecutionContextReserved>
                    reservation =
                    (PersistenceResult<PersistedPlanExecutionContextReserved>)
                            results.get(0);
            @SuppressWarnings("unchecked")
            PersistenceResult<LeaseRecord> release =
                    (PersistenceResult<LeaseRecord>) results.get(1);
            assertEquals(PersistenceOutcome.APPLIED, release.outcome());
            if (reservation.outcome() == PersistenceOutcome.REJECTED) {
                assertEquals(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        reservation.failure().orElseThrow().code());
                assertEquals(0, setup.persistence().state()
                        .planExecutionContextReservations.size());
                assertEquals(0,
                        setup.persistence().state().workspaceOwners.size());
            } else {
                assertEquals(PersistenceOutcome.APPLIED,
                        reservation.outcome());
                assertTrue(setup.persistence()
                        .planExecutionContexts()
                        .inspect(setup.plan().id())
                        .value().orElseThrow()
                        instanceof PersistedPlanExecutionContextReserved);
                assertEquals(1, setup.persistence().state()
                        .planExecutionContextReservations.size());
                assertEquals(1,
                        setup.persistence().state().workspaceOwners.size());
            }
            assertEquals(0, setup.persistence().state()
                    .planExecutionContextConfirmations.size());
        }
    }

    @Test
    void confirmAndLeaseReleaseAreStrictlySerialized()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            Setup setup = started("confirm-release-" + iteration);
            WorkspaceMaterializationSpec spec =
                    PersistenceFixtures.workspaceSpec(
                            "confirm-release-" + iteration);
            requireApplied(setup.persistence()
                    .planExecutionContexts().reserve(
                            PersistenceFixtures
                                    .contextReservationRequest(
                                            setup.plan(),
                                            setup.lease().leaseToken(),
                                            setup.lease().fencingToken(),
                                            spec)));
            var request =
                    PersistenceFixtures.contextConfirmationRequest(
                            setup.plan(),
                            setup.lease().leaseToken(),
                            setup.lease().fencingToken(),
                            spec);
            List<Object> results = raceObjects(List.of(
                    () -> setup.persistence()
                            .planExecutionContexts().confirm(request),
                    () -> setup.persistence().leases().release(
                            setup.plan().id(),
                            setup.lease().leaseToken())));
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    confirmation =
                    (PersistenceResult<PersistedPlanExecutionContextConfirmed>)
                            results.get(0);
            @SuppressWarnings("unchecked")
            PersistenceResult<LeaseRecord> release =
                    (PersistenceResult<LeaseRecord>) results.get(1);
            assertEquals(PersistenceOutcome.APPLIED, release.outcome());
            if (confirmation.outcome() == PersistenceOutcome.REJECTED) {
                assertEquals(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        confirmation.failure().orElseThrow().code());
                assertTrue(setup.persistence()
                        .planExecutionContexts()
                        .inspect(setup.plan().id())
                        .value().orElseThrow()
                        instanceof PersistedPlanExecutionContextReserved);
                assertEquals(0, setup.persistence().state()
                        .planExecutionContextConfirmations.size());
            } else {
                assertEquals(PersistenceOutcome.APPLIED,
                        confirmation.outcome());
                assertTrue(setup.persistence()
                        .planExecutionContexts()
                        .inspect(setup.plan().id())
                        .value().orElseThrow()
                        instanceof PersistedPlanExecutionContextConfirmed);
                assertEquals(1, setup.persistence().state()
                        .planExecutionContextConfirmations.size());
            }
            assertEquals(1, setup.persistence().state()
                    .planExecutionContextReservations.size());
            assertEquals(1,
                    setup.persistence().state().workspaceOwners.size());
        }
    }

    private static Setup started(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        TestPersistence persistence =
                persistence(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(persistence.planBootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        LeaseRecord lease = start(
                persistence,
                plan,
                "owner",
                "token-" + suffix,
                suffix);
        return new Setup(persistence, clock, plan, lease);
    }

    private static LeaseRecord start(
            TestPersistence persistence,
            Plan plan,
            String owner,
            String token,
            String suffix) {
        LeaseRecord lease = requireApplied(persistence.leases().acquire(
                plan.id(),
                owner,
                token,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(persistence.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        plan,
                        token,
                        lease.fencingToken(),
                        "start-" + suffix)));
        return lease;
    }

    private static TestPersistence persistence(
            java.time.Clock clock) {
        InMemoryState state = new InMemoryState(clock);
        return new TestPersistence(
                state,
                new InMemoryPlanBootstrapRepository(state),
                new InMemoryLeaseRepository(state),
                new InMemoryExecutionStartRepository(state),
                new InMemoryPlanExecutionContextRepository(state),
                new InMemoryStepActivationRepository(state),
                new InMemoryExecutionStartRecoveryRepository(state));
    }

    private static <T> List<T> race(
            List<? extends Callable<T>> calls) throws Exception {
        List<Object> values = raceObjects(calls);
        List<T> results = new ArrayList<>();
        for (Object value : values) {
            @SuppressWarnings("unchecked")
            T result = (T) value;
            results.add(result);
        }
        return results;
    }

    private static List<Object> raceObjects(
            List<? extends Callable<?>> calls) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("race did not start");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<?> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    5, TimeUnit.SECONDS));
        }
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(),
                result.toString());
        return result.value().orElseThrow();
    }

    private record Setup(
            TestPersistence persistence,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            LeaseRecord lease) {
    }

    private record TestPersistence(
            InMemoryState state,
            PlanBootstrapRepository planBootstraps,
            LeaseRepository leases,
            ExecutionStartRepository executionStarts,
            PlanExecutionContextRepository planExecutionContexts,
            StepActivationRepository stepActivations,
            ExecutionStartRecoveryRepository executionStartRecovery) {
    }
}
