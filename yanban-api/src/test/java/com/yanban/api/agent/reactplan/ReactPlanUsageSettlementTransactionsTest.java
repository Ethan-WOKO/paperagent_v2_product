package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.quota.UserQuotaService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReactPlanUsageSettlementTransactionsTest {
    private final ReactPlanUsageSettlementRepository settlements =
            mock(ReactPlanUsageSettlementRepository.class);
    private final UserQuotaService quotas = mock(UserQuotaService.class);
    private final ReactPlanUsageSettlementTransactions transactions =
            new ReactPlanUsageSettlementTransactions(settlements, quotas);

    @Test
    void chargesUsageAndMarksTheDurableRequestSettledInOneTransaction() {
        ReactPlanUsageSettlementEntity value = pending();
        when(settlements.findLocked(value.taskId())).thenReturn(Optional.of(value));

        assertThat(transactions.settle(value.taskId())).isTrue();

        verify(quotas).recordTaskUsage(7L, "REACT_PLAN", 19L, 5L);
        verify(settlements).saveAndFlush(value);
        assertThat(value.state()).isEqualTo(ReactPlanUsageSettlementEntity.SETTLED);
    }

    @Test
    void replayDoesNotChargeAnAlreadySettledTaskAgain() {
        ReactPlanUsageSettlementEntity value = pending();
        value.settle(LocalDateTime.parse("2026-08-21T08:00:01"));
        when(settlements.findLocked(value.taskId())).thenReturn(Optional.of(value));

        assertThat(transactions.settle(value.taskId())).isFalse();

        verify(quotas, never()).recordTaskUsage(7L, "REACT_PLAN", 19L, 5L);
        verify(settlements, never()).saveAndFlush(value);
    }

    private ReactPlanUsageSettlementEntity pending() {
        return new ReactPlanUsageSettlementEntity(
                "task." + "a".repeat(64), 7L, 19L, 5L,
                LocalDateTime.parse("2026-08-21T08:00:00"));
    }
}
