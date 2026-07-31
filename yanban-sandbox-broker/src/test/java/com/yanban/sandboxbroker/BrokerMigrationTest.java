package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxDispatch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"yanban.broker.enabled=true","yanban.broker.mode=LOCAL_ACCEPTANCE","yanban.broker.bind-address=127.0.0.1","yanban.broker.remote-access=false","yanban.broker.sbx-executable=C:/test/sbx.exe","yanban.broker.workspace-root=C:/test/work","yanban.broker.provider-home=C:/test/sbx-home","yanban.broker.provider-config-home=C:/test/sbx-home/config","yanban.broker.provider-data-home=C:/test/sbx-home/data","yanban.broker.provider-state-home=C:/test/sbx-home/state","yanban.broker.bearer-token=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","spring.datasource.url=jdbc:h2:mem:broker;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=true","spring.flyway.locations=classpath:db/migration"})
class BrokerMigrationTest {
    @Autowired DataSource dataSource;
    @Autowired SandboxDispatchService dispatches;
    @Autowired SandboxLeaseService leases;
    @Autowired SandboxExecutionRepository executions;
    @BeforeEach void resetExecutions(){executions.deleteAll();}
    @Test void createsDurableExecutionAndGlobalConcurrencySlot(){JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from sandbox_concurrency_slot",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sandbox_executions",Integer.class)).isZero();}

    @Test void durableIdempotencyFenceAndReclaimAreDatabaseBacked() {
        SandboxDispatch request = signed(new SandboxDispatch("durable-key", "", 1, 2, 3, 4, 5, 7,
                "a".repeat(64), "b".repeat(64), Map.of("pom.xml", "x"),
                List.of("mvn", "-o", "test"), 2, 4_294_967_296L, 900_000, 20_971_520, false));
        var first = dispatches.dispatch(request);
        assertThat(dispatches.dispatch(request).executionId()).isEqualTo(first.executionId());

        var lease = leases.claim("worker-a", Duration.ofMinutes(1)).orElseThrow();
        assertThat(lease.executionId()).isEqualTo(first.executionId());
        assertThat(leases.claim("worker-b", Duration.ofMinutes(1))).isEmpty();
        assertThatThrownBy(() -> dispatches.cancel(first.executionId(), 8)).hasMessageContaining("409");
        dispatches.cancel(first.executionId(), 7);
        assertThat(executions.findByExecutionId(first.executionId()).orElseThrow().cancelRequested()).isTrue();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("update sandbox_executions set lease_expires_at=dateadd('SECOND',-1,current_timestamp) where execution_id=?", first.executionId());
        var reclaimed = leases.claim("worker-b", Duration.ofMinutes(1)).orElseThrow();
        assertThat(reclaimed.fence()).isGreaterThan(lease.fence());
        assertThat(reclaimed.recovery()).isTrue();
        assertThatThrownBy(() -> leases.owned(lease)).isInstanceOf(IllegalStateException.class);
    }

    @Test void receiptClockPreservesDatabaseInstantAcrossLocalTimeZones() {
        SandboxDispatch request = signed(new SandboxDispatch(
                "clock-key", "", 1, 2, 3, 4, 10, 14,
                "a".repeat(64), "b".repeat(64),
                Map.of("Main.java", "class Main {}"),
                List.of("java", "Main.java"), 1, 536_870_912L,
                60_000, 1_048_576, false));
        dispatches.dispatch(request);
        var lease = leases.claim(
                "clock-worker", Duration.ofMinutes(1)).orElseThrow();
        Instant before = Instant.now().minusSeconds(2);
        Instant databaseInstant = leases.now(lease);
        Instant after = Instant.now().plusSeconds(2);

        assertThat(databaseInstant).isBetween(before, after);
    }

    @Test void terminalCancelIsNoOpAndSensitivePayloadIsCleared() {
        SandboxDispatch request = signed(new SandboxDispatch("terminal-key", "", 1, 2, 3, 4, 6, 9,
                "a".repeat(64), "b".repeat(64), Map.of("Secret.java", "sensitive-source"),
                List.of("mvn", "-o", "test"), 2, 4_294_967_296L, 900_000, 20_971_520, false));
        var accepted = dispatches.dispatch(request);
        var lease = leases.claim("worker", Duration.ofMinutes(1)).orElseThrow();
        leases.terminal(lease, "FAILED", null, null, "PROVIDER_REJECTED");
        dispatches.cancel(accepted.executionId(), 9);
        var stored = executions.findByExecutionId(accepted.executionId()).orElseThrow();
        assertThat(stored.status()).isEqualTo("FAILED");
        assertThat(stored.requestJson()).isNull();
        assertThat(stored.checkpointJson()).isNull();
        assertThat(dispatches.status(accepted.executionId()).errorCode()).isEqualTo(com.yanban.sandbox.contract.SandboxErrorCode.PROVIDER_REJECTED);
    }

    @Test void concurrentDuplicateDispatchConvergesWithoutRollbackOnlyTransaction() throws Exception {
        SandboxDispatch request = signed(new SandboxDispatch("concurrent-key", "", 1, 2, 3, 4, 7, 11,
                "a".repeat(64), "b".repeat(64), Map.of("pom.xml", "x"), List.of("mvn", "-o", "test"),
                2, 4_294_967_296L, 900_000, 20_971_520, false));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return dispatches.dispatch(request); });
            var b = pool.submit(() -> { start.await(); return dispatches.dispatch(request); });
            start.countDown();
            assertThat(a.get().executionId()).isEqualTo(b.get().executionId());
            assertThat(executions.findByIdempotencyKey("concurrent-key")).isPresent();
        } finally { pool.shutdownNow(); }
    }

    @Test void stagedReceiptIsHiddenUntilCleanupConfirmedTerminal() {
        SandboxDispatch request = signed(new SandboxDispatch("staged-key", "", 1, 2, 3, 4, 8, 12,
                "a".repeat(64), "b".repeat(64), Map.of("pom.xml", "x"), List.of("mvn", "-o", "test"),
                2, 4_294_967_296L, 900_000, 20_971_520, false));
        var accepted=dispatches.dispatch(request);var lease=leases.claim("worker",Duration.ofMinutes(1)).orElseThrow();
        leases.stageReceipt(lease,"d".repeat(64),"{}");leases.transition(lease,"SUCCEEDED_PENDING_CLEANUP","{}");
        assertThat(dispatches.status(accepted.executionId()).receipt()).isNull();
    }

    @Test void stagedReceiptSurvivesLeaseExpiryAndRecoveryClaim() {
        SandboxDispatch request = signed(new SandboxDispatch("staged-recovery-key", "", 1, 2, 3, 4, 9, 13,
                "a".repeat(64), "b".repeat(64), Map.of("Main.java", "class Main {}"), List.of("java", "Main.java"),
                1, 536_870_912L, 60_000, 1_048_576, false));
        var accepted=dispatches.dispatch(request);
        var original=leases.claim("worker-a",Duration.ofMinutes(1)).orElseThrow();
        String digest="e".repeat(64);
        String receipt="{\"status\":\"SUCCEEDED\"}";
        leases.stageReceipt(original,digest,receipt);
        leases.transition(original,"SUCCEEDED_PENDING_CLEANUP","{\"phase\":\"CLEANUP_REQUIRED\"}");

        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        jdbc.update("update sandbox_executions set lease_expires_at=dateadd('SECOND',-1,current_timestamp) where execution_id=?",
                accepted.executionId());
        var recovered=leases.claim("worker-b",Duration.ofMinutes(1)).orElseThrow();
        var stored=executions.findByExecutionId(accepted.executionId()).orElseThrow();

        assertThat(recovered.recovery()).isTrue();
        assertThat(recovered.previousStatus()).isEqualTo("SUCCEEDED_PENDING_CLEANUP");
        assertThat(stored.receiptDigest()).isEqualTo(digest);
        assertThat(stored.receiptJson()).isEqualTo(receipt);
    }

    private SandboxDispatch signed(SandboxDispatch value) {
        String digest = SandboxCanonicalDigest.compute(value);
        return new SandboxDispatch(value.idempotencyKey(), digest, value.userId(), value.projectId(), value.sessionId(),
                value.planId(), value.stepId(), value.fence(), value.projectVersion(), value.policyDigest(), value.files(),
                value.argv(), value.cpus(), value.memoryBytes(), value.timeoutMillis(), value.maxOutputBytes(), value.networkEnabled());
    }
}
