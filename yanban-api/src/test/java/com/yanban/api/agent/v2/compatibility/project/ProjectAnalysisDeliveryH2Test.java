package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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
                        + "agent_v2_project_analysis_deliveries "
                        + "where client_request_id = 'request'",
                Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_v2_project_analysis_steps",
                Long.class));

        assertThrows(IllegalArgumentException.class, () -> deliveries.open(
                7L, 8L, session.getId(), "request", "c".repeat(64),
                "changed", List.of("paper.md"), null, 10, "version",
                "owner", "token", now.plusSeconds(60)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eightConcurrentDeliveriesCreateOneAssistantAndReplay() throws Exception {
        var opened = open("deliver", Instant.now().plusSeconds(60));
        deliveries.bindPlanAndSteps(opened.id(), "plan-deliver", List.of());
        insertSynthesis("synthesis-deliver", "plan-deliver");
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ProjectAnalysisDeliveryEntity>> calls =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index ->
                                    (Callable<ProjectAnalysisDeliveryEntity>)
                                            () -> deliveries.deliver(
                                                    opened.id(),
                                                    "plan-deliver",
                                                    "synthesis-deliver",
                                                    "analysis"))
                            .toList();
            Long messageId = null;
            for (var future : executor.invokeAll(calls)) {
                var value = future.get();
                assertEquals("SUCCEEDED", value.status());
                if (messageId == null) messageId =
                        value.assistantMessageId();
                assertEquals(messageId, value.assistantMessageId());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_messages "
                        + "where session_id = ? and role = 'assistant'",
                Long.class, opened.id().sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedAndExpiredDeliveriesAreStableAndRecoverable() {
        Instant expired = Instant.now().minusSeconds(1);
        var opened = open("failed", expired);
        var rotated = deliveries.rotateExpiredLease(
                opened.id(), "recovery-token",
                Instant.now().plusSeconds(60), Instant.now());
        assertEquals("recovery-token", rotated.leaseToken());
        var failed = deliveries.fail(
                opened.id(), "PROJECT_ANALYSIS_FAILED");
        var replay = deliveries.fail(
                opened.id(), "PROJECT_ANALYSIS_FAILED");
        assertEquals("FAILED", failed.status());
        assertEquals("PROJECT_ANALYSIS_FAILED", failed.errorCode());
        assertEquals("FAILED", replay.status());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from agent_messages "
                        + "where session_id = ? and role = 'assistant'",
                Long.class, opened.id().sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void successAndFailureContendersConvergeOnOneTerminalState()
            throws Exception {
        var opened = open("contenders", Instant.now().plusSeconds(60));
        deliveries.bindPlanAndSteps(
                opened.id(), "plan-contenders", List.of());
        insertSynthesis("synthesis-contenders", "plan-contenders");
        var executor = Executors.newFixedThreadPool(2);
        try {
            executor.invokeAll(List.of(
                    () -> deliveries.deliver(
                            opened.id(), "plan-contenders",
                            "synthesis-contenders", "analysis"),
                    () -> deliveries.fail(
                            opened.id(), "PROJECT_ANALYSIS_FAILED")))
                    .forEach(future -> {
                        try {
                            future.get();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    });
        } finally {
            executor.shutdownNow();
        }
        var terminal = deliveries.find(opened.id());
        assertTrue("SUCCEEDED".equals(terminal.status())
                || "FAILED".equals(terminal.status()));
        assertEquals(terminal.status(),
                deliveries.fail(
                        opened.id(), "PROJECT_ANALYSIS_FAILED").status());
        assertEquals(terminal.status(),
                deliveries.deliver(
                        opened.id(), "plan-contenders",
                        "synthesis-contenders", "analysis").status());
        assertTrue(jdbc.queryForObject(
                "select count(*) from agent_messages "
                        + "where session_id = ? and role = 'assistant'",
                Long.class, opened.id().sessionId()) <= 1L);
    }

    private ProjectAnalysisDeliveryEntity open(
            String requestId, Instant expiresAt) {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "project", "test", "test", 8, true,
                AgentSessionScope.PROJECT, 8L));
        return deliveries.open(
                7L, 8L, session.getId(), requestId, "a".repeat(64),
                "objective", List.of("paper.md"), null, 10, "version",
                "owner", "token", expiresAt);
    }

    private void insertSynthesis(String id, String planId) {
        jdbc.update("""
                insert into agent_v2_final_syntheses (
                  plan_id, synthesis_id, task_frame_id, plan_revision_id,
                  receipt_ids_json, narrative, observed_at,
                  canonical_sha256, committed_at
                ) values (?, ?, 'task', 'revision', '[]', 'analysis',
                  current_timestamp, ?, current_timestamp)
                """, planId, id, "a".repeat(64));
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
