package com.yanban.api.agent.v2.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import org.junit.jupiter.api.Test;

class PersistentPlanAgentLoopReceiptFactsTest {

    @Test
    void completeOutputIsNotShortenedBeforeReflection() {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-output"));
        String complete = "x".repeat(64 * 1024);
        when(receipt.standardOutput()).thenReturn(
                OutputCapture.inline(complete, false));
        when(receipt.standardError()).thenReturn(OutputCapture.empty());
        when(receipt.status()).thenReturn(ReceiptStatus.SUCCESS);

        PersistentPlanAgentLoopReceiptFacts facts =
                PersistentPlanAgentLoopReceiptFacts.from(
                        "step-2", "sandbox.execute", receipt);

        assertEquals(complete, facts.standardOutput());
        assertEquals("step-2", facts.stepId());
        assertEquals("sandbox.execute", facts.toolKind());
        assertEquals("SANDBOX_EXECUTION_ONLY", facts.authorityScope());
    }

    @Test
    void upstreamTruncationRemainsVisibleEvenForShortInlineText() {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-truncated"));
        when(receipt.standardOutput()).thenReturn(
                OutputCapture.inline("partial", true));
        when(receipt.standardError()).thenReturn(OutputCapture.empty());
        when(receipt.status()).thenReturn(ReceiptStatus.SUCCESS);

        PersistentPlanAgentLoopReceiptFacts facts =
                PersistentPlanAgentLoopReceiptFacts.from(
                        "step-1", "project.read", receipt);

        assertEquals(
                "partial\n[OUTPUT_TRUNCATED]",
                facts.standardOutput());
    }

    @Test
    void missingToolKindIsRejectedBeforeReflection() {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-invalid-kind"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PersistentPlanAgentLoopReceiptFacts.from(
                        "step-1", " ", receipt));
    }

    @Test
    void candidateReceiptCarriesCandidateAuthorityScope() {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-candidate"));
        when(receipt.standardOutput()).thenReturn(OutputCapture.empty());
        when(receipt.standardError()).thenReturn(OutputCapture.empty());
        when(receipt.status()).thenReturn(ReceiptStatus.SUCCESS);

        PersistentPlanAgentLoopReceiptFacts facts =
                PersistentPlanAgentLoopReceiptFacts.from(
                        "step-4", "project.candidate.compose", receipt);

        assertEquals(
                "REVIEWABLE_CANDIDATE_CREATED", facts.authorityScope());
    }

    @Test
    void bibtexAuditReceiptCarriesReadOnlyBibliographyAuthorityScope() {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-bibtex"));
        when(receipt.standardOutput()).thenReturn(OutputCapture.empty());
        when(receipt.standardError()).thenReturn(OutputCapture.empty());
        when(receipt.status()).thenReturn(ReceiptStatus.SUCCESS);

        PersistentPlanAgentLoopReceiptFacts facts =
                PersistentPlanAgentLoopReceiptFacts.from(
                        "step-3", "project.bibtex.audit", receipt);

        assertEquals(
                "PROJECT_BIBLIOGRAPHY_READ_ONLY", facts.authorityScope());
    }

    @Test
    void failedCandidateReceiptDoesNotClaimCandidateCreation() {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(
                new ReceiptId("receipt-candidate-failed"));
        when(receipt.status()).thenReturn(ReceiptStatus.FAILURE);
        when(receipt.standardOutput()).thenReturn(OutputCapture.empty());
        when(receipt.standardError()).thenReturn(OutputCapture.inline(
                "Candidate already exists", false));

        PersistentPlanAgentLoopReceiptFacts facts =
                PersistentPlanAgentLoopReceiptFacts.from(
                        "step-3", "project.candidate.compose", receipt);

        assertEquals("FAILED_EFFECT_ONLY", facts.authorityScope());
    }
}
