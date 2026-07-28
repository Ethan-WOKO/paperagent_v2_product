package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopNoEffect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class BoundedStepReplanCompositionTest {

    @Test
    void mapsAppliedReplanFromGenuineFirstTurnNoEffectWithoutFabricatingTurnLimit() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("no-effect-applied");
        var persisted = BoundedStepReplanCompositionTestFixtures.persisted(
                scenario.recovered(), scenario.request());
        var repository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(PersistenceResult.applied(persisted));
        var composer = new DefaultBoundedStepReplanComposer(repository);
        var noEffect = new BoundedStepAgentLoopNoEffect(
                scenario.recovered().planId(),
                scenario.recovered().recovery().activation().stepId(),
                1,
                List.of());

        var outcome = composer.composeNoEffect(
                scenario.recovered(), noEffect, scenario.request());

        var applied = assertInstanceOf(BoundedStepReplanApplied.class, outcome);
        assertSame(persisted, applied.persistedReplan());
        assertEquals(1, repository.calls());
        assertEquals(List.of(scenario.request()), repository.requests());
    }

    @Test
    void mapsAppliedReplanAfterOneUntouchedRepositoryCall() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("applied");
        var persisted = BoundedStepReplanCompositionTestFixtures.persisted(
                scenario.recovered(), scenario.request());
        var repository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(PersistenceResult.applied(persisted));
        var composer = new DefaultBoundedStepReplanComposer(repository);

        var outcome = composer.compose(
                scenario.recovered(), scenario.turnLimitReached(), scenario.request());

        var applied = assertInstanceOf(BoundedStepReplanApplied.class, outcome);
        assertSame(persisted, applied.persistedReplan());
        assertEquals(1, repository.calls());
        assertEquals(java.util.List.of(scenario.request()), repository.requests());
    }

    @Test
    void mapsReplayedReplanToItsDistinctOutcomeAfterOneCall() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("replayed");
        var persisted = BoundedStepReplanCompositionTestFixtures.persisted(
                scenario.recovered(), scenario.request());
        var repository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(PersistenceResult.replayed(persisted));
        var composer = new DefaultBoundedStepReplanComposer(repository);

        var outcome = composer.compose(
                scenario.recovered(), scenario.turnLimitReached(), scenario.request());

        var replayed = assertInstanceOf(BoundedStepReplanReplayed.class, outcome);
        assertSame(persisted, replayed.persistedReplan());
        assertEquals(1, repository.calls());
        assertEquals(java.util.List.of(scenario.request()), repository.requests());
    }

    @Test
    void returnsUnsettledEffectRejectionWithoutRetryOrEffectExecution() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("unsettled");
        var repository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(
                        BoundedStepReplanCompositionTestFixtures
                                .unsettledEffectRejection());
        var composer = new DefaultBoundedStepReplanComposer(repository);

        var outcome = composer.compose(
                scenario.recovered(), scenario.turnLimitReached(), scenario.request());

        var rejected = assertInstanceOf(
                BoundedStepReplanPersistenceRejected.class, outcome);
        assertEquals(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                rejected.failure().code());
        assertEquals("activeStepReplan.settledEffects", rejected.failure().path());
        assertEquals(1, repository.calls());
    }
}
