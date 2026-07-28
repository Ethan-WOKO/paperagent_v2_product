package io.paperagent.v2.runtime.synthesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.InMemoryFinalSynthesisRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultFinalSynthesisComposerTest {
    private static final Instant NOW =
            Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void permanentReplayReturnsBeforeReceiptIntentAndProviderChecks() {
        var repository = new InMemoryFinalSynthesisRepository();
        var terminal = terminal();
        FinalSynthesis existing = new FinalSynthesis(
                new FinalSynthesisId("synthesis-existing"),
                terminal.taskFrame().id(), terminal.plan().id(),
                terminal.plan().latestRevision().id(),
                Optional.empty(), Optional.empty(),
                List.of(new ReceiptId("receipt-1")),
                "Search task queued.", NOW);
        assertEquals(PersistenceOutcome.APPLIED,
                repository.append(existing).outcome());
        AtomicInteger calls = new AtomicInteger();
        var composer = new DefaultFinalSynthesisComposer(
                store(repository),
                receiptSource(new io.paperagent.v2.persistence.ReceiptRepository() {
                    public PersistenceResult<io.paperagent.v2.contracts.ExecutionReceipt> append(
                            io.paperagent.v2.contracts.ExecutionReceipt value) {
                        throw new AssertionError("receipt append");
                    }
                    public PersistenceResult<io.paperagent.v2.contracts.ExecutionReceipt> find(
                            ReceiptId value) {
                        throw new AssertionError("receipt find");
                    }
                }),
                intentSource(new io.paperagent.v2.persistence.EffectIntentRepository() {
                    public PersistenceResult<io.paperagent.v2.persistence.PersistedEffectIntent> persist(
                            io.paperagent.v2.persistence.EffectIntentRequest value) {
                        throw new AssertionError("intent persist");
                    }
                    public PersistenceResult<io.paperagent.v2.persistence.PersistedEffectIntent> find(
                            io.paperagent.v2.contracts.ToolCallId value) {
                        throw new AssertionError("intent find");
                    }
                }),
                request -> {
                    calls.incrementAndGet();
                    return "unexpected";
                });

        var result = composer.compose(new FinalSynthesisCompositionRequest(
                cut(terminal), Optional.empty(), NOW.plusSeconds(99)));

        assertEquals(FinalSynthesisDisposition.REPLAYED,
                result.disposition());
        assertEquals(existing, result.synthesis());
        assertEquals(0, calls.get());
    }

    @Test
    void appliesFromExactSuccessfulReceiptAndSameStepLiteratureIntent() {
        var terminal = terminal();
        ReceiptId receiptId = new ReceiptId("receipt-1");
        var receipt = new ExecutionReceipt(
                receiptId, new io.paperagent.v2.contracts.ToolCallId("call-1"),
                ReceiptStatus.SUCCESS, NOW, NOW,
                Optional.of(0), Optional.empty(),
                OutputCapture.inline("task queued", false),
                OutputCapture.empty(), List.of(), Optional.empty(),
                List.of());
        var persistedIntent = new PersistedEffectIntent(
                new EffectIntent(
                        receipt.toolCallId(), terminal.plan().id(),
                        new PlanStepId("step"), "literature.search",
                        new ObjectValue(Map.of())),
                "owner", 1, new EventId("activation"));
        var composer = new DefaultFinalSynthesisComposer(
                store(new InMemoryFinalSynthesisRepository()),
                receiptSource(new io.paperagent.v2.persistence.ReceiptRepository() {
                    public PersistenceResult<ExecutionReceipt> append(
                            ExecutionReceipt value) {
                        return PersistenceResult.applied(value);
                    }
                    public PersistenceResult<ExecutionReceipt> find(
                            ReceiptId value) {
                        return PersistenceResult.found(receipt);
                    }
                }),
                intentSource(new io.paperagent.v2.persistence.EffectIntentRepository() {
                    public PersistenceResult<PersistedEffectIntent> persist(
                            io.paperagent.v2.persistence.EffectIntentRequest value) {
                        return PersistenceResult.found(persistedIntent);
                    }
                    public PersistenceResult<PersistedEffectIntent> find(
                            io.paperagent.v2.contracts.ToolCallId value) {
                        return PersistenceResult.found(persistedIntent);
                    }
                }),
                request -> "Literature search task queued.");

        var result = composer.compose(new FinalSynthesisCompositionRequest(
                cut(terminal), Optional.empty(), NOW));

        assertEquals(FinalSynthesisDisposition.APPLIED,
                result.disposition());
        assertEquals(List.of(receiptId), result.synthesis().receiptIds());
        assertEquals(Optional.empty(), result.synthesis().workspaceDiff());
    }

    @Test
    void rejectsMissingAuthoritativeReceiptBeforeIntentNarrationOrAppend() {
        var terminal = terminal();
        AtomicInteger intentCalls = new AtomicInteger();
        AtomicInteger narrationCalls = new AtomicInteger();
        AtomicInteger appendCalls = new AtomicInteger();
        var composer = new DefaultFinalSynthesisComposer(
                emptyStore(appendCalls),
                ignored -> Optional.empty(),
                (toolCallId, planId, stepId, kind) -> {
                    intentCalls.incrementAndGet();
                    return true;
                },
                request -> {
                    narrationCalls.incrementAndGet();
                    return "unexpected";
                });

        assertThrows(FinalSynthesisCompositionException.class,
                () -> composer.compose(new FinalSynthesisCompositionRequest(
                        cut(terminal), Optional.empty(), NOW)));

        assertEquals(0, intentCalls.get());
        assertEquals(0, narrationCalls.get());
        assertEquals(0, appendCalls.get());
    }

    @Test
    void rejectsExtraCheckpointReceiptBeforeLookupOrSideEffects() {
        var terminal = terminal();
        var checkpoint = terminal.checkpoint().checkpoint();
        var mismatchedCut = new FinalSynthesisTerminalCut(
                terminal.taskFrame(), terminal.plan(),
                new Checkpoint(
                        checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(), checkpoint.stepStates(),
                        List.of(
                                new ReceiptId("receipt-1"),
                                new ReceiptId("receipt-extra")),
                        checkpoint.createdAt()));
        AtomicInteger receiptCalls = new AtomicInteger();
        AtomicInteger intentCalls = new AtomicInteger();
        AtomicInteger narrationCalls = new AtomicInteger();
        AtomicInteger appendCalls = new AtomicInteger();
        var composer = new DefaultFinalSynthesisComposer(
                emptyStore(appendCalls),
                ignored -> {
                    receiptCalls.incrementAndGet();
                    return Optional.empty();
                },
                (toolCallId, planId, stepId, kind) -> {
                    intentCalls.incrementAndGet();
                    return true;
                },
                request -> {
                    narrationCalls.incrementAndGet();
                    return "unexpected";
                });

        assertThrows(FinalSynthesisCompositionException.class,
                () -> composer.compose(new FinalSynthesisCompositionRequest(
                        mismatchedCut, Optional.empty(), NOW)));

        assertEquals(0, receiptCalls.get());
        assertEquals(0, intentCalls.get());
        assertEquals(0, narrationCalls.get());
        assertEquals(0, appendCalls.get());
    }

    @Test
    void rejectsNonSuccessfulReceiptBeforeIntentNarrationOrAppend() {
        var terminal = terminal();
        var failedReceipt = new ExecutionReceipt(
                new ReceiptId("receipt-1"),
                new io.paperagent.v2.contracts.ToolCallId("call-1"),
                ReceiptStatus.FAILURE, NOW, NOW,
                Optional.of(1), Optional.of("failed"),
                OutputCapture.empty(), OutputCapture.inline(
                "provider failure", false),
                List.of(), Optional.empty(), List.of());
        AtomicInteger intentCalls = new AtomicInteger();
        AtomicInteger narrationCalls = new AtomicInteger();
        AtomicInteger appendCalls = new AtomicInteger();
        var composer = new DefaultFinalSynthesisComposer(
                emptyStore(appendCalls),
                ignored -> Optional.of(failedReceipt),
                (toolCallId, planId, stepId, kind) -> {
                    intentCalls.incrementAndGet();
                    return true;
                },
                request -> {
                    narrationCalls.incrementAndGet();
                    return "unexpected";
                });

        assertThrows(FinalSynthesisCompositionException.class,
                () -> composer.compose(new FinalSynthesisCompositionRequest(
                        cut(terminal), Optional.empty(), NOW)));

        assertEquals(0, intentCalls.get());
        assertEquals(0, narrationCalls.get());
        assertEquals(0, appendCalls.get());
    }

    @Test
    void rejectsReceiptLookupReturningDifferentIdBeforeSideEffects() {
        var terminal = terminal();
        var corruptedReceipt = new ExecutionReceipt(
                new ReceiptId("receipt-other"),
                new io.paperagent.v2.contracts.ToolCallId("call-1"),
                ReceiptStatus.SUCCESS, NOW, NOW,
                Optional.of(0), Optional.empty(),
                OutputCapture.inline("task queued", false),
                OutputCapture.empty(), List.of(), Optional.empty(),
                List.of());
        AtomicInteger intentCalls = new AtomicInteger();
        AtomicInteger narrationCalls = new AtomicInteger();
        AtomicInteger appendCalls = new AtomicInteger();
        var composer = new DefaultFinalSynthesisComposer(
                emptyStore(appendCalls),
                ignored -> Optional.of(corruptedReceipt),
                (toolCallId, planId, stepId, kind) -> {
                    intentCalls.incrementAndGet();
                    return true;
                },
                request -> {
                    narrationCalls.incrementAndGet();
                    return "unexpected";
                });

        assertThrows(FinalSynthesisCompositionException.class,
                () -> composer.compose(new FinalSynthesisCompositionRequest(
                        cut(terminal), Optional.empty(), NOW)));

        assertEquals(0, intentCalls.get());
        assertEquals(0, narrationCalls.get());
        assertEquals(0, appendCalls.get());
    }

    @Test
    void rejectsReceiptWhoseIntentBelongsToAnotherPlanBeforeNarration() {
        var terminal = terminal();
        ReceiptId receiptId = new ReceiptId("receipt-1");
        var receipt = new ExecutionReceipt(
                receiptId, new io.paperagent.v2.contracts.ToolCallId("call-1"),
                ReceiptStatus.SUCCESS, NOW, NOW,
                Optional.of(0), Optional.empty(),
                OutputCapture.inline("task queued", false),
                OutputCapture.empty(), List.of(), Optional.empty(),
                List.of());
        AtomicInteger narrationCalls = new AtomicInteger();
        var composer = new DefaultFinalSynthesisComposer(
                store(new InMemoryFinalSynthesisRepository()),
                ignored -> Optional.of(receipt),
                (toolCallId, planId, stepId, kind) -> false,
                request -> {
                    narrationCalls.incrementAndGet();
                    return "unexpected";
                });

        assertThrows(FinalSynthesisCompositionException.class,
                () -> composer.compose(new FinalSynthesisCompositionRequest(
                        cut(terminal), Optional.empty(), NOW)));
        assertEquals(0, narrationCalls.get());
    }

    private static FinalSynthesisStore emptyStore(
            AtomicInteger appendCalls) {
        return new FinalSynthesisStore() {
            @Override
            public Optional<FinalSynthesis> find(PlanId planId) {
                return Optional.empty();
            }

            @Override
            public Optional<FinalSynthesisCompositionResult> append(
                    FinalSynthesis synthesis) {
                appendCalls.incrementAndGet();
                return Optional.empty();
            }
        };
    }

    private static FinalSynthesisStore store(
            InMemoryFinalSynthesisRepository repository) {
        return new FinalSynthesisStore() {
            @Override
            public Optional<FinalSynthesis> find(PlanId planId) {
                var result = repository.find(planId);
                return result.outcome() == PersistenceOutcome.FOUND
                        ? result.value()
                        : Optional.empty();
            }

            @Override
            public Optional<FinalSynthesisCompositionResult> append(
                    FinalSynthesis synthesis) {
                var result = repository.append(synthesis);
                if (result.outcome() != PersistenceOutcome.APPLIED
                        && result.outcome() != PersistenceOutcome.REPLAYED) {
                    return Optional.empty();
                }
                return Optional.of(new FinalSynthesisCompositionResult(
                        result.value().orElseThrow(),
                        result.outcome() == PersistenceOutcome.APPLIED
                                ? FinalSynthesisDisposition.APPLIED
                                : FinalSynthesisDisposition.REPLAYED));
            }
        };
    }

    private static FinalSynthesisReceiptSource receiptSource(
            io.paperagent.v2.persistence.ReceiptRepository repository) {
        return receiptId -> {
            var result = repository.find(receiptId);
            return result.outcome() == PersistenceOutcome.FOUND
                    ? result.value()
                    : Optional.empty();
        };
    }

    private static LiteratureIntentOwnershipSource intentSource(
            io.paperagent.v2.persistence.EffectIntentRepository repository) {
        return (toolCallId, planId, stepId, kind) -> {
            var result = repository.find(toolCallId);
            return result.outcome() == PersistenceOutcome.FOUND
                    && result.value().orElseThrow().intent().planId()
                    .equals(planId)
                    && result.value().orElseThrow().intent().stepId()
                    .equals(stepId)
                    && result.value().orElseThrow().intent().kind()
                    .equals(kind);
        };
    }

    private static PersistedStepRecoverySucceeded terminal() {
        TaskFrameId taskId = new TaskFrameId("task-frame");
        PlanId planId = new PlanId("plan");
        PlanRevisionId revisionId = new PlanRevisionId("revision");
        PlanStepId stepId = new PlanStepId("step");
        ReceiptId receiptId = new ReceiptId("receipt-1");
        TaskFrame task = new TaskFrame(
                taskId, "queue search", List.of("request"),
                List.of("confirmation"), List.of(),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.ACCESS_NETWORK),
                        NetworkPolicy.ALLOWLIST_ONLY,
                        List.of("literature"),
                        new ResourceLimits(
                                Duration.ofMinutes(1),
                                Duration.ofSeconds(30), 1024, 1024, 1),
                        Set.of()),
                NOW);
        PlanStep step = new PlanStep(
                stepId, "search", "queued", Set.of(),
                List.of("receipt"), new BoundedExecutionHints(
                1, Duration.ofSeconds(30)));
        PlanRevision revision = new PlanRevision(
                revisionId, taskId, 1, Optional.empty(), "initial",
                NOW, List.of(step),
                Map.of(stepId, new CompletionFact(
                        stepId, "outcome", NOW, List.of(receiptId))));
        Plan plan = new Plan(planId, taskId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                taskId, planId, revisionId, 1, 3,
                PlanExecutionState.SUCCEEDED,
                Map.of(stepId, StepExecutionState.SUCCEEDED),
                List.of(receiptId), NOW);
        return new PersistedStepRecoverySucceeded(
                task, plan, new VersionedCheckpoint(4, checkpoint),
                Optional.empty());
    }

    private static FinalSynthesisTerminalCut cut(
            PersistedStepRecoverySucceeded terminal) {
        return new FinalSynthesisTerminalCut(
                terminal.taskFrame(), terminal.plan(),
                terminal.checkpoint().checkpoint());
    }
}
