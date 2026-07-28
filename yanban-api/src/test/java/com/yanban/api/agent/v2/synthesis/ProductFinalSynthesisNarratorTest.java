package com.yanban.api.agent.v2.synthesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.providers.UsageMetadata;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisNarrationRequest;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisReceiptProjection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProductFinalSynthesisNarratorTest {
    @Test
    void sendsBoundedUntrustedReceiptsWithNoTools() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        var narrator = new ProductFinalSynthesisNarrator(request -> {
            captured.set(request);
            return new ModelResponse(
                    Optional.of("QUEUED"),
                    List.of(), FinishReason.STOP,
                    new UsageMetadata(1, 1, 0, Map.of()), Map.of());
        });

        assertEquals("Literature search task queued.",
                narrator.narrate(request()));
        assertTrue(captured.get().availableTools().isEmpty());
        assertTrue(captured.get().messages().get(1).content()
                .contains("UNTRUSTED DATA"));
        assertEquals(0,
                captured.get().generationOptions().maxProposedToolCalls());
    }

    @Test
    void rejectsProviderToolCalls() {
        var narrator = new ProductFinalSynthesisNarrator(request ->
                new ModelResponse(
                        Optional.empty(),
                        List.of(new ProposedToolCall(
                                "provider-call", new ToolId("unexpected"),
                                new ObjectValue(Map.of()))),
                        FinishReason.TOOL_CALLS,
                        new UsageMetadata(1, 1, 0, Map.of()), Map.of()));

        assertThrows(IllegalStateException.class,
                () -> narrator.narrate(request()));
    }

    @Test
    void rejectsProviderClaimsThatResultsWereReturned() {
        var narrator = new ProductFinalSynthesisNarrator(request ->
                new ModelResponse(
                        Optional.of("I found ten excellent papers."),
                        List.of(), FinishReason.STOP,
                        new UsageMetadata(1, 1, 0, Map.of()), Map.of()));

        assertThrows(IllegalStateException.class,
                () -> narrator.narrate(request()));
    }

    private static FinalSynthesisNarrationRequest request() {
        return new FinalSynthesisNarrationRequest(
                new TaskFrameId("task"),
                new PlanId("plan"),
                new PlanRevisionId("revision"),
                List.of(new FinalSynthesisReceiptProjection(
                        new ReceiptId("receipt"),
                        new ToolCallId("tool-call"),
                        "SUCCESS", "queued")));
    }
}
