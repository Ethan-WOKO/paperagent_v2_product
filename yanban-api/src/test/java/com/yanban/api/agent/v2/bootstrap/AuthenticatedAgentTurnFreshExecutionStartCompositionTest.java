package com.yanban.api.agent.v2.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.DeterministicFreshExecutionGate;
import io.paperagent.v2.runtime.execution.start.DefaultFreshExecutionStarter;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartOutcome;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
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
        AgentV2PlanBootstrapConfiguration.class,
        AuthenticatedAgentTurnFreshExecutionStartComposer.class,
        AuthenticatedAgentTurnFreshExecutionStartCompositionTest
                .PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnFreshExecutionStartCompositionTest {
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
