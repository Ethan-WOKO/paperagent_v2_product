package com.yanban.api.agent.v2.progression;

import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EffectDrivenStepProgressionDraftsCompatibilityTest {
    @Test
    void singleReceiptIdentityRemainsByteCompatible() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();

        var completion = EffectDrivenStepProgressionDrafts.completion(
                fixture.recoveredA, fixture.intent, fixture.receipt);
        var activation = EffectDrivenStepProgressionDrafts.activation(
                fixture.readyB, fixture.intent, fixture.receipt,
                fixture.command().nextStepActivationAttempt());

        assertEquals(
                "sha256."
                        + "d3dd78b95a324a2fb9cd4416b3c0af5531bab10c"
                        + "f4607405e6b775005b34663c",
                completion.completionFactDraft().outcomeHash());
        assertEquals(
                "effect-completion-event."
                        + "37e43e5347e4825f8028ec9a649181214fd3277b5"
                        + "a15ec7630048f95573afd0e",
                completion.eventDraft().id().value());
        assertEquals(
                "effect-completion-revision."
                        + "d2cd2cbdd2e9dbb66967f1dab2c6082520a3c58c"
                        + "7b364127b110b14c9b05b416",
                completion.revisionDraft().id().value());
        assertEquals(
                "effect-next-activation-event."
                        + "03364134dcd30d183ee9b185fe496c1ef4413c6a5"
                        + "9d9489a844ab9801f132cc3",
                activation.eventDraft().id().value());
    }

    @Test
    void failedThenSuccessfulEffectsRemainCompleteOrderedEvidence() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        EffectDrivenStepEvidence failure = failure(fixture);
        EffectDrivenStepEvidence success = new EffectDrivenStepEvidence(
                fixture.intent, fixture.receipt);

        var completion = EffectDrivenStepProgressionDrafts.completion(
                fixture.recoveredA, List.of(success, failure));

        assertEquals(
                List.of(
                        failure.receipt().id(),
                        success.receipt().id()),
                completion.completionFactDraft().receiptReferences());
        assertEquals(
                fixture.receipt.endedAt(),
                completion.completionFactDraft().completedAt());
    }

    @Test
    void onlyFailedEffectsCannotCompleteAutonomousStep() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        EffectDrivenStepEvidence failure = failure(fixture);
        when(fixture.history.inspect(fixture.planId, fixture.A))
                .thenReturn(List.of(new V2EffectHistorySource.Entry(
                        failure.intent(),
                        new PersistedEffectResult(
                                failure.receipt(),
                                failure.intent().leaseOwnerId(),
                                failure.intent().fencingToken()))));
        fixture.inspections(fixture.activeA);
        var command = new EffectDrivenStepCompletionCommand(
                fixture.planId, fixture.A,
                fixture.command().currentStepRecoveryAttempt(),
                fixture.command().nextStepActivationAttempt());

        EffectDrivenStepProgressionException rejected = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.completeAll(7L, 42L, command));

        assertEquals("completion.successfulEvidence", rejected.path());
        verify(fixture.recoverer, never()).recover(any());
        verify(fixture.completion, never()).compose(any());
    }

    private static EffectDrivenStepEvidence failure(
            EffectDrivenStepProgressionTestFixtures fixture) {
        ToolCallId callId = new ToolCallId("tool-call-0");
        PersistedEffectIntent intent = new PersistedEffectIntent(
                new EffectIntent(
                        callId, fixture.planId, fixture.A,
                        "project.read", new ObjectValue(Map.of())),
                fixture.intent.leaseOwnerId(),
                fixture.intent.fencingToken(),
                fixture.intent.activationEventId());
        ExecutionReceipt receipt = new ExecutionReceipt(
                new ReceiptId("receipt-0"), callId,
                ReceiptStatus.FAILURE,
                fixture.receipt.startedAt().minusSeconds(2),
                fixture.receipt.endedAt().minusSeconds(2),
                Optional.of(1), Optional.of("FAILED"),
                OutputCapture.empty(),
                OutputCapture.inline("bounded error", false),
                List.of(), Optional.empty(), List.of());
        return new EffectDrivenStepEvidence(intent, receipt);
    }
}
