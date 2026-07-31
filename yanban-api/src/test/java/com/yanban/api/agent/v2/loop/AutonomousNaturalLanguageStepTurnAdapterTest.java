package com.yanban.api.agent.v2.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.runtime.execution.kernel.EffectIntentDecision;
import io.paperagent.v2.runtime.execution.kernel.NoEffectDecision;
import io.paperagent.v2.runtime.execution.kernel.StepTurnInput;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AutonomousNaturalLanguageStepTurnAdapterTest {
    @Test
    void exposesFullCatalogAndKeepsSameActionSlotStable() {
        Fixture fixture = fixture();
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse response = response(
                "project.search", arguments());
        when(fixture.provider().complete(any()))
                .thenReturn(response);

        var first = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()));
        var replay = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()));

        assertEquals(first.intent().toolCallId(),
                replay.intent().toolCallId());
        ArgumentCaptor<ModelRequest> requests =
                ArgumentCaptor.forClass(ModelRequest.class);
        verify(fixture.provider(),
                org.mockito.Mockito.times(2)).complete(requests.capture());
        assertEquals(
                List.of(
                        "literature.search", "project.read",
                        "project.search", "project.candidate.compose",
                        "sandbox.execute"),
                requests.getAllValues().get(0).availableTools().stream()
                        .map(value -> value.id().value()).toList());
    }

    @Test
    void pendingIntentIsReplayedWithoutAnotherModelCall() {
        Fixture fixture = fixture();
        EffectIntent pending = intent(
                fixture, "pending-call", "project.read", arguments());
        PersistedEffectIntent persisted = mock(PersistedEffectIntent.class);
        when(persisted.intent()).thenReturn(pending);
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of(
                        new V2EffectHistorySource.Entry(persisted, null)));

        var decision = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()));

        assertEquals(pending, decision.intent());
        verify(fixture.provider(), never()).complete(any());
        assertTrue(fixture.adapter().diagnostics().contains(
                "EFFECT_INTENT_PENDING_RECONCILIATION"));
    }

    @Test
    void nextCompletedActionGetsANewSlotButExactReplayStaysStable() {
        Fixture fixture = fixture();
        ModelResponse response = response(
                "project.read", arguments());
        when(fixture.provider().complete(any()))
                .thenReturn(response);
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ToolCallId first = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()))
                .intent().toolCallId();

        V2EffectHistorySource.Entry success = completed(
                fixture, first.value(), "project.read",
                arguments(), ReceiptStatus.SUCCESS);
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of(success));
        ToolCallId second = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()))
                .intent().toolCallId();
        ToolCallId secondReplay = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()))
                .intent().toolCallId();

        assertNotEquals(first, second);
        assertEquals(second, secondReplay);
    }

    @Test
    void identicalFailedCallIsReportedAsNoProgress() {
        Fixture fixture = fixture();
        V2EffectHistorySource.Entry failure = completed(
                fixture, "failed-call", "project.search",
                arguments(), ReceiptStatus.FAILURE);
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of(failure));
        ModelResponse response = response(
                "project.search", arguments());
        when(fixture.provider().complete(any()))
                .thenReturn(response);

        assertInstanceOf(
                NoEffectDecision.class,
                fixture.adapter().decide(fixture.input()));
        assertTrue(fixture.adapter().diagnostics().stream()
                .anyMatch(value -> value.startsWith(
                        "NO_PROGRESS_REPEAT")));
    }

    @Test
    void assistantCompletionWithDurableSuccessReplaysSuccessfulIntent() {
        Fixture fixture = fixture();
        V2EffectHistorySource.Entry success = completed(
                fixture, "successful-call", "project.read",
                arguments(), ReceiptStatus.SUCCESS);
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of(success));
        ModelResponse response = mock(ModelResponse.class);
        when(response.proposedToolCalls()).thenReturn(List.of());
        when(response.assistantText()).thenReturn(
                Optional.of("the durable facts satisfy this goal"));
        when(fixture.provider().complete(any())).thenReturn(response);

        EffectIntentDecision decision = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input()));

        assertEquals(
                success.intent().intent(), decision.intent());
        assertTrue(fixture.adapter().diagnostics().contains(
                "MODEL_CHOSE_REFLECTION_WITH_DURABLE_SUCCESS"));
    }

    @Test
    void noToolWithoutDurableSuccessRemainsNoEffect() {
        Fixture fixture = fixture();
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse response = mock(ModelResponse.class);
        when(response.proposedToolCalls()).thenReturn(List.of());
        when(response.assistantText()).thenReturn(
                Optional.of("done"));
        when(fixture.provider().complete(any())).thenReturn(response);

        assertInstanceOf(
                NoEffectDecision.class,
                fixture.adapter().decide(fixture.input()));
        assertTrue(fixture.adapter().diagnostics().contains(
                "MODEL_CHOSE_REFLECTION_WITHOUT_SUCCESS"));
    }

    private static Fixture fixture() {
        ModelProvider provider = mock(ModelProvider.class);
        V2EffectHistorySource history =
                mock(V2EffectHistorySource.class);
        TaskFrame frame = mock(TaskFrame.class);
        io.paperagent.v2.contracts.Plan plan =
                mock(io.paperagent.v2.contracts.Plan.class);
        PlanRevision revision = mock(PlanRevision.class);
        PlanStep step = mock(PlanStep.class);
        StepTurnInput input = mock(StepTurnInput.class);
        TaskFrameId frameId = new TaskFrameId("frame");
        PlanId planId = new PlanId("plan");
        PlanStepId stepId = new PlanStepId("step");
        when(frame.id()).thenReturn(frameId);
        when(frame.objective()).thenReturn("answer the Project question");
        when(frame.targets()).thenReturn(List.of("Project"));
        when(frame.deliverables()).thenReturn(List.of("answer"));
        when(frame.constraints()).thenReturn(List.of("use receipts"));
        when(plan.id()).thenReturn(planId);
        when(plan.latestRevision()).thenReturn(revision);
        when(revision.id()).thenReturn(new PlanRevisionId("revision"));
        when(step.id()).thenReturn(stepId);
        when(step.intent()).thenReturn("inspect the Project");
        when(step.expectedOutcome()).thenReturn("verified answer");
        when(step.completionCriteria()).thenReturn(
                List.of("answer is receipt-backed"));
        when(input.taskFrame()).thenReturn(frame);
        when(input.plan()).thenReturn(plan);
        when(input.activeStep()).thenReturn(step);
        var adapter = new AutonomousNaturalLanguageStepTurnAdapter(
                provider, history,
                List.of(
                        NaturalLanguageStepKernelFactory.descriptor(
                                new ToolId("literature.search")),
                        NaturalLanguageStepKernelFactory.descriptor(
                                new ToolId("project.read")),
                        NaturalLanguageStepKernelFactory.descriptor(
                                new ToolId("project.search")),
                        NaturalLanguageStepKernelFactory.descriptor(
                                new ToolId("project.candidate.compose")),
                        NaturalLanguageStepKernelFactory.descriptor(
                                new ToolId("sandbox.execute"))),
                false);
        return new Fixture(
                provider, history, input, adapter, planId, stepId);
    }

    private static V2EffectHistorySource.Entry completed(
            Fixture fixture, String callId, String kind,
            ObjectValue arguments, ReceiptStatus status) {
        PersistedEffectIntent persisted = mock(PersistedEffectIntent.class);
        when(persisted.intent()).thenReturn(
                intent(fixture, callId, kind, arguments));
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.status()).thenReturn(status);
        when(receipt.resultCode()).thenReturn(
                status == ReceiptStatus.SUCCESS
                        ? Optional.empty()
                        : Optional.of("FAILED"));
        when(receipt.exitCode()).thenReturn(Optional.of(
                status == ReceiptStatus.SUCCESS ? 0 : 1));
        when(receipt.standardOutput()).thenReturn(
                io.paperagent.v2.contracts.OutputCapture.empty());
        when(receipt.standardError()).thenReturn(
                io.paperagent.v2.contracts.OutputCapture.empty());
        PersistedEffectResult result = mock(PersistedEffectResult.class);
        when(result.receipt()).thenReturn(receipt);
        return new V2EffectHistorySource.Entry(persisted, result);
    }

    private static EffectIntent intent(
            Fixture fixture, String callId, String kind,
            ObjectValue arguments) {
        return new EffectIntent(
                new ToolCallId(callId), fixture.planId(),
                fixture.stepId(), kind, arguments);
    }

    private static ObjectValue arguments() {
        return new ObjectValue(Map.of(
                "query", new TextValue("needle"),
                "maxResults", new io.paperagent.v2.contracts
                        .NumberValue(java.math.BigDecimal.TEN)));
    }

    private static ModelResponse response(
            String tool, ObjectValue arguments) {
        ModelResponse response = mock(ModelResponse.class);
        when(response.proposedToolCalls()).thenReturn(List.of(
                new ProposedToolCall(
                        "provider-call", new ToolId(tool), arguments)));
        when(response.assistantText()).thenReturn(Optional.empty());
        return response;
    }

    private record Fixture(
            ModelProvider provider,
            V2EffectHistorySource history,
            StepTurnInput input,
            AutonomousNaturalLanguageStepTurnAdapter adapter,
            PlanId planId,
            PlanStepId stepId) {
    }
}
