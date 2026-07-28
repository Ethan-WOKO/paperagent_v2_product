package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({ProjectCandidateDeliveryTransactions.class, ProjectCandidateDeliveryH2Test.Config.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectCandidateDeliveryH2Test {
    @Autowired ProjectCandidateDeliveryTransactions deliveries;
    @Autowired AgentSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void exactRequestPlanAndAuthoritiesReplayButChangedPayloadConflicts() {
        var value = open("request");
        var replay = deliveries.open(7L, 8L, value.id().sessionId(), "request",
                "a".repeat(64), "objective", List.of("README.md"), "version",
                "owner", "token", Instant.now().plusSeconds(60));
        assertEquals(value.turnId(), replay.turnId());
        var authority = new ProjectCandidateDeliveryTransactions.StepAuthority(
                "project-candidate-compose", "project.candidate.compose",
                "{\"operation\":\"compose\"}", "b".repeat(64));
        deliveries.bindPlanAndSteps(value.id(), "plan", List.of(authority));
        deliveries.bindPlanAndSteps(value.id(), "plan", List.of(authority));
        assertEquals("project.candidate.compose",
                deliveries.authority("plan", "project-candidate-compose").kind());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_v2_project_candidate_deliveries "
                        + "where client_request_id = 'request'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_v2_project_candidate_steps where plan_id = 'plan'",
                Long.class));
        assertThrows(IllegalArgumentException.class, () -> deliveries.open(
                7L, 8L, value.id().sessionId(), "request", "c".repeat(64),
                "changed", List.of("README.md"), "version",
                "owner", "token", Instant.now().plusSeconds(60)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDeliveryCreatesExactlyOneAssistantMessage() throws Exception {
        var value = open("deliver");
        deliveries.bindPlanAndSteps(value.id(), "plan-deliver", List.of());
        deliveries.bindCandidate("plan-deliver", 42L, "c".repeat(64), "d".repeat(64));
        var executor = Executors.newFixedThreadPool(8);
        try {
            var calls = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<ProjectCandidateDeliveryEntity>)
                            () -> deliveries.deliver(value.id())).toList();
            Long message = null;
            for (var future : executor.invokeAll(calls)) {
                var delivered = future.get();
                assertEquals("SUCCEEDED", delivered.status());
                if (message == null) message = delivered.assistantMessageId();
                assertEquals(message, delivered.assistantMessageId());
            }
        } finally { executor.shutdownNow(); }
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_messages where session_id = ? and role = 'assistant'",
                Long.class, value.id().sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void durableFailureIsTerminalAndCreatesNoCandidateOrAssistant() {
        var value = open("failed");
        var failed = deliveries.fail(value.id(), "PROJECT_CANDIDATE_FAILED");
        var replay = deliveries.fail(value.id(), "PROJECT_CANDIDATE_FAILED");
        assertEquals("FAILED", failed.status());
        assertEquals("FAILED", replay.status());
        assertNull(failed.artifactId());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from agent_messages where session_id = ? and role = 'assistant'",
                Long.class, value.id().sessionId()));
    }

    private ProjectCandidateDeliveryEntity open(String request) {
        var session = sessions.saveAndFlush(new AgentSession(
                7L, "project", "test", "test", 8, true,
                AgentSessionScope.PROJECT, 8L));
        return deliveries.open(7L, 8L, session.getId(), request, "a".repeat(64),
                "objective", List.of("README.md"), "version",
                "owner", "token", Instant.now().plusSeconds(60));
    }
    @TestConfiguration static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
