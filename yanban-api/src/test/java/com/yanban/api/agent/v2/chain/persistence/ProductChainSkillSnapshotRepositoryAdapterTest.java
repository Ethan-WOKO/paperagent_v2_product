package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanban.api.agent.v2.chain.context.ProductChainTaskSkillSnapshot;
import java.sql.Connection;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProductChainSkillSnapshotRepositoryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void persistsAndReplaysTheExactTaskSnapshot() throws Exception {
        try (Harness harness = Harness.create("skill-snapshot-repository")) {
            ProductChainTaskSkillSnapshot requested =
                    ProductChainTaskSkillSnapshot.selected(
                            "task-1", "instruction-1", "review", "prompt",
                            Set.of("project.read", "literature.search"), NOW);

            ProductChainTaskSkillSnapshot stored =
                    harness.repository().append(requested);
            ProductChainTaskSkillSnapshot replay =
                    harness.repository().append(requested.copyTo(
                            "task-1", "instruction-1", NOW.plusSeconds(30)));

            assertEquals(stored, replay);
            assertEquals(stored, harness.repository().findByTaskId("task-1")
                    .orElseThrow());
            assertEquals("[\"literature.search\",\"project.read\"]",
                    stored.allowedTools().json());
        }
    }

    @Test
    void rejectsAConflictingSecondSnapshotForTheSameTask() throws Exception {
        try (Harness harness = Harness.create("skill-snapshot-conflict")) {
            harness.repository().append(ProductChainTaskSkillSnapshot.none(
                    "task-1", "instruction-1", NOW));

            ProductChainPersistenceException conflict = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> harness.repository().append(
                            ProductChainTaskSkillSnapshot.selected(
                                    "task-1", "instruction-1", "review",
                                    "prompt", Set.of(), NOW)));

            assertEquals("CHAIN_CONFLICTING_REPLAY", conflict.code());
        }
    }

    private record Harness(
            Connection connection,
            ProductChainSkillSnapshotRepositoryAdapter repository)
            implements AutoCloseable {
        static Harness create(String label) throws Exception {
            Connection connection = ChainMigrationTestSupport.database(label);
            ChainMigrationTestSupport.migrateThrough(connection, 74);
            ChainMigrationTestSupport.seedFoundation(connection);
            var dataSource = new DriverManagerDataSource(
                    connection.getMetaData().getURL(), "sa", "");
            var transactions = new ProductChainTransactions(
                    new NamedParameterJdbcTemplate(dataSource),
                    new ProductChainRecordCodec(),
                    new DataSourceTransactionManager(dataSource), () -> NOW);
            return new Harness(connection,
                    new ProductChainSkillSnapshotRepositoryAdapter(
                            transactions));
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}
