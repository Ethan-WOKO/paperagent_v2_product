package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.*;
import com.yanban.api.agent.v2.context.V2ExecutionContextSource;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import com.yanban.api.agent.v2.result.V2StepResultSource;
import com.yanban.api.agent.v2.result.V2StepResultStatus;
import com.yanban.api.agent.v2.result.V2StepResultService;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2AdaptiveExecutionCoordinatorTest {
    private static ReflectionContext context() {
        return new ReflectionContext(
                "task", "plan", List.of("recent conversation"),
                List.of(), List.of("receipt"), List.of("step"));
    }

    @Test
    void prematureModelCompleteCannotOverrideDurableFacts() {
        var coordinator = coordinator(
                command -> new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED,
                        "step-1", "receipt ok", false, null),
                ignored -> complete());
        var result = coordinator.execute(command(Map.of()));
        assertEquals("FAILED", result.status());
        assertEquals("PREMATURE_COMPLETE", result.errorCode());
    }

    @Test
    void durableTerminalCutAndReflectionCompleteSucceeds() {
        var coordinator = coordinator(
                command -> new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                        "step-1", "receipt ok", true, null),
                ignored -> complete());
        var result = coordinator.execute(command(Map.of()));
        assertEquals("SUCCEEDED", result.status());
        assertEquals("完成", result.finalText());
    }

    @Test
    void reflectionCompleteMaterializesStepBeforeFinalAnswer() {
        AtomicInteger calls = new AtomicInteger();
        V2AdaptiveCyclePort cycle = command -> {
            if (calls.incrementAndGet() == 1) {
                assertEquals(
                        V2AdaptiveCyclePort.Action.EXECUTE,
                        command.action());
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "receipt ok", false, null,
                        List.of("executionReceipt=status=SUCCESS"),
                        true, false);
            }
            assertEquals(
                    V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "step-1", "durable completion", true, null);
        };

        var result = coordinator(cycle, ignored -> complete())
                .execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals("完成", result.finalText());
        assertEquals(2, calls.get());
    }

    @Test
    void durableExecutionContextReachesReflectionModel() {
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = command -> {
            if (cycleCalls.incrementAndGet() == 1) {
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "sandbox succeeded", false, null,
                        List.of("currentCycleFact=success"), true, false);
            }
            assertEquals(
                    V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "step-1", "completed", true, null);
        };
        V2ExecutionContextSource contextSource =
                mock(V2ExecutionContextSource.class);
        when(contextSource.inspect(
                new PlanId("plan-1"), new PlanStepId("step-1")))
                .thenReturn(new V2ExecutionContextSource.Projection(
                        List.of("acceptedStepResult=previous success"),
                        List.of("toolExecution=exitCode 0"),
                        Optional.of("preparedCandidate=full file content"),
                        Optional.of("latestStepDecision=continue")));
        AtomicReference<ReflectionContext> reflected =
                new AtomicReference<>();
        ReflectionProvider provider = value -> {
            reflected.set(value);
            return complete();
        };

        var result = new V2AdaptiveExecutionCoordinator(
                cycles, provider,
                new StrictReflectionDecisionParser(new ObjectMapper()),
                null, contextSource).execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertTrue(reflected.get().completedFacts().contains(
                "acceptedStepResult=previous success"));
        assertTrue(reflected.get().recentExecutionFacts().contains(
                "toolExecution=exitCode 0"));
        assertTrue(reflected.get().recentExecutionFacts().contains(
                "candidateContent=preparedCandidate=full file content"));
        assertTrue(reflected.get().recentExecutionFacts().contains(
                "previousReflection=latestStepDecision=continue"));
    }

    @Test
    void acceptedModelProposalIsPersistedBeforeStepCompletion() {
        AtomicInteger calls = new AtomicInteger();
        V2AdaptiveCyclePort cycle = command -> {
            if (calls.incrementAndGet() == 1) {
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "MODEL_PROPOSED_STEP_RESULT",
                        false, null, List.of(), false, false,
                        Optional.of(proposedStepResult()));
            }
            assertEquals(
                    V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "step-1", "completed", true, null);
        };
        V2StepResultService results = mock(V2StepResultService.class);
        when(results.acceptedCompletedFacts(new PlanId("plan-1")))
                .thenReturn(List.of());
        when(results.accept(
                "step-result.test", "proposed result"))
                .thenReturn(acceptedProposedStepResult());
        var coordinator = new V2AdaptiveExecutionCoordinator(
                cycle, ignored -> complete(),
                new StrictReflectionDecisionParser(new ObjectMapper()),
                results);

        var result = coordinator.execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals("proposed result", result.finalText());
        assertEquals("proposed result", result.steps().get(0).detail());
        verify(results).accept(
                "step-result.test", "proposed result");
    }

    @Test
    void receiptOnlyResultPersistsAllCurrentStepReceiptReferences() {
        ReceiptId firstReceipt = new ReceiptId("receipt-first");
        ReceiptId secondReceipt = new ReceiptId("receipt-second");
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = command -> {
            int call = cycleCalls.incrementAndGet();
            if (call <= 2) {
                ReceiptId receipt = call == 1
                        ? firstReceipt : secondReceipt;
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "receipt available", false, null,
                        List.of("executionReceipt=stepId=step-1,"
                                + "status=SUCCESS,"),
                        true, false, List.of(receipt), Optional.empty());
            }
            assertEquals(V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "step-1", "completed", true, null);
        };
        AtomicInteger reflectionCalls = new AtomicInteger();
        ReflectionProvider reflections = context -> {
            int call = reflectionCalls.incrementAndGet();
            if (call == 1) {
                return """
                        {"decision":"CONTINUE","reason":"more evidence",
                         "finalText":null,"replacementSteps":[]}
                        """;
            }
            assertTrue(context.recentExecutionFacts().contains(
                    "activeStepReceiptId=receipt-first"));
            assertTrue(context.recentExecutionFacts().contains(
                    "activeStepReceiptId=receipt-second"));
            return complete();
        };
        V2StepResultService results = mock(V2StepResultService.class);
        when(results.acceptedCompletedFacts(new PlanId("plan-1")))
                .thenReturn(List.of());
        var proposal = reflectionStepResult(
                V2StepResultStatus.PROPOSED,
                List.of(firstReceipt, secondReceipt));
        var accepted = reflectionStepResult(
                V2StepResultStatus.ACCEPTED,
                List.of(firstReceipt, secondReceipt));
        when(results.proposeCurrent(
                new PlanId("plan-1"), new PlanStepId("step-1"),
                V2StepResultSource.REFLECTION, "完成",
                List.of(firstReceipt, secondReceipt)))
                .thenReturn(proposal);
        when(results.accept("step-result.reflection", "完成"))
                .thenReturn(accepted);

        var result = new V2AdaptiveExecutionCoordinator(
                cycles, reflections,
                new StrictReflectionDecisionParser(new ObjectMapper()),
                results).execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals("完成", result.finalText());
        verify(results).proposeCurrent(
                new PlanId("plan-1"), new PlanStepId("step-1"),
                V2StepResultSource.REFLECTION, "完成",
                List.of(firstReceipt, secondReceipt));
    }

    @Test
    void terminalPlanRecoversFinalTextFromAcceptedStepResult() {
        V2StepResultService results = mock(V2StepResultService.class);
        when(results.latestAcceptedCompleted(new PlanId("plan-1")))
                .thenReturn(Optional.of(acceptedStepResult()));
        var coordinator = new V2AdaptiveExecutionCoordinator(
                ignored -> new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .PLAN_SUCCEEDED,
                        null, "persisted terminal cut", true, null),
                ignored -> {
                    throw new AssertionError(
                            "terminal recovery must not call reflection");
                },
                new StrictReflectionDecisionParser(new ObjectMapper()),
                results);

        var result = coordinator.execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals("persisted final answer", result.finalText());
        assertEquals("SUCCEEDED", result.steps().get(0).status());
    }

    @Test
    void acceptedActiveStepResultResumesCompletionWithoutReflection() {
        AtomicInteger calls = new AtomicInteger();
        var coordinator = coordinator(command -> {
            if (calls.incrementAndGet() == 1) {
                assertEquals(V2AdaptiveCyclePort.Action.EXECUTE,
                        command.action());
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "accepted result recovered",
                        false, null, List.of(), false, false,
                        Optional.of(acceptedStepResult()));
            }
            assertEquals(V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "step-1", "completed", true, null);
        }, ignored -> {
            throw new AssertionError(
                    "accepted Step result must not be reflected again");
        });

        var result = coordinator.execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals("persisted final answer", result.finalText());
        assertEquals(2, calls.get());
    }

    @Test
    void laterReflectionRetainsFactsFromEarlierToolSlots() {
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = ignored -> {
            int call = cycleCalls.incrementAndGet();
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED,
                    "step-1", "receipt " + call, false, null,
                    List.of("executionReceipt=Receipt-" + call),
                    true, false);
        };
        AtomicInteger reflectionCalls = new AtomicInteger();
        ReflectionProvider provider = reflectionContext -> {
            int call = reflectionCalls.incrementAndGet();
            List<String> facts =
                    reflectionContext.recentExecutionFacts();
            assertEquals(
                    1, Collections.frequency(facts, "receipt"),
                    "base facts must not be duplicated");
            assertTrue(facts.contains("activeStepId=step-1"));
            assertTrue(facts.contains("activeStepTitle=step-1"));
            if (call == 1) {
                assertTrue(facts.contains(
                        "executionReceipt=Receipt-1"));
                assertFalse(facts.contains(
                        "executionReceipt=Receipt-2"));
                return """
                        {"decision":"CONTINUE","reason":"run another tool",
                         "finalText":null,"replacementSteps":[]}
                        """;
            }
            assertTrue(facts.contains(
                    "executionReceipt=Receipt-1"));
            assertTrue(facts.contains(
                    "executionReceipt=Receipt-2"));
            return """
                    {"decision":"FAIL","reason":"test complete",
                     "finalText":null,"replacementSteps":[]}
                    """;
        };

        var result = coordinator(cycles, provider)
                .execute(command(Map.of()));

        assertEquals("FAILED", result.status());
        assertEquals("REFLECTION_FAILED", result.errorCode());
        assertEquals("FAILED", result.steps().get(0).status());
        assertEquals(2, cycleCalls.get());
        assertEquals(2, reflectionCalls.get());
    }

    @Test
    void continueDecisionIsPassedToTheNextToolSelectionCycle() {
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = command -> {
            int call = cycleCalls.incrementAndGet();
            if (call == 1) {
                assertEquals(
                        V2AdaptiveCyclePort.Action.EXECUTE,
                        command.action());
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "reflection selected", false, null,
                        List.of(
                                "executionReceipt=status=SUCCESS",
                                "MODEL_PROPOSED_STEP_RESULT"),
                        true, false, Optional.of(proposedStepResult()));
            }
            if (call == 2) {
                assertEquals(
                        V2AdaptiveCyclePort.Action.EXECUTE,
                        command.action());
                assertTrue(command.modelContextFacts().stream()
                        .anyMatch(value -> value.contains(
                                "previousReflectionDecision=CONTINUE")));
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "same proposal", false, null,
                        List.of("MODEL_PROPOSED_STEP_RESULT"),
                        false, false,
                        Optional.of(rejectedStepResult()));
            }
            assertEquals(
                    V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "step-1", "durable completion", true, null);
        };
        AtomicInteger reflectionCalls = new AtomicInteger();
        ReflectionProvider provider = ignored -> {
            int call = reflectionCalls.incrementAndGet();
            if (call == 1) {
                return """
                        {"decision":"CONTINUE","reason":"continue",
                         "finalText":null,"replacementSteps":[]}
                        """;
            }
            return complete();
        };

        var result = coordinator(cycles, provider)
                .execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals(3, cycleCalls.get());
        assertEquals(2, reflectionCalls.get());
    }

    @Test
    void continueDoesNotTriggerASecondReflectionModelCall() {
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = command -> {
            int call = cycleCalls.incrementAndGet();
            if (call == 2) {
                assertTrue(command.modelContextFacts().stream()
                        .anyMatch(value -> value.contains(
                                "previousReflectionDecision=CONTINUE")));
            }
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED,
                    "step-1", "reflection selected", false, null,
                    List.of(
                            "executionReceipt=status=SUCCESS",
                            "MODEL_PROPOSED_STEP_RESULT"),
                    true, false, Optional.of(call == 1
                            ? proposedStepResult()
                            : rejectedStepResult()));
        };
        AtomicInteger reflectionCalls = new AtomicInteger();
        ReflectionProvider provider = ignored -> {
            int call = reflectionCalls.incrementAndGet();
            if (call == 1) {
                return """
                        {"decision":"CONTINUE","reason":"continue",
                         "finalText":null,"replacementSteps":[]}
                        """;
            }
            return """
                    {"decision":"FAIL","reason":"cannot continue",
                     "finalText":null,"replacementSteps":[]}
                    """;
        };

        var result = coordinator(cycles, provider)
                .execute(command(Map.of()));

        assertEquals("FAILED", result.status());
        assertEquals("REFLECTION_FAILED", result.errorCode());
        assertEquals(2, cycleCalls.get());
        assertEquals(2, reflectionCalls.get());
    }

    @Test
    void eightExecutionCyclesAndEightCompletionTransitionsFitBudget() {
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = command -> {
            int call = cycleCalls.incrementAndGet();
            assertEquals(call, command.cycle());
            if ((call & 1) == 1) {
                assertEquals(
                        V2AdaptiveCyclePort.Action.EXECUTE,
                        command.action());
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "receipt " + call, false, null,
                        List.of("executionReceipt=status=SUCCESS"),
                        true, false);
            }
            assertEquals(
                    V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    call == V2AdaptiveExecutionCoordinator.MAX_CYCLES * 2
                            ? V2AdaptiveCyclePort.CycleResult.State
                                    .PLAN_SUCCEEDED
                            : V2AdaptiveCyclePort.CycleResult.State
                                    .STEP_SUCCEEDED,
                    "step-1", "completion " + call,
                    call == V2AdaptiveExecutionCoordinator.MAX_CYCLES * 2,
                    null);
        };
        AtomicInteger reflectionCalls = new AtomicInteger();

        var result = coordinator(cycles, ignored -> {
            reflectionCalls.incrementAndGet();
            return complete();
        }).execute(command(Map.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals(V2AdaptiveExecutionCoordinator.MAX_CYCLES,
                result.reflections());
        assertEquals(V2AdaptiveExecutionCoordinator.MAX_CYCLES * 2,
                cycleCalls.get());
        assertEquals(V2AdaptiveExecutionCoordinator.MAX_CYCLES,
                reflectionCalls.get());
    }

    @Test
    void ninthExecutionCycleIsRejectedAfterEightCompletionTransitions() {
        AtomicInteger cycleCalls = new AtomicInteger();
        V2AdaptiveCyclePort cycles = command -> {
            int call = cycleCalls.incrementAndGet();
            assertEquals(call, command.cycle());
            if ((call & 1) == 1) {
                assertEquals(
                        V2AdaptiveCyclePort.Action.EXECUTE,
                        command.action());
                return new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .STEP_SUCCEEDED,
                        "step-1", "receipt " + call, false, null,
                        List.of("executionReceipt=status=SUCCESS"),
                        true, false);
            }
            assertEquals(
                    V2AdaptiveCyclePort.Action.COMPLETE_STEP,
                    command.action());
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED,
                    "step-1", "completion " + call, false, null);
        };
        AtomicInteger reflectionCalls = new AtomicInteger();

        var result = coordinator(cycles, ignored -> {
            reflectionCalls.incrementAndGet();
            return complete();
        }).execute(command(Map.of()));

        assertEquals("FAILED", result.status());
        assertEquals("CYCLE_LIMIT_EXCEEDED", result.errorCode());
        assertEquals(V2AdaptiveExecutionCoordinator.MAX_CYCLES,
                result.reflections());
        assertEquals(V2AdaptiveExecutionCoordinator.MAX_CYCLES * 2,
                cycleCalls.get());
        assertEquals(V2AdaptiveExecutionCoordinator.MAX_CYCLES,
                reflectionCalls.get());
    }

    @Test
    void failedReceiptReplansWithoutDuplicateRowsAndCountsRepairOnlyAfterCommit() {
        AtomicInteger cycle = new AtomicInteger();
        V2AdaptiveCyclePort cycles = ignored -> switch (
                cycle.incrementAndGet()) {
            case 1 -> new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.FAILED,
                    "step-1", "compile failed", false, null,
                    List.of("executionReceipt=status=FAILURE stderr=bad import"),
                    true, true);
            case 2 -> new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.REPLAN_REQUIRED,
                    "step-1", "REPLAN_APPLIED", false, new Object(),
                    List.of("persistedReplan=revision-2"),
                    false, false);
            default -> new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "repair-1", "compiled", true, null,
                    List.of("executionReceipt=status=SUCCESS exitCode=0"),
                    true, false);
        };
        AtomicInteger reflection = new AtomicInteger();
        ReflectionProvider provider = ignored -> switch (
                reflection.incrementAndGet()) {
            case 1 -> """
                    {"decision":"REPLAN","reason":"repair failed import",
                     "finalText":null,"replacementSteps":[{
                       "id":"repair-1","intent":"Repair source",
                       "expectedOutcome":"Compilation succeeds",
                       "dependencies":[],
                       "completionCriteria":["exit code is zero"],
                       "maxAttempts":1,"maxDurationSeconds":120}]}
                    """;
            case 2 -> complete();
            default -> throw new AssertionError(
                    "persisted replan must execute before another reflection");
        };
        var result = coordinator(cycles, provider).execute(command(
                Map.of("step-1", "project.read")));

        assertEquals("SUCCEEDED", result.status());
        assertEquals(1, result.replans());
        assertEquals(1, result.repairs());
        assertEquals(2, result.steps().size());
        assertEquals("SUPERSEDED_BY_REPLAN",
                result.steps().get(0).status());
        assertEquals("SUCCEEDED", result.steps().get(1).status());
        assertEquals(1, result.steps().stream()
                .filter(value -> "step-1".equals(value.title())).count());
        assertEquals(3, cycle.get());
        assertEquals(2, reflection.get());
    }

    @Test
    void pendingSandboxRecoveryKeepsStepRunning() {
        var result = coordinator(
                ignored -> new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State
                                .RECOVERY_PENDING,
                        "step-1", "sandbox execution is still running",
                        false, null),
                ignored -> {
                    throw new AssertionError(
                            "pending execution must not reflect yet");
                })
                .execute(command(Map.of()));

        assertEquals("RUNNING", result.status());
        assertEquals("RUNNING", result.steps().get(0).status());
        assertEquals("sandbox execution is still running",
                result.steps().get(0).detail());
    }

    @Test
    void sandboxCapabilityReachesRuntimeAndReflection() {
        AtomicInteger cycles = new AtomicInteger();
        AtomicInteger provider = new AtomicInteger();
        var coordinator = coordinator(command -> {
            cycles.incrementAndGet();
            return new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.FAILED,
                    "step-1", "sandbox receipt failed", false, null,
                    List.of("executionReceipt=status=FAILURE"),
                    true, true);
        }, ignored -> {
            provider.incrementAndGet();
            return """
                    {"decision":"FAIL","reason":"sandbox failed",
                     "finalText":null,"replacementSteps":[]}
                    """;
        });
        var result = coordinator.execute(command(
                Map.of("step-1", "sandbox.execute")));
        assertEquals("REFLECTION_FAILED", result.errorCode());
        assertEquals(1, cycles.get());
        assertEquals(1, provider.get());
    }

    @Test
    void reflectionProviderAndParseFailuresAreDistinct() {
        V2AdaptiveCyclePort cycle = ignored ->
                new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State.FAILED,
                        "step-1", "EFFECT_REJECTED", false, null,
                        List.of("failed receipt"), true, true);

        var providerFailure = coordinator(cycle, ignored -> {
            throw new IllegalStateException("provider failed");
        }).execute(command(Map.of("step-1", "project.search")));
        var parseFailure = coordinator(cycle, ignored -> "not-json")
                .execute(command(Map.of(
                        "step-1", "project.search")));

        assertEquals("REFLECTION_PROVIDER_EXCEPTION",
                providerFailure.errorCode());
        assertEquals("REFLECTION_PARSE_INVALID",
                parseFailure.errorCode());
    }

    @Test
    void agentLoopExceptionHasExplicitCycleStageCode() {
        var coordinator = coordinator(command -> {
            throw new V2AdaptiveRuntimeCycleFactory.CycleStageException(
                    "AGENT_LOOP");
        }, ignored -> {
            throw new AssertionError();
        });

        var result = coordinator.execute(command(
                Map.of("step-1", "project.read")));

        assertEquals("CYCLE_AGENT_LOOP_EXCEPTION", result.errorCode());
    }

    @Test
    void agentLoopDiagnosticRetainsItsSanitizedSubstage() {
        var coordinator = coordinator(command -> {
            throw new V2AdaptiveRuntimeCycleFactory.CycleStageException(
                    V2AdaptiveRuntimeCycleFactory.agentLoopStage(
                            "kernel.turn_decision.collaborator_exception"));
        }, ignored -> {
            throw new AssertionError();
        });

        var result = coordinator.execute(command(
                Map.of("step-1", "project.read")));

        assertEquals(
                "CYCLE_LOOP_KERNEL_TURN_DECISION_"
                        + "COLLABORATOR_EXCEPTION",
                result.errorCode());
    }

    @Test
    void unsafeAgentLoopDiagnosticFallsBackWithoutLeakingIt() {
        assertEquals("AGENT_LOOP",
                V2AdaptiveRuntimeCycleFactory.agentLoopStage(
                        "kernel.secret/path"));
    }

    @Test
    void agentLoopDiagnosticAlwaysFitsPersistentErrorCode() {
        String stage = V2AdaptiveRuntimeCycleFactory.agentLoopStage(
                "progression.effectEvidence."
                        + "anExtremelyLongInternalDiagnosticBoundary");

        assertTrue(stage.length() <= 48);
        assertTrue(("CYCLE_" + stage + "_EXCEPTION").length() <= 64);
    }

    @Test
    void replanStepsRemainGoalBasedWithoutToolBindings() {
        ReflectionOutcome replan = new StrictReflectionDecisionParser(
                new ObjectMapper()).parse("""
                {"decision":"REPLAN","reason":"repair",
                 "finalText":null,"replacementSteps":[
                  {"id":"repair-read","intent":"Read",
                   "expectedOutcome":"content","dependencies":[],
                   "completionCriteria":["read"],"maxAttempts":1,
                   "maxDurationSeconds":30},
                  {"id":"repair-analyze","intent":"Analyze",
                   "expectedOutcome":"answer",
                   "dependencies":["repair-read"],
                   "completionCriteria":["answer"],"maxAttempts":1,
                    "maxDurationSeconds":30}]}
                """);

        Map<io.paperagent.v2.contracts.PlanStepId,
                io.paperagent.v2.contracts.ToolId> bindings =
                V2AdaptiveRuntimeCycleFactory.bindingsForCycle(
                        Map.of(), replan);

        assertTrue(bindings.isEmpty());
    }

    private static V2AdaptiveExecutionCoordinator coordinator(
            V2AdaptiveCyclePort cycles, ReflectionProvider provider) {
        return new V2AdaptiveExecutionCoordinator(
                cycles, provider,
                new StrictReflectionDecisionParser(new ObjectMapper()));
    }

    private static V2AdaptiveExecutionCoordinator.Command command(
            Map<String, String> bindings) {
        return new V2AdaptiveExecutionCoordinator.Command(
                1L, 2L, "plan-1",
                List.of(new V2AdaptiveTurnResponse.Step(
                        1, "step-1", "PENDING", "")),
                bindings, Map.of("step-1", 0), context());
    }

    private static String complete() {
        return "{\"decision\":\"COMPLETE\",\"reason\":\"done\","
                + "\"finalText\":\"完成\",\"replacementSteps\":[]}";
    }

    private static V2StepResultSnapshot proposedStepResult() {
        return stepResult(V2StepResultStatus.PROPOSED);
    }

    private static V2StepResultSnapshot rejectedStepResult() {
        return stepResult(V2StepResultStatus.REJECTED);
    }

    private static V2StepResultSnapshot acceptedStepResult() {
        return new V2StepResultSnapshot(
                "step-result.accepted", new PlanId("plan-1"),
                new PlanRevisionId("revision-1"),
                new PlanStepId("step-1"),
                new EventId("activation-1"),
                V2StepResultSource.MODEL,
                "persisted final answer", "a".repeat(64), List.of(),
                V2StepResultStatus.ACCEPTED,
                Optional.of("persisted final answer"),
                Optional.of("b".repeat(64)),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static V2StepResultSnapshot acceptedProposedStepResult() {
        return new V2StepResultSnapshot(
                "step-result.test", new PlanId("plan-1"),
                new PlanRevisionId("revision-1"),
                new PlanStepId("step-1"),
                new EventId("activation-1"),
                V2StepResultSource.MODEL,
                "proposed result", "a".repeat(64), List.of(),
                V2StepResultStatus.ACCEPTED,
                Optional.of("proposed result"),
                Optional.of("c".repeat(64)),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static V2StepResultSnapshot reflectionStepResult(
            V2StepResultStatus status, List<ReceiptId> receipts) {
        return new V2StepResultSnapshot(
                "step-result.reflection", new PlanId("plan-1"),
                new PlanRevisionId("revision-1"),
                new PlanStepId("step-1"),
                new EventId("activation-1"),
                V2StepResultSource.REFLECTION,
                "完成", "d".repeat(64), receipts, status,
                status == V2StepResultStatus.ACCEPTED
                        ? Optional.of("完成") : Optional.empty(),
                status == V2StepResultStatus.ACCEPTED
                        ? Optional.of("e".repeat(64)) : Optional.empty(),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static V2StepResultSnapshot stepResult(
            V2StepResultStatus status) {
        return new V2StepResultSnapshot(
                "step-result.test", new PlanId("plan-1"),
                new PlanRevisionId("revision-1"),
                new PlanStepId("step-1"),
                new EventId("activation-1"),
                V2StepResultSource.MODEL,
                "proposed result", "a".repeat(64), List.of(),
                status,
                Optional.empty(), Optional.empty(),
                Instant.EPOCH, Instant.EPOCH);
    }
}
