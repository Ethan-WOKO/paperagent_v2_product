package com.yanban.api.agent.reactplan;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
class ReactPlanConversationSummaryQueue {
    private static final Logger log = LoggerFactory.getLogger(ReactPlanConversationSummaryQueue.class);
    private final ReactPlanConversationSummaryRepository summaries;
    private final ReactPlanTurnIntakeRepository intakes;

    ReactPlanConversationSummaryQueue(ReactPlanConversationSummaryRepository summaries,
                                      ReactPlanTurnIntakeRepository intakes) {
        this.summaries = summaries;
        this.intakes = intakes;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void afterTerminal(ReactPlanConversationSummaryRequested request) {
        request(request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void catchUp(long userId, long sessionId) {
        var terminal = intakes.findTerminalByOwnerAndSession(userId, sessionId);
        if (!terminal.isEmpty()) {
            request(new ReactPlanConversationSummaryRequested(
                    userId, sessionId, terminal.get(terminal.size() - 1).id()));
        }
    }

    private void request(ReactPlanConversationSummaryRequested request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReactPlanConversationSummaryEntity value = summaries.findLocked(request.sessionId())
                .orElse(null);
        if (value != null) {
            value.request(request.userId(), request.intakeId(), now);
            summaries.save(value);
            return;
        }
        try {
            summaries.saveAndFlush(new ReactPlanConversationSummaryEntity(
                    request.sessionId(), request.userId(), request.intakeId(), now));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent terminal task may create the one-row-per-session work item first.
            log.debug("Concurrent ReAct summary request won sessionId={}", request.sessionId());
        }
    }
}
