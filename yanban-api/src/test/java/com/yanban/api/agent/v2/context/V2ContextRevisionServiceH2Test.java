package com.yanban.api.agent.v2.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:v2_context_revision_service;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class V2ContextRevisionServiceH2Test {
    @Autowired V2ContextRevisionService service;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentTurnRepository turns;
    @Autowired JdbcTemplate jdbc;

    @Test
    void appliesReplaysChainsAndRejectsBothUniqueConflicts() {
        Authority authority = authority();
        V2ContextRevisionDraft first = draft(authority, 1, null, null,
                "planner:1", "{\"goal\":\"review\"}");

        V2ContextRevisionSnapshot applied = service.append(first);
        V2ContextRevisionSnapshot replayed = service.append(first);

        assertThat(applied.outcome()).isEqualTo(V2ContextRevisionOutcome.APPLIED);
        assertThat(replayed.outcome()).isEqualTo(V2ContextRevisionOutcome.REPLAYED);
        assertThat(replayed.id()).isEqualTo(applied.id());
        assertThat(replayed.canonicalJson()).isEqualTo(applied.canonicalJson());
        assertThatThrownBy(() -> service.append(draft(
                authority, 1, null, null, "planner:1",
                "{\"goal\":\"different\"}")))
                .isInstanceOf(V2ContextRevisionConflictException.class)
                .hasMessageContaining("stable stage key");
        assertThatThrownBy(() -> service.append(draft(
                authority, 1, null, null, "planner:other",
                "{\"goal\":\"review\"}")))
                .isInstanceOf(V2ContextRevisionConflictException.class)
                .hasMessageContaining("revision number");

        V2ContextRevisionSnapshot second = service.append(draft(
                authority, 2, applied.id(), applied.contextDigest(),
                "step:1", "{\"step\":1}"));
        assertThat(second.revision().parentSnapshotId()).isEqualTo(applied.id());
        assertThat(second.revision().revisionNumber()).isEqualTo(2);
        assertThat(service.find(authority.userId() + 1000,
                authority.sessionId(), authority.turnId(), "step:1"))
                .isEmpty();
        assertThatThrownBy(() -> service.append(new V2ContextRevisionDraft(
                authority.userId() + 1000, authority.sessionId(),
                authority.turnId(), 3, second.id(), second.contextDigest(),
                V2ContextStage.REFLECTION, "reflection:1",
                V2ContextRevisionStatus.READY,
                "deepseek", "deepseek-v4-flash", 1_000_000, 384_000,
                "utf8-byte-v1", "layered-v1", 20, 50_000,
                List.of(section("{\"result\":\"ok\"}")))))
                .isInstanceOf(V2ContextRevisionConflictException.class)
                .hasMessageContaining("turn authority");
    }

    @Test
    void concurrentSameStageHasOneAppliedWinnerAndOneFreshReplay() throws Exception {
        Authority authority = authority();
        V2ContextRevisionDraft request = draft(
                authority, 1, null, null, "planner:race",
                "{\"goal\":\"race\"}");
        CountDownLatch start = new CountDownLatch(1);
        Callable<V2ContextRevisionSnapshot> append = () -> {
            start.await();
            return service.append(request);
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var left = pool.submit(append);
            var right = pool.submit(append);
            start.countDown();
            List<V2ContextRevisionSnapshot> results = List.of(
                    left.get(), right.get());
            assertThat(results).extracting(V2ContextRevisionSnapshot::outcome)
                    .containsExactlyInAnyOrder(
                            V2ContextRevisionOutcome.APPLIED,
                            V2ContextRevisionOutcome.REPLAYED);
            assertThat(results).extracting(V2ContextRevisionSnapshot::id)
                    .containsOnly(results.get(0).id());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentStageKeysCompetingForRevisionHaveOneConflict()
            throws Exception {
        Authority authority = authority();
        V2ContextRevisionDraft leftRequest = draft(
                authority, 1, null, null, "planner:left",
                "{\"goal\":\"left\"}");
        V2ContextRevisionDraft rightRequest = draft(
                authority, 1, null, null, "planner:right",
                "{\"goal\":\"right\"}");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var left = pool.submit(() -> {
                start.await();
                return service.append(leftRequest);
            });
            var right = pool.submit(() -> {
                start.await();
                return service.append(rightRequest);
            });
            start.countDown();
            int applied = 0;
            int conflicts = 0;
            for (var future : List.of(left, right)) {
                try {
                    V2ContextRevisionSnapshot value = future.get();
                    if (value.outcome() == V2ContextRevisionOutcome.APPLIED) {
                        applied++;
                    }
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertThat(failure.getCause())
                            .isInstanceOf(V2ContextRevisionConflictException.class);
                    conflicts++;
                }
            }
            assertThat(applied).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_context_snapshots WHERE turn_id=?",
                    Integer.class, authority.turnId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Authority authority() {
        String username = "context-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sys_users(username,password_hash) VALUES (?,?)",
                username, "hash");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username=?", Long.class,
                username);
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                userId, "context", "deepseek", "deepseek-v4-flash",
                20, false));
        AgentTurn turn = turns.saveAndFlush(new AgentTurn(
                session.getId(), userId, null));
        return new Authority(userId, session.getId(), turn.getId());
    }

    private static V2ContextRevisionDraft draft(
            Authority authority, int revision, Long parentId,
            String parentDigest, String key, String projection) {
        return new V2ContextRevisionDraft(
                authority.userId(), authority.sessionId(), authority.turnId(),
                revision, parentId, parentDigest,
                revision == 1 ? V2ContextStage.PLANNER
                        : V2ContextStage.STEP_DECISION,
                key, V2ContextRevisionStatus.READY,
                "deepseek", "deepseek-v4-flash", 1_000_000, 384_000,
                "utf8-byte-v1", "layered-v1", 20, 50_000,
                List.of(section(projection)));
    }

    private static V2ContextSectionDraft section(String projection) {
        return new V2ContextSectionDraft(
                ContextSectionType.CORE_AUTHORITY, 10, 100_000,
                20, 20, V2ContextSectionStatus.READY,
                "[{\"kind\":\"taskFrame\",\"id\":\"tf-1\"}]",
                projection, null);
    }

    private record Authority(Long userId, Long sessionId, Long turnId) { }
}
