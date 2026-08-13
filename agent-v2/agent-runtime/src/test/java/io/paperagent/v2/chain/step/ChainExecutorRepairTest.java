package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.*;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainExecutorRepairTest {
    private static final Instant NOW =
            Instant.parse("2026-08-07T04:00:00Z");

    @Test
    void ordinaryFailureRepairsInsideStepWhileDuplicatesAndUnknownEffectsDoNot() {
        ChainStepTestStore store = new ChainStepTestStore();
        ChainExecutorRepairService repairs = new ChainExecutorRepairService(
                ChainRuntimePolicy.V1, store, store);
        ActionBindingRecord failed = action(
                "action-1", 1, digest('1'), "RECEIPT", "receipt-1");
        store.actions.add(failed);
        store.progressSnapshot = snapshot(
                marker(1, '1'), marker(2, '2'));

        var repair = repairs.decide(request(
                digest('2'), false, "action-1", "error-1",
                digest('3')));
        assertEquals(
                ChainExecutorRepairService.RepairNext.CALL_EXECUTOR_REPAIR,
                repair.next());
        assertEquals("action-1", repair.boundActionId());

        ActionBindingRecord duplicate = action(
                "action-2", 2, digest('1'), "RECEIPT", "receipt-2");
        store.actions.add(duplicate);
        store.progressSnapshot = snapshot(marker(1, '1'));
        var repeated = repairs.decide(request(
                digest('1'), false, null, null, null));
        assertEquals(
                ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR,
                repeated.next());
        assertEquals("REPEATED_ACTION_SIGNATURE", repeated.reasonCode());

        ActionBindingRecord inFlight = action(
                "action-unknown", 3, digest('4'), null, null);
        store.actions.add(inFlight);
        store.inFlightActions.add(inFlight);
        store.progressSnapshot = snapshot();
        var unknown = repairs.decide(request(
                digest('5'), true, null, null, null));
        assertEquals(
                ChainExecutorRepairService.RepairNext.RECONCILE_SAME_ACTION,
                unknown.next());
        assertEquals("action-unknown", unknown.boundActionId());
    }

    @Test
    void unchangedRepairAndNoProgressEscalateToReflector() {
        ChainStepTestStore store = new ChainStepTestStore();
        ChainExecutorRepairService repairs = new ChainExecutorRepairService(
                ChainRuntimePolicy.V1, store, store);
        ActionBindingRecord failed = action(
                "action-1", 1, digest('1'), "RECEIPT", "receipt-1");
        store.actions.add(failed);
        store.progressSnapshot = snapshot(marker(1, '1'));
        assertEquals(
                ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR,
                repairs.decide(request(
                        digest('1'), false, "action-1", "error-1",
                        digest('2'))).next());
        store.progressSnapshot = snapshot(
                marker(1, '7'), marker(2, '7'), marker(3, '7'));
        assertEquals(
                ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR,
                repairs.decide(request(
                        digest('2'), false, null, null, null)).next());
    }

    @Test
    void externalReceiptRemovesActionFromInFlightWithoutRedispatch() {
        ChainStepTestStore store = new ChainStepTestStore();
        ChainExecutorRepairService repairs = new ChainExecutorRepairService(
                ChainRuntimePolicy.V1, store, store);
        // Action bindings are immutable. A formal Receipt can therefore close
        // the action while these legacy result columns remain null.
        store.actions.add(action(
                "action-with-receipt", 1, digest('4'), null, null));
        store.progressSnapshot = snapshot();

        var unknown = repairs.decide(request(
                digest('5'), true, null, null, null));

        assertEquals(
                ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR,
                unknown.next());
        assertEquals("UNKNOWN_EFFECT_ACTION_AMBIGUOUS", unknown.reasonCode());
    }

    @Test
    void callerCannotOmitFormalActionsOrProgressToBypassPolicy() {
        ChainStepTestStore store = new ChainStepTestStore();
        store.actions.add(action(
                "action-1", 1, digest('8'), "RECEIPT", "receipt-1"));
        store.actions.add(action(
                "action-2", 2, digest('8'), "RECEIPT", "receipt-2"));
        store.progressSnapshot = snapshot(
                marker(1, '9'), marker(2, '9'), marker(3, '9'));
        ChainExecutorRepairService repairs = new ChainExecutorRepairService(
                ChainRuntimePolicy.V1, store, store);

        assertEquals(
                ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR,
                repairs.decide(request(
                        digest('8'), false, null, null, null)).next());
    }

    @Test
    void actionRuntimeOwnsStableBindingAndRecoversProposalBindFailure() {
        ChainStepTestStore store = new ChainStepTestStore();
        store.contexts.put("context-action", actionContext());
        store.invocations.put("invocation-action", new ModelInvocationRecord(
                "invocation-action", "task-1", "context-action",
                "token-action", ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "tool-action",
                "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), NOW));
        addToolActionProposal(store, "proposal-action");
        ChainActionRuntime runtime = new ChainActionRuntime(
                store, store, store, store, store, store);
        ChainActionRuntime.ActionCommand command =
                new ChainActionRuntime.ActionCommand(
                        "task-1", "proposal-action", NOW);

        store.failActionBindingOnce = true;
        assertThrows(IllegalStateException.class,
                () -> runtime.commit(command));
        assertEquals(1, store.actions.size());
        ActionBindingRecord action = store.actions.get(0);
        assertTrue(action.actionId().startsWith("action."));
        assertEquals(1, action.attemptNo());
        assertNull(action.effectIntentId());
        assertNull(action.dispatchRef());
        assertNull(action.resultAuthorityType());

        store.gateBlocked = true;
        assertTrue(runtime.commit(command).replayed());
        assertEquals(1, store.actions.size());
        assertEquals("ACTION_BINDING", store.proposalStates
                .get("proposal-action").get(1).officialAuthorityType());
        assertEquals(2, store.actionBindingAttempts);
        assertTrue(runtime.commit(new ChainActionRuntime.ActionCommand(
                "task-1", "proposal-action", NOW.plusSeconds(1))).replayed());
        assertEquals(2, store.actionBindingAttempts);

        addToolActionProposal(store, "proposal-blocked");
        assertThrows(ChainStepException.class, () -> runtime.commit(
                new ChainActionRuntime.ActionCommand(
                        "task-1", "proposal-blocked", NOW)));
        assertEquals(1, store.actions.size());
    }

    private static void addToolActionProposal(
            ChainStepTestStore store, String proposalId) {
        store.proposals.put(proposalId, new ModelProposalRecord(
                proposalId, "task-1", "invocation-action", 1,
                ChainRole.EXECUTOR, ChainProposalKind.EXECUTOR_TOOL_ACTION,
                new CanonicalJson(1, digest('a'),
                        "{\"toolId\":\"search\"}"),
                new CanonicalJson(1, digest('b'), "{\"refs\":[]}"),
                null, null, NOW));
        store.proposalStates.put(proposalId, List.of(
                new ProposalStateEventRecord(
                        proposalId, 1, "task-1",
                        "event-accepted-" + proposalId,
                        ChainProposalState.ACCEPTED, null, null, NOW)));
    }

    private static ContextRevisionRecord actionContext() {
        return new ContextRevisionRecord(
                "context-action", "task-1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "tool-action",
                "instruction-1", "frame-1", "plan-1", "revision-1",
                1L, "step-1", "activation-1", null, null,
                "workspace-1", null, null, null, null, null,
                "projectors-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new FormattedJson(1, "{}"), digest('c'), "token-action",
                null, null, NOW, NOW);
    }

    private static ChainExecutorRepairService.RepairRequest request(
            String signature,
            boolean unknown,
            String lastAction,
            String error,
            String change) {
        return new ChainExecutorRepairService.RepairRequest(
                "task-1", "step-1", "activation-1", signature,
                unknown, lastAction, error, change);
    }

    private static ActionBindingRecord action(
            String actionId,
            int attempt,
            String signature,
            String resultType,
            String resultRef) {
        return new ActionBindingRecord(
                actionId, "task-1", "event-" + actionId,
                "proposal-" + actionId, attempt, signature,
                "key-" + actionId, "instruction-1", "frame-1",
                "plan-1", "revision-1", "step-1", "activation-1",
                "workspace-1", "NONE", null, null,
                resultType, resultRef, digest('0'), NOW);
    }

    private static ChainProgressPolicy.ProgressMarker marker(
            long sequence, char digest) {
        return new ChainProgressPolicy.ProgressMarker(
                sequence, digest(digest));
    }

    private static ChainProgressAuthorityPort.ProgressSnapshot snapshot(
            ChainProgressPolicy.ProgressMarker... markers) {
        long cut = markers.length == 0
                ? 0 : markers[markers.length - 1].authorityEventSequence();
        return new ChainProgressAuthorityPort.ProgressSnapshot(
                cut, List.of(markers));
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
