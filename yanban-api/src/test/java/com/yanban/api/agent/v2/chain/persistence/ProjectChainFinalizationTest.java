package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainFinalizationTest {
    private static final String SHA = "0".repeat(64);
    private static final Instant NOW = Instant.parse(
            "2026-08-07T10:00:00Z");

    @Test
    void ordinaryReadJoinsFinalizationWriteTransaction() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "chain-finalization-write-read")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            String url = connection.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var manager = new DataSourceTransactionManager(dataSource);
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var transactions = new ProductChainTransactions(
                    jdbc, new ProductChainRecordCodec(), manager,
                    () -> NOW);
            var foundations = new ProductChainFoundationRepositoryAdapter(
                    transactions, () -> NOW);
            var outer = new TransactionTemplate(manager);

            outer.executeWithoutResult(status -> {
                ChainPersistenceRecords.CommandRecord command =
                        new ChainPersistenceRecords.CommandRecord(
                                "command-write-read", 7, 8,
                                "request-write-read",
                                ChainInstructionRelation.INITIAL,
                                null, null, null, SHA, 9L, 10L,
                                null, null, null,
                                ChainCommandStatus.RECEIVED, null,
                                NOW, null);
                assertFalse(foundations.registerCommand(command).replayed());
                assertTrue(foundations.findCommand(
                        command.commandId()).isPresent());
                status.setRollbackOnly();
            });
        }
    }
}
