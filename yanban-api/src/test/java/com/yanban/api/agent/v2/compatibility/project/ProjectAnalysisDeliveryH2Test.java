package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
        ProjectAnalysisDeliveryTransactions.class,
        ProjectAnalysisDeliveryH2Test.Config.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectAnalysisDeliveryH2Test {
    @Autowired
    ProjectAnalysisDeliveryTransactions deliveries;
    @Autowired
    AgentSessionRepository sessions;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requestAndExactStepAuthoritiesReplayWithoutDuplicateFacts() {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "project", "test", "test", 8, true,
                AgentSessionScope.PROJECT, 8L));
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        var first = deliveries.open(
                7L, 8L, session.getId(), "request", "a".repeat(64),
                "objective", List.of("paper.md"), null, 10, "version",
                "owner", "token", now.plusSeconds(60));
        var replay = deliveries.open(
                7L, 8L, session.getId(), "request", "a".repeat(64),
                "objective", List.of("paper.md"), null, 10, "version",
                "owner", "token", now.plusSeconds(60));
        assertEquals(first.turnId(), replay.turnId());

        String arguments = "{\"path\":\"paper.md\"}";
        var authority = new ProjectAnalysisDeliveryTransactions.StepAuthority(
                "project-read-01", "project.read",
                arguments, "b".repeat(64));
        deliveries.bindPlanAndSteps(
                first.id(), "plan-project", List.of(authority));
        deliveries.bindPlanAndSteps(
                first.id(), "plan-project", List.of(authority));
        assertEquals("project.read",
                deliveries.authority(
                        "plan-project", "project-read-01").effectKind());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from "
                        + "agent_v2_project_analysis_deliveries",
                Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_v2_project_analysis_steps",
                Long.class));

        assertThrows(IllegalArgumentException.class, () -> deliveries.open(
                7L, 8L, session.getId(), "request", "c".repeat(64),
                "changed", List.of("paper.md"), null, 10, "version",
                "owner", "token", now.plusSeconds(60)));
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
