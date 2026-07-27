package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepInterruptionRepositoryConcurrencyTest {
    @Test
    void concurrentSameKindIdentityLinearizesToOneAppliedAndExactReplays() throws Exception {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("same-kind");
        StepPauseRequest request = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-same-kind");
        List<PersistenceResult<PersistedStepInterruption>> results = runTogether(
                6,
                () -> harness.interruptions().pause(request));

        assertEquals(1, results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(5, results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REPLAYED)
                .count());
        PersistedStepInterruption expected = results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                .findFirst()
                .orElseThrow()
                .value()
                .orElseThrow();
        results.forEach(result -> assertEquals(
                expected, result.value().orElseThrow()));
        assertEquals(1, harness.state().stepPauses.get(harness.plan().id()).size());
        assertEquals(2, harness.state().executionMutationLinks.get(harness.plan().id()).size());
    }

    @Test
    void competingKindsWithOneIdentityCannotOverwriteOrForkTheMarker() throws Exception {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("competing-kinds");
        String sharedId = "shared-competing-id";
        StepPauseRequest pause = StepInterruptionRepositoryTest.pauseRequest(
                harness, sharedId);
        StepFailRequest failure = StepInterruptionRepositoryTest.failRequest(
                harness, sharedId);
        List<PersistenceResult<PersistedStepInterruption>> results = runTogether(
                List.of(
                        () -> harness.interruptions().pause(pause),
                        () -> harness.interruptions().fail(failure)));

        assertEquals(1, results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        PersistenceResult<PersistedStepInterruption> rejected = results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst()
                .orElseThrow();
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                rejected.failure().orElseThrow().code());
        assertEquals(1, interruptionMarkerCount(harness));
        assertEquals(2, harness.state().executionMutationLinks.get(harness.plan().id()).size());
    }

    @Test
    void staleConcurrentWriterCannotAppendASecondInterruptionLink() throws Exception {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("stale-writer");
        StepPauseRequest first = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-first-writer");
        StepPauseRequest second = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-stale-writer");
        List<PersistenceResult<PersistedStepInterruption>> results = runTogether(
                List.of(
                        () -> harness.interruptions().pause(first),
                        () -> harness.interruptions().pause(second)));

        assertEquals(1, results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                .count());
        PersistenceResult<PersistedStepInterruption> rejected = results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst()
                .orElseThrow();
        assertEquals(PersistenceErrorCode.STALE_VERSION,
                rejected.failure().orElseThrow().code());
        assertEquals(1, interruptionMarkerCount(harness));
        assertEquals(2, harness.state().executionMutationLinks.get(harness.plan().id()).size());
    }

    private static int interruptionMarkerCount(
            StepInterruptionRepositoryTest.Harness harness) {
        return harness.state().stepPauses.get(harness.plan().id()).size()
                + harness.state().stepFailures.get(harness.plan().id()).size()
                + harness.state().stepCancellations.get(harness.plan().id()).size();
    }

    private static List<PersistenceResult<PersistedStepInterruption>> runTogether(
            int count,
            ThrowingSupplier<PersistenceResult<PersistedStepInterruption>> supplier)
            throws Exception {
        List<ThrowingSupplier<PersistenceResult<PersistedStepInterruption>>> suppliers =
                new ArrayList<>();
        for (int index = 0; index < count; index++) {
            suppliers.add(supplier);
        }
        return runTogether(suppliers);
    }

    private static List<PersistenceResult<PersistedStepInterruption>> runTogether(
            List<ThrowingSupplier<PersistenceResult<PersistedStepInterruption>>> suppliers)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(suppliers.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(suppliers.size());
        try {
            List<Future<PersistenceResult<PersistedStepInterruption>>> futures =
                    new ArrayList<>();
            for (ThrowingSupplier<PersistenceResult<PersistedStepInterruption>> supplier
                    : suppliers) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return supplier.get();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<PersistenceResult<PersistedStepInterruption>> results = new ArrayList<>();
            for (Future<PersistenceResult<PersistedStepInterruption>> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
