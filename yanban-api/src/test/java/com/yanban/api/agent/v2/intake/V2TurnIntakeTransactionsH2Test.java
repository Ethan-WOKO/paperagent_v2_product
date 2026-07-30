package com.yanban.api.agent.v2.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import(V2TurnIntakeTransactions.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class V2TurnIntakeTransactionsH2Test {
    @Autowired
    V2TurnIntakeTransactions transactions;
    @Autowired
    V2TurnIntakeJpaRepository intakes;
    @Autowired
    AgentSessionRepository sessions;
    @Autowired
    AgentMessageRepository messages;
    @Autowired
    AgentTurnRepository turns;

    @Test
    void exactReplayChangedPayloadAndConcurrentSameKeyUseOneTurn()
            throws Exception {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "workspace", "deepseek", "model", 20, false,
                AgentSessionScope.WORKSPACE, null));
        String digest = "a".repeat(64);

        V2TurnIntakeEntity first = transactions.open(
                7L, session.getId(), "request-1", digest,
                "question", false, null, null);
        V2TurnIntakeEntity replay = transactions.open(
                7L, session.getId(), "request-1", digest,
                "question", false, null, null);

        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.userMessageId()).isEqualTo(first.userMessageId());
        assertThatThrownBy(() -> transactions.open(
                7L, session.getId(), "request-1", "b".repeat(64),
                "changed", false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "clientRequestId was already used for another payload");

        int contenders = 8;
        var pool = Executors.newFixedThreadPool(contenders);
        try {
            List<java.util.concurrent.Future<V2TurnIntakeEntity>> futures =
                    new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                futures.add(pool.submit(() -> transactions.open(
                        7L, session.getId(), "request-2", digest,
                        "parallel", false, null, null)));
            }
            List<Long> turnIds = new ArrayList<>();
            for (var future : futures) {
                turnIds.add(future.get(10, TimeUnit.SECONDS).turnId());
            }
            assertThat(turnIds).containsOnly(turnIds.get(0));
        } finally {
            pool.shutdownNow();
        }

        assertThat(intakes.count()).isEqualTo(2);
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(
                session.getId())).hasSize(2);
        assertThat(turns.findBySessionIdAndUserIdOrderByStartedAtDescIdDesc(
                session.getId(), 7L)).hasSize(2);
    }

    @Test
    void concurrentTerminalDeliveryCreatesOneAssistantMessage()
            throws Exception {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                8L, "project", "deepseek", "model", 20, false,
                AgentSessionScope.PROJECT, 91L));
        V2TurnIntakeEntity intake = transactions.open(
                8L, session.getId(), "request-final", "c".repeat(64),
                "question", false, null, null);
        transactions.locked(intake, locked -> {
            transactions.savePersistent(
                    locked, "plan-final", "{}", "[]");
            return null;
        });
        int contenders = 8;
        var pool = Executors.newFixedThreadPool(contenders);
        try {
            List<java.util.concurrent.Future<Long>> futures =
                    new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                futures.add(pool.submit(() ->
                        transactions.savePersistentAssistant(
                                8L, session.getId(), "request-final",
                                "完成").getId()));
            }
            List<Long> ids = new ArrayList<>();
            for (var future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(
                session.getId())).hasSize(2);
    }
}
