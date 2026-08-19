package com.yanban.api.agent.reactplan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReactPlanConversationSummaryWorkerTest {
    private final ReactPlanConversationSummaryTransactions transactions =
            mock(ReactPlanConversationSummaryTransactions.class);
    private final ReactPlanConversationContextService contexts =
            mock(ReactPlanConversationContextService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final ChatModelProvider models = mock(ChatModelProvider.class);
    private final ReactPlanConversationSummaryWorker worker =
            new ReactPlanConversationSummaryWorker(
                    new ObjectMapper(), transactions, contexts, settings, models);

    @Test
    void compressesOnlyTurnsOlderThanTheRecentFour() {
        var work = new ReactPlanConversationSummaryTransactions.Work(11L, 7L, null, 0L, 5L);
        when(transactions.claim()).thenReturn(work);
        when(contexts.terminalTurns(7L, 11L)).thenReturn(turns(5));
        when(settings.resolveModelEndpoint(7L, null, null)).thenReturn(endpoint());
        when(models.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("Earlier request and outcome."), "stop",
                new ChatResponse.Usage(10, 5, 15)));

        worker.scan();

        verify(transactions).succeed(work, "Earlier request and outcome.",
                1L, 1, "test", "test-model", false);
        verify(transactions, never()).fail(any(), anyString());
    }

    @Test
    void modelFailureLeavesTheDurableWorkFailedWithoutThrowingIntoTaskCompletion() {
        var work = new ReactPlanConversationSummaryTransactions.Work(11L, 7L, null, 0L, 5L);
        when(transactions.claim()).thenReturn(work);
        when(contexts.terminalTurns(7L, 11L)).thenReturn(turns(5));
        when(settings.resolveModelEndpoint(7L, null, null)).thenReturn(endpoint());
        when(models.chat(any())).thenThrow(new IllegalStateException("provider unavailable"));

        worker.scan();

        verify(transactions).fail(work, "IllegalStateException");
        verify(transactions, never()).succeed(any(), anyString(), anyLong(), anyInt(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private List<ReactPlanConversationContextService.ConversationTurn> turns(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new ReactPlanConversationContextService.ConversationTurn(
                        index, index, "instruction " + index, "conclusion " + index,
                        "succeeded", "b".repeat(64), "2026-08-18T00:00:00Z"))
                .toList();
    }

    private UserSettingsService.ModelEndpoint endpoint() {
        return new UserSettingsService.ModelEndpoint(
                "test", "test-model", null, "secret", "custom", "Test");
    }
}
