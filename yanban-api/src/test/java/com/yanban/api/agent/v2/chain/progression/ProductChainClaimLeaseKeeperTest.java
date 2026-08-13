package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProductChainClaimLeaseKeeperTest {
    private static final Instant NOW = Instant.parse("2026-08-09T03:00:00Z");
    private static final Duration LIFETIME = Duration.ofSeconds(30);

    @Test
    void renewsTheExactClaimWhileTheActionRuns() {
        FakeClaims claims = new FakeClaims();
        FakePulses pulses = new FakePulses();
        ProductChainClaimLeaseKeeper keeper = new ProductChainClaimLeaseKeeper(
                claims, Clock.fixed(NOW, ZoneOffset.UTC), pulses);
        AtomicBoolean ran = new AtomicBoolean();

        keeper.runProtected(claim(), LIFETIME, () -> {
            pulses.fire();
            ran.set(true);
        });

        assertThat(ran).isTrue();
        assertThat(claims.renewed).containsExactly("task-1/owner-1/token-1/4");
        assertThat(pulses.interval).isEqualTo(Duration.ofSeconds(10));
        assertThat(pulses.closed).isTrue();
    }

    @Test
    void rejectsTheActionWhenHeartbeatLosesTheClaim() {
        FakeClaims claims = new FakeClaims();
        claims.renewal = ProductChainProgressionClaimStore.RenewResult.stale();
        FakePulses pulses = new FakePulses();
        ProductChainClaimLeaseKeeper keeper = new ProductChainClaimLeaseKeeper(
                claims, Clock.fixed(NOW, ZoneOffset.UTC), pulses);

        assertThatThrownBy(() -> keeper.runProtected(
                claim(), LIFETIME, pulses::fire))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat lost authority");
        assertThat(pulses.closed).isTrue();
    }

    private static ProductChainProgressionClaim claim() {
        return new ProductChainProgressionClaim(
                "task-1", "owner-1", "token-1", 4, 9,
                NOW.minusSeconds(1), NOW.plus(LIFETIME));
    }

    private static final class FakePulses
            implements ProductChainClaimLeaseKeeper.PulseScheduler {
        private Runnable task;
        private Duration interval;
        private boolean closed;

        @Override
        public ProductChainClaimLeaseKeeper.Pulse schedule(
                Duration interval, Runnable task) {
            this.interval = interval;
            this.task = task;
            return () -> closed = true;
        }

        private void fire() {
            task.run();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeClaims
            implements ProductChainProgressionClaimStore {
        private final List<String> renewed = new ArrayList<>();
        private RenewResult renewal = RenewResult.renewed(
                new ProductChainProgressionClaim(
                        "task-1", "owner-1", "token-1", 4, 9,
                        NOW.minusSeconds(1), NOW.plusSeconds(60)));

        @Override
        public RenewResult renew(
                String taskId, String ownerId, String claimToken,
                long fence, Instant expiresAt) {
            renewed.add(taskId + "/" + ownerId + "/" + claimToken
                    + "/" + fence);
            return renewal;
        }

        @Override public AcquireResult acquire(String taskId, String ownerId,
                String claimToken, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }
        @Override public CurrentResult assertCurrent(String taskId,
                String ownerId, String claimToken, long fence) {
            throw new UnsupportedOperationException();
        }
        @Override public ReleaseResult release(String taskId, String ownerId,
                String claimToken, long fence) {
            throw new UnsupportedOperationException();
        }
        @Override public CommittedTaskPage scanCommittedRootTasks(
                CommittedTaskCursor afterExclusive, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
