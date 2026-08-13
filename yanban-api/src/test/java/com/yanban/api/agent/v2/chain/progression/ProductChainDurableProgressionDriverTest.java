package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedCommandSource;
import io.paperagent.v2.chain.ChainInstructionRelation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductChainDurableProgressionDriverTest {
    private static final Instant NOW = Instant.parse("2026-08-09T01:00:00Z");
    private static final ProductChainDurableProgressionDriver.TickRequest REQUEST =
            new ProductChainDurableProgressionDriver.TickRequest(
                    "driver-1", Duration.ofSeconds(30), 2, 1);

    @Test
    void pagesReceivedBeforeCommittedTasksAndWrapsEachActionInOneClaim() {
        List<String> order = new ArrayList<>();
        var firstCursor = new ProductChainReceivedCommandSource.ScanCursor(
                NOW.minusSeconds(2), "command-r");
        ProductChainDurableProgressionDriver.ReceivedScanSource source =
                new ProductChainDurableProgressionDriver.ReceivedScanSource() {
                    private int page;

                    @Override
                    public ProductChainReceivedCommandSource.ScanPage scan(
                            ProductChainReceivedCommandSource.ScanCursor after,
                            int limit) {
                        assertThat(limit).isEqualTo(2);
                        if (page++ == 0) {
                            assertThat(after).isNull();
                            return new ProductChainReceivedCommandSource.ScanPage(
                                    List.of(new ProductChainReceivedCommandSource.Ready(
                                                    command("command-r", "task-r")),
                                            new ProductChainReceivedCommandSource.Blocked(
                                                    "command-b", "blocked", "bad fact")),
                                    firstCursor, true);
                        }
                        assertThat(after).isEqualTo(firstCursor);
                        return new ProductChainReceivedCommandSource.ScanPage(
                                List.of(new ProductChainReceivedCommandSource
                                        .NoLongerReceived("command-n")),
                                new ProductChainReceivedCommandSource.ScanCursor(
                                        NOW.minusSeconds(1), "command-n"), false);
                    }
                };
        FakeClaims claims = new FakeClaims(
                List.of(List.of("task-r"), List.of("task-c")));
        ProductChainDurableProgressionDriver driver = driver(
                source, claims,
                (command, claim) -> {
                    claims.lifecycle.add("action:" + command.taskId());
                    order.add("received:" + command.taskId());
                },
                (taskId, claim) -> {
                    claims.lifecycle.add("action:" + taskId);
                    order.add("task:" + taskId);
                });

        ProductChainDurableProgressionDriver.TickResult result =
                driver.advance(REQUEST);

        assertThat(order).containsExactly("received:task-r", "task:task-c");
        assertThat(result.scanned()).isEqualTo(5);
        assertThat(result.advanced()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.failures()).isEmpty();
        assertThat(claims.lifecycle).containsExactly(
                "acquire:task-r", "current:task-r", "renew:task-r",
                "action:task-r", "current:task-r",
                "release:task-r:driver-1:token-task-r:1",
                "scan-committed:first:1",
                "scan-committed:next:1",
                "acquire:task-c", "current:task-c", "renew:task-c",
                "action:task-c", "current:task-c",
                "release:task-c:driver-1:token-task-c:1",
                "scan-committed:next:1");
    }

    @Test
    void skipsBlockedEntriesActiveClaimsAndMissingTasksWithoutActions() {
        ProductChainDurableProgressionDriver.ReceivedScanSource source =
                (after, limit) -> new ProductChainReceivedCommandSource.ScanPage(
                        List.of(new ProductChainReceivedCommandSource.Ready(
                                        command("command-active", "task-active")),
                                new ProductChainReceivedCommandSource.Blocked(
                                        "command-blocked", "blocked", "bad fact")),
                        new ProductChainReceivedCommandSource.ScanCursor(
                                NOW, "command-blocked"), false);
        FakeClaims claims = new FakeClaims(List.of(List.of("task-missing")));
        claims.acquire.put("task-active",
                ProductChainProgressionClaimStore.AcquireResult.active());
        claims.acquire.put("task-missing",
                ProductChainProgressionClaimStore.AcquireResult.taskNotFound());
        List<String> actions = new ArrayList<>();

        var result = driver(source, claims,
                (command, claim) -> actions.add(command.taskId()),
                (taskId, claim) -> actions.add(taskId)).advance(REQUEST);

        assertThat(actions).isEmpty();
        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.advanced()).isZero();
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.failures()).isEmpty();
        assertThat(claims.lifecycle).containsExactly(
                "acquire:task-active", "scan-committed:first:1",
                "acquire:task-missing", "scan-committed:next:1");
    }

    @Test
    void aLostOrFailingClaimDoesNotStopLaterTasksAndFailureWaitsForExpiry() {
        ProductChainDurableProgressionDriver.ReceivedScanSource emptySource =
                (after, limit) -> new ProductChainReceivedCommandSource.ScanPage(
                        List.of(), null, false);
        FakeClaims claims = new FakeClaims(List.of(
                List.of("task-stale"), List.of("task-fails"),
                List.of("task-lost"), List.of("task-ok")));
        claims.current.put("task-stale", queue(
                ProductChainProgressionClaimStore.CurrentResult.STALE_CLAIM));
        claims.current.put("task-lost", queue(
                ProductChainProgressionClaimStore.CurrentResult.CURRENT,
                ProductChainProgressionClaimStore.CurrentResult.EXPIRED_CLAIM));
        claims.release.put("task-ok",
                ProductChainProgressionClaimStore.ReleaseResult.STALE_CLAIM);
        List<String> actions = new ArrayList<>();
        ProductChainDurableProgressionDriver driver = driver(
                emptySource, claims, (command, claim) -> { },
                (taskId, claim) -> {
                    actions.add(taskId);
                    if (taskId.equals("task-fails")) {
                        throw new IllegalStateException("model failed");
                    }
                });

        var result = driver.advance(REQUEST);

        assertThat(actions).containsExactly(
                "task-fails", "task-lost", "task-ok");
        assertThat(result.advanced()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).extracting(
                ProductChainDurableProgressionDriver.TickFailure::taskId)
                .containsExactly("task-fails", "task-lost", "task-ok");
        assertThat(claims.released).containsExactlyInAnyOrder(
                "task-stale", "task-ok");
    }

    @Test
    void protectsTheWholeClaimedActionWithTheInjectedLeaseKeeper() {
        FakeClaims claims = new FakeClaims(List.of(
                List.of("task-1"), List.of()));
        List<String> lifecycle = new ArrayList<>();
        ProductChainDurableProgressionDriver driver =
                new ProductChainDurableProgressionDriver(
                        (after, limit) -> new ProductChainReceivedCommandSource
                                .ScanPage(List.of(), null, false),
                        claims,
                        (command, claim) -> {
                            throw new AssertionError(
                                    "received action not expected");
                        },
                        (taskId, claim) -> lifecycle.add(
                                "action:" + taskId),
                        taskId -> "token-" + taskId,
                        (claim, lifetime, action) -> {
                            lifecycle.add("lease-open:" + claim.taskId());
                            assertThat(lifetime).isEqualTo(
                                    REQUEST.claimLifetime());
                            action.run();
                            lifecycle.add("lease-close:" + claim.taskId());
                        },
                        Clock.fixed(NOW, ZoneOffset.UTC));

        var result = driver.advance(REQUEST);

        assertThat(lifecycle).containsExactly(
                "lease-open:task-1", "action:task-1",
                "lease-close:task-1");
        assertThat(result.advanced()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
    }

    private static ProductChainDurableProgressionDriver driver(
            ProductChainDurableProgressionDriver.ReceivedScanSource source,
            FakeClaims claims,
            ProductChainDurableProgressionDriver.ReceivedProgression received,
            ProductChainDurableProgressionDriver.TaskProgression tasks) {
        return new ProductChainDurableProgressionDriver(
                source, claims, received, tasks,
                taskId -> "token-" + taskId,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProductChainReceivedCommandSource.ReceivedCommand command(
            String commandId, String taskId) {
        return new ProductChainReceivedCommandSource.ReceivedCommand(
                commandId, taskId, "instruction-1", "event-1",
                1L, 2L, "request-1", "a".repeat(64),
                ChainInstructionRelation.INITIAL,
                ChainInstructionRelation.INITIAL, 3L, 4L,
                "request-1", "b".repeat(64), null, null);
    }

    @SafeVarargs
    private static <T> ArrayDeque<T> queue(T... values) {
        return new ArrayDeque<>(List.of(values));
    }

    private static final class FakeClaims
            implements ProductChainProgressionClaimStore {
        private final List<List<String>> committedPages;
        private final List<String> lifecycle = new ArrayList<>();
        private final List<String> released = new ArrayList<>();
        private final Map<String, AcquireResult> acquire = new HashMap<>();
        private final Map<String, ArrayDeque<CurrentResult>> current =
                new HashMap<>();
        private final Map<String, ReleaseResult> release = new HashMap<>();
        private int committedPage;

        private FakeClaims(List<List<String>> committedPages) {
            this.committedPages = committedPages;
        }

        @Override
        public AcquireResult acquire(
                String taskId, String ownerId, String claimToken,
                Instant expiresAt) {
            lifecycle.add("acquire:" + taskId);
            return acquire.getOrDefault(taskId, AcquireResult.acquired(
                    new ProductChainProgressionClaim(
                            taskId, ownerId, claimToken, 1, 7,
                            NOW, expiresAt)));
        }

        @Override
        public RenewResult renew(
                String taskId, String ownerId, String claimToken, long fence,
                Instant expiresAt) {
            lifecycle.add("renew:" + taskId);
            return RenewResult.replayed(new ProductChainProgressionClaim(
                    taskId, ownerId, claimToken, fence, 7, NOW, expiresAt));
        }

        @Override
        public CurrentResult assertCurrent(
                String taskId, String ownerId, String claimToken, long fence) {
            lifecycle.add("current:" + taskId);
            ArrayDeque<CurrentResult> configured = current.get(taskId);
            return configured == null || configured.isEmpty()
                    ? CurrentResult.CURRENT : configured.removeFirst();
        }

        @Override
        public ReleaseResult release(
                String taskId, String ownerId, String claimToken, long fence) {
            lifecycle.add("release:" + taskId + ":" + ownerId + ":"
                    + claimToken + ":" + fence);
            released.add(taskId);
            return release.getOrDefault(taskId, ReleaseResult.RELEASED);
        }

        @Override
        public CommittedTaskPage scanCommittedRootTasks(
                CommittedTaskCursor afterExclusive, int limit) {
            lifecycle.add("scan-committed:"
                    + (afterExclusive == null ? "first" : "next")
                    + ":" + limit);
            if (committedPage >= committedPages.size()) {
                return new CommittedTaskPage(List.of(), null);
            }
            List<String> taskIds = committedPages.get(committedPage++);
            if (taskIds.isEmpty()) {
                return new CommittedTaskPage(List.of(), null);
            }
            return new CommittedTaskPage(taskIds,
                    new CommittedTaskCursor(
                            NOW.plusSeconds(committedPage),
                            taskIds.get(taskIds.size() - 1)));
        }
    }
}
