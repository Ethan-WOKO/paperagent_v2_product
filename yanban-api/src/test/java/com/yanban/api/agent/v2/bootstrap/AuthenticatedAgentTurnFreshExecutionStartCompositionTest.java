package com.yanban.api.agent.v2.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanCodec;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.bootstrap.DefaultPersistentPlanBootstrapper;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.DeterministicFreshExecutionGate;
import io.paperagent.v2.runtime.execution.activation.composition.DefaultStepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.composition.DefaultActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.materialization.DeterministicActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultStepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.start.DefaultFreshExecutionStarter;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartOutcome;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedfreshstart;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AuthenticatedAgentTurnFreshExecutionStartComposer.class,
        AuthenticatedBootstrapCompositionTestSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnFreshExecutionStartCompositionTest {
    @MockBean
    private AgentTurnProductContextResolver contexts;

    @Autowired
    private AuthenticatedAgentTurnFreshExecutionStartComposer composer;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM agent_v2_execution_starts");
        jdbc.update("DELETE FROM agent_v2_plan_leases");
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());
    }

    @Test
    void firstAuthenticatedCallStartsOnceAndSecondRequiresRecovery() {
        var command =
                AuthenticatedAgentTurnFreshExecutionStartComposerTest.command(
                        Optional.of(
                                AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                        .attempt()));

        FreshExecutionStartOutcome first =
                composer.start(7L, 42L, command);
        FreshExecutionStartOutcome second =
                composer.start(7L, 42L, command);

        FreshExecutionStarted started =
                assertInstanceOf(FreshExecutionStarted.class, first);
        assertEquals(PersistenceOutcome.APPLIED, started.startOutcome());
        assertEquals("synthetic-owner",
                started.persistedStart().leaseOwnerId());
        assertEquals(1, started.persistedStart().fencingToken());
        assertEquals("synthetic-start-event",
                started.persistedStart().startEvent().id().value());
        assertEquals(2,
                started.persistedStart().startedCheckpoint().version());

        FreshExecutionRecoveryRequired recovery =
                assertInstanceOf(FreshExecutionRecoveryRequired.class, second);
        assertEquals(started.persistedStart().planId(), recovery.planId());
        assertEquals(1, rowCount("agent_v2_plan_bootstraps"));
        assertEquals(1, rowCount("agent_v2_plan_leases"));
        assertEquals(1, rowCount("agent_v2_execution_starts"));
    }

    @Test
    void ownershipFailureWritesNothing() {
        AgentTurnProductContextResolutionException failure =
                new AgentTurnProductContextResolutionException(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                        "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.start(
                        7L,
                        404L,
                        AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                .command(Optional.empty()))));
        assertEquals(0, rowCount("agent_v2_plan_bootstraps"));
        assertEquals(0, rowCount("agent_v2_plan_leases"));
        assertEquals(0, rowCount("agent_v2_execution_starts"));
    }

    @Test
    void productExposesOneDeterministicRelationalFreshStarter() {
        var starters =
                applicationContext.getBeansOfType(FreshExecutionStarter.class);
        assertEquals(1, starters.size());
        DefaultFreshExecutionStarter starter = assertInstanceOf(
                DefaultFreshExecutionStarter.class,
                starters.values().iterator().next());
        Set<Class<?>> fieldTypes = Arrays.stream(
                        starter.getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                        io.paperagent.v2.runtime.execution.FreshExecutionGate.class,
                        io.paperagent.v2.runtime.execution
                                .ExecutionStartMaterializer.class,
                        LeaseRepository.class,
                        ExecutionStartRepository.class),
                fieldTypes);
        assertInstanceOf(
                DeterministicFreshExecutionGate.class,
                fieldValue(starter, "freshExecutionGate"));
        assertInstanceOf(
                DeterministicExecutionStartMaterializer.class,
                fieldValue(starter, "executionStartMaterializer"));
    }

    private int rowCount(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private static Object fieldValue(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static VerifiedAgentTurnProductContext workspaceContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null),
                Optional.empty());
    }
}

@TestConfiguration
@ComponentScan(
        basePackageClasses = ProductPlanBootstrapRepositoryAdapter.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.yanban\\.api\\.agent\\.v2\\.persistence\\."
                        + "(ProductPlanBootstrap(RepositoryAdapter|Transactions|Codec)"
                        + "|ProductLease(RepositoryAdapter|Transactions)"
                        + "|SystemProductLeaseTimeSource"
                        + "|ProductExecutionStart(RepositoryAdapter|Transactions|"
                        + "RecoveryRepositoryAdapter|RecoveryTransactions|Codec)"
                        + "|SystemProductExecutionStartTimeSource"
                        + "|ProductStepActivation(RepositoryAdapter|Transactions|Codec)"
                        + "|ProductStepRecovery(RepositoryAdapter|Transactions)"
                        + "|ProductStepInterruption(RepositoryAdapter|Transactions|"
                        + "MarkerReader|Codec)"
                        + "|ProductPlanExecutionContextCodec"
                        + "|ProductStepCompletion(MarkerReader|Codec)"
                        + "|ProductEffectOutcome(MarkerReader|Codec)"
                        + "|ProductEffectIntentCodec"
                        + "|ProductReceipt(MarkerReader|Codec)"
                        + "|ProductActiveStepReplan(MarkerReader|Codec))"))
@Import({ProductPlanReplanCodec.class, ProductPlanReplanMarkerReader.class})
class AuthenticatedBootstrapCompositionTestSlice {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    ProductPlanIdDerivation productPlanIdDerivation() {
        return new ProductPlanIdDerivation();
    }

    @Bean
    ProductPersistentPlanBootstrapRequestAdapter productPersistentPlanBootstrapRequestAdapter(
            ProductPlanIdDerivation planIds) {
        return new ProductPersistentPlanBootstrapRequestAdapter(planIds);
    }

    @Bean
    PersistentPlanBootstrapper persistentPlanBootstrapper(
            PlanBootstrapRepository repository) {
        return new DefaultPersistentPlanBootstrapper(
                new DeterministicTaskFrameFreezer(),
                new DeterministicInitialPlanFreezer(),
                new DeterministicInitialCheckpointFreezer(),
                repository);
    }

    @Bean
    FreshExecutionStarter freshExecutionStarter(
            LeaseRepository leases,
            ExecutionStartRepository starts) {
        return new DefaultFreshExecutionStarter(
                new DeterministicFreshExecutionGate(),
                new DeterministicExecutionStartMaterializer(),
                leases,
                starts);
    }

    @Bean
    ExecutionStartRecoverer executionStartRecoverer(
            io.paperagent.v2.persistence.ExecutionStartRecoveryRepository recovery,
            LeaseRepository leases,
            ExecutionStartRepository starts) {
        return new DefaultExecutionStartRecoverer(
                recovery,
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                leases,
                starts);
    }

    @Bean
    StepActivationComposer stepActivationComposer(
            LeaseRepository leases,
            StepActivationRepository activations) {
        return new DefaultStepActivationComposer(
                new DeterministicCommittedStepActivationMaterializer(),
                leases,
                activations);
    }

    @Bean
    StepRecoverer stepRecoverer(
            StepRecoveryRepository recovery,
            LeaseRepository leases) {
        return new DefaultStepRecoverer(recovery, leases);
    }

    @Bean
    ActiveStepInterruptionComposer activeStepInterruptionComposer(
            StepInterruptionRepository interruptions) {
        return new DefaultActiveStepInterruptionComposer(
                new DeterministicActiveStepInterruptionMaterializer(),
                interruptions);
    }
}
