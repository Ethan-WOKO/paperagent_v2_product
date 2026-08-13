package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.BindingRole;
import io.paperagent.v2.chain.ChainPersistenceRecords.CommandRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.InstructionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskInstructionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductChainRepositoryConcurrencyTest {
    private static final String HASH = "0".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-06T11:00:00Z");

    @Test
    void equivalentEventFactContendersAllocateOnceAndReplayWinner()
            throws Exception {
        try (Connection keeper = ChainMigrationTestSupport.database(
                "chain-concurrency")) {
            ChainMigrationTestSupport.migrateThrough(keeper, 73);
            var dataSource = new DriverManagerDataSource(
                    keeper.getMetaData().getURL(), "sa", "");
            var transactions = new ProductChainTransactions(
                    new NamedParameterJdbcTemplate(dataSource),
                    new ProductChainRecordCodec(),
                    new DataSourceTransactionManager(dataSource), () -> NOW);
            var repository = new ProductChainFoundationRepositoryAdapter(
                    transactions, () -> NOW);
            seed(repository);

            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> append(repository, start, NOW));
                var second = pool.submit(() -> append(
                        repository, start, NOW.plusSeconds(30)));
                start.countDown();
                List<AuthoritativeAppendResult<TaskInstructionBindingRecord>>
                        results = List.of(first.get(), second.get());

                assertEquals(1, results.stream().filter(
                        result -> !result.replayed()).count());
                assertEquals(1, results.stream().filter(
                        AuthoritativeAppendResult::replayed).count());
                assertEquals(1,
                        repository.highestAuthorityEventSequence("task-1"));
                assertEquals(1,
                        repository.findTask("task-1").orElseThrow()
                                .nextEventSequence());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static AuthoritativeAppendResult<TaskInstructionBindingRecord>
            append(ProductChainFoundationRepositoryAdapter repository,
                    CountDownLatch start, Instant auditTime) throws Exception {
        start.await();
        TaskInstructionBindingRecord fact =
                new TaskInstructionBindingRecord(
                        "task-1", "event-1", "instruction-1", 1,
                        BindingRole.ORIGIN, auditTime);
        return repository.appendTaskInstructionBinding(
                new AuthoritativeFact<>(new AuthorityEventRequest(
                        "event-1", "task-1", "INSTRUCTION_BOUND", null,
                        HASH, auditTime), fact));
    }

    private static void seed(
            ProductChainFoundationRepositoryAdapter repository) {
        repository.registerCommand(new CommandRecord(
                "command-1", 7, 8, "request-1",
                ChainInstructionRelation.INITIAL, null, null, null, HASH,
                9L, 10L, null, null, null, ChainCommandStatus.RECEIVED,
                null, NOW, null));
        repository.appendTask(new TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                7, 8, 9, 10L, "request-1", HASH,
                null, null, 0, NOW));
        repository.appendInstruction(new InstructionRecord(
                "instruction-1", "command-1", 8, "task-1", 10L,
                HASH, "MESSAGE:10", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW));
    }
}
