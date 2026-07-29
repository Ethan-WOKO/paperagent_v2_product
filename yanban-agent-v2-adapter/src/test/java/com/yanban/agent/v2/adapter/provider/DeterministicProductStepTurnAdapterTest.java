package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.providers.ProviderFailureCode;
import io.paperagent.v2.providers.UsageMetadata;
import io.paperagent.v2.runtime.execution.kernel.EffectIntentDecision;
import io.paperagent.v2.runtime.execution.kernel.NoEffectDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicProductStepTurnAdapterTest {
    private static final ToolDescriptor TOOL = new ToolDescriptor(
            new ToolId("literature.search"), "search literature", Set.of());

    @Test
    void allowedCallProducesDeterministicAuthorityBoundIntent() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider = request -> {
            captured.set(request);
            return toolResponse(
                    "transient-provider-call-" + calls.incrementAndGet(),
                    TOOL.id());
        };
        var adapter = adapter(provider);
        var input = ProductProviderAdapterTestFixtures.input("effect");

        EffectIntentDecision first = assertInstanceOf(
                EffectIntentDecision.class, adapter.decide(input));
        EffectIntentDecision replay = assertInstanceOf(
                EffectIntentDecision.class, adapter.decide(input));

        assertEquals(first, replay);
        assertEquals(input.plan().id(), first.intent().planId());
        assertEquals(input.activeStep().id(), first.intent().stepId());
        assertEquals("literature.search", first.intent().kind());
        ModelRequest request = captured.get();
        assertEquals(Optional.of(input.taskFrame().id()), request.taskFrameId());
        assertEquals(Optional.of(input.plan().id()), request.planId());
        assertEquals(Optional.of(input.plan().latestRevision().id()),
                request.planRevisionId());
        assertEquals(Optional.of(input.activeStep().id()), request.stepId());
        assertEquals(1, request.generationOptions().maxProposedToolCalls());
        assertTrue(request.messages().get(0).content().contains(
                "must call exactly the one provided tool"));
        assertTrue(request.messages().get(1).content().contains(
                "Call literature_search exactly once"));
        assertFalse(request.messages().get(1).content().contains(
                "Call literature.search exactly once"));
    }

    @Test
    void persistedSelectorExposesExactlyOneToolAndRejectsAnotherKind() {
        AtomicInteger calls = new AtomicInteger();
        ToolDescriptor secondTool = new ToolDescriptor(
                new ToolId("paper.polish"), "polish paper", Set.of());
        ModelProvider provider = request -> {
            int call = calls.incrementAndGet();
            return new ModelResponse(
                    Optional.empty(),
                    List.of(new ProposedToolCall(
                            "transient-" + call,
                            call == 1 ? TOOL.id() : secondTool.id(),
                            new ObjectValue(Map.of(
                                    "query",
                                    new TextValue("agents-" + call))))),
                    FinishReason.TOOL_CALLS,
                    usage(),
                    Map.of());
        };
        var adapter = new DeterministicProductStepTurnAdapter(
                provider,
                input -> List.of(TOOL),
                new ProductStepTurnConfiguration(512, 0.1d));
        var input = ProductProviderAdapterTestFixtures.input("conflict-slot");

        EffectIntentDecision first = assertInstanceOf(
                EffectIntentDecision.class, adapter.decide(input));
        ProductStepTurnException changed = assertThrows(
                ProductStepTurnException.class, () -> adapter.decide(input));
        assertEquals(ProductStepTurnError.UNKNOWN_TOOL, changed.code());
        assertEquals("literature.search", first.intent().kind());
    }

    @Test
    void arbitraryPersistedIntentDoesNotBecomeProviderProtocol() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelProvider provider = request -> {
            captured.set(request);
            return toolResponse("transient-arbitrary-intent", TOOL.id());
        };
        var adapter = adapter(provider);
        var input = ProductProviderAdapterTestFixtures.input(
                "arbitrary-intent",
                "Search the available literature and retain relevant results");

        assertInstanceOf(EffectIntentDecision.class, adapter.decide(input));

        String prompt = captured.get().messages().get(1).content();
        assertTrue(prompt.contains(
                "Search the available literature and retain relevant results"));
        assertTrue(prompt.contains(
                "Provider callable function: literature_search"));
        assertFalse(prompt.contains(
                "Internal authoritative ToolId"));
    }

    @Test
    void emptyOrFailingSelectorRejectsBeforeProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelProvider provider = request -> {
            providerCalls.incrementAndGet();
            return toolResponse("unexpected", TOOL.id());
        };
        var empty = new DeterministicProductStepTurnAdapter(
                provider, input -> List.of(),
                new ProductStepTurnConfiguration(512, 0.1d));
        var failed = new DeterministicProductStepTurnAdapter(
                provider, input -> {
                    throw new IllegalStateException("private authority");
                },
                new ProductStepTurnConfiguration(512, 0.1d));

        assertEquals(ProductStepTurnError.INVALID_AUTHORITY,
                assertThrows(ProductStepTurnException.class,
                        () -> empty.decide(
                                ProductProviderAdapterTestFixtures.input(
                                        "empty-selector"))).code());
        assertEquals(ProductStepTurnError.INVALID_AUTHORITY,
                assertThrows(ProductStepTurnException.class,
                        () -> failed.decide(
                                ProductProviderAdapterTestFixtures.input(
                                        "failed-selector"))).code());
        assertEquals(0, providerCalls.get());
    }

    @Test
    void assistantOnlyProducesNoEffect() {
        var adapter = adapter(request -> new ModelResponse(
                Optional.of("done"),
                List.of(),
                FinishReason.STOP,
                usage(),
                Map.of()));

        assertInstanceOf(NoEffectDecision.class,
                adapter.decide(ProductProviderAdapterTestFixtures.input("none")));
    }

    @Test
    void providerFailureMultipleCallsAndUnknownToolFailClosed() {
        assertCode(ProductStepTurnError.PROVIDER_FAILURE,
                adapter(request -> new ProviderFailure(
                        ProviderFailureCode.UNAVAILABLE,
                        "bounded",
                        Map.of())));
        assertCode(ProductStepTurnError.MULTIPLE_TOOL_CALLS,
                adapter(request -> new ModelResponse(
                        Optional.empty(),
                        List.of(
                                call("one", TOOL.id()),
                                call("two", TOOL.id())),
                        FinishReason.TOOL_CALLS,
                        usage(),
                        Map.of())));
        assertCode(ProductStepTurnError.UNKNOWN_TOOL,
                adapter(request -> toolResponse(
                        "one", new ToolId("unknown.tool"))));
    }

    @Test
    void diagnosticsDoNotExposePromptOutputOrProviderDetails() {
        String secret = "payload-never-visible";
        var adapter = adapter(request -> {
            throw new IllegalStateException(secret);
        });
        ProductStepTurnException failure = assertThrows(
                ProductStepTurnException.class,
                () -> adapter.decide(
                        ProductProviderAdapterTestFixtures.input("redaction")));
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(failure.toString().contains(secret));
    }

    private static DeterministicProductStepTurnAdapter adapter(
            ModelProvider provider) {
        return new DeterministicProductStepTurnAdapter(
                provider,
                List.of(TOOL),
                new ProductStepTurnConfiguration(512, 0.1d));
    }

    private static void assertCode(
            ProductStepTurnError code,
            DeterministicProductStepTurnAdapter adapter) {
        ProductStepTurnException failure = assertThrows(
                ProductStepTurnException.class,
                () -> adapter.decide(
                        ProductProviderAdapterTestFixtures.input(code.name())));
        assertEquals(code, failure.code());
    }

    private static ModelResponse toolResponse(
            String providerCallId, ToolId toolId) {
        return new ModelResponse(
                Optional.empty(),
                List.of(call(providerCallId, toolId)),
                FinishReason.TOOL_CALLS,
                usage(),
                Map.of());
    }

    private static ProposedToolCall call(
            String providerCallId, ToolId toolId) {
        return new ProposedToolCall(
                providerCallId,
                toolId,
                new ObjectValue(Map.of("query", new TextValue("agents"))));
    }

    private static UsageMetadata usage() {
        return new UsageMetadata(1, 1, 0, Map.of());
    }
}
