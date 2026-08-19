package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(ReactPlanTurnIntakeTransactions.class)
class ReactPlanTurnIntakePersistenceTest {
    @Autowired
    private ReactPlanTurnIntakeTransactions transactions;
    @Autowired
    private ReactPlanTurnIntakeRepository intakes;
    @Autowired
    private ReactPlanTaskCheckpointRepository checkpoints;
    @Autowired
    private AgentSessionRepository sessions;

    @Test
    void createsAProxiedAtomicMessageTurnAndIntake() {
        assertThat(AopUtils.isAopProxy(transactions)).isTrue();

        ReactPlanTurnIntakeEntity created = transactions.create(
                7L, 11L, "request.0123456789abcdef", "a".repeat(64),
                "Compile Sort.java");

        assertThat(created.turnId()).isPositive();
        assertThat(created.taskId()).isEqualTo(
                ReactPlanRuntimeService.taskId(7L, created.turnId()));
        assertThat(transactions.find(7L, 11L, "request.0123456789abcdef"))
                .hasValueSatisfying(replayed -> {
                    assertThat(replayed.turnId()).isEqualTo(created.turnId());
                    assertThat(replayed.taskId()).isEqualTo(created.taskId());
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initializesOnlyTheFirstTaskAndNeverOverwritesAManualTitle() {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "新会话", "deepseek", "deepseek-chat", 8, true,
                AgentSessionScope.PROJECT, 11L));
        ReactPlanTurnIntakeEntity first = transactions.create(
                7L, session.getId(), "request.first.0123456789", "a".repeat(64),
                "检查 Sort.java");

        assertThat(transactions.shouldInitializeTitle(
                7L, session.getId(), first.taskId())).isTrue();
        assertThat(transactions.initializeTitleIfStillEligible(
                7L, session.getId(), first.taskId(), "检查 Sort.java")).isTrue();
        assertThat(sessions.findById(session.getId()).orElseThrow().getTitle())
                .isEqualTo("检查 Sort.java");

        ReactPlanTurnIntakeEntity second = transactions.create(
                7L, session.getId(), "request.second.0123456789", "b".repeat(64),
                "第二个问题");
        assertThat(transactions.shouldInitializeTitle(
                7L, session.getId(), second.taskId())).isFalse();
        assertThat(transactions.initializeTitleIfStillEligible(
                7L, session.getId(), first.taskId(), "不应覆盖")).isFalse();
    }

    @Test
    void findsOnlyTerminalTasksWithinTheAuthorizedProject() {
        AgentSession authorized = sessions.save(new AgentSession(
                7L, "authorized", "deepseek", "deepseek-chat", 8, true,
                AgentSessionScope.PROJECT, 11L));
        AgentSession otherProject = sessions.save(new AgentSession(
                7L, "other", "deepseek", "deepseek-chat", 8, true,
                AgentSessionScope.PROJECT, 12L));
        LocalDateTime now = LocalDateTime.parse("2026-08-19T01:00:00");

        ReactPlanTurnIntakeEntity succeeded = persistIntake(
                authorized.getId(), 101L, "task." + "1".repeat(64), now.minusMinutes(3));
        persistCheckpoint(succeeded, "succeeded", now.minusMinutes(2));
        ReactPlanTurnIntakeEntity running = persistIntake(
                authorized.getId(), 102L, "task." + "2".repeat(64), now.minusMinutes(2));
        persistCheckpoint(running, "running", now.minusMinutes(1));
        ReactPlanTurnIntakeEntity foreign = persistIntake(
                otherProject.getId(), 103L, "task." + "3".repeat(64), now.minusMinutes(1));
        persistCheckpoint(foreign, "succeeded", now);

        assertThat(intakes.findTerminalHistoryCandidates(
                7L, 11L, AgentSessionScope.PROJECT, null, null, null,
                "task." + "9".repeat(64), PageRequest.of(0, 10)))
                .extracting(ReactPlanTurnIntakeEntity::taskId)
                .containsExactly(succeeded.taskId());
    }

    private ReactPlanTurnIntakeEntity persistIntake(
            long sessionId, long turnId, String taskId, LocalDateTime createdAt) {
        return intakes.save(new ReactPlanTurnIntakeEntity(
                7L, sessionId, "request-" + turnId, "a".repeat(64), turnId,
                turnId + 1000, taskId, createdAt));
    }

    private void persistCheckpoint(
            ReactPlanTurnIntakeEntity intake, String state, LocalDateTime createdAt) {
        checkpoints.save(new ReactPlanTaskCheckpointEntity(
                intake.taskId(), "b".repeat(64), intake.userId(), intake.sessionId(),
                intake.turnId(), state, 1L, "{}", createdAt));
    }
}
