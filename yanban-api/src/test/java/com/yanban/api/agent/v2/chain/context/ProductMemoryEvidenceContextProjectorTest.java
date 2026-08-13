package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModuleStatus;
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
import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.DiffId;
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

class ProductMemoryEvidenceContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void initialPlannerHasProvableEmptyCatalogAtNonzeroTaskHead() {
        var fixture = fixture();
        authority(fixture, List.of(event("instruction.event", 13)));

        var projection = fixture.projector.read(request(initialBuilding()));

        assertEquals(ChainContextModuleStatus.EMPTY, projection.presenceKind());
        assertEquals("emptyCatalogDigestAndObservationCuts",
                projection.emptyWatermark());
        assertEquals(0, number(projection.readBoundaryComponents(),
                "taskCatalogCut"));
        assertEquals(13, number(projection.projectionParameters(),
                "taskAuthorityHead"));
    }

    @Test
    void plannerCatalogsFormalReceiptRefsWithoutOutputBodies() {
        var fixture = fixture();
        var action = action("action.1", "action.event", "step.1",
                "activation.1", 1);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        effect(fixture, action, receipt(action, "SECRET_OUTPUT"));
        authority(fixture, List.of(event("action.event", 29)));

        var projection = fixture.projector.read(request(
                building(ChainRole.PLANNER, null, null)));

        var catalog = array(projection.projectionFields(),
                "evidence.frozenCompleteCatalog");
        var entry = object(catalog, 0);
        var refs = object(entry, "mechanicalRefs");
        assertEquals("ACTION_RECEIPT", text(entry, "kind"));
        assertEquals("receipt.action.1", text(refs, "receiptId"));
        assertEquals(1, array(refs, "artifactRefs").values().size());
        assertFalse(refs.containsKey("stdout"));
        assertFalse(refs.containsKey("stderr"));
    }

    @Test
    void executorGetsFullCatalogButCurrentExpansionUsesExactActivation() {
        var fixture = fixture();
        var prior = action("action.prior", "prior.event", "step.prior",
                "activation.prior", 1);
        var current = action("action.current", "current.event", "step.1",
                "activation.1", 1);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(prior, current));
        effect(fixture, prior, receipt(prior, "prior"));
        effect(fixture, current, receipt(current, "current"));
        authority(fixture, List.of(
                event("prior.event", 7), event("current.event", 41)));

        var projection = fixture.projector.read(request(
                building(ChainRole.EXECUTOR, "step.1", "activation.1")));

        assertEquals(2, array(projection.projectionFields(),
                "evidence.frozenCompleteCatalog").values().size());
        var currentOnly = array(projection.projectionFields(),
                "evidence.currentStepMechanicalExpansion");
        assertEquals(1, currentOnly.values().size());
        assertEquals("receipt.action.current", text(object(currentOnly, 0),
                "authorityRef"));
    }

    @Test
    void reflectorExpandsOnlyCurrentCandidateFormalEvidenceRefs() {
        var fixture = fixture();
        var old = candidate("candidate.old", "old.event", "step.old",
                "activation.old", List.of("old-ref"));
        var current = candidate("candidate.current", "current.event",
                "step.1", "activation.1",
                List.of("literature-result:42", "knowledge-result:7"));
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(old, current));
        authority(fixture, List.of(
                event("old.event", 17), event("current.event", 53)));

        var projection = fixture.projector.read(request(
                building(ChainRole.REFLECTOR, "step.1", "activation.1")));

        var expanded = array(projection.projectionFields(),
                "evidence.candidateReferencedMechanicalExpansion");
        assertEquals(1, expanded.values().size());
        var details = object(object(expanded, 0), "mechanicalRefs");
        assertEquals(2, array(details, "evidenceRefs").values().size());
    }

    @Test
    void answerUsesOnlyOutcomeAcceptedCandidateEvidence() {
        var fixture = fixture();
        var acceptedCandidate = candidate("candidate.accepted",
                "candidate.accepted.event", "step.1", "activation.1",
                List.of("evidence.accepted"));
        var other = candidate("candidate.other", "candidate.other.event",
                "step.2", "activation.2", List.of("evidence.other"));
        var accepted = accepted("accepted.1", "accepted.event",
                acceptedCandidate);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(acceptedCandidate, other));
        when(fixture.workflow.findAcceptedResults("task.1"))
                .thenReturn(List.of(accepted));
        when(fixture.finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.of(outcome("outcome.event",
                        List.of("accepted.1"))));
        authority(fixture, List.of(
                event("candidate.accepted.event", 11),
                event("candidate.other.event", 19),
                event("accepted.event", 31), event("outcome.event", 47)));

        var projection = fixture.projector.read(request(
                answerBuilding()));

        var delivered = array(projection.projectionFields(),
                "evidence.directFrozenOrAcceptedDeliveryEvidence");
        assertEquals(1, delivered.values().size());
        assertEquals("candidate.accepted", text(
                object(delivered, 0), "authorityRef"));
    }

    @Test
    void malformedCandidateEvidenceDigestIsTypedBlocked() {
        var fixture = fixture();
        var malformed = new ChainPersistenceRecords.CandidateStepResultRecord(
                "candidate.1", "task.1", "candidate.event", "proposal.1",
                "content.1", "instruction.1", "frame.1", "plan.1",
                "revision.1", 1, "step.1", "activation.1", null, null,
                null, canonical(List.of()), null, null, null,
                new ChainPersistenceRecords.CanonicalJson(
                        1, HASH, "[\"formal-ref\"]"), HASH, NOW);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(malformed));
        authority(fixture, List.of(event("candidate.event", 5)));

        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.REFLECTOR, "step.1", "activation.1"))));
    }

    @Test
    void evidenceRecordWithoutFormalEventIsTypedBlocked() {
        var fixture = fixture();
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(candidate("candidate.1", "missing.event",
                        "step.1", "activation.1", List.of("ref.1"))));
        authority(fixture, List.of(event("instruction.event", 7)));

        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.REFLECTOR, "step.1", "activation.1"))));
    }

    @Test
    void retainedEffectQueryFailureCannotBecomeEmpty() {
        var fixture = fixture();
        var action = action("action.1", "action.event", "step.1",
                "activation.1", 1);
        when(fixture.workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(PersistenceResult.rejected(
                        PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                        "unavailable"));
        authority(fixture, List.of(event("action.event", 23)));

        assertTypedBlocked(() -> fixture.projector.read(request(
                building(ChainRole.EXECUTOR, "step.1", "activation.1"))));
    }

    private static Fixture fixture() {
        var foundations = mock(ChainFoundationRepository.class);
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var intents = mock(EffectIntentRepository.class);
        var outcomes = mock(EffectOutcomeRepository.class);
        var finalization = mock(ChainFinalizationRepository.class);
        when(foundations.findTask("task.1"))
                .thenReturn(Optional.of(mock(
                        ChainPersistenceRecords.TaskRecord.class)));
        when(intents.find(any())).thenReturn(notFound());
        when(outcomes.findResult(any())).thenReturn(notFound());
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.empty());
        return new Fixture(foundations, workflow, intents, outcomes,
                finalization, new ProductMemoryEvidenceContextProjector(
                        foundations, workflow, intents, outcomes,
                        finalization, new ObjectMapper()));
    }

    private static void effect(
            Fixture fixture,
            ChainPersistenceRecords.ActionBindingRecord action,
            ExecutionReceipt receipt) {
        ToolCallId call = new ToolCallId(action.actionId());
        var intent = new PersistedEffectIntent(new EffectIntent(
                call, new PlanId("plan.1"), new PlanStepId(action.stepId()),
                "tool.execute", new ObjectValue(Map.of(
                        "input", new TextValue("formal")))),
                "owner.1", 3, new EventId(action.activationEventId()));
        when(fixture.intents.find(call)).thenReturn(
                PersistenceResult.found(intent));
        when(fixture.outcomes.findResult(call)).thenReturn(
                PersistenceResult.found(new PersistedEffectResult(
                        receipt, "owner.1", 3)));
    }

    private static ExecutionReceipt receipt(
            ChainPersistenceRecords.ActionBindingRecord action,
            String hiddenOutput) {
        return new ExecutionReceipt(
                new ReceiptId("receipt." + action.actionId()),
                new ToolCallId(action.actionId()), ReceiptStatus.SUCCESS,
                NOW, NOW.plusSeconds(1), Optional.of(0), Optional.empty(),
                OutputCapture.inline(hiddenOutput, false),
                OutputCapture.inline("", false),
                List.of(new ArtifactRef("artifact.1")),
                Optional.of(new DiffId("diff.1")),
                List.of(new EventId("result.event.1")));
    }

    private static ChainPersistenceRecords.ActionBindingRecord action(
            String id, String eventId, String step, String activation,
            int attempt) {
        return new ChainPersistenceRecords.ActionBindingRecord(
                id, "task.1", eventId, "proposal." + id, attempt, HASH,
                "idempotency." + id, "instruction.1", "frame.1", "plan.1",
                "revision.1", step, activation, "workspace.1", "NONE",
                null, null, null, null, HASH, NOW);
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidate(
            String id, String eventId, String step, String activation,
            List<String> evidence) {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                id, "task.1", eventId, "proposal." + id, "content." + id,
                "instruction.1", "frame.1", "plan.1", "revision.1", 1,
                step, activation, null, null, null, canonical(List.of()),
                null, null, null, canonical(evidence), HASH, NOW);
    }

    private static ChainPersistenceRecords.AcceptedResultRecord accepted(
            String id, String eventId,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        return new ChainPersistenceRecords.AcceptedResultRecord(
                id, "task.1", eventId, candidate.candidateResultId(),
                "review.1", "transition.1", candidate.contentId(), HASH, NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord outcome(
            String eventId, List<String> acceptedIds) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome.1", "task.1", eventId, "command.1",
                ChainTaskOutcomeStatus.COMPLETED, "instruction.1", "frame.1",
                "plan.1", "revision.1", canonical(Map.of()),
                canonical(acceptedIds), null, "NONE", "NONE",
                null, null, null, null, canonical(List.of()),
                canonical(List.of()), canonical(List.of()),
                null, null, "decision.1", NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord initialBuilding() {
        return revision(ChainRole.PLANNER, null, null, false);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord answerBuilding() {
        return revision(ChainRole.ANSWER, null, null, true);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String step, String activation) {
        return revision(role, step, activation, true);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord revision(
            ChainRole role, String step, String activation,
            boolean persistent) {
        ChainWorkState state = switch (role) {
            case PLANNER -> ChainWorkState.PLANNING;
            case EXECUTOR -> ChainWorkState.EXECUTING;
            case REFLECTOR -> ChainWorkState.AWAITING_REVIEW;
            case ANSWER -> ChainWorkState.DELIVERING;
        };
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, role, state, "TEST",
                "instruction.1", persistent ? "frame.1" : null,
                persistent ? "plan.1" : null,
                persistent ? "revision.1" : null,
                persistent ? 1L : null, step, activation,
                null, null, step == null ? null : "workspace.1",
                null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(Object value) {
        try {
            String body = new ObjectMapper().writeValueAsString(value);
            return new ChainPersistenceRecords.CanonicalJson(
                    1, ProductChainContractProjectionCodec.sha256(body), body);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
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

    private static ChainContextProjectionRequest request(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static <T> PersistenceResult<T> notFound() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.NOT_FOUND, "test");
    }

    private static void assertTypedBlocked(
            org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(ChainContextException.class, executable);
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static ChainContextValue.ArrayValue array(
            Map<String, ChainContextValue> values, String key) {
        return (ChainContextValue.ArrayValue) values.get(key);
    }

    private static Map<String, ChainContextValue> object(
            ChainContextValue.ArrayValue values, int index) {
        return ((ChainContextValue.ObjectValue) values.values().get(index))
                .values();
    }

    private static Map<String, ChainContextValue> object(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.ObjectValue) values.get(key)).values();
    }

    private static String text(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private static long number(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private record Fixture(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization,
            ProductMemoryEvidenceContextProjector projector) {
    }
}
