package com.yanban.api.agent.v2.chain.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainTurnEntryTransactionsTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private ProjectChainTurnEntryTransactions transactions;

    @BeforeEach
    void setUp() {
        String database = "project-chain-turn-entry-"
                + UUID.randomUUID().toString().replace("-", "");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1", "sa", "");
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE boundary_events (label VARCHAR(64))");
        transactions = new ProjectChainTurnEntryTransactions(
                transactionManager);
    }

    @Test
    void beginWriteHasAnIndependentCommitBoundary() {
        assertCommitsOutsideCallerRollback(() ->
                transactions.inBeginWrite(() -> insert("begin")), "begin");
    }

    @Test
    void publicCutWriteHasAnIndependentCommitBoundary() {
        assertCommitsOutsideCallerRollback(() ->
                transactions.inPublicCutWrite(
                        () -> insert("public-cut")), "public-cut");
    }

    @Test
    void exceptionsRollbackEachIndependentBoundary() {
        assertThrows(BoundaryFailure.class, () ->
                transactions.inBeginWrite(() -> insertThenFail("begin")));
        assertThrows(BoundaryFailure.class, () ->
                transactions.inPublicCutWrite(
                        () -> insertThenFail("public-cut")));

        assertEquals(0, eventCount());
    }

    @Test
    void directCallsDoNotDependOnTransactionalProxyOrSelfInvocation()
            throws Exception {
        assertFalse(hasTransactionalAnnotation(
                ProjectChainTurnEntryTransactions.class.getAnnotations()));
        for (String methodName : List.of(
                "inBeginWrite", "inPublicCutWrite")) {
            Method method = ProjectChainTurnEntryTransactions.class
                    .getMethod(methodName, Supplier.class);
            assertFalse(hasTransactionalAnnotation(method.getAnnotations()));
        }

        assertEquals("committed", transactions.inBeginWrite(() -> {
            assertTrue(TransactionSynchronizationManager
                    .isActualTransactionActive());
            jdbc.update("INSERT INTO boundary_events(label) VALUES (?)",
                    "direct-call");
            return "committed";
        }));
        assertEquals(1, eventCount());
    }

    private void assertCommitsOutsideCallerRollback(
            Runnable boundaryWrite, String expectedLabel) {
        var outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            boundaryWrite.run();
            status.setRollbackOnly();
        });

        assertEquals(List.of(expectedLabel), jdbc.queryForList(
                "SELECT label FROM boundary_events", String.class));
    }

    private String insert(String label) {
        assertTrue(TransactionSynchronizationManager
                .isActualTransactionActive());
        jdbc.update("INSERT INTO boundary_events(label) VALUES (?)", label);
        return label;
    }

    private String insertThenFail(String label) {
        insert(label);
        throw new BoundaryFailure();
    }

    private int eventCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boundary_events", Integer.class);
        return count == null ? 0 : count;
    }

    private static boolean hasTransactionalAnnotation(
            java.lang.annotation.Annotation[] annotations) {
        return java.util.Arrays.stream(annotations).anyMatch(annotation ->
                annotation.annotationType().getName().equals(
                        "org.springframework.transaction.annotation.Transactional"));
    }

    private static final class BoundaryFailure extends RuntimeException {
    }
}
