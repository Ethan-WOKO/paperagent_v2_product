package com.yanban.api.agent.v2.chain.api;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit short write boundaries for the Project chain turn entry only.
 *
 * <p>Programmatic transactions are intentional here: both cuts must commit
 * independently of a caller transaction and must not depend on Spring proxy
 * interception or same-class {@code @Transactional} calls.</p>
 */
@Component
public final class ProjectChainTurnEntryTransactions {
    private final TransactionTemplate beginWrite;
    private final TransactionTemplate publicCutWrite;

    public ProjectChainTurnEntryTransactions(
            PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.beginWrite = writeTemplate(
                transactionManager, "project-chain-turn-begin-write");
        this.publicCutWrite = writeTemplate(
                transactionManager, "project-chain-turn-public-cut-write");
    }

    public <T> T inBeginWrite(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return beginWrite.execute(status -> operation.get());
    }

    public <T> T inPublicCutWrite(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return publicCutWrite.execute(status -> operation.get());
    }

    private static TransactionTemplate writeTemplate(
            PlatformTransactionManager transactionManager, String name) {
        TransactionTemplate template = new TransactionTemplate(
                transactionManager);
        template.setName(name);
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        return template;
    }
}
