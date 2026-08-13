package com.yanban.api.agent.v2.chain.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorPump;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoveryStageAuthorityVerifier;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductChainNormalSuccessorAuthorityTest {
    private static final Instant NOW =
            Instant.parse("2026-08-09T01:00:00Z");
    private static final String HASH = "0".repeat(64);

    @Test
    void executorSuccessorIsCommittedOnceAndReadBackFromVerifiedMarker() {
        ChainFoundationRepository foundations =
                mock(ChainFoundationRepository.class);
        ChainModelRepository models = mock(ChainModelRepository.class);
        ChainWorkflowRepository workflow =
                mock(ChainWorkflowRepository.class);
        ChainCompositeTransitionRuntime transitions =
                mock(ChainCompositeTransitionRuntime.class);
        ProductChainRecoveryStageAuthorityVerifier verifier =
                mock(ProductChainRecoveryStageAuthorityVerifier.class);
        ProjectChainPlannerProgression planner =
                mock(ProjectChainPlannerProgression.class);
        ProductChainExecutorProgression executor =
                mock(ProductChainExecutorProgression.class);
        ProductChainNormalSuccessorAuthority authority =
                new ProductChainNormalSuccessorAuthority(
                        foundations, models, workflow, transitions, verifier,
                        planner, executor, Clock.fixed(NOW, ZoneOffset.UTC));

        var task = new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                1L, 2L, 3L, 4L, "request-1", HASH,
                null, null, 3L, NOW);
        var payload = new ExecutorPayload.ToolAction(
                "sandbox.execute", "{}", "workspace", "continue",
                List.of("receipt"), "SANDBOX_STANDARD", List.of(),
                List.of(), null, null, null, null, null);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal-1", "task-1", "invocation-1", 1,
                ChainRole.EXECUTOR, ChainProposalKind.EXECUTOR_TOOL_ACTION,
                canonical("{}"), canonical("[]"), null, null, NOW);
        var pending = mock(ChainPersistenceRecords.PendingItemRecord.class);
        when(pending.gapId()).thenReturn("gap-1");
        var response = mock(
                ChainPersistenceRecords.PendingItemEventRecord.class);
        when(response.eventKind()).thenReturn(
                ChainPendingItemStatus.RESPONSE_RECEIVED);
        when(response.responseRound()).thenReturn(1);
        String digest = sha256(
                "task-1\0gap-1\0" + 1 + "\0invocation-1");
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.GAP_RESOLUTION, "task-1",
                "invocation-1", digest).transitionId();
        var transition = new ChainPersistenceRecords.TransitionRecord(
                transitionId, "task-1", "transition-event-1",
                ChainTransitionType.GAP_RESOLUTION, "invocation-1",
                digest, NOW);
        var open = new ChainPersistenceRecords.TransitionStageRecord(
                transitionId, ChainTransitionStage.OPEN, "task-1",
                "open-event-1", 0, null, null, null, null, NOW);
        var normal = new ChainPersistenceRecords.TransitionStageRecord(
                transitionId,
                ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                "task-1", "normal-event-1", 1, null, null,
                "ACTION_BINDING", "action-1", NOW);

        when(foundations.findTask("task-1")).thenReturn(Optional.of(task));
        when(models.findProposal("proposal-1"))
                .thenReturn(Optional.of(proposal));
        when(workflow.findPendingItems("task-1"))
                .thenReturn(List.of(pending));
        when(workflow.findPendingItemEvents("gap-1"))
                .thenReturn(List.of(response));
        when(workflow.findTransition(transitionId))
                .thenReturn(Optional.of(transition));
        when(workflow.findTransitionStages(transitionId))
                .thenReturn(List.of(open, normal));
        when(verifier.verify(any())).thenReturn(
                ChainCompositeTransitionRuntime.AuthorityVerification
                        .verified());
        when(executor.consumeAcceptedProposal(
                "task-1", "proposal-1", NOW)).thenReturn(
                new ProductChainExecutorPump.OfficialSuccessor(
                        "ACTION_BINDING", "action-1"));
        when(transitions.resumeThrough(any(), any(), any())).thenAnswer(call -> {
            ChainCompositeTransitionRuntime.StageCommitter committer =
                    call.getArgument(2);
            var result = committer.commit(
                    new ChainCompositeTransitionRuntime.StageCommand(
                            transition,
                            ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                            1));
            assertEquals("ACTION_BINDING",
                    result.successorAuthorityType());
            assertEquals("action-1", result.successorAuthorityRef());
            return null;
        });

        var result = authority.commit(
                new ChainPendingItemRuntime.NormalSuccessorRequest(
                        "task-1", "gap-1", transitionId, "proposal-1",
                        "invocation-1", payload));

        assertEquals("ACTION_BINDING", result.authorityType());
        assertEquals("action-1", result.authorityRef());
        verify(executor).consumeAcceptedProposal(
                "task-1", "proposal-1", NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(
            String json) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
