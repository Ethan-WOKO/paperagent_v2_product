package com.yanban.api.agent.reactplan;

import com.yanban.api.quota.UserQuotaService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReactPlanUsageSettlementTransactions {
    private static final int RECOVERY_BATCH_SIZE = 100;
    private final ReactPlanUsageSettlementRepository settlements;
    private final UserQuotaService quotas;

    ReactPlanUsageSettlementTransactions(
            ReactPlanUsageSettlementRepository settlements,
            UserQuotaService quotas) {
        this.settlements = settlements;
        this.quotas = quotas;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean settle(String taskId) {
        ReactPlanUsageSettlementEntity value = settlements.findLocked(taskId)
                .orElse(null);
        if (value == null || ReactPlanUsageSettlementEntity.SETTLED.equals(value.state())) {
            return false;
        }
        quotas.recordTaskUsage(value.userId(), "REACT_PLAN",
                value.promptTokens(), value.completionTokens());
        value.settle(LocalDateTime.now(ZoneOffset.UTC));
        settlements.saveAndFlush(value);
        return true;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    List<String> pendingTaskIds() {
        return settlements.findByStateOrderByCreatedAtAsc(
                        ReactPlanUsageSettlementEntity.PENDING,
                        PageRequest.of(0, RECOVERY_BATCH_SIZE)).stream()
                .map(ReactPlanUsageSettlementEntity::taskId)
                .toList();
    }
}
