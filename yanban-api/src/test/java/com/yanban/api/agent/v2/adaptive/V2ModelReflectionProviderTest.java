package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionContext;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.UsageMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V2ModelReflectionProviderTest {
    @Test
    void promptDefinesCompleteAsCurrentStepCompletionNotPlanTerminal() {
        String system = captureSystemPrompt();

        assertTrue(system.contains(
                "current active Step's completion criteria are"));
        assertTrue(system.contains("the Plan has later Steps"));
        assertTrue(system.contains("persists this Step"));
        assertTrue(system.contains("completion and advances the Plan"));
        assertTrue(system.contains(
                "final answer only when the completed Step makes the whole"));
        assertTrue(system.contains(
                "CONTINUE means the same active Step still needs"));
        assertTrue(system.contains(
                "completed nonterminal Step"));
        assertTrue(system.contains(
                "discards that provisional text after advancing"));
        assertTrue(system.contains("Do not return"));
        assertTrue(system.contains(
                "CONTINUE merely because later Steps remain"));
        assertTrue(system.contains(
                "successful Receipt satisfies the current active Step"));
        assertTrue(system.contains("Do not REPLAN"));
        assertTrue(system.contains(
                "streamline, combine, reorder"));
        assertTrue(system.contains(
                "active Step itself cannot proceed"));
        assertFalse(system.contains(
                "COMPLETE is allowed only when the supplied durable cut is terminal"));
    }

    @Test
    void promptDefinesTheExactReplanSchemaAndFailedReceiptAction() {
        String system = captureSystemPrompt();

        assertTrue(system.contains(
                "id, intent, expectedOutcome, dependencies"));
        assertTrue(system.contains("completionCriteria"));
        assertTrue(system.contains("maxAttempts"));
        assertTrue(system.contains("maxDurationSeconds"));
        assertTrue(system.contains(
                "Tool selection remains dynamic"));
        assertTrue(system.contains(
                "failed Receipt can be corrected, return REPLAN"));
    }

    @Test
    void promptDoesNotTreatSandboxExecutionAsCandidateDelivery() {
        String system = captureSystemPrompt();

        assertTrue(system.contains(
                "requires creating or modifying Project"));
        assertTrue(system.contains(
                "project.candidate.compose Receipt"));
        assertTrue(system.contains(
                "sandbox.execute Receipt proves"));
        assertTrue(system.contains(
                "does not prove a Workspace diff or Candidate"));
        assertTrue(system.contains(
                "project.read Receipt proves reading only"));
        assertTrue(system.contains(
                "whose intent, expected outcome, or completion criteria require"));
        assertTrue(system.contains(
                "sandbox compilation, execution, or tests"));
        assertTrue(system.contains(
                "successful executionReceipt whose toolKind is sandbox.execute"));
        assertTrue(system.contains(
                "toolKind is project.candidate.compose"));
        assertTrue(system.contains(
                "stepId equals activeStepId"));
        assertTrue(system.contains(
                "REVIEWABLE_CANDIDATE_CREATED"));
    }

    @Test
    void completeDecisionIsAuditedAgainstCurrentStepAuthority() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ModelRequest> audit = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                audit.set(request);
            }
            String decision = call == 1
                    ? "{\"decision\":\"COMPLETE\",\"reason\":\"done\","
                            + "\"finalText\":\"done\","
                            + "\"replacementSteps\":[]}"
                    : "{\"complete\":false,"
                            + "\"reason\":\"candidate not proven\","
                            + "\"stepResult\":null}";
            return response(decision);
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of(
                        "activeStepId=step-3",
                        "executionReceipt=stepId=step-3, "
                                + "authorityScope=SANDBOX_EXECUTION_ONLY"),
                List.of("create candidate")));

        assertTrue(result.contains("\"decision\":\"CONTINUE\""));
        assertTrue(audit.get().messages().get(0).content().contains(
                "SANDBOX_EXECUTION_ONLY does not"));
        assertTrue(audit.get().messages().get(1).content().contains(
                "\"activeStepId\":\"step-3\""));
    }

    @Test
    void supportedCompleteDecisionCanPassAudit() {
        AtomicInteger calls = new AtomicInteger();
        String complete = "{\"decision\":\"COMPLETE\","
                + "\"reason\":\"candidate proven\","
                + "\"finalText\":\"candidate ready\","
                + "\"replacementSteps\":[]}";
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            return response(call == 1 ? complete
                    : "{\"complete\":true,"
                            + "\"reason\":\"candidate receipt matches\","
                            + "\"stepResult\":\"candidate ready\"}");
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of(
                        "activeStepId=step-3",
                        "executionReceipt=stepId=step-3, "
                                + "authorityScope=REVIEWABLE_CANDIDATE_CREATED"),
                List.of("create candidate")));

        assertTrue(result.contains("\"decision\":\"COMPLETE\""));
        assertTrue(result.contains("\"finalText\":\"candidate ready\""));
        assertTrue(calls.get() == 2);
    }

    @Test
    void unsupportedContinueRemainsContinueAfterStepStateAudit() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            return response(call == 1
                    ? "{\"decision\":\"CONTINUE\",\"reason\":\"edit next\","
                            + "\"finalText\":null,"
                            + "\"replacementSteps\":[]}"
                    : "{\"complete\":false,"
                            + "\"reason\":\"only read evidence exists\","
                            + "\"stepResult\":null}");
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of(
                        "activeStepId=step-3",
                        "executionReceipt=stepId=step-3, "
                                + "authorityScope=PROJECT_CONTENT_READ_ONLY"),
                List.of("create candidate")));

        assertTrue(result.contains("\"decision\":\"CONTINUE\""));
        assertTrue(calls.get() == 2);
    }

    @Test
    void staleContinueIsCorrectedWhenCandidateReceiptCompletesStep() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            return response(call == 1
                    ? "{\"decision\":\"CONTINUE\",\"reason\":\"repeat\","
                            + "\"finalText\":null,"
                            + "\"replacementSteps\":[]}"
                    : "{\"complete\":true,"
                            + "\"reason\":\"candidate receipt proves delivery\","
                            + "\"stepResult\":\"候选修改已创建\"}");
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of(
                        "activeStepId=step-3",
                        "executionReceipt=stepId=step-3, "
                                + "authorityScope=REVIEWABLE_CANDIDATE_CREATED"),
                List.of("create candidate")));

        assertTrue(result.contains("\"decision\":\"COMPLETE\""));
        assertTrue(result.contains("候选修改已创建"));
        assertTrue(calls.get() == 2);
    }

    @Test
    void auditHighlightsLaterSuccessfulSandboxReceiptAfterEarlierFailures() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ModelRequest> audit = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                audit.set(request);
            }
            return response(call == 1
                    ? "{\"decision\":\"CONTINUE\",\"reason\":\"retry\","
                            + "\"finalText\":null,"
                            + "\"replacementSteps\":[]}"
                    : "{\"complete\":true,"
                            + "\"reason\":\"later sandbox success wins\","
                            + "\"stepResult\":\"编译运行成功\"}");
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(new ReflectionContext(
                "task",
                "{\"steps\":[{\"id\":\"step-3\","
                        + "\"completionCriteria\":[\"compile and run\"]}]}",
                List.of(), List.of(),
                List.of(
                        "activeStepId=step-3",
                        "activeStepTitle=compile and run candidate",
                        "executionReceipt=PersistentPlanAgentLoopReceiptFacts["
                                + "stepId=step-3, toolKind=sandbox.execute, "
                                + "authorityScope=FAILED_EFFECT_ONLY, "
                                + "status=FAILURE, exitCode=Optional[1], "
                                + "standardOutput=, standardError=compile failed]",
                        "executionReceipt=PersistentPlanAgentLoopReceiptFacts["
                                + "stepId=step-3, toolKind=sandbox.execute, "
                                + "authorityScope=SANDBOX_EXECUTION_ONLY, "
                                + "status=SUCCESS, exitCode=Optional[0], "
                                + "standardOutput=ok, standardError=]"),
                List.of()));

        assertTrue(result.contains("\"decision\":\"COMPLETE\""));
        String input = audit.get().messages().get(1).content();
        assertTrue(input.contains("successfulCurrentStepReceipts"));
        assertTrue(input.contains("status=SUCCESS"));
        assertTrue(input.contains("exitCode=Optional[0]"));
        assertTrue(audit.get().messages().get(0).content().contains(
                "successful Receipt is not invalidated"));
    }

    @Test
    void stepStateAuditExcludesReceiptsFromOtherSteps() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ModelRequest> audit = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                audit.set(request);
            }
            return response(call == 1
                    ? "{\"decision\":\"CONTINUE\",\"reason\":\"check\","
                            + "\"finalText\":null,"
                            + "\"replacementSteps\":[]}"
                    : "{\"complete\":true,\"reason\":\"candidate ready\","
                            + "\"stepResult\":\"candidate ready\"}");
        }, new ObjectMapper(), null, null, null);

        provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of(
                        "activeStepId=step-3",
                        "activeStepTitle=create candidate",
                        "executionReceipt=stepId=step-2, "
                                + "authorityScope=SANDBOX_EXECUTION_ONLY",
                        "executionReceipt=stepId=step-3, "
                                + "authorityScope=REVIEWABLE_CANDIDATE_CREATED"),
                List.of("create candidate")));

        String input = audit.get().messages().get(1).content();
        assertTrue(input.contains("stepId=step-3"));
        assertTrue(input.contains("REVIEWABLE_CANDIDATE_CREATED"));
        assertFalse(input.contains("stepId=step-2"));
        assertFalse(input.contains("SANDBOX_EXECUTION_ONLY"));
    }

    @Test
    void malformedCompletionAuditCannotReplaceStrictReflectionOutput() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new V2ModelReflectionProvider(request -> {
            int call = calls.incrementAndGet();
            return response(call == 1
                    ? "{\"decision\":\"COMPLETE\",\"reason\":\"done\","
                            + "\"finalText\":\"done\","
                            + "\"replacementSteps\":[]}"
                    : "{\"complete\":true,\"reason\":\"ok\","
                            + "\"stepResult\":\"done\","
                            + "\"unexpected\":true}");
        }, new ObjectMapper(), null, null, null);

        assertThrows(IllegalStateException.class, () -> provider.reflect(
                new ReflectionContext(
                        "task", "plan", List.of(), List.of(),
                        List.of("activeStepId=step-1"),
                        List.of("read project"))));
    }

    private static String captureSystemPrompt() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            captured.set(request);
            return response(
                    "{\"decision\":\"FAIL\",\"reason\":\"stop\","
                            + "\"finalText\":null,"
                            + "\"replacementSteps\":[]}");
        }, new ObjectMapper(), null, null, null);

        provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of("failed receipt"), List.of("step")));

        return captured.get().messages().get(0).content();
    }

    private static ModelResponse response(String value) {
        return new ModelResponse(
                Optional.of(value), List.of(), FinishReason.STOP,
                new UsageMetadata(1, 1, 0, Map.of()), Map.of());
    }
}
