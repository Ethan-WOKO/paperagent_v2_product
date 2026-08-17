package com.yanban.api.agent.v2.bootstrap;

import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultStepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
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
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedsteprecovery;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AuthenticatedAgentTurnFreshExecutionStartComposer.class,
        AuthenticatedAgentTurnStepActivationComposer.class,
        AuthenticatedAgentTurnStepRecoveryComposer.class,
        AuthenticatedBootstrapCompositionTestSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnStepRecoveryCompositionTest {
    @MockBean
    private AgentTurnProductContextResolver contexts;

    @Autowired
    private AuthenticatedAgentTurnFreshExecutionStartComposer freshStart;

    @Autowired
    private AuthenticatedAgentTurnStepActivationComposer activation;

    @Autowired
    private AuthenticatedAgentTurnStepRecoveryComposer recovery;

    @Autowired
    private StepRecoveryRepository recoveryRepository;

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
    void activeSnapshotAcquiresReplaysAndReturnsExactV3WithRetainedLease() {
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
                        7L, 42L,
                        AuthenticatedAgentTurnStepActivationComposerTest
                                .command()));

        PersistedStepRecoveryActive initial = assertInstanceOf(
                PersistedStepRecoveryActive.class,
                recoveryRepository.inspect(started.persistedStart().planId())
                        .value().orElseThrow());
        RecoveredActiveStep recovered = assertInstanceOf(
                RecoveredActiveStep.class,
                recovery.recover(
                        7L, 42L,
                        AuthenticatedAgentTurnStepRecoveryComposerTest
                                .command()));

        PersistedStepRecoveryActive post = assertInstanceOf(
                PersistedStepRecoveryActive.class,
                recoveryRepository.inspect(started.persistedStart().planId())
                        .value().orElseThrow());
        assertEquals(initial, recovered.recovery());
        assertEquals(initial, post);
        assertEquals(started.persistedStart().planId(), recovered.planId());
        assertEquals(
                activated.persistedActivation(),
                recovered.recovery().activation());
        assertEquals(3, recovered.recovery().checkpoint().version());
        assertEquals("synthetic-activation-event",
                recovered.recovery().activation()
                        .activationEvent().id().value());
        assertEquals("synthetic-owner", recovered.lease().ownerId());
        assertEquals("synthetic-token", recovered.lease().leaseToken());
        assertEquals(1, recovered.lease().fencingToken());
        assertEquals(
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                recovered.leaseDisposition());
        assertEquals(1, rowCount("agent_v2_plan_bootstraps"));
        assertEquals(1, rowCount("agent_v2_execution_starts"));
        assertEquals(1, rowCount("agent_v2_step_activations"));
        assertEquals(1, rowCount("agent_v2_plan_leases"));
    }

    @Test
    void productExposesOneRelationalStepRecoverer() {
        var recoverers =
                applicationContext.getBeansOfType(StepRecoverer.class);
        assertEquals(1, recoverers.size());
        DefaultStepRecoverer recoverer = assertInstanceOf(
                DefaultStepRecoverer.class,
                recoverers.values().iterator().next());
        Set<Class<?>> fieldTypes = Arrays.stream(
                        recoverer.getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toSet());
        assertEquals(
                Set.of(StepRecoveryRepository.class, LeaseRepository.class),
                fieldTypes);
        assertInstanceOf(
                StepRecoveryRepository.class,
                fieldValue(recoverer, "stepRecoveryRepository"));
        assertInstanceOf(
                LeaseRepository.class,
                fieldValue(recoverer, "leaseRepository"));
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
