package com.yanban.api.agent.v2.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnQueryService;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnResponse;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnSnapshot;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.api.project.CandidateValidationStatusProjectionService;
import com.yanban.core.agent.AgentArtifact;
import com.yanban.core.agent.AgentArtifactRepository;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class V2TurnHistoryQueryServiceTest {
    private final V2TurnIntakeJpaRepository intakes =
            mock(V2TurnIntakeJpaRepository.class);
    private final V2AdaptiveTurnQueryService adaptive =
            mock(V2AdaptiveTurnQueryService.class);
    private final AgentMessageRepository messages =
            mock(AgentMessageRepository.class);
    private final V2EffectHistorySource history =
            mock(V2EffectHistorySource.class);
    private final AgentArtifactRepository artifacts =
            mock(AgentArtifactRepository.class);
    private final CandidateValidationStatusProjectionService validations =
            mock(CandidateValidationStatusProjectionService.class);
    private final V2TurnHistoryQueryService service =
            new V2TurnHistoryQueryService(
                    intakes, adaptive, messages, history,
                    artifacts, validations);

    @Test
    void projectsDirectAnswerAndPlanningFailureOneForOne() {
        V2TurnIntakeEntity failed = intake("failed", "broken");
        failed.fail("PLANNER_INVALID", Instant.parse(
                "2026-07-31T00:00:03Z"));
        V2TurnIntakeEntity direct = intake("direct", "hello");
        direct.completeDirect(31L, "{}", Instant.parse(
                "2026-07-31T00:00:02Z"));
        when(intakes
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        eq(7L), eq(9L), any(Pageable.class)))
                .thenReturn(List.of(failed, direct));
        when(adaptive.find(eq(7L), eq(9L), any()))
                .thenReturn(Optional.empty());
        when(messages.findById(31L)).thenReturn(Optional.of(
                new AgentMessage(9L, 7L, "assistant", "answer",
                        null, null)));

        List<V2TurnHistoryResponse> result = service.list(7L, 9L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).clientRequestId()).isEqualTo("failed");
        assertThat(result.get(0).question()).isEqualTo("broken");
        assertThat(result.get(0).status()).isEqualTo("FAILED");
        assertThat(result.get(0).errorCode())
                .isEqualTo("PLANNER_INVALID");
        assertThat(result.get(1).clientRequestId()).isEqualTo("direct");
        assertThat(result.get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(result.get(1).route()).isEqualTo("DIRECT");
        assertThat(result.get(1).finalText()).isEqualTo("answer");
        verify(intakes)
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        eq(7L), eq(9L), any(Pageable.class));
    }

    @Test
    void projectsCandidateWithAutomaticAndSeparateConfirmationValidation() {
        V2TurnIntakeEntity intake = intake("candidate", "change code");
        intake.completePersistent(
                "plan-1", "{}", "[]",
                Instant.parse("2026-07-31T00:00:02Z"));
        when(intakes
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        eq(7L), eq(9L), any(Pageable.class)))
                .thenReturn(List.of(intake));
        var adaptiveResponse = new V2AdaptiveTurnResponse(
                "WAITING_CONFIRMATION", "PERSISTENT_PLAN_EXECUTE",
                "plan-1", "version-1",
                List.of(new V2AdaptiveTurnResponse.Step(
                        1, "compile", "SUCCEEDED", "exit 0")),
                "done", 42L, List.of("src/Main.java"), null);
        when(adaptive.find(7L, 9L, "candidate")).thenReturn(Optional.of(
                new V2AdaptiveTurnSnapshot(
                        adaptiveResponse,
                        Instant.parse("2026-07-31T00:00:02Z"),
                        Instant.parse("2026-07-31T00:00:04Z"))));
        AgentArtifact candidate = new AgentArtifact(
                7L, 9L, "candidate.json", "TEXT", "{}",
                "CANDIDATE_CHANGESET", null);
        when(artifacts.findByIdAndUserId(42L, 7L))
                .thenReturn(Optional.of(candidate));
        V2EffectHistorySource.Entry successful =
                successfulSandboxReceipt();
        when(history.inspect(new PlanId("plan-1")))
                .thenReturn(List.of(successful));
        when(validations.latest(7L, 9L, 42L)).thenReturn(Optional.of(
                new CandidateValidationStatusProjectionService.Status(
                        "QUEUED", "PENDING", null, null, null)));

        V2TurnHistoryResponse result = service.list(7L, 9L).get(0);

        assertThat(result.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(result.candidateArtifactId()).isEqualTo(42L);
        assertThat(result.outputPaths())
                .containsExactly("src/Main.java");
        assertThat(result.agentAutomaticValidation())
                .isEqualTo(new V2TurnHistoryResponse.AgentAutomaticValidation(
                        "PASSED", "E2B", 0, "receipt-1"));
        assertThat(result.confirmationValidation())
                .isEqualTo(new V2TurnHistoryResponse.ConfirmationValidation(
                        "QUEUED", "PENDING", null, null, null));
        assertThat(result.updatedAt()).isEqualTo(
                Instant.parse("2026-07-31T00:00:04Z"));
    }

    @Test
    void refreshedRunningHistoryPreservesCurrentStepContextPhase() {
        V2TurnIntakeEntity intake = intake("running", "read project");
        intake.completePersistent("plan-1", "{}", "[]", Instant.now());
        when(intakes
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        eq(7L), eq(9L), any(Pageable.class)))
                .thenReturn(List.of(intake));
        var context = new V2AdaptiveTurnResponse.Context(
                "COMPACTING", "step-1", List.of("TOOL_RESULTS"));
        when(adaptive.find(7L, 9L, "running")).thenReturn(Optional.of(
                new V2AdaptiveTurnSnapshot(
                        new V2AdaptiveTurnResponse(
                                "RUNNING", "PERSISTENT_PLAN_EXECUTE",
                                "plan-1", "version-1",
                                List.of(new V2AdaptiveTurnResponse.Step(
                                        1, "read", "RUNNING", null)),
                                null, null, List.of(), null, context),
                        Instant.EPOCH, Instant.EPOCH)));

        V2TurnHistoryResponse result = service.list(7L, 9L).get(0);

        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.context()).isEqualTo(context);
    }

    @Test
    void projectsAppliedCandidateRevisionWithoutMutatingAdaptiveTurn() {
        V2TurnIntakeEntity intake = intake("applied", "change code");
        intake.completePersistent(
                "plan-1", "{}", "[]",
                Instant.parse("2026-07-31T00:00:02Z"));
        when(intakes
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        eq(7L), eq(9L), any(Pageable.class)))
                .thenReturn(List.of(intake));
        when(adaptive.find(7L, 9L, "applied")).thenReturn(Optional.of(
                new V2AdaptiveTurnSnapshot(
                        new V2AdaptiveTurnResponse(
                                "WAITING_CONFIRMATION",
                                "PERSISTENT_PLAN_EXECUTE", "plan-1",
                                "version-1", List.of(), "done", 42L,
                                List.of("src/Main.java"), null),
                        Instant.EPOCH, Instant.EPOCH)));
        when(artifacts.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(
                new AgentArtifact(
                        7L, 9L, "candidate.json", "TEXT", "{}",
                        "CANDIDATE_CHANGESET", null)));
        when(history.inspect(new PlanId("plan-1")))
                .thenReturn(List.of());
        when(validations.latest(7L, 9L, 42L)).thenReturn(Optional.of(
                new CandidateValidationStatusProjectionService.Status(
                        "SUCCEEDED", "APPLIED", 101L, 29L,
                        "f".repeat(64))));

        V2TurnHistoryResponse result = service.list(7L, 9L).get(0);

        assertThat(result.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(result.confirmationValidation())
                .isEqualTo(new V2TurnHistoryResponse.ConfirmationValidation(
                        "SUCCEEDED", "APPLIED", 101L, 29L,
                        "f".repeat(64)));
    }

    @Test
    void failedLatestSandboxReceiptCannotClaimAutomaticValidation() {
        V2TurnIntakeEntity intake = intake("candidate", "change code");
        intake.completePersistent("plan-1", "{}", "[]", Instant.now());
        when(intakes
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        eq(7L), eq(9L), any(Pageable.class)))
                .thenReturn(List.of(intake));
        when(adaptive.find(7L, 9L, "candidate")).thenReturn(Optional.of(
                new V2AdaptiveTurnSnapshot(
                        new V2AdaptiveTurnResponse(
                                "WAITING_CONFIRMATION",
                                "PERSISTENT_PLAN_EXECUTE", "plan-1",
                                "version-1", List.of(), null, 42L,
                                List.of(), null),
                        Instant.EPOCH, Instant.EPOCH)));
        when(artifacts.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(
                new AgentArtifact(
                        7L, 9L, "candidate.json", "TEXT", "{}",
                        "CANDIDATE_CHANGESET", null)));
        V2EffectHistorySource.Entry successful =
                successfulSandboxReceipt();
        V2EffectHistorySource.Entry failed = sandboxReceipt(
                "receipt-2", ReceiptStatus.FAILURE, 1);
        when(history.inspect(new PlanId("plan-1"))).thenReturn(List.of(
                successful, failed));
        when(validations.latest(7L, 9L, 42L))
                .thenReturn(Optional.empty());

        V2TurnHistoryResponse result = service.list(7L, 9L).get(0);

        assertThat(result.agentAutomaticValidation()).isNull();
        assertThat(result.confirmationValidation()).isNull();
    }

    private static V2TurnIntakeEntity intake(
            String requestId, String question) {
        return new V2TurnIntakeEntity(
                7L, 9L, requestId, "a".repeat(64), question,
                false, null, null, 11L, 12L,
                Instant.parse("2026-07-31T00:00:01Z"));
    }

    private static V2EffectHistorySource.Entry successfulSandboxReceipt() {
        return sandboxReceipt("receipt-1", ReceiptStatus.SUCCESS, 0);
    }

    private static V2EffectHistorySource.Entry sandboxReceipt(
            String receiptId, ReceiptStatus status, int exitCode) {
        PersistedEffectIntent persisted = mock(PersistedEffectIntent.class);
        when(persisted.intent()).thenReturn(new EffectIntent(
                new ToolCallId("call-" + receiptId),
                new PlanId("plan-1"), new PlanStepId("step-1"),
                "sandbox.execute", new ObjectValue(Map.of())));
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId(receiptId));
        when(receipt.status()).thenReturn(status);
        when(receipt.exitCode()).thenReturn(Optional.of(exitCode));
        PersistedEffectResult result = mock(PersistedEffectResult.class);
        when(result.receipt()).thenReturn(receipt);
        return new V2EffectHistorySource.Entry(persisted, result);
    }
}
