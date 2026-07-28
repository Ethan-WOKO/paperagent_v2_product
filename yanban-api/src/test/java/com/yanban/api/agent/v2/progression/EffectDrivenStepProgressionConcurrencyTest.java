package com.yanban.api.agent.v2.progression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.bootstrap.AgentV2PlanBootstrapConfiguration;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseDisposition;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionLeaseDisposition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2_effect_progression_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        ProductStepProgressionConfiguration.class,
        AuthenticatedEffectDrivenStepProgressionComposer.class,
        EffectDrivenStepProgressionConcurrencyTest.PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EffectDrivenStepProgressionConcurrencyTest {
    @TestConfiguration
    @ComponentScan(
            basePackageClasses = ProductPlanBootstrapRepositoryAdapter.class)
    static class PersistenceSlice {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean
    private AgentTurnProductContextResolver productContexts;

    @Autowired
    private PlanBootstrapRepository bootstrapRepository;

    @Autowired
    private LeaseRepository leaseRepository;

    @Autowired
    private ExecutionStartRepository executionStartRepository;

    @Autowired
    private StepActivationRepository stepActivationRepository;

    @Autowired
    private EffectIntentRepository effectIntentRepository;

    @Autowired
    private EffectOutcomeRepository effectOutcomeRepository;

    @Autowired
    private AuthenticatedEffectDrivenStepProgressionComposer
            persistedComposer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exactConcurrentCallsConvergeToOneCompletionAndActivation()
            throws Exception {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        AtomicReference<StepRecoverySnapshot> state =
                new AtomicReference<>(fixture.activeA);
        AtomicInteger completionWrites = new AtomicInteger();
        AtomicInteger activationWrites = new AtomicInteger();

        when(fixture.inspector.inspect(fixture.planId)).thenAnswer(ignored ->
                PersistenceResult.found(state.get()));
        when(fixture.completion.compose(any())).thenAnswer(ignored -> {
            boolean winner = state.compareAndSet(
                    fixture.activeA, fixture.readyB);
            if (winner) {
                completionWrites.incrementAndGet();
            }
            return new ActiveStepCompletionCommitted(
                    winner ? PersistenceOutcome.APPLIED
                            : PersistenceOutcome.REPLAYED,
                    fixture.persistedCompletion,
                    ActiveStepCompletionLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        });
        when(fixture.activation.composeReady(any())).thenAnswer(ignored -> {
            boolean winner = state.compareAndSet(
                    fixture.readyB, fixture.activeB);
            if (winner) {
                activationWrites.incrementAndGet();
            }
            return new StepActivationCommitted(
                    winner ? PersistenceOutcome.APPLIED
                            : PersistenceOutcome.REPLAYED,
                    fixture.activeB.activation(),
                    StepActivationLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        });

        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            var tasks = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> pool.submit(() -> {
                        start.await();
                        return fixture.composer.progress(
                                7L, 42L, fixture.command());
                    })).toList();
            start.countDown();
            List<EffectDrivenStepProgressionOutcome> results =
                    tasks.stream().map(task -> {
                        try {
                            return task.get(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }).toList();
            assertTrue(results.stream().allMatch(outcome ->
                    outcome.state()
                            == EffectDrivenStepProgressionState
                                    .NEXT_STEP_ACTIVE));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, completionWrites.get());
        assertEquals(1, activationWrites.get());
        assertEquals(fixture.activeB, state.get());
    }

    @Test
    void productDatabaseBootstrapLockConvergesConcurrentExactCalls()
            throws Exception {
        clearV2Rows();
        var scenario = EffectDrivenStepProgressionTestFixtures.seedDatabase(
                bootstrapRepository, leaseRepository,
                executionStartRepository, stepActivationRepository,
                effectIntentRepository, effectOutcomeRepository);
        when(productContexts.resolve(7L, 42L))
                .thenReturn(scenario.fixture().context);
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            var tasks = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> pool.submit(() -> {
                        start.await();
                        return persistedComposer.progress(
                                7L, 42L, scenario.command());
                    })).toList();
            start.countDown();
            var results = tasks.stream().map(task -> {
                try {
                    return task.get(20, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    if (failure.getCause()
                            instanceof EffectDrivenStepProgressionException
                                    rejected) {
                        throw new AssertionError(
                                "concurrent progression rejected at "
                                        + rejected.path(),
                                failure);
                    }
                    throw new AssertionError(failure);
                }
            }).toList();
            assertTrue(results.stream().allMatch(outcome ->
                    outcome.state()
                            == EffectDrivenStepProgressionState
                                    .NEXT_STEP_ACTIVE));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("agent_v2_step_completions"));
        assertEquals(2, count("agent_v2_step_activations"));
        assertEquals(1, count("agent_v2_effect_results"));
    }

    private void clearV2Rows() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA='PUBLIC' "
                                + "AND TABLE_NAME LIKE 'AGENT_V2_%'",
                        String.class)
                .forEach(table -> jdbc.execute("TRUNCATE TABLE " + table));
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
