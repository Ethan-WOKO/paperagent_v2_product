package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:memory_distillation_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890",
        "yanban.memory.distillation.enabled=false"
})
class MemoryDistillationMigrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MemoryDistillationSettingRepository settings;

    @Autowired
    private MemoryDistillationJobRepository jobs;

    @Autowired
    private MemoryDistillationTransactions transactions;

    @Autowired
    private MemoryDistillationConversationService conversations;

    @Autowired
    private com.yanban.core.agent.AgentSessionRepository sessions;

    @Autowired
    private com.yanban.core.agent.AgentMessageRepository messages;

    @Autowired
    private LongTermMemoryService memories;

    @Test
    void productionMigrationPreservesOldJobCutsAndBackfillsProgress() {
        var isolated = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:memory_progress_upgrade;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new JdbcTemplate(isolated);
        sql.execute("""
                CREATE TABLE agent_memory_distillation_jobs (
                    status VARCHAR(24), from_message_id BIGINT, through_message_id BIGINT,
                    message_count INT, attempt_count INT)
                """);
        sql.update("INSERT INTO agent_memory_distillation_jobs VALUES ('SUCCEEDED', 10, 20, 6, 1), ('RUNNING', 20, 30, 8, 2), ('FAILED', 30, 40, 9, 3)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V104__add_memory_distillation_progress.sql"))
                .execute(isolated);
        var rows = sql.queryForList("SELECT * FROM agent_memory_distillation_jobs ORDER BY from_message_id");
        assertThat(rows.get(0).get("PROCESSED_THROUGH_MESSAGE_ID")).isEqualTo(20L);
        assertThat(rows.get(0).get("PROCESSED_MESSAGE_COUNT")).isEqualTo(6);
        assertThat(rows.get(1).get("PROCESSED_THROUGH_MESSAGE_ID")).isEqualTo(20L);
        assertThat(rows.get(1).get("BATCH_ATTEMPT_COUNT")).isEqualTo(2);
        assertThat(rows.get(2).get("THROUGH_MESSAGE_ID")).isEqualTo(40L);
        assertThat(rows.get(2).get("PROCESSED_MESSAGE_COUNT")).isEqualTo(0);
    }

    @Test
    void oneFrozenRunReachesFrenchPreferenceAfterNinetyOneOlderUserMessages() {
        jdbc.update("INSERT INTO sys_users (username, password_hash) VALUES (?, ?)", "backlog-user", "hash");
        long userId = jdbc.queryForObject("SELECT id FROM sys_users WHERE username = ?", Long.class, "backlog-user");
        settings.saveAndFlush(new MemoryDistillationSettingEntity(userId));
        jdbc.update("""
                INSERT INTO projects (user_id, name, root_type, root_path, canonical_root_path,
                    include_rules, ignore_rules) VALUES (?, 'backlog-project', 'LOCAL', 'fixture', 'fixture', '[]', '[]')
                """, userId);
        long projectId = jdbc.queryForObject("SELECT id FROM projects WHERE user_id=?", Long.class, userId);
        var session = sessions.saveAndFlush(new com.yanban.core.agent.AgentSession(
                userId, "backlog", "test", "test", 4, false,
                com.yanban.core.agent.AgentSessionScope.PROJECT, projectId));
        for (int i = 0; i < 91; i++) {
            messages.saveAndFlush(new com.yanban.core.agent.AgentMessage(
                    session.getId(), userId, "user", "旧问题 " + i, null, null));
        }
        var french = messages.saveAndFlush(new com.yanban.core.agent.AgentMessage(
                session.getId(), userId, "user", "以后默认使用法语进行回答", null, null));
        var job = transactions.request(userId, "MANUAL");
        assertThat(job.throughMessageId()).isEqualTo(french.getId());
        assertThat(job.messageCount()).isEqualTo(92);
        var later = messages.saveAndFlush(new com.yanban.core.agent.AgentMessage(
                session.getId(), userId, "user", "运行开始后新发的消息", null, null));

        var extractor = org.mockito.Mockito.mock(MemoryDistillationModelExtractor.class);
        java.util.List<Long> assessed = new java.util.ArrayList<>();
        org.mockito.Mockito.when(extractor.extract(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(job.id()), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    java.util.List<MemoryDistillationConversationService.ConversationLine> lines = invocation.getArgument(2);
                    assertThat(lines).hasSizeLessThanOrEqualTo(12);
                    assertThat(lines).allSatisfy(line -> {
                        assertThat(line.scope()).isEqualTo("PROJECT");
                        assertThat(line.projectId()).isEqualTo(projectId);
                    });
                    lines.forEach(line -> assessed.add(line.messageId()));
                    return lines.stream().anyMatch(line -> line.messageId() == french.getId())
                            ? java.util.List.of(new MemoryDistillationCandidate("USER", null, "PREFERENCE",
                            "用户偏好默认使用法语回答。", java.util.List.of(), new java.math.BigDecimal("0.95"),
                            java.util.List.of(french.getId()))) : java.util.List.of();
                });
        var worker = new MemoryDistillationWorker(transactions, conversations, extractor, Runnable::run);
        for (int batch = 0; batch < 8; batch++) {
            assertThat(transactions.request(userId, "MANUAL").id()).isEqualTo(job.id());
            worker.scan();
            var current = jobs.findById(job.id()).orElseThrow();
            assertThat(current.status()).isEqualTo(batch == 7 ? "SUCCEEDED" : "PENDING");
            assertThat(current.processedMessageCount()).isEqualTo(Math.min(92, (batch + 1) * 12));
        }
        assertThat(assessed).hasSize(92).doesNotHaveDuplicates().contains(french.getId()).doesNotContain(later.getId());
        assertThat(memories.listMemories(userId, "ACTIVE", 50)).singleElement().satisfies(memory -> {
            assertThat(memory.content()).isEqualTo("用户偏好默认使用法语回答。");
            assertThat(memory.confirmationStatus()).isEqualTo("UNCONFIRMED");
        });
        var next = transactions.request(userId, "MANUAL");
        assertThat(next.fromMessageId()).isEqualTo(french.getId());
        assertThat(next.throughMessageId()).isEqualTo(later.getId());
        assertThat(next.messageCount()).isEqualTo(1);
        // Leave no claimable fixture for the other tests in this shared context.
        jdbc.update("UPDATE agent_memory_distillation_jobs SET status='FAILED' WHERE id=?", next.id());
    }

    @Test
    void migrationCreatesSettingsJobsAndDistilledSourceUniqueness() throws SQLException {
        assertThat(columns("agent_memory_distillation_settings")).contains(
                "user_id", "auto_enabled", "last_processed_message_id", "next_run_at", "last_success_at");
        assertThat(columns("agent_memory_distillation_jobs")).contains(
                "id", "user_id", "trigger_type", "status", "from_message_id", "through_message_id",
                "message_count", "candidate_count", "created_memory_count", "attempt_count",
                "claimed_until", "error_code", "error_message", "started_at", "finished_at",
                "processed_through_message_id", "processed_message_count", "batch_attempt_count");
        assertThat(indexes("agent_memory_distillation_settings"))
                .contains("idx_memory_distillation_settings_due");
        assertThat(indexes("agent_memory_distillation_jobs"))
                .contains("idx_memory_distillation_jobs_user_created", "idx_memory_distillation_jobs_claim");
        assertThat(indexes("agent_long_term_memories")).contains("uk_ltm_distilled_source");
    }

    @Test
    void automaticDistillationDefaultsToDisabled() {
        jdbc.update("INSERT INTO sys_users (username, password_hash) VALUES (?, ?)", "distill-user", "hash");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username = ?", Long.class, "distill-user");
        jdbc.update("INSERT INTO agent_memory_distillation_settings (user_id) VALUES (?)", userId);

        Boolean enabled = jdbc.queryForObject(
                "SELECT auto_enabled FROM agent_memory_distillation_settings WHERE user_id = ?",
                Boolean.class, userId);
        Long cursor = jdbc.queryForObject(
                "SELECT last_processed_message_id FROM agent_memory_distillation_settings WHERE user_id = ?",
                Long.class, userId);

        assertThat(enabled).isFalse();
        assertThat(cursor).isZero();
    }

    @Test
    @Transactional
    void deletedAccountsAreExcludedFromSchedulingAndWorkerClaims() {
        jdbc.update("INSERT INTO sys_users (username, password_hash) VALUES (?, ?)", "deleted-distill-user", "hash");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username = ?", Long.class, "deleted-distill-user");
        jdbc.update("""
                INSERT INTO agent_memory_distillation_settings
                    (user_id, auto_enabled, last_processed_message_id, next_run_at)
                VALUES (?, TRUE, 0, DATEADD('MINUTE', -1, CURRENT_TIMESTAMP))
                """, userId);
        jdbc.update("""
                INSERT INTO agent_memory_distillation_jobs
                    (user_id, trigger_type, status, from_message_id, through_message_id, message_count)
                VALUES (?, 'AUTO', 'PENDING', 0, 5, 2)
                """, userId);
        jdbc.update("UPDATE sys_users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", userId);

        assertThat(settings.findDue(Instant.now(), PageRequest.of(0, 20))).isEmpty();
        assertThat(jobs.findClaimable(Instant.now(), PageRequest.of(0, 20))).isEmpty();
    }

    private Set<String> columns(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(null, null, tableName, null)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString("COLUMN_NAME").toLowerCase());
            return names;
        }
    }

    private Set<String> indexes(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            Set<String> names = new HashSet<>();
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) names.add(name.toLowerCase());
            }
            return names;
        }
    }
}
