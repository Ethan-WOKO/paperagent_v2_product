package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentMessageCacheService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductEngineTurnTransactionsTest {
    private final ProductEngineTurnRepository repository = mock(ProductEngineTurnRepository.class);
    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final AgentTurnRepository turns = mock(AgentTurnRepository.class);
    private final AgentMessageCacheService messageCache = mock(AgentMessageCacheService.class);
    private final ProductEngineTurnTransactions transactions =
            new ProductEngineTurnTransactions(repository, messages, turns, messageCache, new ObjectMapper());
    private ProductEngineTurnEntity entity;

    @BeforeEach
    void setUp() {
        entity = new ProductEngineTurnEntity(ProductEngineMode.DSH, 1, 2, 3, "a".repeat(64),
                "root", "task." + "b".repeat(64), "c".repeat(64), "d".repeat(64),
                "{}", "question", 11, 12);
        when(repository.findLockedByUserIdAndSessionIdAndRootClientRequestId(1L, 2L, "root"))
                .thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void rejectsSucceededBeforeDelivery() {
        assertThatThrownBy(() -> transactions.apply(1, 2, "root", List.of(status(1, "succeeded"))))
                .isInstanceOf(ProductEngineControlException.class)
                .hasMessage("ENGINE_DELIVERY_ORDER_INVALID");
    }

    @Test
    void persistsDeliveryBeforeCompletingCanonicalProductTurn() {
        AgentTurn turn = new AgentTurn(2L, 1L, 11L);
        AgentMessage assistant = mock(AgentMessage.class);
        when(assistant.getId()).thenReturn(77L);
        when(turns.findByIdAndUserId(12L, 1L)).thenReturn(Optional.of(turn));
        when(messages.saveAndFlush(any())).thenReturn(assistant);
        when(turns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        ProductEngineTurnEntity result = transactions.apply(1, 2, "root", List.of(
                delivery(1, "done", List.of("receipt.1")), status(2, "succeeded")));

        assertThat(result.engineState()).isEqualTo("succeeded");
        assertThat(result.finalText()).isEqualTo("done");
        assertThat(result.assistantMessageId()).isEqualTo(77L);
        assertThat(turn.getStatus()).isEqualTo(AgentTurn.STATUS_COMPLETED);
        verify(turns).saveAndFlush(turn);
        verify(messageCache).evictSession(1L, 2L);
        verify(messageCache).putTurnStatus(12L, AgentTurn.STATUS_COMPLETED, null);
    }

    @Test
    void rejectsSequenceGapsAndEventsAfterTerminal() {
        assertThatThrownBy(() -> transactions.apply(1, 2, "root", List.of(status(2, "running"))))
                .isInstanceOf(ProductEngineControlException.class)
                .hasMessage("ENGINE_EVENT_SEQUENCE_GAP");
        AgentTurn turn = new AgentTurn(2L, 1L, 11L);
        when(turns.findByIdAndUserId(12L, 1L)).thenReturn(Optional.of(turn));
        when(turns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        transactions.apply(1, 2, "root", List.of(status(1, "failed")));
        assertThatThrownBy(() -> transactions.apply(1, 2, "root", List.of(status(2, "failed"))))
                .isInstanceOf(ProductEngineControlException.class)
                .hasMessage("ENGINE_EVENT_AFTER_TERMINAL");
    }

    private ProductEngineDtos.Event status(long sequence, String state) {
        ProductEngineDtos.Problem problem = "failed".equals(state)
                ? new ProductEngineDtos.Problem("1.0", "MODEL_LOOP_FAILED", "model", "stable", false, null)
                : null;
        return new ProductEngineDtos.Event("1.0", entity.engineTaskId(), sequence, Instant.EPOCH,
                "status", state, problem,
                null, null, null, null, null, null, null, null, null, List.of());
    }

    private ProductEngineDtos.Event delivery(long sequence, String conclusion, List<String> receipts) {
        return new ProductEngineDtos.Event("1.0", entity.engineTaskId(), sequence, Instant.EPOCH,
                "delivery", null, null,
                null, null, null, null, null, null, null, null, conclusion, receipts);
    }
}
