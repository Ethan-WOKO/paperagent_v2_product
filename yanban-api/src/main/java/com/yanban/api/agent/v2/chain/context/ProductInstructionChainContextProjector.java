package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects the complete instruction prefix ending at the revision instruction. */
@Component
public final class ProductInstructionChainContextProjector
        implements ProductChainContextAuthorityReader {
    static final String PROJECTION_VERSION = "product-instruction-chain-v1";
    private static final ChainContextModule MODULE =
            ChainContextModule.USER_INSTRUCTION_CHAIN;

    private final ProductChainFoundationRepositoryAdapter foundations;
    private final AgentMessageRepository messages;

    public ProductInstructionChainContextProjector(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentMessageRepository messages) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.messages = Objects.requireNonNull(messages, "messages");
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
                    MODULE, "instruction authority query or body validation failed");
        }
    }

    private ProductChainContextAuthorityProjection project(
            ChainContextProjectionRequest request) {
        var revision = request.buildingRevision();
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(revision.taskId()).orElseThrow(() -> blocked("task is missing"));
        long authorityCut = foundations.highestAuthorityEventSequence(task.taskId());
        Map<String, ChainPersistenceRecords.AuthorityEventRecord> events = events(
                task.taskId(), authorityCut);
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> visible = foundations
                .findTaskInstructions(task.taskId(), authorityCut).stream()
                .filter(binding -> events.containsKey(binding.eventId()))
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord
                                ::taskInstructionSequence))
                .toList();
        int headIndex = -1;
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).instructionId().equals(revision.instructionId())) {
                if (headIndex >= 0) throw blocked("instruction binding is duplicated");
                headIndex = index;
            }
        }
        if (headIndex < 0) throw blocked("revision instruction is not bound to task");
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> prefix =
                visible.subList(0, headIndex + 1);
        List<ChainContextValue> structures = new ArrayList<>();
        List<ChainContextValue> bodies = new ArrayList<>();
        List<ChainContextValue> relations = new ArrayList<>();
        ChainPersistenceRecords.InstructionRecord previous = null;
        long previousEventSequence = 0;
        for (int index = 0; index < prefix.size(); index++) {
            var binding = prefix.get(index);
            var event = events.get(binding.eventId());
            var instruction = foundations.findInstruction(binding.instructionId())
                    .orElseThrow(() -> blocked("bound instruction is missing"));
            validate(task, binding, event, instruction, previous,
                    index + 1L, previousEventSequence);
            structures.add(structure(binding, event, instruction));
            bodies.add(body(task, instruction));
            relations.add(relation(binding, instruction));
            previous = instruction;
            previousEventSequence = event.eventSequence();
        }
        var headBinding = prefix.get(prefix.size() - 1);
        var headEvent = events.get(headBinding.eventId());
        var head = Objects.requireNonNull(previous);
        ChainContextValue headValue = structure(headBinding, headEvent, head);
        ChainContextValue chain = ChainContextValue.object(Map.of(
                "taskId", text(task.taskId()),
                "taskInstructionSequenceCut", number(headBinding.taskInstructionSequence()),
                "currentInstructionId", text(head.instructionId()),
                "completeStructure", ChainContextValue.array(structures),
                "effectiveBodies", ChainContextValue.array(bodies),
                "relations", ChainContextValue.array(relations)));
        Map<String, ChainContextValue> fields = new LinkedHashMap<>();
        fields.put("instructions.completeStructure", ChainContextValue.array(structures));
        fields.put("instructions.effectiveBodies", ChainContextValue.array(bodies));
        fields.put("instructions.relations", ChainContextValue.array(relations));
        fields.put("instructions.runningInstructionState", headValue);
        fields.put("instructions.reviewScope", headValue);
        fields.put("instructions.expressionRequirements", headValue);
        fields.put("foundation.instructionChain", chain);
        ChainContextValue messageVersion = ChainContextValue.object(Map.of(
                "messageId", nullableNumber(head.messageId()),
                "bodySha256", nullableText(head.bodySha256()),
                "messageIdentityKey", text(head.messageIdentityKey()),
                "bodyless", ChainContextValue.bool(head.messageId() == null)));
        return ProductChainContextProjectionSupport.present(
                MODULE,
                Map.of(
                        "taskInstructionBindingHead", ChainContextValue.object(Map.of(
                                "eventId", text(headBinding.eventId()),
                                "eventSequence", number(headEvent.eventSequence()),
                                "sequence", number(headBinding.taskInstructionSequence()),
                                "relationRole", text(headBinding.relationRole().name()),
                                "sourceIdentitySha256",
                                text(headEvent.sourceIdentitySha256()))),
                        "instructionId", ChainContextValue.referencedText(
                                head.instructionId(), "instruction:" + head.instructionId()),
                        "messageIdAndBodyHash", messageVersion),
                Map.of("taskInstructionSequenceCut",
                        number(headBinding.taskInstructionSequence())),
                PROJECTION_VERSION, revision.paginationVersion(),
                Map.of("taskId", text(task.taskId()),
                        "instructionId", text(head.instructionId()),
                        "role", text(revision.role().name())),
                fields, request.requiredFields(MODULE).toArray(String[]::new));
    }

    private Map<String, ChainPersistenceRecords.AuthorityEventRecord> events(
            String taskId, long cut) {
        Map<String, ChainPersistenceRecords.AuthorityEventRecord> result =
                new LinkedHashMap<>();
        for (var event : foundations.findAuthorityEvents(taskId, cut)) {
            if (!taskId.equals(event.taskId()) || event.eventSequence() > cut
                    || result.put(event.eventId(), event) != null) {
                throw blocked("instruction authority event cut is inconsistent");
            }
        }
        return result;
    }

    private void validate(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskInstructionBindingRecord binding,
            ChainPersistenceRecords.AuthorityEventRecord event,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.InstructionRecord previous,
            long expectedSequence, long previousEventSequence) {
        boolean firstOrigin = expectedSequence == 1
                && binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN;
        if (!task.taskId().equals(binding.taskId())
                || binding.taskInstructionSequence() != expectedSequence
                || event == null || !"INSTRUCTION_BOUND".equals(event.eventType())
                || event.eventSequence() <= previousEventSequence
                || instruction.sessionId() != task.sessionId()
                || (binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN
                && !task.taskId().equals(instruction.originTaskId()))
                || (firstOrigin && (instruction.relationKind()
                != ChainInstructionRelation.INITIAL
                || instruction.parentInstructionId() != null))
                || (previous != null && (binding.relationRole()
                != ChainPersistenceRecords.BindingRole.ORIGIN
                || instruction.relationKind() == ChainInstructionRelation.INITIAL
                || !previous.instructionId().equals(instruction.parentInstructionId())))
                || ((instruction.relationKind()
                == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM)
                != (instruction.answeredGapId() != null))) {
            throw blocked("instruction binding prefix is inconsistent");
        }
    }

    private ChainContextValue body(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction) {
        AgentMessage message = instruction.messageId() == null ? null
                : messages.findById(instruction.messageId()).orElse(null);
        return ProductConversationAuthoritySupport.instructionBody(
                task, instruction, message);
    }

    private static ChainContextValue structure(
            ChainPersistenceRecords.TaskInstructionBindingRecord binding,
            ChainPersistenceRecords.AuthorityEventRecord event,
            ChainPersistenceRecords.InstructionRecord instruction) {
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        values.put("sequence", number(binding.taskInstructionSequence()));
        values.put("eventId", text(binding.eventId()));
        values.put("eventSequence", number(event.eventSequence()));
        values.put("bindingRole", text(binding.relationRole().name()));
        values.put("instructionId", text(instruction.instructionId()));
        values.put("commandId", text(instruction.commandId()));
        values.put("originTaskId", text(instruction.originTaskId()));
        values.put("relationKind", text(instruction.relationKind().name()));
        values.put("parentInstructionId", nullableText(instruction.parentInstructionId()));
        values.put("answeredGapId", nullableText(instruction.answeredGapId()));
        values.put("effectiveBoundaryDigest", text(instruction.effectiveBoundaryDigest()));
        return ChainContextValue.object(values);
    }

    private static ChainContextValue relation(
            ChainPersistenceRecords.TaskInstructionBindingRecord binding,
            ChainPersistenceRecords.InstructionRecord instruction) {
        return ChainContextValue.object(Map.of(
                "sequence", number(binding.taskInstructionSequence()),
                "instructionId", text(instruction.instructionId()),
                "relationKind", text(instruction.relationKind().name()),
                "bindingRole", text(binding.relationRole().name()),
                "parentInstructionId", nullableText(instruction.parentInstructionId()),
                "answeredGapId", nullableText(instruction.answeredGapId())));
    }

    private ChainContextException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    private static ChainContextValue text(String value) {
        return ChainContextValue.text(value);
    }

    private static ChainContextValue number(long value) {
        return ChainContextValue.number(value);
    }

    private static ChainContextValue nullableText(String value) {
        return value == null ? ChainContextValue.nil() : text(value);
    }

    private static ChainContextValue nullableNumber(Long value) {
        return value == null ? ChainContextValue.nil() : number(value);
    }
}
