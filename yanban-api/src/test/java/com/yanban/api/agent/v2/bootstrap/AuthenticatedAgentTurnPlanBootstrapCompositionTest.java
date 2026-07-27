package com.yanban.api.agent.v2.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapValidationCode;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapValidationException;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedbootstrap;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        AuthenticatedAgentTurnPlanBootstrapComposer.class,
        AuthenticatedAgentTurnPlanBootstrapCompositionTest.PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnPlanBootstrapCompositionTest {
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
    private AuthenticatedAgentTurnPlanBootstrapComposer composer;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());
    }

    @Test
    void identicalAuthenticatedTurnAppliesThenReplaysOneAtomicTuple() {
        ProductPersistentPlanBootstrapCommand command =
                AuthenticatedAgentTurnPlanBootstrapComposerTest.command();

        var applied = composer.bootstrap(7L, 42L, command);
        var replayed = composer.bootstrap(7L, 42L, command);

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(applied.value(), replayed.value());
        assertEquals(1, rowCount());
        assertEquals(
                "product-plan.c2384435948cc96e3c0f65b75c2bbcc41538416633258f273ddaa1acf41bc0e0",
                applied.value().orElseThrow().plan().id().value());
    }

    @Test
    void directRouteFailsWithExistingTypedValidationBeforeAnyRow() {
        ProductPersistentPlanBootstrapCommand base =
                AuthenticatedAgentTurnPlanBootstrapComposerTest.command();
        RoutingDecision direct = new RoutingDecision(
                new RoutingRequestId("route-direct-42"),
                Route.DIRECT,
                RoutingDecisionReason.DIRECT_ELIGIBLE,
                Set.of());
        ProductPersistentPlanBootstrapCommand command =
                new ProductPersistentPlanBootstrapCommand(
                        direct,
                        base.taskFrameDraft(),
                        base.executionProfile(),
                        base.initialPlanDraft(),
                        base.taskFrameCreatedAt(),
                        base.planCreatedAt(),
                        base.checkpointCreatedAt());

        PersistentPlanBootstrapValidationException failure = assertThrows(
                PersistentPlanBootstrapValidationException.class,
                () -> composer.bootstrap(7L, 42L, command));

        assertEquals(
                PersistentPlanBootstrapValidationCode.ROUTE_NOT_PERSISTENT,
                failure.code());
        assertEquals(
                "persistentPlanBootstrapRequest"
                        + ".taskFrameFreezeRequest.routingDecision.route",
                failure.path());
        assertEquals(0, rowCount());
    }

    private int rowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_v2_plan_bootstraps",
                Integer.class);
    }

    private static VerifiedAgentTurnProductContext workspaceContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null),
                Optional.empty());
    }
}
