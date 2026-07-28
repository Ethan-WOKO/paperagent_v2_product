package com.yanban.api.agent.v2.synthesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.persistence.ProductFinalSynthesisRepositoryAdapter;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.PersistenceOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        ProductFinalSynthesisRepositoryAdapter.class,
        ProductFinalSynthesisRepositoryH2Test.Config.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProductFinalSynthesisRepositoryH2Test {
    @Autowired
    ProductFinalSynthesisRepositoryAdapter repository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void exactReplayAndEightConcurrentCallsPersistOneAuthority() throws Exception {
        FinalSynthesis value = synthesis();
        assertEquals(PersistenceOutcome.APPLIED,
                repository.append(value).outcome());
        assertEquals(PersistenceOutcome.REPLAYED,
                repository.append(value).outcome());

        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PersistenceOutcome>> calls =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index -> (Callable<PersistenceOutcome>)
                                    () -> repository.append(value).outcome())
                            .toList();
            for (var result : executor.invokeAll(calls)) {
                assertEquals(PersistenceOutcome.REPLAYED, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_v2_final_syntheses "
                        + "where plan_id = 'plan-h2'", Long.class));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void projectProvenanceRoundTripsAndMismatchFailsClosed() {
        ProjectVersionRef source = new ProjectVersionRef(
                "42", "version-42");
        WorkspaceDiff diff = new WorkspaceDiff(
                new DiffId("diff-h2"),
                new WorkspaceRef(new WorkspaceId("workspace-h2"), source),
                List.of(), Instant.parse("2026-07-28T00:00:01Z"));
        FinalSynthesis value = new FinalSynthesis(
                new FinalSynthesisId("synthesis-project-h2"),
                new TaskFrameId("task-project-h2"),
                new PlanId("plan-project-h2"),
                new PlanRevisionId("revision-project-h2"),
                Optional.of(source), Optional.of(diff),
                List.of(new ReceiptId("receipt-project-h2")),
                "Read-only Project analysis.",
                Instant.parse("2026-07-28T00:00:02Z"));

        assertEquals(PersistenceOutcome.APPLIED,
                repository.append(value).outcome());
        assertEquals(value,
                repository.find(value.planId()).value().orElseThrow());
        jdbc.update("update agent_v2_final_syntheses "
                + "set source_project_version_id = null "
                + "where plan_id = ?", value.planId().value());
        assertThrows(IllegalStateException.class,
                () -> repository.find(value.planId()));
    }

    private static FinalSynthesis synthesis() {
        return new FinalSynthesis(
                new FinalSynthesisId("synthesis-h2"),
                new TaskFrameId("task-h2"),
                new PlanId("plan-h2"),
                new PlanRevisionId("revision-h2"),
                Optional.empty(), Optional.empty(),
                List.of(new ReceiptId("receipt-h2")),
                "Literature search task queued.",
                Instant.parse("2026-07-28T00:00:00Z"));
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
