package io.paperagent.v2.providers;

import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCall;
import io.paperagent.v2.contracts.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderValuesTest {
    @Test
    void textOnlyResponseAndOrderedMessagesAreDefensivelyImmutable() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage(MessageRole.SYSTEM, "system"));
        messages.add(new ModelMessage(MessageRole.USER, "user"));
        Map<String, String> deterministicOptions = new LinkedHashMap<>();
        deterministicOptions.put("mode", "strict");

        ModelRequest request = new ModelRequest(
                new ModelRequestId("request-1"),
                new CorrelationId("correlation-1"),
                messages,
                List.of(ProviderFixtures.tool("search")),
                new GenerationOptions(
                        128,
                        2,
                        0.0d,
                        OptionalLong.of(7),
                        deterministicOptions),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);

        messages.clear();
        deterministicOptions.clear();
        assertEquals(List.of(MessageRole.SYSTEM, MessageRole.USER),
                request.messages().stream().map(ModelMessage::role).toList());
        assertEquals(Map.of("mode", "strict"),
                request.generationOptions().deterministicOptions());
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(new ModelMessage(MessageRole.USER, "later")));
        assertThrows(UnsupportedOperationException.class,
                () -> request.generationOptions().deterministicOptions().put("x", "y"));

        ModelResponse response = ProviderFixtures.textResponse("answer");
        assertEquals(Optional.of("answer"), response.assistantText());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertTrue(response.proposedToolCalls().isEmpty());
    }

    @Test
    void singleAndMultipleToolProposalsPreserveOrderAndArguments() {
        ProposedToolCall first = ProviderFixtures.proposedCall("call-1", "search", "alpha");
        ProposedToolCall second = ProviderFixtures.proposedCall("call-2", "read", "beta");
        ModelResponse response = new ModelResponse(
                Optional.of("I will inspect both sources."),
                List.of(first, second),
                FinishReason.TOOL_CALLS,
                ProviderFixtures.usage(),
                Map.of());

        assertEquals(List.of("call-1", "call-2"),
                response.proposedToolCalls().stream()
                        .map(ProposedToolCall::providerCallId)
                        .toList());
        assertEquals(
                new TextValue("beta"),
                response.proposedToolCalls().get(1).arguments().values().get("query"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> response.proposedToolCalls().clear());
    }

    @Test
    void proposedToolCallIsNotAnExecutedToolCall() {
        assertFalse(ToolCall.class.isAssignableFrom(ProposedToolCall.class));
        List<String> componentNames = java.util.Arrays.stream(
                        ProposedToolCall.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertEquals(List.of("providerCallId", "toolId", "arguments"), componentNames);
        assertFalse(componentNames.contains("requestedAt"));
        assertFalse(componentNames.contains("taskFrameId"));
    }

    @Test
    void rejectsEmptyAndContradictoryResponseCombinationsWithStableCode() {
        assertValidation(
                ProviderValidationCode.INVALID_RESPONSE_COMBINATION,
                () -> new ModelResponse(
                        Optional.empty(),
                        List.of(),
                        FinishReason.STOP,
                        ProviderFixtures.usage(),
                        Map.of()));
        assertValidation(
                ProviderValidationCode.INVALID_RESPONSE_COMBINATION,
                () -> new ModelResponse(
                        Optional.of("text"),
                        List.of(),
                        FinishReason.TOOL_CALLS,
                        ProviderFixtures.usage(),
                        Map.of()));
        assertValidation(
                ProviderValidationCode.INVALID_RESPONSE_COMBINATION,
                () -> new ModelResponse(
                        Optional.of("text"),
                        List.of(ProviderFixtures.proposedCall("call-1", "search", "q")),
                        FinishReason.STOP,
                        ProviderFixtures.usage(),
                        Map.of()));
    }

    @Test
    void rejectsInvalidUsageDuplicateProviderCallIdsAndNullElements() {
        assertValidation(
                ProviderValidationCode.INVALID_USAGE_COUNT,
                () -> new UsageMetadata(-1, 0, 0, Map.of()));
        assertValidation(
                ProviderValidationCode.INVALID_USAGE_COUNT,
                () -> new UsageMetadata(0, 0, 0, Map.of("custom", -1L)));

        ProposedToolCall duplicate = ProviderFixtures.proposedCall("call-1", "search", "q");
        assertValidation(
                ProviderValidationCode.DUPLICATE_ID,
                () -> new ModelResponse(
                        Optional.empty(),
                        List.of(duplicate, duplicate),
                        FinishReason.TOOL_CALLS,
                        ProviderFixtures.usage(),
                        Map.of()));

        List<ModelMessage> messagesWithNull = new ArrayList<>();
        messagesWithNull.add(new ModelMessage(MessageRole.USER, "question"));
        messagesWithNull.add(null);
        assertValidation(
                ProviderValidationCode.NULL_COLLECTION_ELEMENT,
                () -> new ModelRequest(
                        new ModelRequestId("request-null"),
                        new CorrelationId("correlation-null"),
                        messagesWithNull,
                        List.of(),
                        ProviderFixtures.options(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false));
    }

    @Test
    void rejectsDuplicateToolDescriptorsAndInvalidGenerationOptions() {
        ToolDescriptor tool = ProviderFixtures.tool("search");
        assertValidation(
                ProviderValidationCode.DUPLICATE_ID,
                () -> new ModelRequest(
                        new ModelRequestId("request-duplicate-tool"),
                        new CorrelationId("correlation-duplicate-tool"),
                        List.of(new ModelMessage(MessageRole.USER, "question")),
                        List.of(tool, tool),
                        ProviderFixtures.options(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false));
        assertValidation(
                ProviderValidationCode.INVALID_GENERATION_OPTIONS,
                () -> new GenerationOptions(
                        0,
                        1,
                        0.0d,
                        OptionalLong.empty(),
                        Map.of()));
        assertValidation(
                ProviderValidationCode.INVALID_GENERATION_OPTIONS,
                () -> new GenerationOptions(
                        1,
                        1,
                        Double.NaN,
                        OptionalLong.empty(),
                        Map.of()));
    }

    @Test
    void rejectsIncompleteOptionalExecutionReferences() {
        assertValidation(
                ProviderValidationCode.INCONSISTENT_REFERENCE,
                () -> new ModelRequest(
                        new ModelRequestId("request-orphan-plan"),
                        new CorrelationId("correlation-orphan-plan"),
                        List.of(new ModelMessage(MessageRole.USER, "question")),
                        List.of(),
                        ProviderFixtures.options(),
                        Optional.empty(),
                        Optional.of(new PlanId("plan-1")),
                        Optional.empty(),
                        Optional.empty(),
                        false));
    }

    @Test
    void allFailureCategoriesAreStableValuesWithImmutableDetails() {
        assertEquals(
                EnumSet.of(
                        ProviderFailureCode.INVALID_REQUEST,
                        ProviderFailureCode.UNAVAILABLE,
                        ProviderFailureCode.RATE_LIMITED,
                        ProviderFailureCode.TIMEOUT,
                        ProviderFailureCode.CANCELLED,
                        ProviderFailureCode.PROTOCOL_VIOLATION,
                        ProviderFailureCode.SCRIPTED_MISMATCH,
                        ProviderFailureCode.SCRIPTED_OUT_OF_ORDER,
                        ProviderFailureCode.SCRIPTED_EXHAUSTED,
                        ProviderFailureCode.SCRIPTED_UNCONSUMED),
                EnumSet.allOf(ProviderFailureCode.class));

        for (ProviderFailureCode code : ProviderFailureCode.values()) {
            Map<String, String> mutableDetails = new LinkedHashMap<>();
            mutableDetails.put("key", "value");
            ProviderFailure failure = new ProviderFailure(code, "message", mutableDetails);
            mutableDetails.clear();
            assertEquals(code, failure.code());
            assertEquals(Map.of("key", "value"), failure.details());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> failure.details().put("other", "value"));
        }
    }

    @Test
    void structuredArgumentsRemainDefensivelyImmutable() {
        Map<String, ContractValue> arguments = new LinkedHashMap<>();
        arguments.put("query", new TextValue("paper"));
        ProposedToolCall call = new ProposedToolCall(
                "call-immutable",
                ProviderFixtures.tool("search").id(),
                new ObjectValue(arguments));
        arguments.clear();

        assertEquals(new TextValue("paper"), call.arguments().values().get("query"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> call.arguments().values().put("x", new TextValue("y")));
    }

    private static void assertValidation(
            ProviderValidationCode code,
            org.junit.jupiter.api.function.Executable executable) {
        ProviderValidationException exception = assertThrows(
                ProviderValidationException.class,
                executable);
        assertEquals(code, exception.code());
        assertFalse(exception.path().isBlank());
        assertNotEquals("", exception.getMessage());
    }
}
