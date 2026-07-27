package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2bootstrap;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductPlanBootstrapRepositoryAdapter.class,
        ProductPlanBootstrapTransactions.class,
        ProductPlanBootstrapCodec.class,
        ProductPlanBootstrapRepositoryAdapterTest.CodecConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductPlanBootstrapRepositoryAdapterTest {
    static class CodecConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @jakarta.annotation.Resource
    private ProductPlanBootstrapRepositoryAdapter adapter;

    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository rows;

    @BeforeEach
    void clearRows() {
        rows.deleteAll();
        rows.flush();
    }

    @Test
    void appliesThenReplaysTheReconstructedStoredTuple() {
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.project("plan-1", "task-1");

        PersistenceResult<PersistedPlanBootstrap> applied = bootstrap(tuple);
        PersistenceResult<PersistedPlanBootstrap> replayed = bootstrap(tuple);

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(tuple, applied.value().orElseThrow());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(tuple, replayed.value().orElseThrow());
        assertEquals(1, rows.count());
    }

    @Test
    void rejectsSamePlanWithDifferentCanonicalContent() {
        PersistedPlanBootstrap first =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap changed = withObjective(first, "Different objective");
        bootstrap(first);

        assertFailure(
                bootstrap(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");
        assertEquals(1, rows.count());
    }

    @Test
    void rejectsDifferentPlanClaimingPersistedTaskFrame() {
        PersistedPlanBootstrap first =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap contender =
                ProductPlanBootstrapTestFixtures.workspace("plan-2", "task-1");
        bootstrap(first);

        assertFailure(
                bootstrap(contender),
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                "bootstrap");
        assertEquals(1, rows.count());
    }

    @Test
    void rejectsNullArgumentsWithStablePaths() {
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        assertFailure(
                adapter.bootstrap(null, tuple.plan(), tuple.initialCheckpoint().checkpoint()),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "taskFrame");
        assertFailure(
                adapter.bootstrap(tuple.taskFrame(), null, tuple.initialCheckpoint().checkpoint()),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "plan");
        assertFailure(
                adapter.bootstrap(tuple.taskFrame(), tuple.plan(), null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "checkpoint");
        assertEquals(0, rows.count());
    }

    @Test
    void rejectsTaskFrameMismatchBeforeStorage() {
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap other =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-2");

        assertFailure(
                adapter.bootstrap(
                        tuple.taskFrame(),
                        other.plan(),
                        other.initialCheckpoint().checkpoint()),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "plan.taskFrameId");
        assertEquals(0, rows.count());
    }

    @Test
    void rejectsNonInitialCheckpointShape() {
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        Checkpoint checkpoint = tuple.initialCheckpoint().checkpoint();
        var states = new LinkedHashMap<>(checkpoint.stepStates());
        states.replaceAll((ignored, value) -> StepExecutionState.ACTIVE);
        Checkpoint advanced = new Checkpoint(
                checkpoint.taskFrameId(),
                checkpoint.planId(),
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                1,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                checkpoint.createdAt());

        assertFailure(
                adapter.bootstrap(tuple.taskFrame(), tuple.plan(), advanced),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint");
        assertEquals(0, rows.count());
    }

    @Test
    void equivalentConcurrentContendersConvergeAppliedAndReplayed() throws Exception {
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.project("plan-1", "task-1");
        List<PersistenceResult<PersistedPlanBootstrap>> results =
                race(tuple, tuple);

        assertOutcomes(results, PersistenceOutcome.APPLIED, PersistenceOutcome.REPLAYED);
        assertEquals(1, rows.count());
    }

    @Test
    void conflictingConcurrentPlanContendersConvergeAppliedAndConflict() throws Exception {
        PersistedPlanBootstrap first =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap changed = withObjective(first, "Different objective");
        List<PersistenceResult<PersistedPlanBootstrap>> results = race(first, changed);

        assertOneAppliedAndFailure(
                results, PersistenceErrorCode.CONFLICTING_REPLAY, "plan.id");
        assertEquals(1, rows.count());
    }

    @Test
    void sameTaskFrameConcurrentPlansConvergeAppliedAndPartialState() throws Exception {
        PersistedPlanBootstrap first =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap second =
                ProductPlanBootstrapTestFixtures.workspace("plan-2", "task-1");
        List<PersistenceResult<PersistedPlanBootstrap>> results = race(first, second);

        assertOneAppliedAndFailure(
                results, PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE, "bootstrap");
        assertEquals(1, rows.count());
    }

    @Test
    void insertRaceReadsOnlyAfterFailedRequiresNewInsertReturns() {
        ProductPlanBootstrapTransactions transactions =
                mock(ProductPlanBootstrapTransactions.class);
        ProductPlanBootstrapCodec codec =
                new ProductPlanBootstrapCodec(new ObjectMapper());
        ProductPlanBootstrapRepositoryAdapter isolated =
                new ProductPlanBootstrapRepositoryAdapter(transactions, codec);
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        var payload = codec.encode(tuple);
        ProductPlanBootstrapEntity winner = new ProductPlanBootstrapEntity(
                "plan-1", "task-1", 1, payload.sha256(), payload.json(), Instant.now());
        when(transactions.findByPlanId("plan-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactions.findByTaskFrameId("task-1")).thenReturn(Optional.empty());
        when(transactions.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("synthetic race"));

        assertEquals(PersistenceOutcome.REPLAYED, bootstrap(isolated, tuple).outcome());

        var order = inOrder(transactions);
        order.verify(transactions).findByPlanId("plan-1");
        order.verify(transactions).findByTaskFrameId("task-1");
        order.verify(transactions).insert(org.mockito.ArgumentMatchers.any());
        order.verify(transactions).findByPlanId("plan-1");
    }

    @Test
    void insertRaceUsesSamePlanTaskFrameWinnerForExactReplay() {
        ProductPlanBootstrapTransactions transactions =
                mock(ProductPlanBootstrapTransactions.class);
        ProductPlanBootstrapCodec codec =
                new ProductPlanBootstrapCodec(new ObjectMapper());
        ProductPlanBootstrapRepositoryAdapter isolated =
                new ProductPlanBootstrapRepositoryAdapter(transactions, codec);
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        var payload = codec.encode(tuple);
        ProductPlanBootstrapEntity winner = new ProductPlanBootstrapEntity(
                "plan-1", "task-1", 1, payload.sha256(), payload.json(), Instant.now());
        when(transactions.findByPlanId("plan-1")).thenReturn(Optional.empty());
        when(transactions.findByTaskFrameId("task-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactions.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("synthetic race"));

        assertEquals(PersistenceOutcome.REPLAYED,
                bootstrap(isolated, tuple).outcome());
        var order = inOrder(transactions);
        order.verify(transactions).findByPlanId("plan-1");
        order.verify(transactions).findByTaskFrameId("task-1");
        order.verify(transactions).insert(org.mockito.ArgumentMatchers.any());
        order.verify(transactions).findByPlanId("plan-1");
        order.verify(transactions).findByTaskFrameId("task-1");
    }

    @Test
    void insertRaceUsesSamePlanTaskFrameWinnerForConflict() {
        ProductPlanBootstrapTransactions transactions =
                mock(ProductPlanBootstrapTransactions.class);
        ProductPlanBootstrapCodec codec =
                new ProductPlanBootstrapCodec(new ObjectMapper());
        ProductPlanBootstrapRepositoryAdapter isolated =
                new ProductPlanBootstrapRepositoryAdapter(transactions, codec);
        PersistedPlanBootstrap winnerTuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap changed =
                withObjective(winnerTuple, "Different objective");
        var payload = codec.encode(winnerTuple);
        ProductPlanBootstrapEntity winner = new ProductPlanBootstrapEntity(
                "plan-1", "task-1", 1, payload.sha256(), payload.json(), Instant.now());
        when(transactions.findByPlanId("plan-1")).thenReturn(Optional.empty());
        when(transactions.findByTaskFrameId("task-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactions.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("synthetic race"));

        assertFailure(
                bootstrap(isolated, changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");
    }

    @Test
    void insertRaceKeepsDifferentPlanTaskFrameWinnerPartial() {
        ProductPlanBootstrapTransactions transactions =
                mock(ProductPlanBootstrapTransactions.class);
        ProductPlanBootstrapCodec codec =
                new ProductPlanBootstrapCodec(new ObjectMapper());
        ProductPlanBootstrapRepositoryAdapter isolated =
                new ProductPlanBootstrapRepositoryAdapter(transactions, codec);
        PersistedPlanBootstrap requested =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        PersistedPlanBootstrap winnerTuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-2", "task-1");
        var payload = codec.encode(winnerTuple);
        ProductPlanBootstrapEntity winner = new ProductPlanBootstrapEntity(
                "plan-2", "task-1", 1, payload.sha256(), payload.json(), Instant.now());
        when(transactions.findByPlanId("plan-1")).thenReturn(Optional.empty());
        when(transactions.findByTaskFrameId("task-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactions.insert(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("synthetic race"));

        assertFailure(
                bootstrap(isolated, requested),
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                "bootstrap");
    }

    @Test
    void propagatesUnrelatedStorageFailureWhenFreshReadsFindNoWinner() {
        ProductPlanBootstrapTransactions transactions =
                mock(ProductPlanBootstrapTransactions.class);
        ProductPlanBootstrapRepositoryAdapter isolated =
                new ProductPlanBootstrapRepositoryAdapter(
                        transactions, new ProductPlanBootstrapCodec(new ObjectMapper()));
        PersistedPlanBootstrap tuple =
                ProductPlanBootstrapTestFixtures.workspace("plan-1", "task-1");
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated");
        when(transactions.findByPlanId("plan-1")).thenReturn(Optional.empty());
        when(transactions.findByTaskFrameId("task-1")).thenReturn(Optional.empty());
        when(transactions.insert(org.mockito.ArgumentMatchers.any())).thenThrow(failure);

        assertSame(failure, assertThrows(
                DataIntegrityViolationException.class,
                () -> bootstrap(isolated, tuple)));
        assertEquals(0, rows.count());
    }

    private List<PersistenceResult<PersistedPlanBootstrap>> race(
            PersistedPlanBootstrap first,
            PersistedPlanBootstrap second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PersistenceResult<PersistedPlanBootstrap>> left =
                    pool.submit(() -> awaitAndBootstrap(ready, start, first));
            Future<PersistenceResult<PersistedPlanBootstrap>> right =
                    pool.submit(() -> awaitAndBootstrap(ready, start, second));
            ready.await();
            start.countDown();
            return List.of(left.get(), right.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private PersistenceResult<PersistedPlanBootstrap> awaitAndBootstrap(
            CountDownLatch ready,
            CountDownLatch start,
            PersistedPlanBootstrap tuple) throws InterruptedException {
        ready.countDown();
        start.await();
        return bootstrap(tuple);
    }

    private PersistenceResult<PersistedPlanBootstrap> bootstrap(
            PersistedPlanBootstrap tuple) {
        return bootstrap(adapter, tuple);
    }

    private static PersistenceResult<PersistedPlanBootstrap> bootstrap(
            ProductPlanBootstrapRepositoryAdapter target,
            PersistedPlanBootstrap tuple) {
        return target.bootstrap(
                tuple.taskFrame(),
                tuple.plan(),
                tuple.initialCheckpoint().checkpoint());
    }

    private static PersistedPlanBootstrap withObjective(
            PersistedPlanBootstrap source,
            String objective) {
        TaskFrame frame = source.taskFrame();
        TaskFrame changed = new TaskFrame(
                frame.id(),
                objective,
                frame.targets(),
                frame.deliverables(),
                frame.constraints(),
                frame.sourceProjectVersion(),
                frame.executionProfile(),
                frame.createdAt());
        return new PersistedPlanBootstrap(changed, source.plan(), source.initialCheckpoint());
    }

    private static void assertOutcomes(
            List<PersistenceResult<PersistedPlanBootstrap>> results,
            PersistenceOutcome first,
            PersistenceOutcome second) {
        assertEquals(1, results.stream().filter(value -> value.outcome() == first).count());
        assertEquals(1, results.stream().filter(value -> value.outcome() == second).count());
    }

    private static void assertOneAppliedAndFailure(
            List<PersistenceResult<PersistedPlanBootstrap>> results,
            PersistenceErrorCode code,
            String path) {
        assertEquals(1, results.stream()
                .filter(value -> value.outcome() == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(1, results.stream()
                .filter(value -> value.failure().map(failure ->
                        failure.code() == code && failure.path().equals(path)).orElse(false))
                .count());
    }

    private static void assertFailure(
            PersistenceResult<PersistedPlanBootstrap> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }
}
