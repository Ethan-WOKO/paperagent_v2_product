package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRecoveryCompositionConcurrencyTest {

    @Test
    void leaseTurnoverCannotReturnThePreLeaseSnapshot() throws Exception {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("turnover");
        PersistedStepRecoveryActive beforeLease = StepRecoveryCompositionTestFixtures.active(
                "turnover", "before", false);
        PersistedStepRecoveryActive afterTurnover = StepRecoveryCompositionTestFixtures.active(
                "turnover", "after", false);
        LeaseRecord lease = StepRecoveryCompositionTestFixtures.matchingLease(request, 4);
        AtomicReference<PersistedStepRecoveryActive> current =
                new AtomicReference<>(beforeLease);
        AtomicInteger inspections = new AtomicInteger();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch allowPostLeaseInspection = new CountDownLatch(1);
        StepRecoveryRepository recoveryRepository = planId -> {
            if (inspections.getAndIncrement() == 0) {
                return PersistenceResult.found(beforeLease);
            }
            try {
                assertTrue(allowPostLeaseInspection.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("post-lease inspection interrupted", exception);
            }
            return PersistenceResult.found(current.get());
        };
        LeaseRepository leaseRepository = new LeaseRepository() {
            @Override
            public PersistenceResult<LeaseRecord> acquire(
                    PlanId planId,
                    String ownerId,
                    String token,
                    Instant expiresAt) {
                acquired.countDown();
                return PersistenceResult.applied(lease);
            }

            @Override
            public PersistenceResult<LeaseRecord> renew(
                    PlanId planId,
                    String token,
                    Instant expiresAt) {
                throw new AssertionError("renew must not be called");
            }

            @Override
            public PersistenceResult<LeaseRecord> release(PlanId planId, String token) {
                throw new AssertionError("release must not be called");
            }

            @Override
            public PersistenceResult<LeaseRecord> find(PlanId planId) {
                throw new AssertionError("find must not be called");
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> new DefaultStepRecoverer(
                    recoveryRepository, leaseRepository).recover(request));
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            current.set(afterTurnover);
            allowPostLeaseInspection.countDown();

            RecoveredActiveStep recovered = assertInstanceOf(
                    RecoveredActiveStep.class, future.get(5, TimeUnit.SECONDS));
            assertSame(afterTurnover, recovered.recovery());
            assertSame(lease, recovered.lease());
            assertEquals(2, inspections.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void postLeasePlanReplacementFailsClosedRatherThanMixingAuthorities() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("replacement");
        PersistedStepRecoveryActive initial = StepRecoveryCompositionTestFixtures.active(
                "replacement", "initial", false);
        PersistedStepRecoveryActive replaced = StepRecoveryCompositionTestFixtures.active(
                "other-plan", "replaced", false);
        var recoveryRepository = StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository.found(initial, replaced);
        var leaseRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedLeaseRepository(PersistenceResult.applied(
                        StepRecoveryCompositionTestFixtures.matchingLease(request, 5)));

        try {
            new DefaultStepRecoverer(recoveryRepository, leaseRepository).recover(request);
        } catch (StepRecoveryProtocolException exception) {
            assertEquals(StepRecoveryStage.POST_LEASE_INSPECT, exception.stage());
            assertEquals(StepRecoveryProtocolCode.INCONSISTENT_INSPECTION_RESULT,
                    exception.code());
            assertEquals(StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                    exception.leaseDisposition());
            assertEquals(2, recoveryRepository.inspectCalls());
            assertEquals(1, leaseRepository.acquireCalls());
            return;
        }
        throw new AssertionError("a replaced Plan snapshot must fail closed");
    }
}
