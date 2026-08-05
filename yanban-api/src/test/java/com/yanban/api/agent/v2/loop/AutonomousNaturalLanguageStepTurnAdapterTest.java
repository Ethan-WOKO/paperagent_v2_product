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
import com.yanban.api.agent.v2.context.V2ExecutionContextSource;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ReceiptId;
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
import io.paperagent.v2.runtime.execution.kernel.StepResultDecision;
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
                        "project.search", "project.bibtex.audit",
                        "project.candidate.compose", "sandbox.execute"),
                requests.getAllValues().get(0).availableTools().stream()
                        .map(value -> value.id().value()).toList());
        String system = requests.getAllValues().get(0)
                .messages().get(0).content();
        assertTrue(system.contains("only model that writes the new"));
        assertTrue(system.contains("complete resulting file text"));
        assertTrue(system.contains("Before the first Java or Python"));
        assertTrue(system.contains("same command may be"));
        assertTrue(system.contains("byte-identical content"));
        assertTrue(system.contains("Do not hide them merely"));
        assertTrue(system.contains(
                "Work only on the current persisted Plan"));
        assertTrue(system.contains("accepted results from completed Steps"));
        assertTrue(system.contains("Do not\nrepeat work merely"));
    }

    @Test
    void completedCandidateStepIsVisibleToLaterToolSelection() {
        Fixture fixture = fixture();
        PlanRevision revision = fixture.input().plan().latestRevision();
        PlanStep completed = mock(PlanStep.class);
        PlanStepId completedId = new PlanStepId("candidate-step");
        when(completed.id()).thenReturn(completedId);
        when(completed.intent()).thenReturn(
                "create the reviewable Candidate");
        var completedFact = mock(
                io.paperagent.v2.contracts.CompletionFact.class);
        when(revision.steps()).thenReturn(List.of(completed));
        when(revision.completedFacts()).thenReturn(Map.of(
                completedId, completedFact));
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse sandboxResponse = response(
                "sandbox.execute", arguments());
        when(fixture.provider().complete(any())).thenReturn(
                sandboxResponse);

        fixture.adapter().decide(fixture.input());

        ArgumentCaptor<ModelRequest> request =
                ArgumentCaptor.forClass(ModelRequest.class);
        verify(fixture.provider()).complete(request.capture());
        String prompt = request.getValue().messages().get(1).content();
        assertTrue(prompt.contains("Completed Plan Steps"));
        assertTrue(prompt.contains(
                "candidate-step: create the reviewable Candidate"));
    }

    @Test
    void acceptedResultsCandidateAndPriorReflectionReachNextStepModel() {
        Fixture fixture = fixture();
        V2ExecutionContextSource contexts = mock(
                V2ExecutionContextSource.class);
        String completeReplacement = "public class Sort {"
                + " // COMPLETE_REPLACEMENT\n}";
        when(contexts.inspect(fixture.planId(), fixture.stepId()))
                .thenReturn(new V2ExecutionContextSource.Projection(
                        List.of("acceptedStepResult[stepId=step-3,"
                                + "result=compiled and ran]"),
                        List.of("toolExecution[stepId=step-3,"
                                + "toolKind=sandbox.execute,exitCode=0,"
                                + "stdout=ok]"),
                        Optional.of("preparedCandidate[diffFingerprint=abc]"
                                + "\n<replacement path=\"src/Sort.java\">\n"
                                + completeReplacement
                                + "\n</replacement>"),
                        Optional.empty()));
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse result = mock(ModelResponse.class);
        when(result.proposedToolCalls()).thenReturn(List.of());
        when(result.assistantText()).thenReturn(Optional.of(
                "reuse the accepted sandbox result"));
        when(fixture.provider().complete(any())).thenReturn(result);
        var adapter = new AutonomousNaturalLanguageStepTurnAdapter(
                fixture.provider(), fixture.history(), contexts,
                tools(), false,
                List.of("previousReflectionDecision=CONTINUE; "
                        + "reason=use the successful result"));

        assertInstanceOf(
                StepResultDecision.class,
                adapter.decide(fixture.input()));

        ArgumentCaptor<ModelRequest> request =
                ArgumentCaptor.forClass(ModelRequest.class);
        verify(fixture.provider()).complete(request.capture());
        String prompt = request.getValue().messages().get(1).content();
        assertTrue(prompt.contains("acceptedStepResult[stepId=step-3"));
        assertTrue(prompt.contains("toolKind=sandbox.execute"));
        assertTrue(prompt.contains("exitCode=0"));
        assertTrue(prompt.contains(completeReplacement));
        assertTrue(prompt.contains(
                "previousReflectionDecision=CONTINUE"));
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
    void completeProjectReadOutputReachesTheNextModelTurn() {
        Fixture fixture = fixture();
        String completeSource = "class Sort {\n"
                + "x".repeat(12_000)
                + "\n// COMPLETE_FILE_END\n}";
        V2EffectHistorySource.Entry success = completed(
                fixture, "read-call", "project.read",
                arguments(), ReceiptStatus.SUCCESS, completeSource);
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of(success));
        ModelResponse next = response("project.search", arguments());
        when(fixture.provider().complete(any())).thenReturn(next);

        fixture.adapter().decide(fixture.input());

        ArgumentCaptor<ModelRequest> request =
                ArgumentCaptor.forClass(ModelRequest.class);
        verify(fixture.provider()).complete(request.capture());
        String prompt = request.getValue().messages().get(1).content();
        assertTrue(prompt.contains(completeSource));
        assertTrue(prompt.contains("COMPLETE_FILE_END"));
    }

    @Test
    void identicalFailedCallRemainsAvailableForBoundedRetry() {
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

        EffectIntent retry = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input())).intent();

        assertEquals("project.search", retry.kind());
        assertEquals(arguments(), retry.arguments());
        assertTrue(fixture.adapter().diagnostics().stream()
                .noneMatch(value -> value.startsWith(
                        "NO_PROGRESS_REPEAT")));
    }

    @Test
    void multipleToolCallsUseOnlyTheFirstWithAStableIntent() {
        Fixture fixture = fixture();
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse response = mock(ModelResponse.class);
        when(response.proposedToolCalls()).thenReturn(List.of(
                new ProposedToolCall(
                        "provider-call-1", new ToolId("project.read"),
                        arguments()),
                new ProposedToolCall(
                        "provider-call-2", new ToolId("sandbox.execute"),
                        new ObjectValue(Map.of()))));
        when(response.assistantText()).thenReturn(Optional.empty());
        when(fixture.provider().complete(any())).thenReturn(response);

        EffectIntent first = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input())).intent();
        EffectIntent replay = assertInstanceOf(
                EffectIntentDecision.class,
                fixture.adapter().decide(fixture.input())).intent();

        assertEquals("project.read", first.kind());
        assertEquals(arguments(), first.arguments());
        assertEquals(first, replay);
        assertTrue(fixture.adapter().diagnostics().contains(
                "MODEL_FORMAT_MULTIPLE_TOOL_CALLS_USING_FIRST"));
    }

    @Test
    void unknownFirstToolCallFailsWithoutSkippingToLaterValidCall() {
        Fixture fixture = fixture();
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse response = mock(ModelResponse.class);
        when(response.proposedToolCalls()).thenReturn(List.of(
                new ProposedToolCall(
                        "provider-call-1", new ToolId("unknown.tool"),
                        arguments()),
                new ProposedToolCall(
                        "provider-call-2", new ToolId("project.read"),
                        arguments())));
        when(response.assistantText()).thenReturn(Optional.empty());
        when(fixture.provider().complete(any())).thenReturn(response);

        assertInstanceOf(
                NoEffectDecision.class,
                fixture.adapter().decide(fixture.input()));

        assertTrue(fixture.adapter().diagnostics().contains(
                "MODEL_SELECTED_UNKNOWN_TOOL"));
    }

    @Test
    void assistantCompletionWithDurableSuccessProposesStepResult() {
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

        StepResultDecision decision = assertInstanceOf(
                StepResultDecision.class,
                fixture.adapter().decide(fixture.input()));

        assertEquals(
                "the durable facts satisfy this goal",
                decision.resultText());
        assertEquals(
                List.of(success.result().receipt().id()),
                decision.evidenceReceiptIds());
        assertTrue(fixture.adapter().diagnostics().contains(
                "MODEL_PROPOSED_STEP_RESULT"));
    }

    @Test
    void noToolWithoutDurableSuccessProposesReasoningResult() {
        Fixture fixture = fixture();
        when(fixture.history().inspect(
                fixture.planId(), fixture.stepId()))
                .thenReturn(List.of());
        ModelResponse response = mock(ModelResponse.class);
        when(response.proposedToolCalls()).thenReturn(List.of());
        when(response.assistantText()).thenReturn(
                Optional.of("done"));
        when(fixture.provider().complete(any())).thenReturn(response);

        StepResultDecision decision = assertInstanceOf(
                StepResultDecision.class,
                fixture.adapter().decide(fixture.input()));
        assertEquals("done", decision.resultText());
        assertEquals(List.of(), decision.evidenceReceiptIds());
        assertTrue(fixture.adapter().diagnostics().contains(
                "MODEL_PROPOSED_STEP_RESULT"));
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
        when(revision.steps()).thenReturn(List.of());
        when(revision.completedFacts()).thenReturn(Map.of());
        when(step.id()).thenReturn(stepId);
        when(step.intent()).thenReturn("inspect the Project");
        when(step.expectedOutcome()).thenReturn("verified answer");
        when(step.completionCriteria()).thenReturn(
                List.of("answer is receipt-backed"));
        when(input.taskFrame()).thenReturn(frame);
        when(input.plan()).thenReturn(plan);
        when(input.activeStep()).thenReturn(step);
        var adapter = new AutonomousNaturalLanguageStepTurnAdapter(
                provider, history, tools(),
                false);
        return new Fixture(
                provider, history, input, adapter, planId, stepId);
    }

    private static List<io.paperagent.v2.contracts.ToolDescriptor> tools() {
        return List.of(
                NaturalLanguageStepKernelFactory.descriptor(
                        new ToolId("literature.search")),
                NaturalLanguageStepKernelFactory.descriptor(
                        new ToolId("project.read")),
                NaturalLanguageStepKernelFactory.descriptor(
                        new ToolId("project.search")),
                NaturalLanguageStepKernelFactory.descriptor(
                        new ToolId("project.bibtex.audit")),
                NaturalLanguageStepKernelFactory.descriptor(
                        new ToolId("project.candidate.compose")),
                NaturalLanguageStepKernelFactory.descriptor(
                        new ToolId("sandbox.execute")));
    }

    private static V2EffectHistorySource.Entry completed(
            Fixture fixture, String callId, String kind,
            ObjectValue arguments, ReceiptStatus status) {
        return completed(
                fixture, callId, kind, arguments, status, "");
    }

    private static V2EffectHistorySource.Entry completed(
            Fixture fixture, String callId, String kind,
            ObjectValue arguments, ReceiptStatus status, String output) {
        PersistedEffectIntent persisted = mock(PersistedEffectIntent.class);
        when(persisted.intent()).thenReturn(
                intent(fixture, callId, kind, arguments));
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-" + callId));
        when(receipt.status()).thenReturn(status);
        when(receipt.resultCode()).thenReturn(
                status == ReceiptStatus.SUCCESS
                        ? Optional.empty()
                        : Optional.of("FAILED"));
        when(receipt.exitCode()).thenReturn(Optional.of(
                status == ReceiptStatus.SUCCESS ? 0 : 1));
        when(receipt.standardOutput()).thenReturn(output.isEmpty()
                ? OutputCapture.empty()
                : OutputCapture.inline(output, false));
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
