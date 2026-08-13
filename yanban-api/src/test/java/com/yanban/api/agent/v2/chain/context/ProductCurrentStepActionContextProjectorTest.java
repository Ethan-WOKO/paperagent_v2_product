package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductCurrentStepActionContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void onlyInitialPlannerUsesFormalEmptyAtNonzeroTaskHead() {
        var fixture = fixture();
        authority(fixture, List.of(event("instruction.event", 17)));

        var projection = fixture.projector.read(request(initialBuilding()));

        assertEquals(ChainContextModuleStatus.EMPTY, projection.presenceKind());
        assertEquals("actionSequence=0", projection.emptyWatermark());
        assertEquals(17, number(object(projection.readBoundaryComponents(),
                "planStepActivationActionFence"), "taskAuthorityHead"));
    }

    @Test
    void executorOrdersAttemptsByFormalSequenceAndExpandsExactReceiptBody() {
        var fixture = fixture();
        var first = action("action.1", "action.event.1", 1, "b".repeat(64));
        var second = action("action.2", "action.event.2", 2, "c".repeat(64));
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(second, first));
        effect(fixture, first, receipt(first, ReceiptStatus.FAILURE,
                "FIRST_FAILURE", "", "exact first error", false));
        effect(fixture, second, receipt(second, ReceiptStatus.FAILURE,
                "SECOND_FAILURE", "", "exact second error", false));
        authority(fixture, List.of(event(first.eventId(), 31),
                event(second.eventId(), 47)));

        var projection = fixture.projector.read(request(
                building(ChainRole.EXECUTOR)));

        var attempts = array(projection.projectionFields(),
                "action.currentStepAttemptTable");
        assertEquals("action.1", text(object(attempts, 0), "actionRef"));
        assertEquals("action.2", text(object(attempts, 1), "actionRef"));
        var expanded = array(projection.projectionFields(),
                "action.latestOrUnresolvedReceiptAndErrorExpansion");
        assertEquals(2, expanded.values().size());
        var receipt = object(object(expanded, 1), "receipt");
        assertEquals("exact second error", text(
                object(receipt, "stderr"), "body"));
        assertEquals(47, number(object(projection.sourceVersionComponents(),
                "actionCut"), "authorityEventSequence"));
        assertEquals(List.of("actionCut", "effectIntent", "progress",
                        "receiptAndOutcomeIds"),
                projection.sourceVersionComponents().keySet().stream()
                        .sorted().toList());
    }

    @Test
    void executorProjectsExactCandidateFailureCodeAndReferenceWithoutText() {
        var fixture = fixture();
        var action = action("action.1", "action.event.1", 1, HASH);
        var failure = new ChainPersistenceRecords
                .CandidateMaterializationFailureRecord(
                "candidate-failure.1", "task.1", "candidate-failure.event.1",
                action.actionId(), action.workspaceId(),
                action.baseCandidateKey(), "WORKSPACE_CHANGE_BODY",
                "content.1", action.versionFenceSha256(),
                "CANDIDATE_NO_ACTUAL_CHANGE", NOW);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        when(fixture.candidateFailures
                .findCandidateMaterializationFailure(
                        "task.1", action.actionId()))
                .thenReturn(Optional.of(failure));
        authority(fixture, List.of(event(action.eventId(), 13),
                event(failure.eventId(), 17)));

        var projection = fixture.projector.read(request(
                building(ChainRole.EXECUTOR)));

        var expanded = array(projection.projectionFields(),
                "action.latestOrUnresolvedReceiptAndErrorExpansion");
        var exact = object(object(expanded, 0),
                "candidateMaterializationFailure");
        assertEquals("candidate-failure.1", text(exact, "failureRef"));
        assertEquals("CANDIDATE_NO_ACTUAL_CHANGE",
                text(exact, "errorCode"));
        assertFalse(ProductChainContractProjectionCodec.canonicalJson(
                projection.projectionFields().get(
                        "action.latestOrUnresolvedReceiptAndErrorExpansion"))
                .contains("exception"));
    }

    @Test
    void laterCandidateCommitClosesEarlierCandidateFailure() {
        var fixture = fixture();
        var first = action("action.1", "action.event.1", 1, HASH);
        var second = action("action.2", "action.event.2", 2,
                "b".repeat(64));
        var failure = new ChainPersistenceRecords
                .CandidateMaterializationFailureRecord(
                "candidate-failure.1", "task.1", "candidate-failure.event.1",
                first.actionId(), first.workspaceId(),
                first.baseCandidateKey(), "WORKSPACE_CHANGE_BODY",
                "content.1", first.versionFenceSha256(),
                "CANDIDATE_NO_ACTUAL_CHANGE", NOW);
        var candidate = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "workspace-candidate.2", "task.1", "candidate.event.2",
                second.actionId(), second.workspaceId(), "version.1", 91L,
                "c".repeat(64), "d".repeat(64),
                second.versionFenceSha256(), NOW);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(first, second));
        when(fixture.workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of(candidate));
        when(fixture.candidateFailures
                .findCandidateMaterializationFailure(
                        "task.1", first.actionId()))
                .thenReturn(Optional.of(failure));
        authority(fixture, List.of(event(first.eventId(), 11),
                event(failure.eventId(), 13), event(second.eventId(), 17),
                event(candidate.eventId(), 19)));

        var projection = fixture.projector.read(request(
                building(ChainRole.EXECUTOR)));

        var expanded = array(projection.projectionFields(),
                "action.latestOrUnresolvedReceiptAndErrorExpansion");
        assertEquals(1, expanded.values().size());
        assertEquals(second.actionId(), text(
                object(object(expanded, 0), "attempt"), "actionRef"));
        var attempts = array(projection.projectionFields(),
                "action.currentStepAttemptTable");
        assertEquals("workspace-candidate.2", text(
                object(object(attempts, 1), "candidate"),
                "candidateRef"));
    }

    @Test
    void plannerReadsUnresolvedFailuresAndTerminalSummariesWithoutBodies() {
        var fixture = fixture();
        var first = action("action.1", "action.event.1", 1, "b".repeat(64));
        var second = action("action.2", "action.event.2", 2, "c".repeat(64));
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(first, second));
        effect(fixture, first, receipt(first, ReceiptStatus.SUCCESS,
                null, "completed", "", false));
        effect(fixture, second, receipt(second, ReceiptStatus.FAILURE,
                "FAILED_AFTER_SUCCESS", "", "private body", false));
        authority(fixture, List.of(event(first.eventId(), 11),
                event(second.eventId(), 19)));

        var projection = fixture.projector.read(request(
                building(ChainRole.PLANNER)));

        var unresolved = array(projection.projectionFields(),
                "action.unresolvedFailures");
        var terminal = array(projection.projectionFields(),
                "action.terminalSummary");
        assertEquals(1, unresolved.values().size());
        assertEquals(2, terminal.values().size());
        assertFalse(ProductChainContractProjectionCodec.canonicalJson(
                unresolved).contains("private body"));
    }

    @Test
    void plannerWithoutCurrentStepStillReadsFormalTaskFailureNotFalseEmpty() {
        var fixture = fixture();
        var action = action("action.1", "action.event.1", 1, HASH);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        effect(fixture, action, receipt(action, ReceiptStatus.FAILURE,
                "REPLAN_CAUSE", "", "formal failure", false));
        authority(fixture, List.of(event(action.eventId(), 23)));

        var projection = fixture.projector.read(request(initialBuilding()));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        var failures = array(projection.projectionFields(),
                "action.unresolvedFailures");
        assertEquals(1, failures.values().size());
        assertEquals("receipt.1", text(object(failures, 0), "receiptRef"));
    }

    @Test
    void reflectorGetsMechanicalDiagnosisAndNoProgressWithoutPolicyDecision() {
        var fixture = fixture();
        var first = action("action.1", "action.event.1", 1, HASH);
        var second = action("action.2", "action.event.2", 2, HASH);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(first, second));
        effect(fixture, first, receipt(first, ReceiptStatus.FAILURE,
                "SAME_FAILURE", "", "failure", false));
        effect(fixture, second, receipt(second, ReceiptStatus.FAILURE,
                "SAME_FAILURE", "", "failure", false));
        authority(fixture, List.of(event(first.eventId(), 23),
                event(second.eventId(), 29)));

        var projection = fixture.projector.read(request(
                building(ChainRole.REFLECTOR)));

        var diagnosis = object(projection.projectionFields(),
                "action.diagnosis");
        var noProgress = object(projection.projectionFields(),
                "action.noProgressState");
        assertEquals("MECHANICAL_RECEIPT_DIAGNOSIS", text(
                diagnosis, "kind"));
        assertEquals(2, number(noProgress,
                "latestSignatureOccurrences"));
        assertEquals(2, number(noProgress,
                "consecutiveIdenticalTerminalOutcomes"));
    }

    @Test
    void answerReadsOnlyFormalFailureSummaryAndNotRawReceiptBody() {
        var fixture = fixture();
        var action = action("action.1", "action.event.1", 1, HASH);
        var outcome = failedOutcome("outcome.event");
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        when(fixture.finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.of(outcome));
        effect(fixture, action, receipt(action, ReceiptStatus.FAILURE,
                "TOOL_FAILED", "", "raw private error", false));
        authority(fixture, List.of(event(action.eventId(), 13),
                event(outcome.eventId(), 31)));

        var projection = fixture.projector.read(request(
                building(ChainRole.ANSWER)));

        var summary = object(projection.projectionFields(),
                "action.officialFailureSummaryOnly");
        var taskOutcome = object(summary, "taskOutcome");
        assertEquals("EXECUTION", text(taskOutcome, "failureCategory"));
        assertFalse(ProductChainContractProjectionCodec.canonicalJson(
                projection.projectionFields().get(
                        "action.officialFailureSummaryOnly"))
                .contains("raw private error"));
    }

    @Test
    void actionWithoutFormalAuthorityEventIsTypedBlocked() {
        var fixture = fixture();
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action("action.1", "missing.event", 1, HASH)));
        authority(fixture, List.of(event("instruction.event", 7)));

        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.EXECUTOR))));
    }

    @Test
    void retainedEffectAuthorityFailureIsTypedBlocked() {
        var fixture = fixture();
        var action = action("action.1", "action.event.1", 1, HASH);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        when(fixture.intents.find(new ToolCallId(action.actionId())))
                .thenReturn(PersistenceResult.rejected(
                        PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                        "effectIntent"));
        authority(fixture, List.of(event(action.eventId(), 11)));

        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.EXECUTOR))));
    }

    @Test
    void crossTaskOrFrozenIdentityMismatchIsTypedBlocked() {
        var fixture = fixture();
        var wrongTask = action("action.1", "action.event.1", 1, HASH,
                "task.other", "frame.1");
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(wrongTask));
        authority(fixture, List.of(event(wrongTask.eventId(), 11)));
        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.EXECUTOR))));

        var wrongFrame = action("action.2", "action.event.2", 1, HASH,
                "task.1", "frame.other");
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(wrongFrame));
        authority(fixture, List.of(event(wrongFrame.eventId(), 13)));
        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.EXECUTOR))));
    }

    @Test
    void authorityTruncatedErrorBodyRemainsVisibleWithExplicitLimitFlag() {
        var fixture = fixture();
        var action = action("action.1", "action.event.1", 1, HASH);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        effect(fixture, action, receipt(action, ReceiptStatus.FAILURE,
                "TRUNCATED", "", "partial", true));
        authority(fixture, List.of(event(action.eventId(), 17)));

        var projection = fixture.projector.read(request(
                building(ChainRole.EXECUTOR)));
        var expanded = array(projection.projectionFields(),
                "action.latestOrUnresolvedReceiptAndErrorExpansion");
        var receipt = object(object(expanded, 0), "receipt");
        var stderr = object(receipt, "stderr");
        assertEquals("partial", text(stderr, "body"));
        assertEquals(true, bool(stderr, "truncated"));
    }

    private static Fixture fixture() {
        var foundations = mock(ChainFoundationRepository.class);
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var intents = mock(EffectIntentRepository.class);
        var outcomes = mock(EffectOutcomeRepository.class);
        var finalization = mock(ChainFinalizationRepository.class);
        var candidateFailures = mock(
                ProductChainCandidateMaterializationFailureRepositoryAdapter.class);
        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                mock(ChainPersistenceRecords.TaskRecord.class)));
        when(intents.find(any())).thenReturn(notFound());
        when(outcomes.readProgress(any())).thenReturn(notFound());
        when(outcomes.findResult(any())).thenReturn(notFound());
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.empty());
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of());
        when(candidateFailures.findCandidateMaterializationFailure(any(), any()))
                .thenReturn(Optional.empty());
        return new Fixture(foundations, workflow, intents, outcomes,
                finalization, candidateFailures,
                new ProductCurrentStepActionContextProjector(
                        foundations, workflow, intents, outcomes,
                        finalization, candidateFailures));
    }

    private static void effect(
            Fixture fixture,
            ChainPersistenceRecords.ActionBindingRecord action,
            ExecutionReceipt receipt) {
        var call = new ToolCallId(action.actionId());
        var intent = new PersistedEffectIntent(new EffectIntent(
                call, new PlanId("plan.1"), new PlanStepId("step.1"),
                "tool.execute", new ObjectValue(Map.of(
                "input", new TextValue("formal")))),
                "owner.1", 3, new EventId("activation.1"));
        when(fixture.intents.find(call)).thenReturn(
                PersistenceResult.found(intent));
        when(fixture.outcomes.findResult(call)).thenReturn(
                PersistenceResult.found(new PersistedEffectResult(
                        receipt, "owner.1", 3)));
    }

    private static ExecutionReceipt receipt(
            ChainPersistenceRecords.ActionBindingRecord action,
            ReceiptStatus status, String resultCode, String stdout,
            String stderr, boolean truncatedError) {
        return new ExecutionReceipt(
                new ReceiptId("receipt." + action.attemptNo()),
                new ToolCallId(action.actionId()), status, NOW, NOW.plusSeconds(1),
                status == ReceiptStatus.FAILURE
                        ? Optional.of(1) : Optional.of(0),
                Optional.ofNullable(resultCode),
                OutputCapture.inline(stdout, false),
                OutputCapture.inline(stderr, truncatedError),
                List.of(), Optional.empty(), List.of());
    }

    private static ChainPersistenceRecords.ActionBindingRecord action(
            String id, String eventId, int attempt, String signature) {
        return action(id, eventId, attempt, signature, "task.1", "frame.1");
    }

    private static ChainPersistenceRecords.ActionBindingRecord action(
            String id, String eventId, int attempt, String signature,
            String taskId, String frameId) {
        return new ChainPersistenceRecords.ActionBindingRecord(
                id, taskId, eventId, "proposal." + attempt, attempt,
                signature, "idempotency." + attempt, "instruction.1",
                frameId, "plan.1", "revision.1", "step.1",
                "activation.1", "workspace.1", "NONE",
                null, null, null, null, HASH, NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord failedOutcome(
            String eventId) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome.1", "task.1", eventId, "command.1",
                ChainTaskOutcomeStatus.FAILED, "instruction.1", "frame.1",
                "plan.1", "revision.1", json("{}"), json("[]"),
                null, "NONE", "NONE", null, null, null, null,
                json("[\"unfinished\"]"), json("[]"), json("[]"),
                "EXECUTION", "NO_PROGRESS", "decision.1", NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role) {
        ChainWorkState state = switch (role) {
            case PLANNER -> ChainWorkState.PLANNING;
            case EXECUTOR -> ChainWorkState.EXECUTING;
            case REFLECTOR -> ChainWorkState.AWAITING_REVIEW;
            case ANSWER -> ChainWorkState.DELIVERING;
        };
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, role, state, "TEST",
                "instruction.1", "frame.1", "plan.1", "revision.1", 1L,
                "step.1", "activation.1", null, null, "workspace.1",
                null, null, null, null, null, "projectors.v1", "pages.v1",
                "policy.v1", ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord initialBuilding() {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.PLANNER,
                ChainWorkState.PLANNING, "TEST", "instruction.1",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "projectors.v1", "pages.v1",
                "policy.v1", ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static ChainContextProjectionRequest request(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static void authority(
            Fixture fixture,
            List<ChainPersistenceRecords.AuthorityEventRecord> events) {
        long cut = events.stream().mapToLong(
                ChainPersistenceRecords.AuthorityEventRecord::eventSequence)
                .max().orElse(0);
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(cut);
        when(fixture.foundations.findAuthorityEvents("task.1", cut))
                .thenReturn(events);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord event(
            String id, long sequence) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                id, "task.1", sequence, "TEST", null, HASH, NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String body) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, body);
    }

    private static <T> PersistenceResult<T> notFound() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.NOT_FOUND, "test");
    }

    private static void assertTypedBlocked(org.junit.jupiter.api.function
                                                    .Executable executable) {
        var failure = assertThrows(ChainContextException.class, executable);
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static ChainContextValue.ArrayValue array(
            Map<String, ChainContextValue> values, String key) {
        return (ChainContextValue.ArrayValue) values.get(key);
    }

    private static Map<String, ChainContextValue> object(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.ObjectValue) values.get(key)).values();
    }

    private static Map<String, ChainContextValue> object(
            ChainContextValue.ArrayValue values, int index) {
        return ((ChainContextValue.ObjectValue) values.values().get(index))
                .values();
    }

    private static String text(Map<String, ChainContextValue> values,
                               String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private static long number(Map<String, ChainContextValue> values,
                               String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private static boolean bool(Map<String, ChainContextValue> values,
                                String key) {
        return ((ChainContextValue.BooleanValue) values.get(key)).value();
    }

    private record Fixture(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization,
            ProductChainCandidateMaterializationFailureRepositoryAdapter
                    candidateFailures,
            ProductCurrentStepActionContextProjector projector) {
    }
}
