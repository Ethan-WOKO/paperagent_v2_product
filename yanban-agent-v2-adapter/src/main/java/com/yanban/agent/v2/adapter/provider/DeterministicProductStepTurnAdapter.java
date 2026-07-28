package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelProviderResult;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.runtime.execution.kernel.EffectIntentDecision;
import io.paperagent.v2.runtime.execution.kernel.NoEffectDecision;
import io.paperagent.v2.runtime.execution.kernel.StepTurnDecision;
import io.paperagent.v2.runtime.execution.kernel.StepTurnInput;
import io.paperagent.v2.runtime.execution.kernel.StepTurnPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministically maps one authoritative active Step to one model turn. */
public final class DeterministicProductStepTurnAdapter implements StepTurnPort {
    private final ModelProvider provider;
    private final List<ToolDescriptor> tools;
    private final Map<ToolId, ToolDescriptor> toolsById;
    private final ProductStepTurnConfiguration configuration;

    public DeterministicProductStepTurnAdapter(
            ModelProvider provider,
            List<ToolDescriptor> tools,
            ProductStepTurnConfiguration configuration) {
        if (provider == null || tools == null || configuration == null) {
            throw failure(ProductStepTurnError.INVALID_CONFIGURATION,
                    "productStepTurn");
        }
        this.provider = provider;
        this.tools = List.copyOf(tools);
        Map<ToolId, ToolDescriptor> indexed = new LinkedHashMap<>();
        for (ToolDescriptor tool : this.tools) {
            if (tool == null || indexed.put(tool.id(), tool) != null) {
                throw failure(ProductStepTurnError.INVALID_CONFIGURATION,
                        "productStepTurn.tools");
            }
        }
        this.toolsById = Map.copyOf(indexed);
        this.configuration = configuration;
    }

    @Override
    public StepTurnDecision decide(StepTurnInput input) {
        Authority authority = authority(input);
        ModelRequest request = request(authority);
        ModelProviderResult result;
        try {
            result = provider.complete(request);
        } catch (RuntimeException exception) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.PROVIDER_FAILURE,
                    "productStepTurn.provider");
        }
        if (result instanceof ProviderFailure) {
            throw failure(ProductStepTurnError.PROVIDER_FAILURE,
                    "productStepTurn.provider");
        }
        if (!(result instanceof ModelResponse response)) {
            throw failure(ProductStepTurnError.MALFORMED_RESPONSE,
                    "productStepTurn.response");
        }
        if (response.proposedToolCalls().isEmpty()) {
            if (response.assistantText().isEmpty()) {
                throw failure(ProductStepTurnError.MALFORMED_RESPONSE,
                        "productStepTurn.response");
            }
            return new NoEffectDecision();
        }
        if (response.proposedToolCalls().size() != 1) {
            throw failure(ProductStepTurnError.MULTIPLE_TOOL_CALLS,
                    "productStepTurn.response.proposedToolCalls");
        }
        ProposedToolCall call = response.proposedToolCalls().get(0);
        if (!toolsById.containsKey(call.toolId())) {
            throw failure(ProductStepTurnError.UNKNOWN_TOOL,
                    "productStepTurn.response.proposedToolCalls.toolId");
        }
        return new EffectIntentDecision(new EffectIntent(
                toolCallId(authority, request, call),
                authority.input().plan().id(),
                authority.input().activeStep().id(),
                call.toolId().value(),
                call.arguments()));
    }

    private ModelRequest request(Authority authority) {
        String binding = binding(authority);
        return new ModelRequest(
                new ModelRequestId("product-turn." + sha256("request\0" + binding)),
                new CorrelationId("product-turn." + sha256("correlation\0" + binding)),
                List.of(
                        new ModelMessage(MessageRole.SYSTEM, systemMessage()),
                        new ModelMessage(MessageRole.USER, userMessage(authority))),
                tools,
                configuration.generationOptions(),
                Optional.of(authority.input().taskFrame().id()),
                Optional.of(authority.input().plan().id()),
                Optional.of(authority.revision().id()),
                Optional.of(authority.input().activeStep().id()),
                false);
    }

    private static Authority authority(StepTurnInput input) {
        if (input == null) {
            throw failure(ProductStepTurnError.INVALID_AUTHORITY,
                    "productStepTurn.input");
        }
        try {
            PlanRevision revision = input.plan().latestRevision();
            if (!input.taskFrame().id().equals(input.plan().taskFrameId())
                    || !revision.id().equals(
                            input.checkpoint().checkpoint().revisionId())
                    || revision.number()
                            != input.checkpoint().checkpoint().revisionNumber()
                    || !revision.steps().contains(input.activeStep())) {
                throw failure(ProductStepTurnError.INVALID_AUTHORITY,
                        "productStepTurn.input.authority");
            }
            return new Authority(input, revision);
        } catch (ProductStepTurnException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.INVALID_AUTHORITY,
                    "productStepTurn.input.authority");
        }
    }

    private static String systemMessage() {
        return "Complete exactly one persisted plan step. "
                + "Use at most one allowed tool and do not invent authority.";
    }

    private static String userMessage(Authority authority) {
        var task = authority.input().taskFrame();
        var step = authority.input().activeStep();
        return "Objective: " + task.objective()
                + "\nTargets: " + String.join("; ", task.targets())
                + "\nDeliverables: " + String.join("; ", task.deliverables())
                + "\nConstraints: " + String.join("; ", task.constraints())
                + "\nStep intent: " + step.intent()
                + "\nExpected outcome: " + step.expectedOutcome()
                + "\nCompletion criteria: "
                + String.join("; ", step.completionCriteria());
    }

    private static ToolCallId toolCallId(
            Authority authority,
            ModelRequest request,
            ProposedToolCall call) {
        String stable = binding(authority)
                + "\0" + request.requestId().value()
                + "\0" + call.providerCallId();
        return new ToolCallId("product-tool-call." + sha256(stable));
    }

    private static String binding(Authority authority) {
        return authority.input().taskFrame().id().value()
                + "\0" + authority.input().plan().id().value()
                + "\0" + authority.revision().id().value()
                + "\0" + authority.input().activeStep().id().value();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ProductStepTurnException failure(
            ProductStepTurnError code, String path) {
        return new ProductStepTurnException(code, path);
    }

    private record Authority(StepTurnInput input, PlanRevision revision) {
    }
}
