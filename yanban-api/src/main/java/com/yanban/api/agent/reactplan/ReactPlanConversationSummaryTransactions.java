package com.yanban.api.agent.reactplan;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReactPlanConversationSummaryTransactions {
    private final ReactPlanConversationSummaryRepository summaries;

    ReactPlanConversationSummaryTransactions(ReactPlanConversationSummaryRepository summaries) {
        this.summaries = summaries;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Work claim() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<ReactPlanConversationSummaryEntity> available = summaries.findClaimable(
                now, PageRequest.of(0, 1));
        if (available.isEmpty()) return null;
        ReactPlanConversationSummaryEntity value = available.get(0);
        value.claim(now.plusMinutes(2), now);
        summaries.saveAndFlush(value);
        return new Work(value.sessionId(), value.userId(), value.summaryText(),
                value.coveredIntakeId(), value.targetIntakeId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void noWork(Work work) {
        summaries.findLocked(work.sessionId()).ifPresent(value -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            if (value.targetIntakeId() > work.targetIntakeId()) value.requeue(now);
            else value.noWork(now);
            summaries.saveAndFlush(value);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void succeed(Work work, String summary, long coveredIntakeId, int coveredTurnCount,
                 String provider, String model, boolean moreWork) {
        ReactPlanConversationSummaryEntity value = summaries.findLocked(work.sessionId())
                .orElseThrow();
        if (value.coveredIntakeId() != work.coveredIntakeId()) {
            value.requeue(LocalDateTime.now(ZoneOffset.UTC));
        } else {
            value.succeed(summary, coveredIntakeId, coveredTurnCount,
                    provider, model, moreWork || value.targetIntakeId() > work.targetIntakeId(),
                    LocalDateTime.now(ZoneOffset.UTC));
        }
        summaries.saveAndFlush(value);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(Work work, String code) {
        summaries.findLocked(work.sessionId()).ifPresent(value -> {
            value.fail(code, LocalDateTime.now(ZoneOffset.UTC));
            summaries.saveAndFlush(value);
        });
    }

    record Work(long sessionId, long userId, String existingSummary,
                long coveredIntakeId, long targetIntakeId) { }
}
