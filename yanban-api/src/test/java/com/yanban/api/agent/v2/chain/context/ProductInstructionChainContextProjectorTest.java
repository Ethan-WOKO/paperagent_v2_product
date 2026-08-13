package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductInstructionChainContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String TASK = "task.1";
    private static final String BODY_1 = "initial request";
    private static final String BODY_2 = "correction" + "x".repeat(20_000)
            + "TAIL_MARKER";

    @Test
    void projectsExactBindingPrefixRelationsAndCompleteBodiesAtFiniteCut() {
        Fixture fixture = fixture();

        var projection = fixture.subject.read(request(ChainRole.EXECUTOR));

        assertThat(projection.sourceVersionComponents()).containsOnlyKeys(
                "taskInstructionBindingHead", "instructionId",
                "messageIdAndBodyHash");
        assertThat(((ChainContextValue.NumberValue) projection
                .readBoundaryComponents().get("taskInstructionSequenceCut"))
                .value()).isEqualTo(2);
        assertThat(projection.projectionFields()).containsKeys(
                request(ChainRole.EXECUTOR).requiredFields(
                        ChainContextModule.USER_INSTRUCTION_CHAIN)
                        .toArray(String[]::new));
        String canonical = ProductChainContractProjectionCodec.canonicalJson(
                projection.projectionFields().get(
                        "foundation.instructionChain"));
        assertThat(canonical).contains(
                "\"relationKind\":\"CORRECTION\"",
                "\"parentInstructionId\":\"instruction.1\"",
                "\"messageIdentityKey\":\"message-key.2\"",
                "TAIL_MARKER");
        assertThat(projection.projectionFields().get(
                "instructions.effectiveBodies").authorityRefs())
                .contains("agent-message:12:sha256:" + sha(BODY_2));
        verify(fixture.foundations).findAuthorityEvents(TASK, 7);
        verify(fixture.foundations).findTaskInstructions(TASK, 7);
    }

    @Test
    void missingInstructionBodyIsTypedBlockedInsteadOfEmpty() {
        Fixture fixture = fixture();
        when(fixture.messages.findById(12L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.subject.read(request(ChainRole.PLANNER)))
                .isInstanceOfSatisfying(ChainContextException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                ChainContextErrorCode.CONTEXT_INPUT_BLOCKED));
    }

    private static Fixture fixture() {
        ProductChainFoundationRepositoryAdapter foundations =
                mock(ProductChainFoundationRepositoryAdapter.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        ChainPersistenceRecords.TaskRecord task = task();
        var first = instruction("instruction.1", 11L, BODY_1,
                ChainInstructionRelation.INITIAL, null, "message-key.1");
        var second = instruction("instruction.2", 12L, BODY_2,
                ChainInstructionRelation.CORRECTION, "instruction.1",
                "message-key.2");
        var firstBinding = binding("event.1", "instruction.1", 1);
        var secondBinding = binding("event.2", "instruction.2", 2);
        when(foundations.findTask(TASK)).thenReturn(Optional.of(task));
        when(foundations.highestAuthorityEventSequence(TASK)).thenReturn(7L);
        when(foundations.findAuthorityEvents(TASK, 7)).thenReturn(List.of(
                event("event.1", 2), event("event.2", 7)));
        when(foundations.findTaskInstructions(TASK, 7))
                .thenReturn(List.of(firstBinding, secondBinding));
        when(foundations.findInstruction("instruction.1"))
                .thenReturn(Optional.of(first));
        when(foundations.findInstruction("instruction.2"))
                .thenReturn(Optional.of(second));
        AgentMessage firstMessage = message(11L, 7L, BODY_1);
        AgentMessage secondMessage = message(12L, 7L, BODY_2);
        when(messages.findById(11L)).thenReturn(Optional.of(firstMessage));
        when(messages.findById(12L)).thenReturn(Optional.of(secondMessage));
        return new Fixture(foundations, messages,
                new ProductInstructionChainContextProjector(
                        foundations, messages));
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                TASK, "command.1", "instruction.1", null, 7, 9, 3,
                11L, "request.1", "a".repeat(64), 41L, "version.1",
                99, NOW);
    }

    private static ChainPersistenceRecords.InstructionRecord instruction(
            String id, Long messageId, String body,
            ChainInstructionRelation relation, String parent, String messageKey) {
        return new ChainPersistenceRecords.InstructionRecord(
                id, "command." + id, 9, TASK, messageId, sha(body),
                messageKey, relation, parent, null, "b".repeat(64), NOW);
    }

    private static ChainPersistenceRecords.TaskInstructionBindingRecord binding(
            String event, String instruction, long sequence) {
        return new ChainPersistenceRecords.TaskInstructionBindingRecord(
                TASK, event, instruction, sequence,
                ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord event(
            String id, long sequence) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                id, TASK, sequence, "INSTRUCTION_BOUND", null,
                "c".repeat(64), NOW);
    }

    private static AgentMessage message(long id, long userId, String body) {
        AgentMessage message = mock(AgentMessage.class);
        when(message.getId()).thenReturn(id);
        when(message.getSessionId()).thenReturn(9L);
        when(message.getUserId()).thenReturn(userId);
        when(message.getRole()).thenReturn("user");
        when(message.getContent()).thenReturn(body);
        return message;
    }

    private static ChainContextProjectionRequest request(ChainRole role) {
        var revision = new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", TASK, null, role, ChainWorkState.EXECUTING,
                "advance", "instruction.2", "frame.1", "plan.1",
                "revision.1", 1L, "step.1", "activation.1", 41L,
                "version.1", "workspace.1", null, null, null, null,
                null, "projectors-v1", "pagination-v1", "policy-v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
        return new ChainContextProjectionRequest(revision, 1_000_000);
    }

    private static String sha(String body) {
        return ProductChainContractProjectionCodec.sha256(body);
    }

    private record Fixture(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentMessageRepository messages,
            ProductInstructionChainContextProjector subject) {
    }
}
