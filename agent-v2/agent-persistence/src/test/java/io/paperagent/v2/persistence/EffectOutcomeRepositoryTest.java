package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectOutcomeRepositoryTest {
    private static final String OWNER = "effect-outcome-owner";
    private static final String TOKEN = "effect-outcome-token";

    @Test
    void publicPersistenceValuesValidateRedactAndExposeTheOutcomePort() {
        EffectProgress progress = progress("progress-opaque", "call-opaque", 1, "detail-opaque");
        ExecutionReceipt receipt = receipt("receipt-opaque", "call-opaque");
        EffectProgressRequest progressRequest = new EffectProgressRequest(
                progress, "token-opaque", 7);
        PersistedEffectProgress persistedProgress = new PersistedEffectProgress(
                progress, "owner-opaque", 7);
        EffectResultRequest resultRequest = new EffectResultRequest(
                receipt, "token-opaque", 7);
        PersistedEffectResult persistedResult = new PersistedEffectResult(
                receipt, "owner-opaque", 7);

        assertNotNull(new InMemoryPersistence().effectOutcomes());
        for (Object value : List.of(
                progressRequest, persistedProgress, resultRequest, persistedResult)) {
            String text = value.toString();
            for (String sentinel : List.of(
                    "progress-opaque", "call-opaque", "detail-opaque",
                    "receipt-opaque", "token-opaque", "owner-opaque", "stdout-opaque")) {
                assertFalse(text.contains(sentinel), text);
            }
        }
        assertThrows(NullPointerException.class, () ->
                new EffectProgressRequest(null, TOKEN, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new EffectProgressRequest(progress, " ", 1));
        assertThrows(IllegalArgumentException.class, () ->
                new PersistedEffectProgress(progress, OWNER, 0));
        assertThrows(NullPointerException.class, () ->
                new EffectResultRequest(null, TOKEN, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new EffectResultRequest(receipt, TOKEN, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new PersistedEffectResult(receipt, " ", 1));
    }

    @Test
    void firstProgressAndFinalReceiptAreDurableAndDoNotMutateExecutionAuthority() {
        Harness harness = activated("happy", 60);
        ToolCallId toolCallId = durableIntent(harness, "happy");
        AuthoritySnapshot before = snapshot(harness.state());
        EffectProgressRequest progressRequest = progressRequest(
                "happy", toolCallId, 1, TOKEN, harness.lease().fencingToken());

        PersistedEffectProgress progress = requireApplied(
                harness.outcomes().appendProgress(progressRequest));

        assertEquals(progressRequest.progress(), progress.progress());
        assertEquals(OWNER, progress.leaseOwnerId());
        assertEquals(harness.lease().fencingToken(), progress.fencingToken());
        assertEquals(List.of(progress), requireFound(
                harness.outcomes().readProgress(toolCallId)));
        assertThrows(UnsupportedOperationException.class, () ->
                requireFound(harness.outcomes().readProgress(toolCallId)).add(progress));
        assertExecutionAuthorityUnchanged(before, harness.state());
        assertEquals(1, harness.state().effectProgresses.size());
        assertTrue(harness.state().effectResults.isEmpty());
        assertTrue(harness.state().receipts.isEmpty());

        ExecutionReceipt receipt = receipt("happy", toolCallId.value());
        PersistedEffectResult result = requireApplied(harness.outcomes().recordResult(
                new EffectResultRequest(receipt, TOKEN, harness.lease().fencingToken())));

        assertEquals(receipt, result.receipt());
        assertEquals(result, requireFound(harness.outcomes().findResult(toolCallId)));
        assertEquals(receipt, requireFound(harness.receipts().find(receipt.id())));
        assertExecutionAuthorityUnchanged(before, harness.state());
        assertEquals(1, harness.state().effectResults.size());
        assertEquals(1, harness.state().receipts.size());
        assertTrue(harness.state().idempotency.isEmpty());
    }

    @Test
    void exactProgressAndResultReplayAfterLeaseTakeoverWithoutClockOrLiveLeaseAccess() {
        Harness harness = activated("takeover", 10);
        ToolCallId toolCallId = durableIntent(harness, "takeover");
        EffectProgressRequest progress = progressRequest(
                "takeover", toolCallId, 1, TOKEN, harness.lease().fencingToken());
        EffectResultRequest result = new EffectResultRequest(
                receipt("takeover", toolCallId.value()),
                TOKEN,
                harness.lease().fencingToken());
        PersistedEffectProgress originalProgress = requireApplied(
                harness.outcomes().appendProgress(progress));
        PersistedEffectResult originalResult = requireApplied(
                harness.outcomes().recordResult(result));
        harness.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        LeaseRecord takeover = requireApplied(harness.leases().acquire(
                harness.plan().id(),
                "new-owner",
                "new-token",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));
        assertEquals(2, takeover.fencingToken());
        harness.clock().failOnObservation();

        assertEquals(originalProgress, requireReplayed(harness.outcomes().appendProgress(progress)));
        assertEquals(originalResult, requireReplayed(harness.outcomes().recordResult(result)));
        assertEquals(List.of(originalProgress), requireFound(harness.outcomes().readProgress(toolCallId)));
        assertEquals(originalResult, requireFound(harness.outcomes().findResult(toolCallId)));
    }

    @Test
    void progressIsContiguousConflictsByIdentityAndCannotBeNewAfterFinalResult() {
        Harness harness = activated("sequence", 60);
        ToolCallId toolCallId = durableIntent(harness, "sequence");
        EffectProgressRequest first = progressRequest(
                "sequence-1", toolCallId, 1, TOKEN, harness.lease().fencingToken());
        PersistedEffectProgress firstPersisted = requireApplied(
                harness.outcomes().appendProgress(first));
        EffectProgressRequest second = progressRequest(
                "sequence-2", toolCallId, 2, TOKEN, harness.lease().fencingToken());
        requireApplied(harness.outcomes().appendProgress(second));

        assertFailure(harness.outcomes().appendProgress(progressRequest(
                "sequence-gap", toolCallId, 4, TOKEN, harness.lease().fencingToken())),
                PersistenceErrorCode.EFFECT_PROGRESS_OUT_OF_SEQUENCE,
                "request.progress.sequence");
        assertFailure(harness.outcomes().appendProgress(new EffectProgressRequest(
                progress("progress-sequence-1", toolCallId.value(), 1, "changed-detail"),
                TOKEN,
                harness.lease().fencingToken())),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.progress.details");
        assertEquals(firstPersisted, requireFound(harness.outcomes().readProgress(toolCallId)).get(0));

        requireApplied(harness.outcomes().recordResult(new EffectResultRequest(
                receipt("sequence", toolCallId.value()),
                TOKEN,
                harness.lease().fencingToken())));
        assertFailure(harness.outcomes().appendProgress(progressRequest(
                "sequence-after-final", toolCallId, 3, TOKEN, harness.lease().fencingToken())),
                PersistenceErrorCode.EFFECT_OUTCOME_FINALIZED,
                "request.progress.toolCallId");
    }

    @Test
    void newWritesRequireIntactIntentActivationAndCurrentLease() {
        Harness missing = activated("missing", 60);
        assertFailure(missing.outcomes().appendProgress(progressRequest(
                "missing", new ToolCallId("unknown-effect"), 1, TOKEN, 1)),
                PersistenceErrorCode.NOT_FOUND,
                "request.progress.toolCallId");
        assertFailure(missing.outcomes().recordResult(new EffectResultRequest(
                receipt("unknown-effect", "unknown-effect"), TOKEN, 1)),
                PersistenceErrorCode.NOT_FOUND,
                "request.receipt.toolCallId");

        Harness wrongLease = activated("wrong-lease", 60);
        ToolCallId wrongLeaseCall = durableIntent(wrongLease, "wrong-lease");
        assertFailure(wrongLease.outcomes().appendProgress(progressRequest(
                "wrong-lease", wrongLeaseCall, 1, "other-token", 1)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        assertFailure(wrongLease.outcomes().appendProgress(progressRequest(
                "wrong-fence", wrongLeaseCall, 1, TOKEN, 2)),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");

        Harness expired = activated("expired", 10);
        ToolCallId expiredCall = durableIntent(expired, "expired");
        expired.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        assertFailure(expired.outcomes().appendProgress(progressRequest(
                "expired", expiredCall, 1, TOKEN, 1)),
                PersistenceErrorCode.LEASE_EXPIRED,
                "effectIntent.planId");

        Harness corruptIntent = activated("corrupt-intent", 60);
        ToolCallId corruptCall = durableIntent(corruptIntent, "corrupt-intent");
        InMemoryState.EffectIntentMarker marker = corruptIntent.state().effectIntents.get(corruptCall);
        corruptIntent.state().effectIntents.put(corruptCall,
                new InMemoryState.EffectIntentMarker(
                        marker.request(),
                        new PersistedEffectIntent(
                                marker.result().intent(),
                                "tampered-owner",
                                marker.result().fencingToken(),
                                marker.result().activationEventId()),
                        marker.leaseOwnerId()));
        assertFailure(corruptIntent.outcomes().appendProgress(progressRequest(
                "corrupt-intent", corruptCall, 1, TOKEN, 1)),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");

        Harness wrongStep = activated("wrong-step", 60);
        ToolCallId wrongStepCall = durableIntent(wrongStep, "wrong-step");
        InMemoryState.EffectIntentMarker wrongStepMarker = wrongStep.state()
                .effectIntents.get(wrongStepCall);
        EffectIntent changedStep = new EffectIntent(
                wrongStepCall,
                wrongStep.plan().id(),
                PersistenceFixtures.STEP_2,
                "workspace.edit",
                new ObjectValue(Map.of("input", new TextValue("argument-wrong-step"))));
        wrongStep.state().effectIntents.put(wrongStepCall,
                new InMemoryState.EffectIntentMarker(
                        new EffectIntentRequest(
                                changedStep,
                                wrongStepMarker.request().leaseToken(),
                                wrongStepMarker.request().fencingToken(),
                                wrongStepMarker.request().expectedActivationEventId()),
                        new PersistedEffectIntent(
                                changedStep,
                                wrongStepMarker.result().leaseOwnerId(),
                                wrongStepMarker.result().fencingToken(),
                                wrongStepMarker.result().activationEventId()),
                        wrongStepMarker.leaseOwnerId()));
        assertFailure(wrongStep.outcomes().appendProgress(progressRequest(
                "wrong-step", wrongStepCall, 1, TOKEN, 1)),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "effectIntent.stepId");

        Harness corruptActivation = activated("corrupt-activation", 60);
        ToolCallId activationCall = durableIntent(corruptActivation, "corrupt-activation");
        corruptActivation.state().executionMutationHeads.remove(corruptActivation.plan().id());
        assertFailure(corruptActivation.outcomes().recordResult(new EffectResultRequest(
                receipt("corrupt-activation", activationCall.value()), TOKEN, 1)),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
    }

    @Test
    void resultOwnershipIsAtomicAndOrdinaryReceiptAppendCannotClaimAnEffect() {
        Harness harness = activated("ownership", 60);
        ToolCallId toolCallId = durableIntent(harness, "ownership");
        ExecutionReceipt owned = receipt("ownership", toolCallId.value());

        assertFailure(harness.receipts().append(owned),
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "receipt.toolCallId");
        assertTrue(harness.state().receipts.isEmpty());

        ExecutionReceipt unowned = receipt("preexisting", "unowned-call");
        requireApplied(harness.receipts().append(unowned));
        ExecutionReceipt sameId = receipt("preexisting", toolCallId.value());
        assertFailure(harness.outcomes().recordResult(new EffectResultRequest(
                sameId, TOKEN, harness.lease().fencingToken())),
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "request.receipt.id");
        assertEquals(unowned, requireFound(harness.receipts().find(unowned.id())));
        assertTrue(harness.state().effectResults.isEmpty());

        ExecutionReceipt ordinary = receipt("ordinary", "ordinary-call");
        assertEquals(ordinary, requireApplied(harness.receipts().append(ordinary)));
        assertEquals(ordinary, requireReplayed(harness.receipts().append(ordinary)));
    }

    @Test
    void aTornReceiptMarkerFailsClosedWithoutClockOrLeaseInspection() {
        Harness harness = activated("torn", 60);
        ToolCallId toolCallId = durableIntent(harness, "torn");
        EffectResultRequest request = new EffectResultRequest(
                receipt("torn", toolCallId.value()), TOKEN, harness.lease().fencingToken());
        requireApplied(harness.outcomes().recordResult(request));
        harness.state().receipts.clear();
        harness.clock().failOnObservation();

        assertFailure(harness.outcomes().recordResult(request),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
        assertFailure(harness.outcomes().findResult(toolCallId),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
    }

    private static Harness activated(String suffix, long leaseSeconds) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(), plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(), OWNER, TOKEN,
                PersistenceFixtures.T0.plusSeconds(leaseSeconds)));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("outcome-" + suffix));
        EventId activation = new EventId("activation-" + suffix);
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(), activation.value())));
        return new Harness(
                state,
                clock,
                plan,
                lease,
                leases,
                activation,
                new InMemoryEffectIntentRepository(state),
                new InMemoryEffectOutcomeRepository(state),
                new InMemoryReceiptRepository(state));
    }

    private static ToolCallId durableIntent(Harness harness, String suffix) {
        ToolCallId toolCallId = new ToolCallId("effect-" + suffix);
        EffectIntent intent = new EffectIntent(
                toolCallId,
                harness.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                new ObjectValue(Map.of("input", new TextValue("argument-" + suffix))));
        requireApplied(harness.intents().persist(new EffectIntentRequest(
                intent,
                TOKEN,
                harness.lease().fencingToken(),
                harness.activationEventId())));
        return toolCallId;
    }

    private static EffectProgressRequest progressRequest(
            String suffix,
            ToolCallId toolCallId,
            long sequence,
            String leaseToken,
            long fencingToken) {
        return new EffectProgressRequest(
                progress("progress-" + suffix, toolCallId.value(), sequence, "detail-" + suffix),
                leaseToken,
                fencingToken);
    }

    private static EffectProgress progress(
            String progressId,
            String toolCallId,
            long sequence,
            String detail) {
        return new EffectProgress(
                new EffectProgressId(progressId),
                new ToolCallId(toolCallId),
                sequence,
                PersistenceFixtures.T0.plusSeconds(sequence),
                new ObjectValue(Map.of("detail", new TextValue(detail))));
    }

    private static ExecutionReceipt receipt(String suffix, String toolCallId) {
        return new ExecutionReceipt(
                new ReceiptId("receipt-" + suffix),
                new ToolCallId(toolCallId),
                ReceiptStatus.SUCCESS,
                PersistenceFixtures.T0,
                PersistenceFixtures.T0.plusSeconds(1),
                Optional.of(0),
                Optional.empty(),
                OutputCapture.inline("stdout-opaque", false),
                OutputCapture.empty(),
                List.of(),
                Optional.empty(),
                List.of());
    }

    private static AuthoritySnapshot snapshot(InMemoryState state) {
        return new AuthoritySnapshot(
                state.eventsById.size(),
                state.eventStreams.values().stream().mapToInt(Map::size).sum(),
                state.checkpoints.get(PersistenceFixtures.plan().id()),
                state.executionMutationHeads.get(PersistenceFixtures.plan().id()),
                state.stepActivations.get(PersistenceFixtures.plan().id()),
                state.planExecutionContextReservations.get(PersistenceFixtures.plan().id()),
                state.planExecutionContextConfirmations.get(PersistenceFixtures.plan().id()),
                state.idempotency.size());
    }

    private static void assertExecutionAuthorityUnchanged(
            AuthoritySnapshot before,
            InMemoryState state) {
        assertEquals(before, snapshot(state));
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static <T> T requireReplayed(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.REPLAYED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static <T> T requireFound(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.FOUND, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        PersistenceFailure failure = result.failure().orElseThrow();
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }

    private record AuthoritySnapshot(
            int eventCount,
            int streamCount,
            VersionedCheckpoint checkpoint,
            InMemoryState.ExecutionMutationHead head,
            Map<EventId, InMemoryState.StepActivationMarker> activations,
            InMemoryState.PlanExecutionContextReservationMarker reservation,
            InMemoryState.PlanExecutionContextConfirmationMarker confirmation,
            int idempotencyCount) {
    }

    private record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            LeaseRecord lease,
            InMemoryLeaseRepository leases,
            EventId activationEventId,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ReceiptRepository receipts) {
    }
}
