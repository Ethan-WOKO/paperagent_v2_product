package com.yanban.api.agent.v2.chain.finalization;

import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainTerminalOutcomeAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String REQUEST = "b".repeat(64);
    private static final String RECEIPT = "c".repeat(64);
    private static final String PUBLISH = "d".repeat(64);

    @Test
    void resolvesExactRootPublishedVersionAndFinalActivation() {
        Fixture fixture = fixture();
        fixture.events(activation("step.final", "activation.final", 1),
                completion("step.final", "activation.final", 2));

        var facts = fixture.authority.requireExact(task(), outcome(
                "check.1", "readiness.1", ChainTaskOutcomeStatus.COMPLETED));

        assertEquals("readiness.1", facts.readiness().readinessId());
        assertEquals("check.1", facts.check().finalizationCheckId());
        assertEquals("step.final", facts.finalStepId());
        assertEquals("activation.final", facts.activationEventId());
        assertEquals("version.published", facts.effectiveProjectVersion());
        assertEquals("validation.1", facts.validation().validationId());
        assertEquals(REQUEST, facts.validation().requestDigest());
        assertEquals(RECEIPT, facts.validation().receiptDigest());
    }

    @Test
    void rejectsWrongCheckId() {
        Fixture fixture = fixture();
        fixture.events(activation("step.final", "activation.final", 1),
                completion("step.final", "activation.final", 2));

        var failure = assertThrows(IllegalStateException.class,
                () -> fixture.authority.requireExact(task(), outcome(
                        "check.other", "readiness.1",
                        ChainTaskOutcomeStatus.COMPLETED)));

        assertEquals("CHAIN_TERMINAL_CHECK_NOT_EXACT", failure.getMessage());
    }

    @Test
    void doesNotSubstituteALaterActivationOfAnotherStep() {
        Fixture fixture = fixture();
        fixture.events(activation("step.other", "activation.later", 3));

        var failure = assertThrows(IllegalStateException.class,
                () -> fixture.authority.requireExact(task(), outcome(
                        "check.1", "readiness.1",
                        ChainTaskOutcomeStatus.COMPLETED)));

        assertEquals("CHAIN_TERMINAL_STEP_COMPLETION_NOT_EXACT",
                failure.getMessage());
    }

    @Test
    void rejectsSupersededFinalActivation() {
        Fixture fixture = fixture();
        fixture.events(activation("step.final", "activation.final", 1),
                completion("step.final", "activation.final", 2),
                superseded("step.final", "activation.final", 3));

        var failure = assertThrows(IllegalStateException.class,
                () -> fixture.authority.requireExact(task(), outcome(
                        "check.1", "readiness.1",
                        ChainTaskOutcomeStatus.COMPLETED)));

        assertEquals("CHAIN_TERMINAL_STEP_ACTIVATION_NOT_EXACT",
                failure.getMessage());
    }

    @Test
    void rejectsCompletedOutcomeWithoutFinalizationRoot() {
        Fixture fixture = fixture();
        var rootless = new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome.1", "task.1", "outcome.event", "command.1",
                ChainTaskOutcomeStatus.COMPLETED, "instruction.1", "frame.1",
                "plan.1", "revision.1", json("[]"), json("[]"),
                11L, "candidate.1", "validation.1", null, null, null, null,
                json("[]"), json("[]"), json("[]"), null, null,
                "transition.1", NOW);

        var failure = assertThrows(IllegalStateException.class,
                () -> fixture.authority.requireExact(task(), rootless));

        assertEquals("CHAIN_TERMINAL_OUTCOME_ROOT_MISSING",
                failure.getMessage());
    }

    private static Fixture fixture() {
        ChainFinalizationRepository finalization =
                mock(ChainFinalizationRepository.class);
        ProductChainStepAuthorityAdapter steps =
                mock(ProductChainStepAuthorityAdapter.class);
        var readiness = readiness();
        when(finalization.findReadinessById("readiness.1"))
                .thenReturn(Optional.of(readiness));
        when(finalization.findFinalizationChecks("readiness.1"))
                .thenReturn(List.of(check()));
        return new Fixture(new ProductChainTerminalOutcomeAuthority(
                finalization, steps), steps);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                1L, 2L, 3L, 4L, "request.1", HASH,
                5L, "version.base", 1L, NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            readiness() {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness.1", "task.1", "readiness.event", "transition.1",
                HASH, "frame.1", "plan.1", "revision.1", 1L,
                "step.final", "review.1", json("[]"), 1L,
                11L, "candidate.1", "workspace.1", "validation.1",
                REQUEST, RECEIPT, json("[]"), ChainPublishRequirement.REQUIRED,
                PUBLISH, "instruction.1", "version.base", NOW);
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord check() {
        return new ChainPersistenceRecords.FinalizationCheckRecord(
                "check.1", "task.1", "check.event", "readiness.1",
                "transition.1", 1, "frame.1", "revision.1", HASH,
                "candidate.1", "workspace.1", "validation.1", REQUEST,
                RECEIPT, PUBLISH, "instruction.1", "version.base",
                HASH, HASH, HASH, ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                "chain-runtime-policy-v1", NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord outcome(
            String checkId, String readinessId,
            ChainTaskOutcomeStatus status) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome.1", "task.1", "outcome.event", "command.1", status,
                "instruction.1", "frame.1", "plan.1", "revision.1",
                json("[]"), json("[]"), 11L, "candidate.1", readinessId,
                checkId, "validation.1", REQUEST, RECEIPT,
                ChainPublishRequirement.REQUIRED, PUBLISH,
                "project-revision-operation:7", "version.published", 8L,
                "project-revision-operation:7", json("[]"), json("[]"),
                json("[]"), null, null, "transition.1", NOW);
    }

    private static ChainStepAuthorityPort.StepEvent activation(
            String stepId, String activationId, long sequence) {
        return event(activationId, stepId, activationId,
                ChainStepAuthorityPort.StepEventKind.ACTIVATED, sequence);
    }

    private static ChainStepAuthorityPort.StepEvent completion(
            String stepId, String activationId, long sequence) {
        return event("completion." + sequence, stepId, activationId,
                ChainStepAuthorityPort.StepEventKind.COMPLETED, sequence);
    }

    private static ChainStepAuthorityPort.StepEvent superseded(
            String stepId, String activationId, long sequence) {
        return event("superseded." + sequence, stepId, activationId,
                ChainStepAuthorityPort.StepEventKind.SUPERSEDED_BY_REPLAN,
                sequence);
    }

    private static ChainStepAuthorityPort.StepEvent event(
            String eventId, String stepId, String activationId,
            ChainStepAuthorityPort.StepEventKind kind, long sequence) {
        return new ChainStepAuthorityPort.StepEvent(
                new ChainStepAuthorityPort.StepEventCommand(
                        eventId, "task.1", "revision.1", stepId,
                        activationId, kind, "review.1", "transition.1",
                        NOW.plusSeconds(sequence)), sequence);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, value);
    }

    private record Fixture(
            ProductChainTerminalOutcomeAuthority authority,
            ProductChainStepAuthorityAdapter steps) {
        void events(ChainStepAuthorityPort.StepEvent... values) {
            when(steps.findStepEvents("task.1", "revision.1"))
                    .thenReturn(List.of(values));
        }
    }
}
