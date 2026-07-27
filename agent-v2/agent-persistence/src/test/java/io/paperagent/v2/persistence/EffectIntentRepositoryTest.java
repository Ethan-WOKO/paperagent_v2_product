package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectIntentRepositoryTest {
    private static final String OWNER = "effect-owner-a";
    private static final String TOKEN = "effect-token-a";

    @Test
    void publicValuesValidateAndRedactEveryPersistenceSurface() {
        EffectIntent intent = intent(
                PersistenceFixtures.plan(),
                "effect-call-opaque",
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "secret-argument-opaque");
        EffectIntentRequest request = new EffectIntentRequest(
                intent,
                "secret-token-opaque",
                7,
                new EventId("activation-opaque"));
        PersistedEffectIntent persisted = new PersistedEffectIntent(
                intent,
                "secret-owner-opaque",
                7,
                new EventId("activation-opaque"));

        assertNotNull(new InMemoryPersistence().effectIntents());
        for (Object value : List.of(request, persisted)) {
            String text = value.toString();
            assertFalse(text.contains("effect-call-opaque"), text);
            assertFalse(text.contains("secret-argument-opaque"), text);
            assertFalse(text.contains("secret-token-opaque"), text);
            assertFalse(text.contains("secret-owner-opaque"), text);
            assertFalse(text.contains("activation-opaque"), text);
        }
        assertThrows(NullPointerException.class, () ->
                new EffectIntentRequest(null, TOKEN, 1, new EventId("activation-null")));
        assertThrows(IllegalArgumentException.class, () ->
                new EffectIntentRequest(intent, " ", 1, new EventId("activation-blank")));
        assertThrows(IllegalArgumentException.class, () ->
                new EffectIntentRequest(intent, TOKEN, 0, new EventId("activation-fence")));
        assertThrows(NullPointerException.class, () ->
                new PersistedEffectIntent(intent, OWNER, 1, null));
    }

    @Test
    void firstPersistFindAndReplayLeaveExecutionAuthorityUntouched() {
        Harness harness = activated("happy", 60);
        EffectIntentRequest request = request(
                harness,
                "effect-call-happy",
                harness.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-happy",
                TOKEN,
                harness.lease().fencingToken(),
                harness.activationEventId());
        AuthoritySnapshot before = authoritySnapshot(harness.state());

        PersistedEffectIntent applied = requireApplied(
                harness.effectIntents().persist(request));

        assertEquals(request.intent(), applied.intent());
        assertEquals(OWNER, applied.leaseOwnerId());
        assertEquals(harness.lease().fencingToken(), applied.fencingToken());
        assertEquals(harness.activationEventId(), applied.activationEventId());
        assertEquals(PersistenceOutcome.FOUND,
                harness.effectIntents().find(request.intent().toolCallId()).outcome());
        assertAuthorityUnchanged(before, harness.state());
        assertEquals(1, harness.state().effectIntents.size());
        assertTrue(harness.state().idempotency.isEmpty());

        AuthoritySnapshot afterApplied = authoritySnapshot(harness.state());
        PersistenceResult<PersistedEffectIntent> replay =
                harness.effectIntents().persist(request);
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(applied, replay.value().orElseThrow());
        assertAuthorityUnchanged(afterApplied, harness.state());
        assertEquals(1, harness.state().effectIntents.size());
    }

    @Test
    void exactReplayAndFindSurviveLeaseTakeoverWithoutClockAccess() {
        Harness harness = activated("takeover", 10);
        EffectIntentRequest request = request(
                harness,
                "effect-call-takeover",
                harness.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-takeover",
                TOKEN,
                harness.lease().fencingToken(),
                harness.activationEventId());
        PersistedEffectIntent original = requireApplied(
                harness.effectIntents().persist(request));
        harness.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        LeaseRecord takeover = requireApplied(harness.leases().acquire(
                harness.plan().id(),
                "effect-owner-b",
                "effect-token-b",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));
        assertEquals(2, takeover.fencingToken());
        AuthoritySnapshot beforeReplay = authoritySnapshot(harness.state());
        harness.clock().failOnObservation();

        PersistenceResult<PersistedEffectIntent> replay =
                harness.effectIntents().persist(request);
        PersistenceResult<PersistedEffectIntent> found =
                harness.effectIntents().find(request.intent().toolCallId());

        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(PersistenceOutcome.FOUND, found.outcome());
        assertEquals(original, replay.value().orElseThrow());
        assertEquals(original, found.value().orElseThrow());
        assertAuthorityUnchanged(beforeReplay, harness.state());
    }

    @Test
    void changedRequestFieldsConflictAtTheirExactPathsAndCannotOverwrite() {
        Harness harness = activated("conflicts", 60);
        EffectIntentRequest original = request(
                harness,
                "effect-call-conflicts",
                harness.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-original",
                TOKEN,
                harness.lease().fencingToken(),
                harness.activationEventId());
        PersistedEffectIntent persisted = requireApplied(
                harness.effectIntents().persist(original));

        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        new PlanId("plan-other"),
                        PersistenceFixtures.STEP_1,
                        "workspace.edit",
                        "input-original",
                        TOKEN,
                        1,
                        harness.activationEventId())),
                "request.intent.planId");
        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        harness.plan().id(),
                        PersistenceFixtures.STEP_2,
                        "workspace.edit",
                        "input-original",
                        TOKEN,
                        1,
                        harness.activationEventId())),
                "request.intent.stepId");
        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        harness.plan().id(),
                        PersistenceFixtures.STEP_1,
                        "workspace.read",
                        "input-original",
                        TOKEN,
                        1,
                        harness.activationEventId())),
                "request.intent.kind");
        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        harness.plan().id(),
                        PersistenceFixtures.STEP_1,
                        "workspace.edit",
                        "input-changed",
                        TOKEN,
                        1,
                        harness.activationEventId())),
                "request.intent.arguments");
        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        harness.plan().id(),
                        PersistenceFixtures.STEP_1,
                        "workspace.edit",
                        "input-original",
                        TOKEN,
                        1,
                        new EventId("activation-other"))),
                "request.expectedActivationEventId");
        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        harness.plan().id(),
                        PersistenceFixtures.STEP_1,
                        "workspace.edit",
                        "input-original",
                        "effect-token-other",
                        1,
                        harness.activationEventId())),
                "request.leaseToken");
        assertConflict(
                harness.effectIntents().persist(request(
                        harness,
                        "effect-call-conflicts",
                        harness.plan().id(),
                        PersistenceFixtures.STEP_1,
                        "workspace.edit",
                        "input-original",
                        TOKEN,
                        2,
                        harness.activationEventId())),
                "request.fencingToken");

        assertEquals(persisted,
                harness.effectIntents().find(original.intent().toolCallId())
                        .value().orElseThrow());
        assertEquals(1, harness.state().effectIntents.size());
    }

    @Test
    void missingWrongOrCorruptAuthorityAndInvalidLeaseFailClosed() {
        Harness missing = activated("missing", 60);
        assertFailure(missing.effectIntents().persist(request(
                missing,
                "effect-call-missing",
                missing.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-missing",
                TOKEN,
                1,
                new EventId("activation-not-found"))),
                PersistenceErrorCode.NOT_FOUND,
                "request.expectedActivationEventId");

        Harness wrongStep = activated("wrong-step", 60);
        assertFailure(wrongStep.effectIntents().persist(request(
                wrongStep,
                "effect-call-wrong-step",
                wrongStep.plan().id(),
                PersistenceFixtures.STEP_2,
                "workspace.edit",
                "input-wrong-step",
                TOKEN,
                1,
                wrongStep.activationEventId())),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "request.intent.stepId");

        Harness token = activated("bad-token", 60);
        assertFailure(token.effectIntents().persist(request(
                token,
                "effect-call-bad-token",
                token.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-token",
                "effect-token-wrong",
                1,
                token.activationEventId())),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");

        Harness fence = activated("bad-fence", 60);
        assertFailure(fence.effectIntents().persist(request(
                fence,
                "effect-call-bad-fence",
                fence.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-fence",
                TOKEN,
                2,
                fence.activationEventId())),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");

        Harness expired = activated("expired", 10);
        expired.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        assertFailure(expired.effectIntents().persist(request(
                expired,
                "effect-call-expired",
                expired.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-expired",
                TOKEN,
                1,
                expired.activationEventId())),
                PersistenceErrorCode.LEASE_EXPIRED,
                "request.intent.planId");

        Harness corrupt = activated("corrupt", 60);
        corrupt.state().executionMutationHeads.remove(corrupt.plan().id());
        assertFailure(corrupt.effectIntents().persist(request(
                corrupt,
                "effect-call-corrupt",
                corrupt.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-corrupt",
                TOKEN,
                1,
                corrupt.activationEventId())),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");

        for (Harness harness : List.of(missing, wrongStep, token, fence, expired, corrupt)) {
            assertTrue(harness.state().effectIntents.isEmpty());
        }
    }

    @Test
    void corruptDurableOwnerFailsClosedWithoutClockOrLiveLeaseInspection() {
        Harness harness = activated("corrupt-owner", 60);
        EffectIntentRequest request = request(
                harness,
                "effect-call-corrupt-owner",
                harness.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-owner",
                TOKEN,
                1,
                harness.activationEventId());
        requireApplied(harness.effectIntents().persist(request));
        InMemoryState.EffectIntentMarker original = harness.state()
                .effectIntents.get(request.intent().toolCallId());
        PersistedEffectIntent altered = new PersistedEffectIntent(
                original.result().intent(),
                "effect-owner-altered",
                original.result().fencingToken(),
                original.result().activationEventId());
        harness.state().effectIntents.put(request.intent().toolCallId(),
                new InMemoryState.EffectIntentMarker(
                        original.request(), altered, original.leaseOwnerId()));
        harness.clock().failOnObservation();

        assertFailure(harness.effectIntents().persist(request),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
        assertFailure(harness.effectIntents().find(request.intent().toolCallId()),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
    }

    @Test
    void findNeedsNoLeaseAndPreservesExistingNotFoundAndInvalidArgumentPaths() {
        Harness harness = activated("find", 60);
        EffectIntentRequest request = request(
                harness,
                "effect-call-find",
                harness.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                "input-find",
                TOKEN,
                1,
                harness.activationEventId());
        PersistedEffectIntent persisted = requireApplied(
                harness.effectIntents().persist(request));
        requireApplied(harness.leases().release(harness.plan().id(), TOKEN));
        harness.clock().failOnObservation();

        assertEquals(persisted,
                harness.effectIntents().find(request.intent().toolCallId())
                        .value().orElseThrow());
        assertFailure(harness.effectIntents().find(new ToolCallId("effect-call-absent")),
                PersistenceErrorCode.NOT_FOUND,
                "toolCallId");
        assertFailure(harness.effectIntents().find(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "toolCallId");
        assertFailure(harness.effectIntents().persist(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request");
    }

    private static Harness activated(String suffix, long leaseSeconds) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(),
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plusSeconds(leaseSeconds)));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("effect-" + suffix));
        EventId activationEventId = new EventId("activation-" + suffix);
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan,
                        TOKEN,
                        lease.fencingToken(),
                        activationEventId.value())));
        return new Harness(
                state,
                clock,
                plan,
                lease,
                leases,
                new InMemoryEffectIntentRepository(state),
                activationEventId);
    }

    private static EffectIntentRequest request(
            Harness harness,
            String toolCallId,
            PlanId planId,
            PlanStepId stepId,
            String kind,
            String argument,
            String leaseToken,
            long fencingToken,
            EventId activationEventId) {
        return new EffectIntentRequest(
                intent(harness.plan(), toolCallId, planId, stepId, kind, argument),
                leaseToken,
                fencingToken,
                activationEventId);
    }

    private static EffectIntent intent(
            Plan plan,
            String toolCallId,
            PlanStepId stepId,
            String kind,
            String argument) {
        return intent(plan, toolCallId, plan.id(), stepId, kind, argument);
    }

    private static EffectIntent intent(
            Plan plan,
            String toolCallId,
            PlanId planId,
            PlanStepId stepId,
            String kind,
            String argument) {
        return new EffectIntent(
                new ToolCallId(toolCallId),
                planId,
                stepId,
                kind,
                new ObjectValue(Map.of("input", new TextValue(argument))));
    }

    private static void assertConflict(
            PersistenceResult<?> result,
            String path) {
        assertFailure(result, PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
        assertTrue(result.value().isEmpty());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static AuthoritySnapshot authoritySnapshot(InMemoryState state) {
        return new AuthoritySnapshot(
                new LinkedHashMap<>(state.plans),
                new LinkedHashMap<>(state.receipts),
                new LinkedHashMap<>(state.leases),
                new LinkedHashMap<>(state.planBootstraps),
                new LinkedHashMap<>(state.executionStarts),
                new LinkedHashMap<>(state.eventsById),
                new LinkedHashMap<>(state.eventStreams),
                new LinkedHashMap<>(state.checkpoints),
                new LinkedHashMap<>(state.stepActivations),
                new LinkedHashMap<>(state.executionMutationLinks),
                new LinkedHashMap<>(state.executionMutationHeads),
                new LinkedHashMap<>(state.planExecutionContextReservations),
                new LinkedHashMap<>(state.planExecutionContextConfirmations),
                new LinkedHashMap<>(state.workspaceOwners),
                new LinkedHashMap<>(state.idempotency));
    }

    private static void assertAuthorityUnchanged(
            AuthoritySnapshot expected,
            InMemoryState state) {
        assertEquals(expected.plans(), state.plans);
        assertEquals(expected.receipts(), state.receipts);
        assertEquals(expected.leases(), state.leases);
        assertEquals(expected.bootstraps(), state.planBootstraps);
        assertEquals(expected.starts(), state.executionStarts);
        assertEquals(expected.eventsById(), state.eventsById);
        assertEquals(expected.eventStreams(), state.eventStreams);
        assertEquals(expected.checkpoints(), state.checkpoints);
        assertEquals(expected.activations(), state.stepActivations);
        assertEquals(expected.links(), state.executionMutationLinks);
        assertEquals(expected.heads(), state.executionMutationHeads);
        assertEquals(expected.contextReservations(), state.planExecutionContextReservations);
        assertEquals(expected.contextConfirmations(), state.planExecutionContextConfirmations);
        assertEquals(expected.workspaceOwners(), state.workspaceOwners);
        assertEquals(expected.idempotency(), state.idempotency);
    }

    private record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            LeaseRecord lease,
            InMemoryLeaseRepository leases,
            EffectIntentRepository effectIntents,
            EventId activationEventId) {
    }

    private record AuthoritySnapshot(
            Map<?, ?> plans,
            Map<?, ?> receipts,
            Map<?, ?> leases,
            Map<?, ?> bootstraps,
            Map<?, ?> starts,
            Map<?, ?> eventsById,
            Map<?, ?> eventStreams,
            Map<?, ?> checkpoints,
            Map<?, ?> activations,
            Map<?, ?> links,
            Map<?, ?> heads,
            Map<?, ?> contextReservations,
            Map<?, ?> contextConfirmations,
            Map<?, ?> workspaceOwners,
            Map<?, ?> idempotency) {
    }
}
