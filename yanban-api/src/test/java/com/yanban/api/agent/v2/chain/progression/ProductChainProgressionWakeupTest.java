package com.yanban.api.agent.v2.chain.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProductChainProgressionWakeupTest {
    private static final ProductChainDurableProgressionDriver.TickRequest
            REQUEST = new ProductChainDurableProgressionDriver.TickRequest(
                    "owner", Duration.ofMinutes(1), 10, 10);

    @Test
    void disabledWakeDoesNotScan() {
        ProductChainProgressionProperties properties = properties(false);
        AtomicInteger calls = new AtomicInteger();
        ProductChainProgressionWakeup wakeup = wakeup(
                request -> {
                    calls.incrementAndGet();
                    return empty();
                }, properties);

        wakeup.wake();

        assertEquals(0, calls.get());
    }

    @Test
    void concurrentWakeDoesNotOverlapAndLaterWakeCanRun() throws Exception {
        ProductChainProgressionProperties properties = properties(true);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProductChainProgressionWakeup wakeup = wakeup(request -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return empty();
        }, properties);
        Thread first = new Thread(wakeup::wake);

        first.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            wakeup.wake();
        } finally {
            release.countDown();
            first.join(2000);
        }
        assertFalse(first.isAlive());
        wakeup.wake();

        assertEquals(2, calls.get());
    }

    @Test
    void failedScanDoesNotEscapeAndDoesNotLeaveWakeLocked() {
        ProductChainProgressionProperties properties = properties(true);
        AtomicInteger calls = new AtomicInteger();
        ProductChainProgressionWakeup wakeup = wakeup(request -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            return empty();
        }, properties);

        wakeup.wake();
        wakeup.wake();

        assertEquals(2, calls.get());
    }

    private static ProductChainProgressionWakeup wakeup(
            ProductChainProgressionWakeup.ProgressionTick tick,
            ProductChainProgressionProperties properties) {
        return new ProductChainProgressionWakeup(tick, properties, REQUEST);
    }

    private static ProductChainProgressionProperties properties(
            boolean enabled) {
        ProductChainProgressionProperties properties =
                new ProductChainProgressionProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static ProductChainDurableProgressionDriver.TickResult empty() {
        return new ProductChainDurableProgressionDriver.TickResult(
                0, 0, 0, List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
