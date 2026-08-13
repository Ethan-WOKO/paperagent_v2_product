package io.paperagent.v2.chain.effect;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkspaceCandidateWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainEffectAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String FENCE = "b".repeat(64);
    private static final String CANDIDATE = "c".repeat(64);
    private static final String DIFF = "d".repeat(64);

    @Test
    void formalBindingAndEffectIntentExistBeforeDispatch() {
        Store store = new Store();
        store.addAction(action("action-1", "action-key-1", "proposal-tool-1"),
                ChainProposalKind.EXECUTOR_TOOL_ACTION);
        EffectStub effects = new EffectStub();
        effects.reconciled = reconciliation(
                "action-1", "action-key-1",
                ChainEffectRuntime.EffectStatus.NOT_DISPATCHED, null);
        effects.dispatched = reconciliation(
                "action-1", "action-key-1",
                ChainEffectRuntime.EffectStatus.SUCCEEDED, null);
        CandidateStub candidates = new CandidateStub();
        ChainEffectRuntime runtime = runtime(
                store, effects, candidates, currentGate(), missingWorkspaceSource());

        ChainEffectRuntime.ExecutionOutcome outcome = runtime.executeTool(
                new ChainEffectRuntime.ToolActionRequest("task-1", "action-1", NOW));

        assertEquals(ChainEffectRuntime.OutcomeKind.EFFECT_SUCCEEDED, outcome.kind());
        assertEquals(List.of("reconcile:action-1:action-key-1",
                        "prepare:action-1:action-key-1",
                        "dispatch:action-1:action-key-1"),
                effects.calls);
        assertFalse(candidates.materialized);

        EffectStub missingEffects = new EffectStub();
        ChainEffectRuntime missingRuntime = runtime(
                new Store(), missingEffects, new CandidateStub(),
                currentGate(), missingWorkspaceSource());
        assertThrows(IllegalStateException.class, () -> missingRuntime.executeTool(
                new ChainEffectRuntime.ToolActionRequest("task-1", "action-1", NOW)));
        assertTrue(missingEffects.calls.isEmpty(),
                "no EffectIntent or dispatch may precede the formal action binding");

        boolean[] workspaceSourceLoaded = {false};
        assertThrows(IllegalStateException.class, () -> runtime(
                new Store(), new EffectStub(), new CandidateStub(), currentGate(),
                (taskId, actionId) -> {
                    workspaceSourceLoaded[0] = true;
                    return workspaceBinding("proposal-missing", actionId);
                }).applyWorkspaceChange(new ChainEffectRuntime.WorkspaceChangeRequest(
                "task-1", "action-missing", NOW)));
        assertFalse(workspaceSourceLoaded[0],
                "Workspace mutation source cannot precede the formal ActionBinding");
    }

    @Test
    void cancellationAfterDurableIntentCannotStrandThePreparedEffect() {
        Store store = new Store();
        store.addAction(action("action-in-flight", "key-in-flight",
                        "proposal-in-flight"),
                ChainProposalKind.EXECUTOR_TOOL_ACTION);
        EffectStub effects = new EffectStub();
        effects.reconciled = reconciliation(
                "action-in-flight", "key-in-flight",
                ChainEffectRuntime.EffectStatus.NOT_DISPATCHED, null);
        effects.dispatched = reconciliation(
                "action-in-flight", "key-in-flight",
                ChainEffectRuntime.EffectStatus.SUCCEEDED, null);
        AtomicInteger gateReads = new AtomicInteger();
        ChainEffectRuntime runtime = runtime(
                store, effects, new CandidateStub(),
                ignored -> gateReads.getAndIncrement() == 0
                        ? ChainEffectRuntime.GateStatus.CURRENT
                        : ChainEffectRuntime.GateStatus.CANCELLED,
                missingWorkspaceSource());

        ChainEffectRuntime.ExecutionOutcome outcome = runtime.executeTool(
                new ChainEffectRuntime.ToolActionRequest(
                        "task-1", "action-in-flight", NOW));

        assertEquals(ChainEffectRuntime.OutcomeKind.LATE_RESULT_RETAINED,
                outcome.kind());
        assertEquals(ChainEffectRuntime.GateStatus.CANCELLED,
                outcome.gateStatus());
        assertEquals(List.of(
                        "reconcile:action-in-flight:key-in-flight",
                        "prepare:action-in-flight:key-in-flight",
                        "dispatch:action-in-flight:key-in-flight"),
                effects.calls);
    }

    @Test
    void toolAndWorkspaceChangeShareCandidateAuthorityAndReplayOriginalTime() {
        Store store = new Store();
        store.authoritativeTime = NOW.plusSeconds(7);
        store.addAction(action("action-tool", "key-tool", "proposal-tool"),
                ChainProposalKind.EXECUTOR_TOOL_ACTION);
        store.addAction(action(
                        "action-workspace", "key-workspace", "proposal-workspace"),
                ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
        EffectStub effects = new EffectStub();
        effects.reconciled = reconciliation(
                "action-tool", "key-tool", ChainEffectRuntime.EffectStatus.SUCCEEDED,
                new ChainEffectRuntime.WorkspaceMutation("tool-result-1"));
        CandidateStub candidates = new CandidateStub();
        ChainEffectRuntime.WorkspaceChangeBinding workspace = workspaceBinding(
                "proposal-workspace", "action-workspace");
        ChainEffectRuntime runtime = runtime(
                store, effects, candidates, currentGate(),
                (taskId, proposalId) -> workspace);

        int effectCallsBeforeWrongRoute = effects.calls.size();
        assertThrows(IllegalStateException.class, () -> runtime.executeTool(
                new ChainEffectRuntime.ToolActionRequest(
                        "task-1", "action-workspace", NOW)));
        assertEquals(effectCallsBeforeWrongRoute, effects.calls.size(),
                "WORKSPACE_CHANGE cannot enter EffectAuthority");

        ChainEffectRuntime.ExecutionOutcome tool = runtime.executeTool(
                new ChainEffectRuntime.ToolActionRequest("task-1", "action-tool", NOW));
        assertEquals(ChainEffectRuntime.OutcomeKind.CANDIDATE_COMMITTED, tool.kind());
        assertEquals("TOOL_EFFECT_RESULT",
                candidates.commands.get(0).mutationAuthorityType());
        assertEquals("tool-result-1",
                candidates.commands.get(0).mutationAuthorityRef());

        ChainEffectRuntime.ExecutionOutcome replay = runtime.executeTool(
                new ChainEffectRuntime.ToolActionRequest(
                        "task-1", "action-tool", NOW.plusSeconds(60)));
        assertEquals(tool.candidate(), replay.candidate());
        assertEquals(store.authoritativeTime, replay.candidate().createdAt(),
                "recovery must retain the writer-owned Candidate audit time");

        ChainEffectRuntime.ExecutionOutcome direct = runtime.applyWorkspaceChange(
                new ChainEffectRuntime.WorkspaceChangeRequest(
                        "task-1", "action-workspace", NOW));
        assertEquals(ChainEffectRuntime.OutcomeKind.CANDIDATE_COMMITTED, direct.kind());
        assertEquals("WORKSPACE_CHANGE_BODY",
                candidates.commands.get(candidates.commands.size() - 1)
                        .mutationAuthorityType());
        assertEquals("change-body-1",
                candidates.commands.get(candidates.commands.size() - 1)
                        .mutationAuthorityRef());
        assertEquals(2, store.candidates.size());
        assertEquals(List.of(
                        ChainEffectRuntime.SourceKind.TOOL_ACTION,
                        ChainEffectRuntime.SourceKind.TOOL_ACTION,
                        ChainEffectRuntime.SourceKind.WORKSPACE_CHANGE),
                candidates.commands.stream()
                        .map(value -> value.mutation().sourceKind()).toList());
    }

    @Test
    void lateToolAndWorkspaceResultsLeaveAuthorityTraceButCreateNoCandidate() {
        for (ChainEffectRuntime.SourceKind sourceKind : ChainEffectRuntime.SourceKind.values()) {
            Store store = new Store();
            CandidateStub candidates = new CandidateStub();
            EffectStub effects = new EffectStub();
            ChainEffectRuntime.ExecutionOutcome outcome;
            if (sourceKind == ChainEffectRuntime.SourceKind.TOOL_ACTION) {
                store.addAction(action(
                                "action-late", "key-late", "proposal-late-tool"),
                        ChainProposalKind.EXECUTOR_TOOL_ACTION);
                effects.reconciled = reconciliation(
                        "action-late", "key-late",
                        ChainEffectRuntime.EffectStatus.SUCCEEDED,
                        new ChainEffectRuntime.WorkspaceMutation("late-tool-result"));
                outcome = runtime(
                        store, effects, candidates,
                        ignored -> ChainEffectRuntime.GateStatus.CANCELLED,
                        missingWorkspaceSource()).executeTool(
                        new ChainEffectRuntime.ToolActionRequest(
                                "task-1", "action-late", NOW));
                assertEquals(1, effects.calls.size(),
                        "late Tool result is reconciled and retained");
            } else {
                candidates.forceLate = true;
                store.addAction(action(
                                "action-late-workspace", "key-late-workspace",
                                "proposal-late-workspace"),
                        ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
                ChainEffectRuntime.WorkspaceChangeBinding binding =
                        workspaceBinding(
                                "proposal-late-workspace", "action-late-workspace");
                outcome = runtime(
                        store, effects, candidates, currentGate(),
                        (taskId, proposalId) -> binding).applyWorkspaceChange(
                        new ChainEffectRuntime.WorkspaceChangeRequest(
                                "task-1", "action-late-workspace", NOW));
                assertTrue(candidates.materialized,
                        "Workspace authority retains its late result internally");
            }
            assertEquals(ChainEffectRuntime.OutcomeKind.LATE_RESULT_RETAINED,
                    outcome.kind(), sourceKind.name());
            assertTrue(store.candidates.isEmpty(), sourceKind.name());
        }
    }

    @Test
    void existingCandidateMustMatchDerivedIdentityAndWorkspaceAuthority() {
        for (int corruption = 0; corruption < 4; corruption++) {
            Store store = new Store();
            ChainPersistenceRecords.ActionBindingRecord action = action(
                    "action-existing", "key-existing", "proposal-existing");
            store.addAction(action, ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
            CandidateStub candidates = new CandidateStub();
            ChainEffectRuntime.MaterializedCandidate authoritative =
                    new ChainEffectRuntime.MaterializedCandidate(
                            ChainEffectRuntime.CandidateDisposition.COMMITTED,
                            action.actionId(), action.workspaceId(),
                            corruption == 3 ? "e".repeat(64) : action.baseCandidateKey(),
                            "project-version-1", 7L, CANDIDATE, DIFF,
                            action.versionFenceSha256());
            candidates.committed.put(action.actionId(), authoritative);
            String candidateId = "workspace-candidate." + sha256(
                    action.actionId() + "\0" + action.workspaceId() + "\0" + 7L
                            + "\0" + CANDIDATE + "\0" + DIFF);
            String eventId = "workspace-candidate.binding." + sha256(candidateId);
            if (corruption == 0) candidateId = "workspace-candidate.wrong";
            if (corruption == 1) eventId = "workspace-candidate.binding.wrong";
            String baseProjectVersion = corruption == 2
                    ? "project-version-wrong" : "project-version-1";
            ChainPersistenceRecords.WorkspaceCandidateRecord existing =
                    new ChainPersistenceRecords.WorkspaceCandidateRecord(
                            candidateId, action.taskId(), eventId, action.actionId(),
                            action.workspaceId(), baseProjectVersion, 7L,
                            CANDIDATE, DIFF, action.versionFenceSha256(), NOW);
            store.candidates.put(existing.workspaceCandidateId(), existing);
            ChainEffectRuntime runtime = runtime(
                    store, new EffectStub(), candidates, currentGate(),
                    (taskId, actionId) -> workspaceBinding(
                            action.proposalId(), action.actionId()));

            int caseNo = corruption;
            assertThrows(IllegalStateException.class,
                    () -> runtime.applyWorkspaceChange(
                            new ChainEffectRuntime.WorkspaceChangeRequest(
                                    action.taskId(), action.actionId(), NOW)),
                    "corruption=" + caseNo);
        }
    }

    @Test
    void existingCandidateAfterCancellationIsTraceOnly() {
        Store store = new Store();
        ChainPersistenceRecords.ActionBindingRecord action = action(
                "action-existing-late", "key-existing-late",
                "proposal-existing-late");
        store.addAction(action, ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
        CandidateStub candidates = new CandidateStub();
        ChainEffectRuntime.MaterializedCandidate materialized =
                new ChainEffectRuntime.MaterializedCandidate(
                        ChainEffectRuntime.CandidateDisposition.COMMITTED,
                        action.actionId(), action.workspaceId(),
                        action.baseCandidateKey(), "project-version-1", 7L,
                        CANDIDATE, DIFF, action.versionFenceSha256());
        candidates.committed.put(action.actionId(), materialized);
        String candidateId = "workspace-candidate." + sha256(
                action.actionId() + "\0" + action.workspaceId() + "\0" + 7L
                        + "\0" + CANDIDATE + "\0" + DIFF);
        ChainPersistenceRecords.WorkspaceCandidateRecord binding =
                new ChainPersistenceRecords.WorkspaceCandidateRecord(
                        candidateId, action.taskId(),
                        "workspace-candidate.binding." + sha256(candidateId),
                        action.actionId(), action.workspaceId(),
                        "project-version-1", 7L, CANDIDATE, DIFF,
                        action.versionFenceSha256(), NOW);
        store.candidates.put(binding.workspaceCandidateId(), binding);
        ChainEffectRuntime runtime = runtime(
                store, new EffectStub(), candidates,
                ignored -> ChainEffectRuntime.GateStatus.CANCELLED,
                (taskId, actionId) -> workspaceBinding(
                        action.proposalId(), action.actionId()));

        ChainEffectRuntime.ExecutionOutcome outcome = runtime.applyWorkspaceChange(
                new ChainEffectRuntime.WorkspaceChangeRequest(
                        action.taskId(), action.actionId(), NOW.plusSeconds(1)));

        assertEquals(ChainEffectRuntime.OutcomeKind.LATE_RESULT_RETAINED,
                outcome.kind());
        assertEquals(binding, outcome.candidate());
        assertEquals(ChainEffectRuntime.GateStatus.CANCELLED,
                outcome.gateStatus());
    }

    @Test
    void effectReconciliationRejectsContradictoryFactShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainEffectRuntime.EffectReconciliation(
                        "action", "key", ChainEffectRuntime.EffectStatus.NOT_DISPATCHED,
                        "receipt", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainEffectRuntime.EffectReconciliation(
                        "action", "key", ChainEffectRuntime.EffectStatus.IN_FLIGHT,
                        null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainEffectRuntime.EffectReconciliation(
                        "action", "key", ChainEffectRuntime.EffectStatus.UNKNOWN,
                        null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainEffectRuntime.EffectReconciliation(
                        "action", "key", ChainEffectRuntime.EffectStatus.UNKNOWN,
                        "receipt", null, "effect-intent-action", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainEffectRuntime.EffectReconciliation(
                        "action", "key", ChainEffectRuntime.EffectStatus.SUCCEEDED,
                        "receipt", "error", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainEffectRuntime.EffectReconciliation(
                        "action", "key", ChainEffectRuntime.EffectStatus.FAILED,
                        null, "error", null,
                        new ChainEffectRuntime.WorkspaceMutation("workspace-result")));
    }

    @Test
    void committedCandidateMustUseAtomicBindingCallback() {
        Store store = new Store();
        ChainPersistenceRecords.ActionBindingRecord action = action(
                "action-atomic", "key-atomic", "proposal-atomic");
        store.addAction(action, ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
        ChainEffectRuntime.WorkspaceCandidateAuthority broken =
                new ChainEffectRuntime.WorkspaceCandidateAuthority() {
                    @Override
                    public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
                            ChainEffectRuntime.CandidateMutation mutation) {
                        return Optional.empty();
                    }

                    @Override
                    public ChainEffectRuntime.MaterializedCandidate materialize(
                            ChainEffectRuntime.CandidateMutation mutation,
                            ChainEffectRuntime.CandidateBindingPort ignored) {
                        ChainEffectRuntime.FrozenMutation frozen = mutation.mutation();
                        return new ChainEffectRuntime.MaterializedCandidate(
                                ChainEffectRuntime.CandidateDisposition.COMMITTED,
                                frozen.actionId(), frozen.workspaceId(),
                                frozen.baseCandidateKey(), "project-version-1", 9L,
                                CANDIDATE, DIFF, frozen.versionFenceSha256());
                    }
                };
        ChainEffectRuntime runtime = new ChainEffectRuntime(
                store, store, new EffectStub(),
                (taskId, actionId) -> workspaceBinding(
                        action.proposalId(), action.actionId()),
                broken, store, currentGate());

        assertThrows(IllegalStateException.class,
                () -> runtime.applyWorkspaceChange(
                        new ChainEffectRuntime.WorkspaceChangeRequest(
                                action.taskId(), action.actionId(), NOW)));
        assertTrue(store.candidates.isEmpty());
    }

    @Test
    void typedCandidateFailureReturnsFormalErrorWithoutCandidateBinding() {
        Store store = new Store();
        ChainPersistenceRecords.ActionBindingRecord action = action(
                "action-candidate-failed", "key-candidate-failed",
                "proposal-candidate-failed");
        store.addAction(action, ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE);
        ChainEffectRuntime.WorkspaceCandidateAuthority failed =
                new ChainEffectRuntime.WorkspaceCandidateAuthority() {
                    @Override
                    public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
                            ChainEffectRuntime.CandidateMutation mutation) {
                        return Optional.empty();
                    }

                    @Override
                    public ChainEffectRuntime.MaterializedCandidate materialize(
                            ChainEffectRuntime.CandidateMutation mutation,
                            ChainEffectRuntime.CandidateBindingPort ignored) {
                        var frozen = mutation.mutation();
                        return new ChainEffectRuntime.MaterializedCandidate(
                                ChainEffectRuntime.CandidateDisposition.FAILED,
                                frozen.actionId(), frozen.workspaceId(),
                                frozen.baseCandidateKey(), null, 0,
                                null, null, frozen.versionFenceSha256(),
                                "candidate-failure.1",
                                "CANDIDATE_NO_ACTUAL_CHANGE");
                    }
                };
        ChainEffectRuntime runtime = new ChainEffectRuntime(
                store, store, new EffectStub(),
                (taskId, actionId) -> workspaceBinding(
                        action.proposalId(), action.actionId()),
                failed, store, currentGate());

        ChainEffectRuntime.ExecutionOutcome outcome =
                runtime.applyWorkspaceChange(
                        new ChainEffectRuntime.WorkspaceChangeRequest(
                                action.taskId(), action.actionId(), NOW));

        assertEquals(ChainEffectRuntime.OutcomeKind.EFFECT_FAILED,
                outcome.kind());
        assertEquals("candidate-failure.1", outcome.errorRef());
        assertTrue(store.candidates.isEmpty());
    }

    private static ChainEffectRuntime runtime(
            Store store,
            EffectStub effects,
            CandidateStub candidates,
            ChainEffectRuntime.CurrentAuthorityGate gate,
            ChainEffectRuntime.WorkspaceChangeSource workspaceSource) {
        return new ChainEffectRuntime(
                store, store, effects, workspaceSource, candidates, store, gate);
    }

    private static ChainEffectRuntime.CurrentAuthorityGate currentGate() {
        return ignored -> ChainEffectRuntime.GateStatus.CURRENT;
    }

    private static ChainEffectRuntime.WorkspaceChangeSource missingWorkspaceSource() {
        return (taskId, proposalId) -> {
            throw new IllegalStateException("workspace source was not expected");
        };
    }

    private static ChainEffectRuntime.EffectReconciliation reconciliation(
            String actionId,
            String key,
            ChainEffectRuntime.EffectStatus status,
            ChainEffectRuntime.WorkspaceMutation mutation) {
        return new ChainEffectRuntime.EffectReconciliation(
                actionId, key, status,
                status == ChainEffectRuntime.EffectStatus.SUCCEEDED
                        || status == ChainEffectRuntime.EffectStatus.IN_FLIGHT
                        ? "receipt-1" : null,
                status == ChainEffectRuntime.EffectStatus.FAILED
                        ? "error-1" : null,
                status == ChainEffectRuntime.EffectStatus.UNKNOWN
                        ? "effect-intent-" + actionId : null,
                mutation);
    }

    private static ChainPersistenceRecords.ActionBindingRecord action(
            String actionId, String key, String proposalId) {
        return new ChainPersistenceRecords.ActionBindingRecord(
                actionId, "task-1", "event-" + actionId, proposalId, 1,
                HASH, key, "instruction-1", "task-frame-1", "plan-1",
                "revision-1", "step-1", "activation-1", "workspace-1",
                ChainIdentity.NONE, null, null, null, null, FENCE, NOW);
    }

    private static ChainEffectRuntime.WorkspaceChangeBinding workspaceBinding(
            String proposalId, String actionId) {
        return new ChainEffectRuntime.WorkspaceChangeBinding(
                "task-1", proposalId, actionId, "change-body-1");
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

    private static final class EffectStub implements ChainEffectRuntime.EffectAuthority {
        private final List<String> calls = new ArrayList<>();
        private ChainEffectRuntime.EffectReconciliation reconciled;
        private ChainEffectRuntime.EffectReconciliation dispatched;

        @Override
        public ChainEffectRuntime.EffectReconciliation reconcile(
                ChainEffectRuntime.FrozenMutation action) {
            calls.add("reconcile:" + action.actionId() + ":" + action.idempotencyKey());
            if (reconciled == null) throw new IllegalStateException("missing reconciliation");
            return reconciled;
        }

        @Override
        public ChainEffectRuntime.PreparedEffect prepare(
                ChainEffectRuntime.FrozenMutation action) {
            calls.add("prepare:" + action.actionId() + ":" + action.idempotencyKey());
            return new ChainEffectRuntime.PreparedEffect(
                    "intent-" + action.actionId(), action.actionId(),
                    action.idempotencyKey(), action.versionFenceSha256(),
                    "dispatch-permit-" + action.actionId());
        }

        @Override
        public ChainEffectRuntime.EffectReconciliation dispatch(
                ChainEffectRuntime.PreparedEffect prepared) {
            calls.add("dispatch:" + prepared.actionId() + ":" + prepared.idempotencyKey());
            if (dispatched == null) throw new IllegalStateException("missing dispatch result");
            return dispatched;
        }
    }

    private static final class CandidateStub
            implements ChainEffectRuntime.WorkspaceCandidateAuthority {
        private final List<ChainEffectRuntime.CandidateMutation> commands = new ArrayList<>();
        private final Map<String, ChainEffectRuntime.MaterializedCandidate> committed =
                new HashMap<>();
        private boolean materialized;
        private boolean forceLate;

        @Override
        public Optional<ChainEffectRuntime.MaterializedCandidate> reconcile(
                ChainEffectRuntime.CandidateMutation mutation) {
            commands.add(mutation);
            return Optional.ofNullable(committed.get(mutation.mutation().actionId()));
        }

        @Override
        public ChainEffectRuntime.MaterializedCandidate materialize(
                ChainEffectRuntime.CandidateMutation command,
                ChainEffectRuntime.CandidateBindingPort binding) {
            materialized = true;
            ChainEffectRuntime.FrozenMutation mutation = command.mutation();
            if (forceLate) {
                return new ChainEffectRuntime.MaterializedCandidate(
                        ChainEffectRuntime.CandidateDisposition.LATE_RETAINED,
                        mutation.actionId(), mutation.workspaceId(),
                        mutation.baseCandidateKey(), null, 0,
                        null, null, mutation.versionFenceSha256());
            }
            long artifactId = Math.abs((long) mutation.actionId().hashCode()) + 1L;
            ChainEffectRuntime.MaterializedCandidate result =
                    new ChainEffectRuntime.MaterializedCandidate(
                            ChainEffectRuntime.CandidateDisposition.COMMITTED,
                            mutation.actionId(), mutation.workspaceId(),
                            mutation.baseCandidateKey(), "project-version-1",
                            artifactId, CANDIDATE, DIFF, mutation.versionFenceSha256());
            committed.put(mutation.actionId(), result);
            binding.bind(result);
            return result;
        }
    }

    private static final class Store implements
            ChainWorkflowRepository, ChainModelRepository,
            ChainWorkspaceCandidateWriter {
        private final List<ChainPersistenceRecords.ActionBindingRecord> actions =
                new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.WorkspaceCandidateRecord> candidates =
                new LinkedHashMap<>();
        private final Map<String, ChainPersistenceRecords.ModelProposalRecord> proposals =
                new HashMap<>();
        private long eventSequence;
        private Instant authoritativeTime;

        private void addAction(
                ChainPersistenceRecords.ActionBindingRecord action,
                ChainProposalKind kind) {
            actions.add(action);
            proposals.put(action.proposalId(), new ChainPersistenceRecords.ModelProposalRecord(
                    action.proposalId(), action.taskId(),
                    "invocation-" + action.proposalId(), 1, ChainRole.EXECUTOR,
                    kind, new ChainPersistenceRecords.CanonicalJson(1, HASH, "{}"),
                    new ChainPersistenceRecords.CanonicalJson(1, HASH, "[]"),
                    kind == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE
                            ? "WORKSPACE_CHANGE_BODY" : null,
                    kind == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE
                            ? "change-body-1" : null,
                    NOW));
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.WorkspaceCandidateRecord> appendWorkspaceCandidate(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.WorkspaceCandidateRecord> requested) {
            ChainPersistenceRecords.WorkspaceCandidateRecord existing = candidates.putIfAbsent(
                    requested.fact().workspaceCandidateId(),
                    authoritativeTime == null
                            ? requested.fact()
                            : withCreatedAt(requested.fact(), authoritativeTime));
            ChainPersistenceRecords.WorkspaceCandidateRecord fact = existing == null
                    ? candidates.get(requested.fact().workspaceCandidateId()) : existing;
            ChainPersistenceRecords.AuthorityEventRecord event =
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            requested.event().eventId(), requested.event().taskId(),
                            ++eventSequence, requested.event().eventType(),
                            requested.event().transitionId(),
                            requested.event().sourceIdentitySha256(),
                            authoritativeTime == null
                                    ? requested.event().committedAt()
                                    : authoritativeTime);
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, fact, existing != null);
        }

        private static ChainPersistenceRecords.WorkspaceCandidateRecord withCreatedAt(
                ChainPersistenceRecords.WorkspaceCandidateRecord fact,
                Instant createdAt) {
            return new ChainPersistenceRecords.WorkspaceCandidateRecord(
                    fact.workspaceCandidateId(), fact.taskId(), fact.eventId(),
                    fact.actionId(), fact.workspaceId(), fact.baseProjectVersion(),
                    fact.artifactId(), fact.candidateFingerprint(),
                    fact.diffDigest(), fact.versionFenceSha256(), createdAt);
        }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String taskId) {
            return actions.stream().filter(value -> value.taskId().equals(taskId)).toList();
        }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String taskId) {
            return findActionBindings(taskId);
        }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String taskId) {
            return candidates.values().stream().filter(value -> value.taskId().equals(taskId)).toList();
        }
        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposal(String id) { return Optional.ofNullable(proposals.get(id)); }
        @Override public Optional<ChainPersistenceRecords.ModelInvocationRecord> findInvocation(String id) { return Optional.empty(); }
        @Override public long highestInvocationOrdinal(String taskId) { return 0; }
        @Override public int highestProviderAttemptNo(String invocationId) { return 0; }
        @Override public List<ChainPersistenceRecords.ModelInvocationRecord> findInvocations(String id, long cut) { return List.of(); }
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
