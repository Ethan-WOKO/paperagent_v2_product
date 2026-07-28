package com.yanban.api.agent.v2.effect;

import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRequest;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolResult;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiteratureSearchEffectExecutionVerticalTest {
    @Test
    void validIntentMapsToProductToolWithOnlyServerDerivedAuthority() {
        var fixture = new LiteratureSearchEffectTestFixtures();

        var outcome = fixture.composer.execute(
                7L, 42L, fixture.command());
        assertEquals(ReceiptStatus.SUCCESS,
                outcome.result().receipt().status());
        assertFalse(outcome.replayed());
        assertNull(ToolExecutionContext.getCurrentUserId());

        ArgumentCaptor<ToolCall> call =
                ArgumentCaptor.forClass(ToolCall.class);
        verify(fixture.executor).execute(call.capture());
        assertEquals("literature_search_start", call.getValue().name());
        assertEquals("graph retrieval",
                call.getValue().arguments().path("query").asText());
        assertFalse(call.getValue().arguments().has("userId"));
        assertFalse(call.getValue().arguments().has("projectId"));
        String requestId = call.getValue().arguments()
                .path("clientRequestId").asText();
        assertEquals(86, requestId.length());
        assertEquals(true,
                requestId.matches("v2-literature-request\\.[0-9a-f]{64}"));
    }

    @Test
    void unexpectedOrOutOfRangeArgumentsFailBeforeClaimAndTool() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        var changed = fixture.intent(Map.of(
                "query", new TextValue("topic"),
                "topK", new NumberValue(BigDecimal.valueOf(21)),
                "clientRequestId", new TextValue("model-authority")));
        when(fixture.intents.find(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(changed));

        assertThrows(
                AuthenticatedLiteratureSearchEffectExecutionException.class,
                () -> fixture.composer.execute(
                        7L, 42L, fixture.command()));
        verify(fixture.claims, never()).execute(any());
        verify(fixture.executor, never()).execute(any());
        assertNull(ToolExecutionContext.getCurrentUserId());
    }

    @Test
    void rawProductFailureIsReplacedByBoundedStableReceipt() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        when(fixture.executor.execute(any())).thenReturn(
                ToolResult.failure(
                        fixture.command().toolCallId().value(),
                        "literature_search_start",
                        "secret stack trace credential=abc"));

        var receipt = fixture.composer.execute(
                7L, 42L, fixture.command()).result().receipt();
        assertEquals(ReceiptStatus.FAILURE, receipt.status());
        assertEquals("LITERATURE_START_FAILED",
                receipt.resultCode().orElseThrow());
        String error = receipt.standardError()
                .inlineText().orElseThrow();
        assertEquals("literature_search_start failed", error);
        assertFalse(error.contains("secret"));
    }

    @Test
    void replayFromClaimLayerDoesNotInvokeToolAgain() {
        var fixture = new LiteratureSearchEffectTestFixtures();
        var first = fixture.composer.execute(
                7L, 42L, fixture.command()).result();
        org.mockito.Mockito.doReturn(
                new com.yanban.api.agent.v2.persistence
                        .ProductEffectExecutionClaimResult(first, true))
                .when(fixture.claims).execute(any());

        var replay = fixture.composer.execute(
                7L, 42L, fixture.command());
        assertEquals(first, replay.result());
        assertEquals(true, replay.replayed());
        verify(fixture.executor).execute(any());
    }
}
