package com.yanban.api.agent.v2.bootstrap;

import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryResolution;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredExecutionStart;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
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
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedrecovery;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AuthenticatedAgentTurnPlanBootstrapComposer.class,
        AuthenticatedAgentTurnExecutionStartRecoveryComposer.class,
        AuthenticatedBootstrapCompositionTestSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnExecutionStartRecoveryCompositionTest {
    @MockBean
    private AgentTurnProductContextResolver contexts;

    @Autowired
    private AuthenticatedAgentTurnPlanBootstrapComposer bootstrapComposer;

    @Autowired
    private AuthenticatedAgentTurnExecutionStartRecoveryComposer composer;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM agent_v2_execution_starts");
        jdbc.update("DELETE FROM agent_v2_plan_leases");
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
    }

    @Test
    void readyRecoveryStartsOnceThenCommittedRecoveryOnlyObserves() {
        var bootstrap = bootstrapComposer.bootstrap(
                7L,
                42L,
                AuthenticatedAgentTurnPlanBootstrapComposerTest.command());
        assertEquals(PersistenceOutcome.APPLIED, bootstrap.outcome());

        RecoveredExecutionStart started = assertInstanceOf(
                RecoveredExecutionStart.class,
                composer.recover(
                        7L,
                        42L,
                        AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                                .command(Optional.of(
                                        AuthenticatedAgentTurnFreshExecutionStartComposerTest
                                                .attempt()))));
        assertEquals(
                ExecutionStartRecoveryResolution.ATOMIC_START_APPLIED,
                started.resolution());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                started.leaseDisposition());
        assertEquals("synthetic-owner",
                started.persistedStart().leaseOwnerId());
        assertEquals(1, started.persistedStart().fencingToken());
        assertEquals("synthetic-start-event",
                started.persistedStart().startEvent().id().value());
        assertEquals(2,
                started.persistedStart().startedCheckpoint().version());

        RecoveredExecutionStart observed = assertInstanceOf(
                RecoveredExecutionStart.class,
                composer.recover(
                        7L,
                        42L,
                        AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                                .command(Optional.empty())));
        assertEquals(
                ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                observed.resolution());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                observed.leaseDisposition());
        assertEquals(started.persistedStart(), observed.persistedStart());
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
                () -> composer.recover(
                        7L,
                        404L,
                        AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                                .command(Optional.empty()))));
        assertEquals(0, rowCount("agent_v2_plan_bootstraps"));
        assertEquals(0, rowCount("agent_v2_plan_leases"));
        assertEquals(0, rowCount("agent_v2_execution_starts"));
    }

    @Test
    void productExposesOneDeterministicRelationalRecoverer() {
        var recoverers =
                applicationContext.getBeansOfType(ExecutionStartRecoverer.class);
        assertEquals(1, recoverers.size());
        DefaultExecutionStartRecoverer recoverer = assertInstanceOf(
                DefaultExecutionStartRecoverer.class,
                recoverers.values().iterator().next());
        Set<Class<?>> fieldTypes = Arrays.stream(
                        recoverer.getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                        ExecutionStartRecoveryRepository.class,
                        io.paperagent.v2.runtime.execution.recovery
                                .materialization
                                .RecoveryReadyExecutionStartMaterializer.class,
                        LeaseRepository.class,
                        ExecutionStartRepository.class),
                fieldTypes);
        assertInstanceOf(
                DeterministicRecoveryReadyExecutionStartMaterializer.class,
                fieldValue(recoverer, "materializer"));
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
