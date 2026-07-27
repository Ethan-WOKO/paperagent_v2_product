package com.yanban.api.agent.v2.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCommitted;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionLeaseDisposition;
import io.paperagent.v2.runtime.execution.interruption.composition.DefaultActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.interruption.materialization.DeterministicActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedstepinterruption;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        AuthenticatedAgentTurnFreshExecutionStartComposer.class,
        AuthenticatedAgentTurnStepActivationComposer.class,
        AuthenticatedAgentTurnStepInterruptionComposer.class,
        AuthenticatedAgentTurnStepInterruptionCompositionTest.PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnStepInterruptionCompositionTest {
    @TestConfiguration
    @ComponentScan(basePackageClasses = ProductPlanBootstrapRepositoryAdapter.class)
    static class PersistenceSlice {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean
    private AgentTurnProductContextResolver contexts;

    @Autowired
    private AuthenticatedAgentTurnFreshExecutionStartComposer freshStart;

    @Autowired
    private AuthenticatedAgentTurnStepActivationComposer activation;

    @Autowired
    private AuthenticatedAgentTurnStepInterruptionComposer interruption;

    @Autowired
    private StepInterruptionRepository interruptionRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM agent_v2_step_interruptions");
        jdbc.update("DELETE FROM agent_v2_step_activations");
        jdbc.update("DELETE FROM agent_v2_execution_starts");
        jdbc.update("DELETE FROM agent_v2_plan_leases");
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
    }

    @Test
    void authenticatedRecoveredStepIsInterruptedThroughExactRelationalPort() {
        FreshExecutionStarted started = assertInstanceOf(
                FreshExecutionStarted.class,
                freshStart.start(
                        7L,
                        42L,
                        AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                .command(Optional.of(
                                        AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                                .attempt()))));
        StepActivationCommitted activated = assertInstanceOf(
                StepActivationCommitted.class,
                activation.activate(
                        7L,
                        42L,
                        AuthenticatedAgentTurnStepActivationComposerTest
                                .command()));

        var intent =
                AuthenticatedAgentTurnStepInterruptionComposerTest
                        .command(StepInterruptionKind.PAUSE);
        var command = new AuthenticatedAgentTurnStepInterruptionCommand(
                AuthenticatedAgentTurnStepRecoveryComposerTest.attempt(
                        "synthetic-owner", "synthetic-token"),
                intent.kind(),
                intent.eventDraft(),
                intent.checkpointCreatedAt());
        AuthenticatedAgentTurnStepInterruptionOutcome raw =
                interruption.interrupt(
                        7L,
                        42L,
                        command);
        AuthenticatedAgentTurnStepInterrupted product = assertInstanceOf(
                AuthenticatedAgentTurnStepInterrupted.class,
                raw,
                raw::toString);
        ActiveStepInterruptionCommitted committed = assertInstanceOf(
                ActiveStepInterruptionCommitted.class,
                product.interruption());

        assertEquals(started.persistedStart().planId(), product.planId());
        assertEquals(
                activated.persistedActivation().stepId(),
                committed.persistedInterruption().stepId());
        assertEquals(
                StepInterruptionKind.PAUSE,
                committed.persistedInterruption().kind());
        assertEquals(
                ActiveStepInterruptionLeaseDisposition.RETAINED_FOR_RECOVERY,
                product.leaseDisposition());
        assertEquals(1, rowCount("agent_v2_step_interruptions"));
    }

    @Test
    void productExposesExactlyOneDeterministicComposerBoundToProductRepository() {
        var composers =
                applicationContext.getBeansOfType(
                        ActiveStepInterruptionComposer.class);
        assertEquals(1, composers.size());
        DefaultActiveStepInterruptionComposer core = assertInstanceOf(
                DefaultActiveStepInterruptionComposer.class,
                composers.values().iterator().next());
        assertInstanceOf(
                DeterministicActiveStepInterruptionMaterializer.class,
                fieldValue(core, "materializer"));
        assertSame(
                interruptionRepository,
                fieldValue(core, "repository"));
        assertEquals(
                ActiveStepInterruptionMaterializer.class,
                field(core, "materializer").getType());
        assertEquals(
                StepInterruptionRepository.class,
                field(core, "repository").getType());
    }

    private int rowCount(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private static Object fieldValue(Object target, String fieldName) {
        try {
            Field field = field(target, fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(Object target, String fieldName) {
        try {
            return target.getClass().getDeclaredField(fieldName);
        } catch (NoSuchFieldException failure) {
            throw new AssertionError(failure);
        }
    }
}
