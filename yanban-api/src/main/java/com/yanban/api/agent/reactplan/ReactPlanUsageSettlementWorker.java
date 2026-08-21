package com.yanban.api.agent.reactplan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
class ReactPlanUsageSettlementWorker {
    private static final Logger log = LoggerFactory.getLogger(
            ReactPlanUsageSettlementWorker.class);
    private final ReactPlanUsageSettlementTransactions transactions;

    ReactPlanUsageSettlementWorker(ReactPlanUsageSettlementTransactions transactions) {
        this.transactions = transactions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterTerminalCheckpoint(ReactPlanUsageSettlementRequested request) {
        settleSafely(request.taskId());
    }

    @Scheduled(fixedDelayString = "${yanban.agent.reactplan.usage-settlement-scan-ms:2000}")
    void recoverPending() {
        for (String taskId : transactions.pendingTaskIds()) {
            settleSafely(taskId);
        }
    }

    private void settleSafely(String taskId) {
        try {
            if (transactions.settle(taskId)) {
                log.info("reactplan_usage taskId={} traceId={} outcome=settled",
                        taskId, ReactPlanTraceIds.forTask(taskId));
            }
        } catch (RuntimeException failure) {
            log.warn("reactplan_usage taskId={} traceId={} outcome=deferred reason={}",
                    taskId, ReactPlanTraceIds.forTask(taskId),
                    failure.getClass().getSimpleName());
        }
    }
}
