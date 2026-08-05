package com.yanban.api.agent.v2.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.effect.project
        .NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.api.agent.v2.result.V2StepResultService;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import com.yanban.api.agent.v2.result.V2StepResultSource;
import com.yanban.api.agent.v2.result.V2StepResultStatus;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class V2ExecutionContextSourceTest {
    @Test
    void rebuildsAcceptedToolAndFullCandidateFactsWithoutUnrelatedHistory() {
        PlanId planId = new PlanId("plan-1");
        PlanStepId activeStep = new PlanStepId("step-4");
        ReceiptId sandboxReceipt = new ReceiptId("receipt-sandbox");
        V2EffectHistorySource effects = mock(V2EffectHistorySource.class);
        V2StepResultService results = mock(V2StepResultService.class);
        NaturalLanguageCandidateAuthorityStore candidates = mock(
                NaturalLanguageCandidateAuthorityStore.class);
        V2EffectHistorySource.Entry sandbox = completed(
                planId, new PlanStepId("step-3"),
                "sandbox-call", "sandbox.execute",
                sandboxReceipt, ReceiptStatus.SUCCESS,
                "PROGRAM_OK");
        V2EffectHistorySource.Entry unrelated = completed(
                planId, new PlanStepId("step-1"),
                "read-call", "project.read",
                new ReceiptId("receipt-read"),
                ReceiptStatus.SUCCESS, "UNRELATED_CONTENT");
        when(effects.inspect(planId)).thenReturn(
                List.of(unrelated, sandbox));
        when(results.acceptedCompletedFacts(planId)).thenReturn(
                List.of(accepted(planId, sandboxReceipt)));
        when(results.latestDecisionForActive(planId, activeStep))
                .thenReturn(Optional.empty());
        String replacement = "public class Sort {"
                + " // FULL_REPLACEMENT\n}";
        when(candidates.findPrepared("plan-1")).thenReturn(Optional.of(
                new NaturalLanguageCandidateAuthorityStore.Prepared(
                        Map.of("src/Sort.java", replacement),
                        "d".repeat(64))));

        var projection = new V2ExecutionContextSource(
                effects, results, candidates)
                .inspect(planId, activeStep);

        assertEquals(1, projection.acceptedStepResults().size());
        assertTrue(projection.acceptedStepResults().get(0)
                .contains("compiled and ran"));
        assertEquals(1, projection.relatedToolResults().size());
        assertTrue(projection.relatedToolResults().get(0)
                .contains("toolKind=sandbox.execute"));
        assertTrue(projection.relatedToolResults().get(0)
                .contains("PROGRAM_OK"));
        assertFalse(projection.relatedToolResults().get(0)
                .contains("UNRELATED_CONTENT"));
        assertTrue(projection.preparedCandidate().orElseThrow()
                .contains(replacement));
    }

    private static V2StepResultSnapshot accepted(
            PlanId planId, ReceiptId receiptId) {
        return new V2StepResultSnapshot(
                "result-3", planId,
                new PlanRevisionId("revision-3"),
                new PlanStepId("step-3"),
                new EventId("activation-3"),
                V2StepResultSource.REFLECTION,
                "compiled and ran", "a".repeat(64),
                List.of(receiptId), V2StepResultStatus.ACCEPTED,
                Optional.of("compiled and ran"),
                Optional.of("b".repeat(64)),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static V2EffectHistorySource.Entry completed(
            PlanId planId, PlanStepId stepId, String callId,
            String toolKind, ReceiptId receiptId,
            ReceiptStatus status, String stdout) {
        PersistedEffectIntent persistedIntent = mock(
                PersistedEffectIntent.class);
        when(persistedIntent.intent()).thenReturn(new EffectIntent(
                new ToolCallId(callId), planId, stepId, toolKind,
                new ObjectValue(Map.of(
                        "command", new TextValue("run exact code")))));
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(receiptId);
        when(receipt.status()).thenReturn(status);
        when(receipt.resultCode()).thenReturn(Optional.empty());
        when(receipt.exitCode()).thenReturn(Optional.of(0));
        when(receipt.standardOutput()).thenReturn(
                OutputCapture.inline(stdout, false));
        when(receipt.standardError()).thenReturn(OutputCapture.empty());
        when(receipt.artifactReferences()).thenReturn(List.of());
        when(receipt.resultingDiff()).thenReturn(Optional.empty());
        PersistedEffectResult persistedResult = mock(
                PersistedEffectResult.class);
        when(persistedResult.receipt()).thenReturn(receipt);
        return new V2EffectHistorySource.Entry(
                persistedIntent, persistedResult);
    }
}
