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
    void returnsRuntimeResultWithoutCallingAnotherModel() {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        var narrator = new ProductFinalSynthesisNarrator(request -> {
            calls.incrementAndGet();
            throw new AssertionError("model must not be called");
        });

        assertEquals("Literature search task queued.",
                narrator.narrate(request()));
        assertEquals(0, calls.get());
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
