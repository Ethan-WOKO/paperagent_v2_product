package io.paperagent.v2.runtime.execution.activation.materialization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicReadyStepActivationMaterializerTest {
    @Test
    void activatesDeterministicReadyStepFromDynamicHead() {
        var ready =
                CommittedStepActivationMaterializationFixture
                        .laterReady("later");
        var result = new DeterministicReadyStepActivationMaterializer()
                .materialize(new ReadyStepActivationMaterializationRequest(
                        ready,
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("later"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(3)));

        assertEquals(4, result.activationEvent().sequence());
        assertEquals(4,
                result.activatedCheckpoint().lastEventSequence());
        assertEquals(
                io.paperagent.v2.contracts.StepExecutionState.ACTIVE,
                result.activatedCheckpoint().stepStates()
                        .get(ready.readyStepId()));
        var completedStep = ready.plan().latestRevision().steps().get(0).id();
        assertEquals(
                io.paperagent.v2.contracts.StepExecutionState.SUCCEEDED,
                result.activatedCheckpoint().stepStates().get(completedStep));
        assertEquals(
                ready.checkpoint().checkpoint().receiptReferences(),
                result.activatedCheckpoint().receiptReferences());
    }
}
