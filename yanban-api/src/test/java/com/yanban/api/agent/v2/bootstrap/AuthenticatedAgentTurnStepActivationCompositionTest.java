package com.yanban.api.agent.v2.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.runtime.execution.activation.composition.DefaultStepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicReadyStepActivationMaterializer;
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
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedactivation;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        AuthenticatedAgentTurnPlanBootstrapComposer.class,
        AuthenticatedAgentTurnFreshExecutionStartComposer.class,
        AuthenticatedAgentTurnStepActivationComposer.class,
        AuthenticatedAgentTurnStepActivationCompositionTest.PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnStepActivationCompositionTest {
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
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM agent_v2_step_activations");
        jdbc.update("DELETE FROM agent_v2_execution_starts");
        jdbc.update("DELETE FROM agent_v2_plan_leases");
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
    }

    @Test
    void committedH0ActivatesExactlyOneFirstStepAndReplays() {
        FreshExecutionStarted started = assertInstanceOf(
                FreshExecutionStarted.class,
                freshStart.start(
                        7L,
                        42L,
                        AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                .command(Optional.of(
                                        AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                                .attempt()))));

        StepActivationCommitted first = assertInstanceOf(
                StepActivationCommitted.class,
                activation.activate(
                        7L, 42L,
                        AuthenticatedAgentTurnStepActivationComposerTest
                                .command()));
        StepActivationCommitted replay = assertInstanceOf(
                StepActivationCommitted.class,
                activation.activate(
                        7L, 42L,
                        AuthenticatedAgentTurnStepActivationComposerTest
                                .command()));

        assertEquals(PersistenceOutcome.APPLIED, first.activationOutcome());
        assertEquals(PersistenceOutcome.REPLAYED, replay.activationOutcome());
        assertEquals(started.persistedStart().planId(), first.planId());
        assertEquals("step-1", first.persistedActivation().stepId().value());
        assertEquals("synthetic-activation-event",
                first.persistedActivation().activationEvent().id().value());
        assertEquals(3,
                first.persistedActivation().activatedCheckpoint().version());
        assertEquals(first.persistedActivation(), replay.persistedActivation());
        assertEquals(1, rowCount("agent_v2_plan_bootstraps"));
        assertEquals(1, rowCount("agent_v2_plan_leases"));
        assertEquals(1, rowCount("agent_v2_execution_starts"));
        assertEquals(1, rowCount("agent_v2_step_activations"));
    }

    @Test
    void productExposesOneDeterministicRelationalActivationComposer() {
        var composers =
                applicationContext.getBeansOfType(StepActivationComposer.class);
        assertEquals(1, composers.size());
        DefaultStepActivationComposer composer = assertInstanceOf(
                DefaultStepActivationComposer.class,
                composers.values().iterator().next());
        Set<Class<?>> fieldTypes = Arrays.stream(
                        composer.getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                        CommittedStepActivationMaterializer.class,
                        DeterministicReadyStepActivationMaterializer.class,
                        LeaseRepository.class,
                        StepActivationRepository.class),
                fieldTypes);
        assertInstanceOf(
                DeterministicCommittedStepActivationMaterializer.class,
                fieldValue(composer, "materializer"));
        assertInstanceOf(
                DeterministicReadyStepActivationMaterializer.class,
                fieldValue(composer, "readyMaterializer"));
        assertInstanceOf(
                LeaseRepository.class,
                fieldValue(composer, "leaseRepository"));
        assertInstanceOf(
                StepActivationRepository.class,
                fieldValue(composer, "stepActivationRepository"));
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
}
