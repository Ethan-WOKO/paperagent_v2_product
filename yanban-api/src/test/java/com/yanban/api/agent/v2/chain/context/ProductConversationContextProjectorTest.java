package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSessionSummary;
import com.yanban.core.agent.AgentSessionSummaryRepository;
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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductConversationContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final String TASK = "task.1";
    private static final String BODY = "current body" + "y".repeat(20_000)
            + "TAIL_MARKER";

    @Test
    void projectsCompleteRecentBodyAndSummaryThroughCurrentMessageId() {
        Fixture fixture = fixture(false);
        AgentSessionSummary summary = summary(10L);
        when(fixture.summaries.findBySessionIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(summary));
        AgentMessage prior = message(10L, 7L, "assistant", "prior response");
        AgentMessage current = message(11L, 7L, "user", BODY);
        when(fixture.messages.findBySessionIdOrderByCreatedAtAsc(9L))
                .thenReturn(List.of(prior, current));

        var projection = fixture.subject.read(request("instruction.2"));

        assertThat(((ChainContextValue.NumberValue) projection
                .readBoundaryComponents().get("maxMessageId")).value())
                .isEqualTo(11);
        assertThat(ProductChainContractProjectionCodec.canonicalJson(
                projection.projectionFields().get(
                        "conversation.recentComplete")))
                .contains("TAIL_MARKER")
                .doesNotContain("truncated");
        assertThat(projection.projectionFields()).containsKeys(
                request("instruction.2").requiredFields(
                        ChainContextModule.CONVERSATION_CONTEXT)
                        .toArray(String[]::new));
        verify(fixture.foundations).findAuthorityEvents(TASK, 8);
        verify(fixture.foundations).findTaskInstructions(TASK, 8);
    }

    @Test
    void projectsBodyFreeSuccessfulDeliveryFromItsAnswerAuthority() {
        Fixture fixture = fixture(false);
        when(fixture.summaries.findBySessionIdAndUserId(9L, 7L))
                .thenReturn(Optional.empty());
        AgentMessage delivered = message(10L, 7L, "assistant", null);
        when(delivered.getToolCallId()).thenReturn("chain-delivery:delivery.1");
        AgentMessage current = message(11L, 7L, "user", BODY);
        when(fixture.messages.findBySessionIdOrderByCreatedAtAsc(9L))
                .thenReturn(List.of(delivered, current));
        when(fixture.deliveredMessages.resolve(delivered)).thenReturn(
                new ProductConversationAuthoritySupport.VisibleMessage(
                        delivered, "formal delivered answer", "content.answer.1"));

        var projection = fixture.subject.read(request("instruction.2"));

        String recent = ProductChainContractProjectionCodec.canonicalJson(
                projection.projectionFields().get(
                        "conversation.recentComplete"));
        assertThat(recent).contains("formal delivered answer");
        assertThat(projection.projectionFields().get(
                "conversation.recentComplete").authorityRefs())
                .contains("content.answer.1");
    }

    @Test
    void futureSummaryCoverageIsTypedBlocked() {
        Fixture fixture = fixture(false);
        AgentSessionSummary future = summary(12L);
        when(fixture.summaries.findBySessionIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(future));

        assertBlocked(() -> fixture.subject.read(request("instruction.2")));
    }

    @Test
    void sameSessionMessageOwnedByAnotherUserIsTypedBlocked() {
        Fixture fixture = fixture(false);
        when(fixture.summaries.findBySessionIdAndUserId(9L, 7L))
                .thenReturn(Optional.empty());
        AgentMessage foreign = message(11L, 8L, "user", BODY);
        when(fixture.messages.findBySessionIdOrderByCreatedAtAsc(9L))
                .thenReturn(List.of(foreign));

        assertBlocked(() -> fixture.subject.read(request("instruction.2")));
    }

    @Test
    void repositoryQueryFailureIsTypedBlocked() {
        Fixture fixture = fixture(false);
        when(fixture.foundations.findTask(TASK))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertBlocked(() -> fixture.subject.read(request("instruction.2")));
    }

    @Test
    void bodylessInstructionUsesMaximumMessageIdFromItsValidBindingPrefix() {
        Fixture fixture = fixture(true);
        when(fixture.summaries.findBySessionIdAndUserId(9L, 7L))
                .thenReturn(Optional.empty());
        AgentMessage initial = message(11L, 7L, "user", "initial body");
        when(fixture.messages.findBySessionIdOrderByCreatedAtAsc(9L))
                .thenReturn(List.of(initial));

        var projection = fixture.subject.read(request("instruction.cancel"));

        assertThat(((ChainContextValue.NumberValue) projection
                .readBoundaryComponents().get("maxMessageId")).value())
                .isEqualTo(11);
    }

    private static Fixture fixture(boolean cancelCurrent) {
        ProductChainFoundationRepositoryAdapter foundations =
                mock(ProductChainFoundationRepositoryAdapter.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentSessionSummaryRepository summaries =
                mock(AgentSessionSummaryRepository.class);
        ProductDeliveredConversationMessageReader deliveredMessages =
                mock(ProductDeliveredConversationMessageReader.class);
        when(deliveredMessages.resolve(any(AgentMessage.class)))
                .thenAnswer(invocation -> {
                    AgentMessage message = invocation.getArgument(0);
                    String body = message.getContent();
                    return new ProductConversationAuthoritySupport.VisibleMessage(
                            message, body, body == null ? null
                            : "agent-message:" + message.getId() + ":body");
                });
        when(foundations.findTask(TASK)).thenReturn(Optional.of(task()));
        when(foundations.highestAuthorityEventSequence(TASK)).thenReturn(8L);
        when(foundations.findAuthorityEvents(TASK, 8)).thenReturn(List.of(
                event("event.1", 3), event("event.2", 8)));
        String currentId = cancelCurrent ? "instruction.cancel" : "instruction.2";
        when(foundations.findTaskInstructions(TASK, 8)).thenReturn(List.of(
                binding("event.1", "instruction.1", 1),
                binding("event.2", currentId, 2)));
        var initial = instruction("instruction.1", 11L, "initial body",
                ChainInstructionRelation.INITIAL, null);
        var current = cancelCurrent
                ? new ChainPersistenceRecords.InstructionRecord(
                currentId, "command.cancel", 9, TASK, null, null,
                "command:cancel", ChainInstructionRelation.CANCEL,
                "instruction.1", null, "b".repeat(64), NOW)
                : instruction(currentId, 11L, BODY,
                ChainInstructionRelation.CORRECTION, "instruction.1");
        when(foundations.findInstruction("instruction.1"))
                .thenReturn(Optional.of(initial));
        when(foundations.findInstruction(currentId)).thenReturn(Optional.of(current));
        AgentMessage initialMessage = message(
                11L, 7L, "user", cancelCurrent ? "initial body" : BODY);
        when(messages.findById(11L)).thenReturn(Optional.of(initialMessage));
        return new Fixture(foundations, messages, summaries,
                new ProductConversationContextProjector(
                        foundations, messages, summaries, deliveredMessages),
                deliveredMessages);
    }

    private static void assertBlocked(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(
                ChainContextException.class,
                failure -> assertThat(failure.code()).isEqualTo(
                        ChainContextErrorCode.CONTEXT_INPUT_BLOCKED));
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                TASK, "command.1", "instruction.1", null, 7, 9, 3,
                11L, "request.1", "a".repeat(64), 41L, "version.1",
                99, NOW);
    }

    private static ChainPersistenceRecords.InstructionRecord instruction(
            String id, Long messageId, String body,
            ChainInstructionRelation relation, String parent) {
        return new ChainPersistenceRecords.InstructionRecord(
                id, "command." + id, 9, TASK, messageId,
                ProductChainContractProjectionCodec.sha256(body),
                "message-key." + id, relation, parent, null,
                "b".repeat(64), NOW);
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

    private static AgentMessage message(
            long id, long userId, String role, String body) {
        AgentMessage message = mock(AgentMessage.class);
        when(message.getId()).thenReturn(id);
        when(message.getSessionId()).thenReturn(9L);
        when(message.getUserId()).thenReturn(userId);
        when(message.getRole()).thenReturn(role);
        when(message.getContent()).thenReturn(body);
        return message;
    }

    private static AgentSessionSummary summary(long covered) {
        AgentSessionSummary summary = mock(AgentSessionSummary.class);
        when(summary.getId()).thenReturn(5L);
        when(summary.getUpdatedAt()).thenReturn(NOW);
        when(summary.getCoveredMessageId()).thenReturn(covered);
        when(summary.getMessageCount()).thenReturn(10);
        when(summary.getSummaryText()).thenReturn("complete earlier summary");
        return summary;
    }

    private static ChainContextProjectionRequest request(String instructionId) {
        var revision = new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", TASK, null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "advance", instructionId,
                "frame.1", "plan.1", "revision.1", 1L, "step.1",
                "activation.1", 41L, "version.1", "workspace.1", null,
                null, null, null, null, "projectors-v1", "pagination-v1",
                "policy-v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
        return new ChainContextProjectionRequest(revision, 1_000_000);
    }

    private record Fixture(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentMessageRepository messages,
            AgentSessionSummaryRepository summaries,
            ProductConversationContextProjector subject,
            ProductDeliveredConversationMessageReader deliveredMessages) {
    }
}
