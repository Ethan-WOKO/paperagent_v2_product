package com.yanban.api.agent.v2.chain.progression;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Renews one exact progression claim while its bounded action is running. */
public final class ProductChainClaimLeaseKeeper
        implements ProductChainDurableProgressionDriver.ClaimLeaseKeeper,
        AutoCloseable {
    private final ProductChainProgressionClaimStore claims;
    private final Clock clock;
    private final PulseScheduler pulses;

    public ProductChainClaimLeaseKeeper(
            ProductChainProgressionClaimStore claims,
            Clock clock) {
        this(claims, clock, new ExecutorPulseScheduler());
    }

    ProductChainClaimLeaseKeeper(
            ProductChainProgressionClaimStore claims,
            Clock clock,
            PulseScheduler pulses) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pulses = Objects.requireNonNull(pulses, "pulses");
    }

    @Override
    public void runProtected(
            ProductChainProgressionClaim claim,
            Duration claimLifetime,
            Runnable action) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(claimLifetime, "claimLifetime");
        Objects.requireNonNull(action, "action");
        if (claimLifetime.isNegative() || claimLifetime.isZero()) {
            throw new IllegalArgumentException(
                    "claimLifetime must be positive");
        }
        Duration interval = claimLifetime.dividedBy(3);
        if (interval.isZero()) {
            throw new IllegalArgumentException(
                    "claimLifetime is too short for renewal");
        }
        AtomicReference<ProductChainProgressionClaim> current =
                new AtomicReference<>(claim);
        AtomicReference<RuntimeException> renewalFailure =
                new AtomicReference<>();
        Pulse pulse = pulses.schedule(interval, () -> {
            if (renewalFailure.get() != null) return;
            ProductChainProgressionClaim held = current.get();
            try {
                var renewed = claims.renew(
                        held.taskId(), held.ownerId(), held.claimToken(),
                        held.fence(), clock.instant().plus(claimLifetime));
                if (renewed.status()
                        != ProductChainProgressionClaimStore.RenewStatus.RENEWED
                        && renewed.status()
                        != ProductChainProgressionClaimStore.RenewStatus.REPLAYED) {
                    renewalFailure.compareAndSet(null,
                            new IllegalStateException(
                                    "progression claim heartbeat lost authority: "
                                            + renewed.status().name()));
                    return;
                }
                current.set(renewed.claim());
            } catch (RuntimeException failure) {
                renewalFailure.compareAndSet(null, failure);
            }
        });
        try {
            action.run();
        } finally {
            pulse.close();
        }
        RuntimeException failure = renewalFailure.get();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        pulses.close();
    }

    interface PulseScheduler extends AutoCloseable {
        Pulse schedule(Duration interval, Runnable task);

        @Override
        void close();
    }

    @FunctionalInterface
    interface Pulse extends AutoCloseable {
        @Override
        void close();
    }

    private static final class ExecutorPulseScheduler
            implements PulseScheduler {
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(
                        new DaemonThreadFactory());

        @Override
        public Pulse schedule(Duration interval, Runnable task) {
            long nanos = interval.toNanos();
            PulseTask pulse = new PulseTask(task);
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                    pulse::run, nanos, nanos, TimeUnit.NANOSECONDS);
            pulse.future = future;
            return pulse::stop;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        private static final class PulseTask {
            private final Runnable task;
            private boolean stopped;
            private ScheduledFuture<?> future;

            private PulseTask(Runnable task) {
                this.task = task;
            }

            private synchronized void run() {
                if (!stopped) task.run();
            }

            private synchronized void stop() {
                stopped = true;
                future.cancel(false);
            }
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    task, "agent-v2-progression-claim-heartbeat");
            thread.setDaemon(true);
            return thread;
        }
    }
}
