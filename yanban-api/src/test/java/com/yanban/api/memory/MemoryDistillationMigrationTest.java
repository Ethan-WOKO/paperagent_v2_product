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

    @Test
    void migrationCreatesSettingsJobsAndDistilledSourceUniqueness() throws SQLException {
        assertThat(columns("agent_memory_distillation_settings")).contains(
                "user_id", "auto_enabled", "last_processed_message_id", "next_run_at", "last_success_at");
        assertThat(columns("agent_memory_distillation_jobs")).contains(
                "id", "user_id", "trigger_type", "status", "from_message_id", "through_message_id",
                "message_count", "candidate_count", "created_memory_count", "attempt_count",
                "claimed_until", "error_code", "error_message", "started_at", "finished_at");
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
