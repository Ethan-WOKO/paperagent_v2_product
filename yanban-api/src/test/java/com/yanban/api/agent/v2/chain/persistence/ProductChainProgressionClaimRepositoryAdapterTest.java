package com.yanban.api.agent.v2.chain.persistence;

import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.AcquireStatus.ACQUIRED;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.AcquireStatus.ACTIVE_CLAIM;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.AcquireStatus.STOPPED;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.CurrentResult.CURRENT;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.CurrentResult.EXPIRED_CLAIM;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.FailureDisposition.BLOCKED;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.FailureDisposition.RETRY;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.ReleaseResult.RELEASED;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.ReleaseResult.STALE_CLAIM;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.RenewStatus.RENEWED;
import static com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.RenewStatus.REPLAYED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.AcquireResult;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.AcquireStatus;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.CommittedTaskPage;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.CurrentResult;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.ReleaseResult;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.RenewResult;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore.RenewStatus;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProductChainProgressionClaimRepositoryAdapterTest {
    private static final Instant NOW =
            Instant.parse("2026-08-08T08:00:00.123456789Z");
    private static final Instant CANONICAL_NOW =
            Instant.parse("2026-08-08T08:00:00.123456Z");

    @Test
    void deterministicFailureStopsTheTaskImmediately() throws Exception {
        try (Harness harness = Harness.create("claim-deterministic-stop")) {
            assertEquals(BLOCKED, harness.repository().recordFailure(
                    "task-1", 1, "a".repeat(64),
                    "PROJECT_INPUTS projection blocked", true));

            assertTrue(harness.repository().scanCommittedRootTasks(
                    null, 10).taskIds().isEmpty());
            assertEquals(STOPPED, harness.repository().acquire(
                    "task-1", "node-a", "token-a",
                    NOW.plusSeconds(30)).status());
        }
    }

    @Test
    void identicalRetryableFailureStopsOnTheThirdAttempt() throws Exception {
        try (Harness harness = Harness.create("claim-third-stop")) {
            assertEquals(RETRY, harness.repository().recordFailure(
                    "task-1", 1, "b".repeat(64), "provider timeout", false));
            assertEquals(RETRY, harness.repository().recordFailure(
                    "task-1", 1, "b".repeat(64), "provider timeout", false));
            assertEquals(BLOCKED, harness.repository().recordFailure(
                    "task-1", 1, "b".repeat(64), "provider timeout", false));

            assertTrue(harness.repository().scanCommittedRootTasks(
                    null, 10).taskIds().isEmpty());
            assertEquals(STOPPED, harness.repository().acquire(
                    "task-1", "node-a", "token-a",
                    NOW.plusSeconds(30)).status());
        }
    }

    @Test
    void acquiresAtCurrentAuthorityCutAndRejectsAnActiveClaim()
            throws Exception {
        try (Harness harness = Harness.create("claim-acquire")) {
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET next_event_sequence = 2
                     WHERE task_id = 'task-1'
                    """);
            harness.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES ('event-2','task-1',2,'PROGRESSION_TEST_CUT',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            AcquireResult acquired = harness.repository().acquire(
                    "task-1", "node-a", "token-a",
                    NOW.plusSeconds(30).plusNanos(987));
            AcquireResult rejected = harness.repository().acquire(
                    "task-1", "node-b", "token-b", NOW.plusSeconds(60));

            assertEquals(ACQUIRED, acquired.status());
            assertEquals(1, acquired.claim().fence());
            assertEquals(2, acquired.claim().authorityEventCut());
            assertEquals(CANONICAL_NOW, acquired.claim().acquiredAt());
            assertEquals(Instant.parse("2026-08-08T08:00:30.123457Z"),
                    acquired.claim().expiresAt());
            assertEquals(ACTIVE_CLAIM, rejected.status());
            assertNull(rejected.claim());
            assertEquals(1, harness.countClaims("task-1"));
        }
    }

    @Test
    void exactReleaseAllowsNextFenceAndOldIdentityCannotReleaseIt()
            throws Exception {
        try (Harness harness = Harness.create("claim-release")) {
            AcquireResult first = harness.repository().acquire(
                    "task-1", "node-a", "token-a", NOW.plusSeconds(30));

            assertEquals(STALE_CLAIM, harness.repository().release(
                    "task-1", "node-a", "wrong-token",
                    first.claim().fence()));
            assertEquals(STALE_CLAIM, harness.repository().release(
                    "task-1", "wrong-owner", "token-a",
                    first.claim().fence()));
            assertEquals(STALE_CLAIM, harness.repository().release(
                    "task-1", "node-a", "token-a",
                    first.claim().fence() + 1));
            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "node-a", "token-a",
                    first.claim().fence()));

            harness.time().set(NOW.plusSeconds(1));
            AcquireResult takeover = harness.repository().acquire(
                    "task-1", "node-b", "token-b", NOW.plusSeconds(60));

            assertEquals(ACQUIRED, takeover.status());
            assertEquals(2, takeover.claim().fence());
            assertEquals(STALE_CLAIM, harness.repository().release(
                    "task-1", "node-a", "token-a",
                    first.claim().fence()));
            assertEquals(STALE_CLAIM, harness.repository().release(
                    "task-1", "node-a", "token-a",
                    takeover.claim().fence()));
            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "node-b", "token-b",
                    takeover.claim().fence()));
            assertEquals(2, harness.countClaims("task-1"));
        }
    }

    @Test
    void expiredClaimIsTakenOverWithTheNextFence() throws Exception {
        try (Harness harness = Harness.create("claim-expiry")) {
            AcquireResult first = harness.repository().acquire(
                    "task-1", "node-a", "token-a", NOW.plusSeconds(1));
            harness.time().set(NOW.plusSeconds(1));

            AcquireResult takeover = harness.repository().acquire(
                    "task-1", "node-b", "token-b", NOW.plusSeconds(31));

            assertEquals(ACQUIRED, takeover.status());
            assertEquals(first.claim().fence() + 1, takeover.claim().fence());
            assertEquals("token-b", takeover.claim().claimToken());
            assertEquals(STALE_CLAIM, harness.repository().release(
                    "task-1", "node-a", "token-a",
                    first.claim().fence()));
        }
    }

    @Test
    void exactCurrentClaimRenewsOnlyExpiryAtMicrosecondPrecision()
            throws Exception {
        try (Harness harness = Harness.create("claim-renew")) {
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET next_event_sequence = 2
                     WHERE task_id = 'task-1'
                    """);
            harness.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES ('event-2','task-1',2,'PROGRESSION_TEST_CUT',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            AcquireResult acquired = harness.repository().acquire(
                    "task-1", "node-a", "token-a", NOW.plusSeconds(30));
            harness.update("""
                    UPDATE agent_v2_chain_tasks
                       SET next_event_sequence = 3
                     WHERE task_id = 'task-1'
                    """);
            harness.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES ('event-3','task-1',3,'AFTER_CLAIM_TEST_CUT',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);

            assertEquals(CURRENT, harness.repository().assertCurrent(
                    "task-1", "node-a", "token-a",
                    acquired.claim().fence()));
            RenewResult renewed = harness.repository().renew(
                    "task-1", "node-a", "token-a",
                    acquired.claim().fence(),
                    NOW.plusSeconds(90).plusNanos(876));

            assertEquals(RENEWED, renewed.status());
            assertEquals(Instant.parse("2026-08-08T08:01:30.123457Z"),
                    renewed.claim().expiresAt());
            assertEquals(acquired.claim().acquiredAt(),
                    renewed.claim().acquiredAt());
            assertEquals(acquired.claim().authorityEventCut(),
                    renewed.claim().authorityEventCut());
            assertEquals(acquired.claim().fence(), renewed.claim().fence());
            assertEquals(acquired.claim().ownerId(), renewed.claim().ownerId());
            assertEquals(acquired.claim().claimToken(),
                    renewed.claim().claimToken());
            assertEquals(CURRENT, harness.repository().assertCurrent(
                    "task-1", "node-a", "token-a",
                    acquired.claim().fence()));
            assertEquals(1, harness.countClaims("task-1"));
            assertEquals(1L, harness.claimField("task-1", "fence"));
            assertEquals(2L,
                    harness.claimField("task-1", "authority_event_cut"));
            assertEquals(Timestamp.from(CANONICAL_NOW),
                    harness.claimField("task-1", "acquired_at"));
            assertEquals(Timestamp.from(renewed.claim().expiresAt()),
                    harness.claimField("task-1", "expires_at"));

            RenewResult replayed = harness.repository().renew(
                    "task-1", "node-a", "token-a",
                    acquired.claim().fence(), renewed.claim().expiresAt());
            assertEquals(REPLAYED, replayed.status());
            assertEquals(renewed.claim(), replayed.claim());
            assertThrows(IllegalArgumentException.class,
                    () -> harness.repository().renew(
                            "task-1", "node-a", "token-a",
                            acquired.claim().fence(), NOW.plusSeconds(60)));
        }
    }

    @Test
    void staleReleasedOrExpiredIdentityCannotRenewOrAssertCurrent()
            throws Exception {
        try (Harness harness = Harness.create("claim-renew-stale")) {
            AcquireResult first = harness.repository().acquire(
                    "task-1", "node-a", "token-a", NOW.plusSeconds(1));
            long firstFence = first.claim().fence();

            assertEquals(CurrentResult.STALE_CLAIM,
                    harness.repository().assertCurrent(
                            "task-1", "wrong-owner", "token-a",
                            firstFence));
            assertEquals(CurrentResult.STALE_CLAIM,
                    harness.repository().assertCurrent(
                            "task-1", "node-a", "wrong-token",
                            firstFence));
            assertEquals(RenewStatus.STALE_CLAIM,
                    harness.repository().renew(
                            "task-1", "wrong-owner", "token-a",
                            firstFence, NOW.plusSeconds(30)).status());
            assertEquals(RenewStatus.STALE_CLAIM,
                    harness.repository().renew(
                            "task-1", "node-a", "token-a",
                            firstFence + 1, NOW.plusSeconds(30)).status());

            harness.time().set(NOW.plusSeconds(1));
            assertEquals(EXPIRED_CLAIM,
                    harness.repository().assertCurrent(
                            "task-1", "node-a", "token-a",
                            firstFence));
            assertEquals(RenewStatus.EXPIRED_CLAIM,
                    harness.repository().renew(
                            "task-1", "node-a", "token-a",
                            firstFence, NOW.plusSeconds(30)).status());

            AcquireResult takeover = harness.repository().acquire(
                    "task-1", "node-b", "token-b", NOW.plusSeconds(30));
            assertEquals(firstFence + 1, takeover.claim().fence());
            assertEquals(CurrentResult.STALE_CLAIM,
                    harness.repository().assertCurrent(
                            "task-1", "node-a", "token-a",
                            firstFence));
            assertEquals(RenewStatus.STALE_CLAIM,
                    harness.repository().renew(
                            "task-1", "node-a", "token-a",
                            firstFence, NOW.plusSeconds(60)).status());

            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "node-b", "token-b",
                    takeover.claim().fence()));
            assertEquals(CurrentResult.STALE_CLAIM,
                    harness.repository().assertCurrent(
                            "task-1", "node-b", "token-b",
                            takeover.claim().fence()));
            assertEquals(RenewStatus.STALE_CLAIM,
                    harness.repository().renew(
                            "task-1", "node-b", "token-b",
                            takeover.claim().fence(),
                            NOW.plusSeconds(60)).status());
        }
    }

    @Test
    void missingTaskCannotBeRenewedOrAssertedCurrent() throws Exception {
        try (Harness harness = Harness.create("claim-renew-missing")) {
            assertEquals(CurrentResult.TASK_NOT_FOUND,
                    harness.repository().assertCurrent(
                            "missing-task", "node-a", "token-a", 1));
            assertEquals(RenewStatus.TASK_NOT_FOUND,
                    harness.repository().renew(
                            "missing-task", "node-a", "token-a", 1,
                            NOW.plusSeconds(30)).status());
        }
    }

    @Test
    void missingTaskCannotBeAcquiredOrReleased() throws Exception {
        try (Harness harness = Harness.create("claim-missing")) {
            AcquireResult acquired = harness.repository().acquire(
                    "missing-task", "node-a", "token-a",
                    NOW.plusSeconds(30));

            assertEquals(AcquireStatus.TASK_NOT_FOUND,
                    acquired.status());
            assertNull(acquired.claim());
            assertEquals(ReleaseResult.TASK_NOT_FOUND,
                    harness.repository().release(
                            "missing-task", "node-a", "token-a", 1));
        }
    }

    @Test
    void scansOnlyCommittedCommandsWhoseRootIdentityAndResultMatch()
            throws Exception {
        try (Harness harness = Harness.create("claim-scan")) {
            ChainMigrationTestSupport.seedSecondFoundation(
                    harness.connection());
            harness.commitRoot("command-2", "task-2", "request-2",
                    ChainMigrationTestSupport.HASH, 17, 18,
                    "2026-08-08 08:00:01");

            harness.commitRoot("command-1", "task-1", "request-1",
                    ChainMigrationTestSupport.HASH, 7, 8,
                    "2026-08-08 08:00:01");
            CommittedTaskPage all = harness.repository()
                    .scanCommittedRootTasks(null, 10);
            assertEquals(List.of("task-1", "task-2"), all.taskIds());
            CommittedTaskPage firstPage = harness.repository()
                    .scanCommittedRootTasks(null, 1);
            assertEquals(List.of("task-1"), firstPage.taskIds());
            AcquireResult frontClaim = harness.repository().acquire(
                    "task-1", "front-owner", "front-token",
                    NOW.plusSeconds(30));
            assertEquals(ACQUIRED, frontClaim.status());
            AcquireResult blockedFront = harness.repository().acquire(
                    "task-1", "scanner-owner", "scanner-token",
                    NOW.plusSeconds(30));
            assertEquals(ACTIVE_CLAIM, blockedFront.status());
            assertNull(blockedFront.claim());
            assertEquals(List.of("task-2"), harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds());
            CommittedTaskPage secondPage = harness.repository()
                    .scanCommittedRootTasks(firstPage.nextCursor(), 1);
            assertEquals(List.of("task-2"), secondPage.taskIds());
            assertTrue(harness.repository().scanCommittedRootTasks(
                    secondPage.nextCursor(), 1).taskIds().isEmpty());
            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "front-owner", "front-token",
                    frontClaim.claim().fence()));
            harness.appendAuthorityEvent(
                    "task-1", "event-2", 2,
                    "AFTER_RELEASE_TEST_CUT");

            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET result_task_id = 'task-1'
                     WHERE command_id = 'command-2'
                    """);
            assertEquals(List.of("task-1"), harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds());
            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET result_task_id = 'task-2'
                     WHERE command_id = 'command-2'
                    """);
            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET request_sha256 = REPEAT('1',64)
                     WHERE command_id = 'command-2'
                    """);
            assertEquals(List.of("task-1"), harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds());
            harness.update("""
                    UPDATE agent_v2_chain_commands
                       SET status = 'RECEIVED', committed_at = NULL,
                           result_task_id = NULL, result_event_id = NULL,
                           result_instruction_id = NULL
                     WHERE command_id = 'command-1'
                    """);
            assertTrue(harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds().isEmpty());
        }
    }

    @Test
    void releasedWaitingClaimWithoutNewAuthorityIsNotScannedAgain()
            throws Exception {
        try (Harness harness = Harness.create("claim-scan-waiting")) {
            harness.commitRoot("command-1", "task-1", "request-1",
                    ChainMigrationTestSupport.HASH, 7, 8,
                    "2026-08-08 08:00:01");
            harness.appendAuthorityEvent(
                    "task-1", "waiting-event", 2,
                    "WAITING_TEST_CUT");
            AcquireResult claim = harness.repository().acquire(
                    "task-1", "waiting-owner", "waiting-token",
                    NOW.plusSeconds(30));

            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "waiting-owner", "waiting-token",
                    claim.claim().fence()));
            assertTrue(harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds().isEmpty());
            assertEquals(1, harness.countClaims("task-1"));
        }
    }

    @Test
    void releasedTerminalClaimWithoutNewAuthorityIsNotScannedAgain()
            throws Exception {
        try (Harness harness = Harness.create("claim-scan-terminal")) {
            harness.commitRoot("command-1", "task-1", "request-1",
                    ChainMigrationTestSupport.HASH, 7, 8,
                    "2026-08-08 08:00:01");
            harness.appendAuthorityEvent(
                    "task-1", "terminal-event", 2,
                    "TERMINAL_TEST_CUT");
            AcquireResult claim = harness.repository().acquire(
                    "task-1", "terminal-owner", "terminal-token",
                    NOW.plusSeconds(30));

            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "terminal-owner", "terminal-token",
                    claim.claim().fence()));
            assertTrue(harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds().isEmpty());
            assertEquals(1, harness.countClaims("task-1"));
        }
    }

    @Test
    void releasedClaimIsScannedOnceAfterANewerAuthorityEvent()
            throws Exception {
        try (Harness harness = Harness.create("claim-scan-new-fact")) {
            harness.commitRoot("command-1", "task-1", "request-1",
                    ChainMigrationTestSupport.HASH, 7, 8,
                    "2026-08-08 08:00:01");
            AcquireResult first = harness.repository().acquire(
                    "task-1", "first-owner", "first-token",
                    NOW.plusSeconds(30));
            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "first-owner", "first-token",
                    first.claim().fence()));
            assertTrue(harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds().isEmpty());

            harness.appendAuthorityEvent(
                    "task-1", "event-2", 2,
                    "NEW_FORMAL_FACT_TEST_CUT");
            assertEquals(List.of("task-1"), harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds());

            AcquireResult second = harness.repository().acquire(
                    "task-1", "second-owner", "second-token",
                    NOW.plusSeconds(30));
            assertEquals(2, second.claim().authorityEventCut());
            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "second-owner", "second-token",
                    second.claim().fence()));
            assertTrue(harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds().isEmpty());
        }
    }

    @Test
    void activeClaimIsNotScannedButAnExpiredUnreleasedClaimIs()
            throws Exception {
        try (Harness harness = Harness.create("claim-scan-expired")) {
            harness.commitRoot("command-1", "task-1", "request-1",
                    ChainMigrationTestSupport.HASH, 7, 8,
                    "2026-08-08 08:00:01");
            harness.repository().acquire(
                    "task-1", "active-owner", "active-token",
                    NOW.plusSeconds(1));

            assertTrue(harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds().isEmpty());
            harness.time().set(NOW.plusSeconds(1));
            assertEquals(List.of("task-1"), harness.repository()
                    .scanCommittedRootTasks(null, 10).taskIds());
        }
    }

    @Test
    void committedCursorPaginationSkipsAnIneligibleEarlierTask()
            throws Exception {
        try (Harness harness = Harness.create("claim-scan-page")) {
            ChainMigrationTestSupport.seedSecondFoundation(
                    harness.connection());
            harness.commitRoot("command-1", "task-1", "request-1",
                    ChainMigrationTestSupport.HASH, 7, 8,
                    "2026-08-08 08:00:01");
            harness.commitRoot("command-2", "task-2", "request-2",
                    ChainMigrationTestSupport.HASH, 17, 18,
                    "2026-08-08 08:00:01");
            AcquireResult first = harness.repository().acquire(
                    "task-1", "page-owner", "page-token",
                    NOW.plusSeconds(30));
            assertEquals(RELEASED, harness.repository().release(
                    "task-1", "page-owner", "page-token",
                    first.claim().fence()));

            CommittedTaskPage page = harness.repository()
                    .scanCommittedRootTasks(null, 1);
            assertEquals(List.of("task-2"), page.taskIds());
            assertEquals("task-2", page.nextCursor().taskId());
            assertTrue(harness.repository().scanCommittedRootTasks(
                    page.nextCursor(), 1).taskIds().isEmpty());
        }
    }

    @Test
    void concurrentAcquireSerializesOnTheTaskAndCreatesOneGeneration()
            throws Exception {
        try (Harness harness = Harness.create("claim-concurrency")) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<AcquireResult> first = executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return harness.repository().acquire(
                            "task-1", "node-a", "token-a",
                            NOW.plusSeconds(30));
                });
                Future<AcquireResult> second = executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return harness.repository().acquire(
                            "task-1", "node-b", "token-b",
                            NOW.plusSeconds(30));
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                List<AcquireResult> results = List.of(
                        first.get(10, TimeUnit.SECONDS),
                        second.get(10, TimeUnit.SECONDS));
                assertEquals(1, results.stream()
                        .filter(result -> result.status() == ACQUIRED).count());
                assertEquals(1, results.stream()
                        .filter(result -> result.status() == ACTIVE_CLAIM)
                        .count());
                assertTrue(results.stream()
                        .filter(result -> result.status() == ACTIVE_CLAIM)
                        .allMatch(result -> result.claim() == null));
                assertEquals(1, harness.countClaims("task-1"));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void concurrentRenewalAndTakeoverAreSerializedByTheTaskFence()
            throws Exception {
        try (Harness harness = Harness.create("claim-renew-concurrency")) {
            AcquireResult first = harness.repository().acquire(
                    "task-1", "node-a", "token-a", NOW.plusSeconds(30));
            long firstFence = first.claim().fence();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch activeStart = new CountDownLatch(1);
                Future<RenewResult> renewal = executor.submit(() -> {
                    activeStart.await(5, TimeUnit.SECONDS);
                    return harness.repository().renew(
                            "task-1", "node-a", "token-a", firstFence,
                            NOW.plusSeconds(60));
                });
                Future<AcquireResult> activeCompetitor = executor.submit(() -> {
                    activeStart.await(5, TimeUnit.SECONDS);
                    return harness.repository().acquire(
                            "task-1", "node-b", "token-b",
                            NOW.plusSeconds(60));
                });
                activeStart.countDown();

                assertEquals(RENEWED,
                        renewal.get(10, TimeUnit.SECONDS).status());
                AcquireResult blocked = activeCompetitor.get(
                        10, TimeUnit.SECONDS);
                assertEquals(ACTIVE_CLAIM, blocked.status());
                assertNull(blocked.claim());
                assertEquals(1, harness.countClaims("task-1"));

                harness.time().set(NOW.plusSeconds(60));
                CountDownLatch expiredStart = new CountDownLatch(1);
                Future<RenewResult> expiredRenewal = executor.submit(() -> {
                    expiredStart.await(5, TimeUnit.SECONDS);
                    return harness.repository().renew(
                            "task-1", "node-a", "token-a", firstFence,
                            NOW.plusSeconds(120));
                });
                Future<AcquireResult> takeover = executor.submit(() -> {
                    expiredStart.await(5, TimeUnit.SECONDS);
                    return harness.repository().acquire(
                            "task-1", "node-c", "token-c",
                            NOW.plusSeconds(120));
                });
                expiredStart.countDown();

                RenewStatus staleRenewal = expiredRenewal.get(
                        10, TimeUnit.SECONDS).status();
                assertTrue(staleRenewal == RenewStatus.EXPIRED_CLAIM
                        || staleRenewal == RenewStatus.STALE_CLAIM);
                AcquireResult acquiredTakeover = takeover.get(
                        10, TimeUnit.SECONDS);
                assertEquals(ACQUIRED, acquiredTakeover.status());
                assertEquals(firstFence + 1,
                        acquiredTakeover.claim().fence());
                assertEquals(CurrentResult.STALE_CLAIM,
                        harness.repository().assertCurrent(
                                "task-1", "node-a", "token-a",
                                firstFence));
                assertEquals(2, harness.countClaims("task-1"));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private record Harness(
            Connection connection,
            ProductChainProgressionClaimRepositoryAdapter repository,
            NamedParameterJdbcTemplate jdbc,
            AtomicReference<Instant> time) implements AutoCloseable {
        static Harness create(String label) throws Exception {
            Connection connection = ChainMigrationTestSupport.database(label);
            ChainMigrationTestSupport.migrateThrough(connection, 75);
            ChainMigrationTestSupport.execute(connection,
                    ChainMigrationTestSupport.read(true,
                            ChainMigrationTestSupport.fileName(86)));
            ChainMigrationTestSupport.seedFoundation(connection);
            var dataSource = new DriverManagerDataSource(
                    connection.getMetaData().getURL(), "sa", "");
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var time = new AtomicReference<>(NOW);
            var repository = new ProductChainProgressionClaimRepositoryAdapter(
                    jdbc, new DataSourceTransactionManager(dataSource),
                    time::get);
            return new Harness(connection, repository, jdbc, time);
        }

        long countClaims(String taskId) {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM agent_v2_chain_progression_claims
                     WHERE task_id = :taskId
                    """, java.util.Map.of("taskId", taskId), Long.class);
            return count == null ? 0 : count;
        }

        Object claimField(String taskId, String column) {
            if (!java.util.Set.of(
                    "fence", "authority_event_cut", "acquired_at",
                    "expires_at").contains(column)) {
                throw new IllegalArgumentException("unsupported column");
            }
            return jdbc.queryForObject(
                    "SELECT " + column
                            + " FROM agent_v2_chain_progression_claims"
                            + " WHERE task_id = :taskId"
                            + " ORDER BY fence DESC LIMIT 1",
                    java.util.Map.of("taskId", taskId), Object.class);
        }

        void appendAuthorityEvent(
                String taskId, String eventId, long sequence,
                String eventType) {
            jdbc.update("""
                    UPDATE agent_v2_chain_tasks
                       SET next_event_sequence = :sequence
                     WHERE task_id = :taskId
                    """, java.util.Map.of(
                    "sequence", sequence,
                    "taskId", taskId));
            jdbc.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES (
                      :eventId,:taskId,:sequence,:eventType,
                      :digest,CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("eventId", eventId)
                    .addValue("taskId", taskId)
                    .addValue("sequence", sequence)
                    .addValue("eventType", eventType)
                    .addValue("digest", ChainMigrationTestSupport.HASH));
        }

        void commitRoot(
                String commandId, String taskId, String clientRequestId,
                String requestSha256, long userId, long sessionId,
                String committedAt) {
            jdbc.update("""
                    UPDATE agent_v2_chain_commands
                       SET user_id = :userId,
                           session_id = :sessionId,
                           client_request_id = :clientRequestId,
                           request_sha256 = :requestSha256,
                           result_task_id = :taskId,
                           result_event_id = :eventId,
                           result_instruction_id = :instructionId,
                           turn_id = :turnId,
                           user_message_id = :userMessageId,
                           status = 'COMMITTED',
                           committed_at = :committedAt
                     WHERE command_id = :commandId
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("sessionId", sessionId)
                    .addValue("clientRequestId", clientRequestId)
                    .addValue("requestSha256", requestSha256)
                    .addValue("taskId", taskId)
                    .addValue("eventId", taskId.equals("task-1")
                            ? "event-1" : "task-2-event-1")
                    .addValue("instructionId", taskId.equals("task-1")
                            ? "instruction-1" : "instruction-2")
                    .addValue("turnId", taskId.equals("task-1") ? 9L : 19L)
                    .addValue("userMessageId",
                            taskId.equals("task-1") ? 10L : 20L)
                    .addValue("committedAt", committedAt)
                    .addValue("commandId", commandId));
        }

        void update(String sql) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}
