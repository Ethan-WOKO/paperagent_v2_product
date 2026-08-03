package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ContextConfiguration(classes = AgentSessionSummaryConcurrencyTest.TestConfig.class)
@Import(AgentSessionSummaryService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentSessionSummaryConcurrencyTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AgentSession.class)
    @EnableJpaRepositories(basePackageClasses = AgentSessionRepository.class)
    static class TestConfig { }

    private final AgentSessionRepository sessions;
    private final AgentSessionSummaryRepository summaries;
    private final AgentMessageRepository messages;
    private final AgentSessionSummaryService service;

    @Autowired
    AgentSessionSummaryConcurrencyTest(
            AgentSessionRepository sessions,
            AgentSessionSummaryRepository summaries,
            AgentMessageRepository messages,
            AgentSessionSummaryService service) {
        this.sessions = sessions;
        this.summaries = summaries;
        this.messages = messages;
        this.service = service;
    }

    @Test
    void concurrentWritersSerializeOnOwnedSessionAndCannotRegress() throws Exception {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                1001L, "summary", "test", "model", 4, true));
        AgentMessage first = assistant(session.getId(), 1001L, "first");
        AgentMessage second = assistant(session.getId(), 1001L, "second");
        AgentMessage third = assistant(session.getId(), 1001L, "third");
        service.upsert(update(session.getId(), first.getId(), 1));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> lower = executor.submit(() -> runUpdate(
                    session.getId(), second.getId(), 2, ready, start, failures));
            Future<?> higher = executor.submit(() -> runUpdate(
                    session.getId(), third.getId(), 3, ready, start, failures));
            ready.await();
            start.countDown();
            lower.get();
            higher.get();
        } finally {
            executor.shutdownNow();
        }

        AgentSessionSummary stored = summaries
                .findBySessionIdAndUserId(session.getId(), 1001L)
                .orElseThrow();
        assertThat(stored.getCoveredMessageId()).isEqualTo(third.getId());
        assertThat(stored.getMessageCount()).isEqualTo(3);
        assertThat(failures).allSatisfy(failure ->
                assertThat(failure).isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void rejectsForeignAndUserRoleCoverageMessagesButAllowsLegacyNull() {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                1001L, "authority", "test", "model", 4, true));
        AgentMessage foreign = assistant(session.getId(), 2002L, "foreign");
        AgentMessage user = messages.saveAndFlush(new AgentMessage(
                session.getId(), 1001L, "user", "question", null, null));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.upsert(update(session.getId(), foreign.getId(), 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage message authority");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.upsert(update(session.getId(), user.getId(), 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage message authority");

        AgentSessionSummary legacy = service.upsert(
                update(session.getId(), null, 0));
        assertThat(legacy.getCoveredMessageId()).isNull();
    }

    private AgentMessage assistant(
            Long sessionId,
            Long userId,
            String content) {
        return messages.saveAndFlush(new AgentMessage(
                sessionId, userId, "assistant", content, null, null));
    }

    private void runUpdate(
            Long sessionId,
            Long coverage,
            int messageCount,
            CountDownLatch ready,
            CountDownLatch start,
            List<Throwable> failures) {
        ready.countDown();
        try {
            start.await();
            service.upsert(update(sessionId, coverage, messageCount));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Throwable failure) {
            synchronized (failures) {
                failures.add(failure);
            }
        }
    }

    private static AgentSessionSummaryUpdate update(
            Long sessionId,
            Long coverage,
            int messageCount) {
        return new AgentSessionSummaryUpdate(
                sessionId, 1001L, "coverage " + coverage,
                coverage, messageCount, "test", "model");
    }
}
