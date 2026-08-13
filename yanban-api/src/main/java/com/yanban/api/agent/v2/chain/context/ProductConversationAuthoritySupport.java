package com.yanban.api.agent.v2.chain.context;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentSessionSummary;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure identity/body validation and canonical encoding for conversation facts. */
final class ProductConversationAuthoritySupport {
    private static final ChainContextModule MODULE =
            ChainContextModule.CONVERSATION_CONTEXT;

    private ProductConversationAuthoritySupport() {
    }

    static void validateInstructionMessage(
            ChainContextModule module,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            AgentMessage message) {
        if (instruction.messageId() == null
                || !Objects.equals(message.getId(), instruction.messageId())
                || !Objects.equals(message.getSessionId(), task.sessionId())
                || !Objects.equals(message.getUserId(), task.userId())
                || message.getContent() == null
                || !ProductChainContractProjectionCodec.sha256(
                message.getContent()).equals(instruction.bodySha256())) {
            throw blocked(module,
                    "instruction message identity or body digest is invalid");
        }
    }

    static ChainContextValue instructionBody(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            AgentMessage message) {
        if (instruction.messageId() == null) {
            if (instruction.bodySha256() != null
                    || instruction.relationKind() != ChainInstructionRelation.CANCEL) {
                throw blocked(ChainContextModule.USER_INSTRUCTION_CHAIN,
                        "bodyless instruction identity is invalid");
            }
            return ChainContextValue.object(Map.of(
                    "instructionId", text(instruction.instructionId()),
                    "messageIdentityKey", text(instruction.messageIdentityKey()),
                    "body", ChainContextValue.nil(),
                    "bodyless", ChainContextValue.bool(true)));
        }
        if (message == null) {
            throw blocked(ChainContextModule.USER_INSTRUCTION_CHAIN,
                    "instruction message body is missing");
        }
        validateInstructionMessage(ChainContextModule.USER_INSTRUCTION_CHAIN,
                task, instruction, message);
        String ref = "agent-message:" + message.getId() + ":sha256:"
                + instruction.bodySha256();
        return ChainContextValue.object(Map.of(
                "instructionId", text(instruction.instructionId()),
                "messageId", number(message.getId()),
                "messageIdentityKey", text(instruction.messageIdentityKey()),
                "bodySha256", text(instruction.bodySha256()),
                "body", ChainContextValue.referencedText(
                        message.getContent(), ref),
                "bodyless", ChainContextValue.bool(false)));
    }

    static AgentSessionSummary validateSummary(
            AgentSessionSummary summary, long messageCut) {
        if (summary == null) return null;
        if (summary.getCoveredMessageId() == null
                || summary.getCoveredMessageId() > messageCut) {
            throw blocked("summary coverage exceeds the instruction message cut");
        }
        if (summary.getId() == null || summary.getUpdatedAt() == null
                || summary.getCoveredMessageId() < 0
                || summary.getSummaryText() == null
                || summary.getSummaryText().isBlank()) {
            throw blocked("summary identity or body is incomplete");
        }
        return summary;
    }

    static void validateSessionIdentity(
            ChainPersistenceRecords.TaskRecord task,
            List<AgentMessage> values) {
        for (AgentMessage message : values) {
            if (message.getId() == null
                    || !Objects.equals(message.getSessionId(), task.sessionId())
                    || !Objects.equals(message.getUserId(), task.userId())) {
                throw blocked("session message owner identity is inconsistent");
            }
        }
    }

    record VisibleMessage(
            AgentMessage source, String content, String bodyAuthorityRef) {
        VisibleMessage {
            Objects.requireNonNull(source, "source");
            if (content != null && (bodyAuthorityRef == null
                    || bodyAuthorityRef.isBlank())) {
                throw new IllegalArgumentException(
                        "visible message body authority is missing");
            }
            if (content == null && bodyAuthorityRef != null) {
                throw new IllegalArgumentException(
                        "bodyless visible message cannot carry body authority");
            }
        }
    }

    static void validateVisibleMessages(List<VisibleMessage> values) {
        long prior = 0;
        for (VisibleMessage visible : values) {
            AgentMessage message = visible.source();
            if (message.getId() == null || message.getId() <= prior
                    || message.getRole() == null || message.getRole().isBlank()
                    || ((visible.content() == null || visible.content().isBlank())
                    && (message.getToolCallsJson() == null
                    || message.getToolCallsJson().isBlank()))) {
                throw blocked("recent message identity or complete body is missing");
            }
            prior = message.getId();
        }
    }

    static ChainContextValue messageValue(VisibleMessage visible) {
        AgentMessage message = visible.source();
        Map<String, ChainContextValue> value = new LinkedHashMap<>();
        value.put("messageId", number(message.getId()));
        value.put("role", text(message.getRole()));
        value.put("body", referencedNullable(visible.content(),
                visible.bodyAuthorityRef()));
        value.put("toolCallsJson", referencedNullable(message.getToolCallsJson(),
                "agent-message:" + message.getId() + ":tool-calls"));
        value.put("toolCallId", nullableText(message.getToolCallId()));
        value.put("paperTaskId", nullableNumber(message.getPaperTaskId()));
        return ChainContextValue.object(value);
    }

    static ChainContextValue summaryVersion(AgentSessionSummary summary) {
        if (summary == null) return text("NONE");
        return ChainContextValue.object(Map.of(
                "summaryId", number(summary.getId()),
                "updatedAt", text(summary.getUpdatedAt().toString()),
                "coveredMessageId", number(summary.getCoveredMessageId()),
                "messageCount", number(Objects.requireNonNullElse(
                        summary.getMessageCount(), 0)),
                "digest", text(ProductChainContractProjectionCodec.sha256(
                        summary.getSummaryText()))));
    }

    static ChainContextValue coverage(
            AgentSessionSummary summary, long covered, long cut, int recentCount,
            String selectionVersion) {
        return ChainContextValue.object(Map.of(
                "summaryId", summary == null ? text("NONE") : number(summary.getId()),
                "coveredMessageId", number(covered),
                "summaryMessageCount", number(summary == null ? 0
                        : Objects.requireNonNullElse(summary.getMessageCount(), 0)),
                "recentFromExclusive", number(covered),
                "recentThroughInclusive", number(cut),
                "recentVisibleCount", number(recentCount),
                "selectionVersion", text(selectionVersion)));
    }

    static Map<String, ChainContextValue> parameters(
            ChainPersistenceRecords.ContextRevisionRecord revision,
            String selectionVersion) {
        return Map.of("taskId", text(revision.taskId()),
                "instructionId", text(revision.instructionId()),
                "role", text(revision.role().name()),
                "selectionVersion", text(selectionVersion));
    }

    static ChainContextException blocked(String reason) {
        return blocked(MODULE, reason);
    }

    private static ChainContextException blocked(
            ChainContextModule module, String reason) {
        return ProductChainContextProjectionSupport.blocked(module, reason);
    }

    static ChainContextValue text(String value) {
        return ChainContextValue.text(value);
    }

    static ChainContextValue number(long value) {
        return ChainContextValue.number(value);
    }

    static ChainContextValue nullableText(String value) {
        return value == null ? ChainContextValue.nil() : text(value);
    }

    static ChainContextValue nullableNumber(Long value) {
        return value == null ? ChainContextValue.nil() : number(value);
    }

    private static ChainContextValue referencedNullable(
            String value, String ref) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.referencedText(value, ref);
    }
}
