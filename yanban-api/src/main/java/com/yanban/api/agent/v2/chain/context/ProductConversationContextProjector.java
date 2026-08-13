package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSessionSummary;
import com.yanban.core.agent.AgentSessionSummaryRepository;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.yanban.api.agent.v2.chain.context.ProductConversationAuthoritySupport.blocked;

/** Projects summary coverage and complete messages through the instruction cut. */
@Component
public final class ProductConversationContextProjector
        implements ProductChainContextAuthorityReader {
    static final String PROJECTION_VERSION = "product-conversation-context-v1";
    private static final String SELECTION_VERSION =
            "summary-coverage-message-id-v1";
    private static final ChainContextModule MODULE =
            ChainContextModule.CONVERSATION_CONTEXT;

    private final ProductChainFoundationRepositoryAdapter foundations;
    private final AgentMessageRepository messages;
    private final AgentSessionSummaryRepository summaries;
    private final ProductDeliveredConversationMessageReader deliveredMessages;

    public ProductConversationContextProjector(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentMessageRepository messages,
            AgentSessionSummaryRepository summaries,
            ProductDeliveredConversationMessageReader deliveredMessages) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.deliveredMessages = Objects.requireNonNull(
                deliveredMessages, "deliveredMessages");
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return project(request);
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw ProductChainContextProjectionSupport.blocked(
                    MODULE, "conversation authority query or body validation failed");
        }
    }

    private ProductChainContextAuthorityProjection project(
            ChainContextProjectionRequest request) {
        var revision = request.buildingRevision();
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(revision.taskId()).orElseThrow(() -> blocked("task is missing"));
        ChainPersistenceRecords.InstructionRecord current = foundations
                .findInstruction(revision.instructionId())
                .orElseThrow(() -> blocked("revision instruction is missing"));
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> prefix =
                instructionPrefix(task, current.instructionId());
        long messageCut = current.messageId() == null
                ? maximumBoundMessageId(task, prefix)
                : current.messageId();
        if (current.messageId() != null) validateInstructionBody(task, current);

        AgentSessionSummary storedSummary = summaries
                .findBySessionIdAndUserId(task.sessionId(), task.userId())
                .orElse(null);
        AgentSessionSummary summary = ProductConversationAuthoritySupport
                .validateSummary(storedSummary, messageCut);
        long covered = summary == null ? 0L : summary.getCoveredMessageId();
        List<AgentMessage> sessionMessages = messages
                .findBySessionIdOrderByCreatedAtAsc(task.sessionId());
        ProductConversationAuthoritySupport.validateSessionIdentity(
                task, sessionMessages);
        List<ProductConversationAuthoritySupport.VisibleMessage> visible = sessionMessages.stream()
                .filter(message -> message.getId() != null
                        && message.getId() <= messageCut)
                .sorted(Comparator.comparingLong(AgentMessage::getId))
                .map(deliveredMessages::resolve)
                .toList();
        ProductConversationAuthoritySupport.validateVisibleMessages(visible);
        List<ProductConversationAuthoritySupport.VisibleMessage> recent = visible.stream()
                .filter(message -> message.source().getId() > covered).toList();
        if (messageCut == 0 && summary == null && recent.isEmpty()) {
            return ProductChainContextProjectionSupport.empty(
                    MODULE,
                    Map.of("summaryIdentityUpdatedAtCoverageAndDigest", text("NONE"),
                            "messageCut", number(0)),
                    Map.of("sessionId", number(task.sessionId()),
                            "maxMessageId", number(0)),
                    PROJECTION_VERSION, revision.paginationVersion(),
                    ProductConversationAuthoritySupport.parameters(
                            revision, SELECTION_VERSION),
                    "summary=NONE,maxMessageId=0");
        }
        ChainContextValue recentValue = ChainContextValue.array(
                recent.stream().map(ProductConversationAuthoritySupport::messageValue)
                        .toList());
        ProductConversationAuthoritySupport.VisibleMessage latestUser = visible.stream()
                .filter(message -> "user".equalsIgnoreCase(
                        message.source().getRole()))
                .reduce((left, right) -> right).orElse(null);
        ChainContextValue summaryValue = summary == null
                ? ChainContextValue.nil()
                : ChainContextValue.referencedText(summary.getSummaryText(),
                "agent-session-summary:" + summary.getId() + ":sha256:"
                        + ProductChainContractProjectionCodec.sha256(
                        summary.getSummaryText()));
        ChainContextValue coverage = ProductConversationAuthoritySupport.coverage(
                summary, covered, messageCut, recent.size(), SELECTION_VERSION);
        ChainContextValue latestUserValue = latestUser == null
                ? ChainContextValue.nil()
                : ProductConversationAuthoritySupport.messageValue(latestUser);
        ChainContextValue combined = ChainContextValue.object(Map.of(
                "recentComplete", recentValue,
                "earlierSummary", summaryValue,
                "summaryCoverage", coverage,
                "latestUserMessage", latestUserValue));
        Map<String, ChainContextValue> fields = new LinkedHashMap<>();
        fields.put("conversation.recentComplete", recentValue);
        fields.put("conversation.earlierSummary", summaryValue);
        fields.put("conversation.summaryCoverage", coverage);
        fields.put("conversation.latestUserMessage", latestUserValue);
        fields.put("foundation.conversationWithCoverage", combined);
        ChainContextValue messageVector = ChainContextValue.array(
                visible.stream().map(ProductConversationAuthoritySupport::messageValue)
                        .toList());
        String messageDigest = ProductChainContractProjectionCodec.sha256(
                ProductChainContractProjectionCodec.canonicalJson(messageVector));
        return ProductChainContextProjectionSupport.present(
                MODULE,
                Map.of(
                        "summaryIdentityUpdatedAtCoverageAndDigest",
                        ProductConversationAuthoritySupport.summaryVersion(summary),
                        "messageCut", ChainContextValue.object(Map.of(
                                "maxMessageId", number(messageCut),
                                "visibleMessageCount", number(visible.size()),
                                "visibleMessageDigest", text(messageDigest)))),
                Map.of("sessionId", number(task.sessionId()),
                        "maxMessageId", number(messageCut)),
                PROJECTION_VERSION, revision.paginationVersion(),
                ProductConversationAuthoritySupport.parameters(
                        revision, SELECTION_VERSION), fields,
                request.requiredFields(MODULE).toArray(String[]::new));
    }

    private List<ChainPersistenceRecords.TaskInstructionBindingRecord>
            instructionPrefix(
                    ChainPersistenceRecords.TaskRecord task,
                    String instructionId) {
        long authorityCut = foundations.highestAuthorityEventSequence(task.taskId());
        Map<String, Long> eventSequences = new LinkedHashMap<>();
        for (var event : foundations.findAuthorityEvents(
                task.taskId(), authorityCut)) {
            if (!task.taskId().equals(event.taskId())
                    || event.eventSequence() > authorityCut) {
                throw blocked("instruction authority event cut is inconsistent");
            }
            if ("INSTRUCTION_BOUND".equals(event.eventType())
                    && eventSequences.put(event.eventId(),
                    event.eventSequence()) != null) {
                throw blocked("instruction authority event is duplicated");
            }
        }
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                foundations.findTaskInstructions(
                                task.taskId(), authorityCut).stream()
                        .filter(binding -> eventSequences.containsKey(binding.eventId()))
                        .sorted(Comparator.comparingLong(
                                ChainPersistenceRecords.TaskInstructionBindingRecord
                                        ::taskInstructionSequence))
                        .toList();
        int currentIndex = -1;
        long priorEvent = 0;
        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index);
            Long eventSequence = eventSequences.get(binding.eventId());
            if (binding.taskInstructionSequence() != index + 1L
                    || eventSequence == null || eventSequence <= priorEvent) {
                throw blocked("instruction binding prefix is inconsistent");
            }
            priorEvent = eventSequence;
            if (binding.instructionId().equals(instructionId)) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex < 0) {
            throw blocked("revision instruction is not bound to task");
        }
        return List.copyOf(bindings.subList(0, currentIndex + 1));
    }

    private long maximumBoundMessageId(
            ChainPersistenceRecords.TaskRecord task,
            List<ChainPersistenceRecords.TaskInstructionBindingRecord> prefix) {
        long maximum = 0;
        ChainPersistenceRecords.InstructionRecord previous = null;
        for (var binding : prefix) {
            var instruction = foundations.findInstruction(binding.instructionId())
                    .orElseThrow(() -> blocked("bound instruction is missing"));
            boolean origin = binding.relationRole()
                    == ChainPersistenceRecords.BindingRole.ORIGIN;
            if (instruction.sessionId() != task.sessionId()
                    || (origin && !task.taskId().equals(instruction.originTaskId()))
                    || (previous == null && origin
                    && (instruction.relationKind() != ChainInstructionRelation.INITIAL
                    || instruction.parentInstructionId() != null))
                    || (previous != null && (!origin
                    || instruction.relationKind() == ChainInstructionRelation.INITIAL
                    || !previous.instructionId().equals(
                    instruction.parentInstructionId())))) {
                throw blocked("bound instruction relationship is invalid");
            }
            if (instruction.messageId() != null) {
                validateInstructionBody(task, instruction);
                maximum = Math.max(maximum, instruction.messageId());
            }
            previous = instruction;
        }
        return maximum;
    }

    private void validateInstructionBody(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction) {
        AgentMessage message = messages.findById(instruction.messageId())
                .orElseThrow(() -> blocked("instruction message body is missing"));
        ProductConversationAuthoritySupport.validateInstructionMessage(
                MODULE, task, instruction, message);
    }

    private static ChainContextValue text(String value) {
        return ProductConversationAuthoritySupport.text(value);
    }

    private static ChainContextValue number(long value) {
        return ProductConversationAuthoritySupport.number(value);
    }
}
