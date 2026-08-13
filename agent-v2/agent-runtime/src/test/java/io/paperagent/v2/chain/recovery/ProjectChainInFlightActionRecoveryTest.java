package io.paperagent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkspaceCandidateWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectChainInFlightActionRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String FENCE = "b".repeat(64);

    @Test
    void knownSuccessAndFailureAreNeverDispatchedAgain() {
        for (ChainEffectRuntime.EffectStatus status : List.of(
                ChainEffectRuntime.EffectStatus.SUCCEEDED,
                ChainEffectRuntime.EffectStatus.FAILED)) {
            Fixture fixture = new Fixture(status, status);
            ChainRecoveryRuntime.RecoveryResult result = fixture.recovery.recover("task-1", NOW);

            assertFalse(result.unresolved(), status.name());
            assertEquals(List.of("reconcile:action-1:key-1"), fixture.effects.calls,
                    status.name());
            assertFalse(fixture.effects.prepared, status.name());
            assertFalse(fixture.effects.dispatched, status.name());
            ChainRecoveryRuntime.ActionRecoveryFact recovered = result.actions().get(0);
            if (status == ChainEffectRuntime.EffectStatus.SUCCEEDED) {
                assertEquals("receipt-1", recovered.receiptRef());
                assertEquals(null, recovered.errorRef());
            } else {
                assertEquals("error-1", recovered.errorRef());
                assertEquals(null, recovered.receiptRef());
            }
        }
    }

    @Test
    void unknownSideEffectIsNotRerun() {
        Fixture fixture = new Fixture(
                ChainEffectRuntime.EffectStatus.UNKNOWN,
                ChainEffectRuntime.EffectStatus.UNKNOWN);

        ChainRecoveryRuntime.RecoveryResult result = fixture.recovery.recover("task-1", NOW);

        assertTrue(result.unresolved());
        assertEquals(List.of("reconcile:action-1:key-1"), fixture.effects.calls);
        assertEquals("effect-intent-1",
                result.actions().get(0).uncertaintyRef());
        assertEquals(null, result.actions().get(0).errorRef());
        assertFalse(fixture.effects.prepared);
        assertFalse(fixture.effects.dispatched,
                "unknown non-idempotent side effect must not be rerun");
    }

    @Test
    void neverDispatchedBindingContinuesWithOriginalActionAndIdempotencyKey() {
        Fixture fixture = new Fixture(
                ChainEffectRuntime.EffectStatus.NOT_DISPATCHED,
                ChainEffectRuntime.EffectStatus.IN_FLIGHT);

        ChainRecoveryRuntime.RecoveryResult result = fixture.recovery.recover("task-1", NOW);

        assertTrue(result.unresolved());
        assertEquals(List.of(
                "reconcile:action-1:key-1",
                "prepare:action-1:key-1",
                "dispatch:action-1:key-1"), fixture.effects.calls);
        assertTrue(fixture.effects.prepared);
        assertTrue(fixture.effects.dispatched);
    }

    @Test
    void workspaceChangeRecoveryUsesCandidateAuthorityAndConvergesExistingBinding() {
        for (boolean alreadyBound : List.of(false, true)) {
            Store store = new Store();
            ChainPersistenceRecords.ActionBindingRecord action = action(
                    "action-workspace", "proposal-workspace", "key-workspace");
            store.addAction(action, ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
            if (alreadyBound) store.candidates.add(candidate(action));
            EffectStub effects = new EffectStub(
                    ChainEffectRuntime.EffectStatus.UNKNOWN,
                    ChainEffectRuntime.EffectStatus.UNKNOWN);
            CandidateStub candidates = new CandidateStub();
            if (alreadyBound) {
                candidates.reconciledCandidate = materialized(action);
            }
            ChainEffectRuntime runtime = new ChainEffectRuntime(
                    store, store, effects,
                    (taskId, actionId) -> new ChainEffectRuntime.WorkspaceChangeBinding(
                            taskId, "proposal-workspace", actionId, "change-body-1"),
                    candidates, store,
                    ignored -> ChainEffectRuntime.GateStatus.CURRENT);

            ChainRecoveryRuntime.RecoveryResult result =
                    new ChainInFlightActionRecovery(store, runtime)
                            .recover("task-1", NOW);

            assertFalse(result.unresolved(), "existing=" + alreadyBound);
            assertEquals(ChainEffectRuntime.OutcomeKind.CANDIDATE_COMMITTED,
                    result.actions().get(0).outcome());
            assertEquals("action-workspace", result.actions().get(0).actionId());
            assertTrue(effects.calls.isEmpty(),
                    "WORKSPACE_CHANGE must never enter EffectAuthority");
            assertTrue(candidates.reconciled,
                    "Workspace authority must confirm the Candidate binding");
            assertEquals(!alreadyBound, candidates.materialized,
                    "only an absent WorkspaceCandidate is materialized");
            assertEquals(1, store.candidates.size());
            assertEquals("action-workspace", store.candidates.get(0).actionId());
        }
    }

    private static final class Fixture {
        private final Store store = new Store();
        private final EffectStub effects;
        private final ChainInFlightActionRecovery recovery;

        private Fixture(
                ChainEffectRuntime.EffectStatus reconciled,
                ChainEffectRuntime.EffectStatus dispatched) {
            store.addAction(action(), ChainProposalKind.EXECUTOR_TOOL_ACTION);
            effects = new EffectStub(reconciled, dispatched);
            ChainEffectRuntime runtime = new ChainEffectRuntime(
                    store, store, effects,
                    (taskId, proposalId) -> {
                        throw new IllegalStateException("workspace source not expected");
                    },
                    new ChainEffectRuntime.WorkspaceCandidateAuthority() {
                        @Override
                        public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
                                ChainEffectRuntime.CandidateMutation mutation) {
                            return Optional.empty();
                        }

                        @Override
                        public ChainEffectRuntime.MaterializedCandidate materialize(
                                ChainEffectRuntime.CandidateMutation mutation,
                                ChainEffectRuntime.CandidateBindingPort binding) {
                            throw new IllegalStateException("Candidate not expected");
                        }
                    },
                    store,
                    ignored -> ChainEffectRuntime.GateStatus.CURRENT);
            recovery = new ChainInFlightActionRecovery(store, runtime);
        }
    }

    private static ChainPersistenceRecords.ActionBindingRecord action() {
        return action("action-1", "proposal-1", "key-1");
    }

    private static ChainPersistenceRecords.ActionBindingRecord action(
            String actionId, String proposalId, String idempotencyKey) {
        return new ChainPersistenceRecords.ActionBindingRecord(
                actionId, "task-1", "event-" + actionId, proposalId, 1,
                HASH, idempotencyKey, "instruction-1", "task-frame-1", "plan-1",
                "revision-1", "step-1", "activation-1", "workspace-1",
                ChainIdentity.NONE, null, null, null, null, FENCE, NOW);
    }

    private static ChainPersistenceRecords.WorkspaceCandidateRecord candidate(
            ChainPersistenceRecords.ActionBindingRecord action) {
        String candidateId = "workspace-candidate." + sha256(
                action.actionId() + "\0" + action.workspaceId() + "\0" + 1L
                        + "\0" + "c".repeat(64) + "\0" + "d".repeat(64));
        return new ChainPersistenceRecords.WorkspaceCandidateRecord(
                candidateId, action.taskId(),
                "workspace-candidate.binding." + sha256(candidateId),
                action.actionId(), action.workspaceId(), "project-version-1", 1L,
                "c".repeat(64), "d".repeat(64), action.versionFenceSha256(), NOW);
    }

    private static ChainEffectRuntime.MaterializedCandidate materialized(
            ChainPersistenceRecords.ActionBindingRecord action) {
        return new ChainEffectRuntime.MaterializedCandidate(
                ChainEffectRuntime.CandidateDisposition.COMMITTED,
                action.actionId(), action.workspaceId(), action.baseCandidateKey(),
                "project-version-1", 1L, "c".repeat(64), "d".repeat(64),
                action.versionFenceSha256());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class CandidateStub
            implements ChainEffectRuntime.WorkspaceCandidateAuthority {
        private boolean reconciled;
        private boolean materialized;
        private ChainEffectRuntime.MaterializedCandidate reconciledCandidate;

        @Override
        public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
                ChainEffectRuntime.CandidateMutation mutation) {
            reconciled = true;
            return Optional.ofNullable(reconciledCandidate);
        }

        @Override
        public ChainEffectRuntime.MaterializedCandidate materialize(
                ChainEffectRuntime.CandidateMutation mutation,
                ChainEffectRuntime.CandidateBindingPort binding) {
            materialized = true;
            ChainEffectRuntime.FrozenMutation action = mutation.mutation();
            ChainEffectRuntime.MaterializedCandidate result =
                    new ChainEffectRuntime.MaterializedCandidate(
                    ChainEffectRuntime.CandidateDisposition.COMMITTED,
                    action.actionId(), action.workspaceId(),
                    action.baseCandidateKey(), "project-version-1", 1L,
                    "c".repeat(64), "d".repeat(64), action.versionFenceSha256());
            binding.bind(result);
            return result;
        }
    }

    private static final class EffectStub implements ChainEffectRuntime.EffectAuthority {
        private final List<String> calls = new ArrayList<>();
        private final ChainEffectRuntime.EffectStatus reconciledStatus;
        private final ChainEffectRuntime.EffectStatus dispatchedStatus;
        private boolean prepared;
        private boolean dispatched;

        private EffectStub(
                ChainEffectRuntime.EffectStatus reconciledStatus,
                ChainEffectRuntime.EffectStatus dispatchedStatus) {
            this.reconciledStatus = reconciledStatus;
            this.dispatchedStatus = dispatchedStatus;
        }

        @Override
        public ChainEffectRuntime.EffectReconciliation reconcile(
                ChainEffectRuntime.FrozenMutation action) {
            calls.add("reconcile:" + action.actionId() + ":" + action.idempotencyKey());
            return result(action, reconciledStatus);
        }

        @Override
        public ChainEffectRuntime.PreparedEffect prepare(
                ChainEffectRuntime.FrozenMutation action) {
            prepared = true;
            calls.add("prepare:" + action.actionId() + ":" + action.idempotencyKey());
            return new ChainEffectRuntime.PreparedEffect(
                    "intent-1", action.actionId(), action.idempotencyKey(),
                    action.versionFenceSha256(), "dispatch-permit-1");
        }

        @Override
        public ChainEffectRuntime.EffectReconciliation dispatch(
                ChainEffectRuntime.PreparedEffect preparedEffect) {
            dispatched = true;
            calls.add("dispatch:" + preparedEffect.actionId() + ":"
                    + preparedEffect.idempotencyKey());
            return result(
                    preparedEffect.actionId(), preparedEffect.idempotencyKey(),
                    dispatchedStatus);
        }

        private static ChainEffectRuntime.EffectReconciliation result(
                ChainEffectRuntime.FrozenMutation action,
                ChainEffectRuntime.EffectStatus status) {
            return result(action.actionId(), action.idempotencyKey(), status);
        }

        private static ChainEffectRuntime.EffectReconciliation result(
                String actionId,
                String idempotencyKey,
                ChainEffectRuntime.EffectStatus status) {
            return new ChainEffectRuntime.EffectReconciliation(
                    actionId, idempotencyKey, status,
                    status == ChainEffectRuntime.EffectStatus.SUCCEEDED
                            || status == ChainEffectRuntime.EffectStatus.IN_FLIGHT
                            ? "receipt-1" : null,
                    status == ChainEffectRuntime.EffectStatus.FAILED
                            ? "error-1" : null,
                    status == ChainEffectRuntime.EffectStatus.UNKNOWN
                            ? "effect-intent-1" : null,
                    null);
        }
    }

    private static final class Store implements
            ChainWorkflowRepository, ChainModelRepository,
            ChainWorkspaceCandidateWriter {
        private final List<ChainPersistenceRecords.ActionBindingRecord> actions =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.WorkspaceCandidateRecord> candidates =
                new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.ModelProposalRecord> proposals =
                new HashMap<>();
        private long eventSequence;

        private void addAction(
                ChainPersistenceRecords.ActionBindingRecord action,
                ChainProposalKind proposalKind) {
            actions.add(action);
            proposals.put(action.proposalId(), new ChainPersistenceRecords.ModelProposalRecord(
                    action.proposalId(), action.taskId(),
                    "invocation-" + action.proposalId(), 1, ChainRole.EXECUTOR,
                    proposalKind,
                    new ChainPersistenceRecords.CanonicalJson(1, HASH, "{}"),
                    new ChainPersistenceRecords.CanonicalJson(1, HASH, "[]"),
                    proposalKind == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE
                            ? "WORKSPACE_CHANGE_BODY" : null,
                    proposalKind == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE
                            ? "change-body-1" : null,
                    NOW));
        }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String taskId) {
            return actions.stream().filter(value -> value.taskId().equals(taskId)).toList();
        }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String taskId) {
            return findActionBindings(taskId);
        }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String id) { return candidates.stream().filter(value -> value.taskId().equals(id)).toList(); }
        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.WorkspaceCandidateRecord> appendWorkspaceCandidate(ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.WorkspaceCandidateRecord> value) {
            ChainPersistenceRecords.WorkspaceCandidateRecord existing = candidates.stream()
                    .filter(candidate -> candidate.actionId().equals(value.fact().actionId()))
                    .findFirst().orElse(null);
            ChainPersistenceRecords.WorkspaceCandidateRecord fact =
                    existing == null ? value.fact() : existing;
            if (existing == null) candidates.add(fact);
            ChainPersistenceRecords.AuthorityEventRecord event =
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            value.event().eventId(), value.event().taskId(), ++eventSequence,
                            value.event().eventType(), value.event().transitionId(),
                            value.event().sourceIdentitySha256(), value.event().committedAt());
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, fact, existing != null);
        }
        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposal(String id) { return Optional.ofNullable(proposals.get(id)); }
        @Override public Optional<ChainPersistenceRecords.ModelInvocationRecord> findInvocation(String id) { return Optional.empty(); }
        @Override public long highestInvocationOrdinal(String taskId) { return 0; }
        @Override public List<ChainPersistenceRecords.ModelInvocationRecord> findInvocations(String id, long cut) { return List.of(); }
        @Override public int highestProviderAttemptNo(String invocationId) { return 0; }
        @Override public List<ChainPersistenceRecords.ProviderAttemptRecord> findProviderAttempts(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ContentRecord> findContents(String id) { return List.of(); }
        @Override public Optional<ChainPersistenceRecords.ContentRecord> findContent(String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposalByInvocation(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.ProposalStateEventRecord> findProposalStateEvents(String id) { return List.of(); }
        @Override public Optional<ChainPersistenceRecords.TransitionRecord> findTransition(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.TransitionStageRecord> findTransitionStages(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.TransitionRecord> findIncompleteTransitions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String id) { return List.of(); }
    }
}
