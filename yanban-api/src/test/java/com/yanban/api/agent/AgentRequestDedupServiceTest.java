package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRequestDedupServiceTest {

    @Test
    void executesSameClientRequestOnlyOnceWhileInFlight() throws Exception {
        AgentRequestDedupService service = new AgentRequestDedupService();
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<SendMessageResponse> first = executor.submit(() -> service.execute(1L, 2L, "req-1", () -> {
                invocations.incrementAndGet();
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
                return successResponse("hello");
            }));

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            Future<SendMessageResponse> second = executor.submit(() -> service.execute(1L, 2L, "req-1", () -> {
                invocations.incrementAndGet();
                return successResponse("duplicate");
            }));

            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).assistantContent()).isEqualTo("hello");
            assertThat(second.get(5, TimeUnit.SECONDS).assistantContent()).isEqualTo("hello");
            assertThat(invocations.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reusesCompletedResponseWithinTtl() {
        AgentRequestDedupService service = new AgentRequestDedupService();
        AtomicInteger invocations = new AtomicInteger();

        SendMessageResponse first = service.execute(1L, 2L, "req-2", () -> {
            invocations.incrementAndGet();
            return successResponse("cached");
        });
        SendMessageResponse second = service.execute(1L, 2L, "req-2", () -> {
            invocations.incrementAndGet();
            return successResponse("new");
        });

        assertThat(first.assistantContent()).isEqualTo("cached");
        assertThat(second.assistantContent()).isEqualTo("cached");
        assertThat(invocations.get()).isEqualTo(1);
    }

    private SendMessageResponse successResponse(String content) {
        return new SendMessageResponse(true, content, 1, null, null, List.of(), null);
    }
}
