package io.paperagent.v2.chain.step;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChainActionProgressIdentityTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void ignoresPerAttemptIdsButRetainsSemanticOutcome() {
        String signature = "a".repeat(64);

        String first = ChainActionProgressIdentity.receipt(
                signature, receipt("action-1", "receipt-1", "COMPILE_FAILED", 1));
        String replayedMeaning = ChainActionProgressIdentity.receipt(
                signature, receipt("action-2", "receipt-2", "COMPILE_FAILED", 1));
        String changedResult = ChainActionProgressIdentity.receipt(
                signature, receipt("action-3", "receipt-3", "RUN_FAILED", 1));

        assertEquals(first, replayedMeaning);
        assertNotEquals(first, changedResult);
    }

    @Test
    void candidateFailureUsesActionSignatureAndTypedFailureCode() {
        String signature = "a".repeat(64);
        String first = ChainActionProgressIdentity.candidateFailure(
                signature, "CANDIDATE_NO_ACTUAL_CHANGE");

        assertEquals(first, ChainActionProgressIdentity.candidateFailure(
                signature, "CANDIDATE_NO_ACTUAL_CHANGE"));
        assertNotEquals(first, ChainActionProgressIdentity.candidateFailure(
                "b".repeat(64), "CANDIDATE_NO_ACTUAL_CHANGE"));
        assertNotEquals(first, ChainActionProgressIdentity.candidateFailure(
                signature, "CANDIDATE_REPLACEMENT_BUNDLE_INVALID"));
    }

    @Test
    void newOutputArtifactsDiffOrCandidateEvidenceCountsAsProgress() {
        String signature = "a".repeat(64);
        ExecutionReceipt baseline = receipt(
                "action-1", "receipt-1", "COMPILE_FAILED", 1);
        ExecutionReceipt outputChanged = new ExecutionReceipt(
                baseline.id(), baseline.toolCallId(), baseline.status(),
                baseline.startedAt(), baseline.endedAt(), baseline.exitCode(),
                baseline.resultCode(), OutputCapture.inline("new output", false),
                baseline.standardError(), List.of(new ArtifactRef("artifact-1")),
                Optional.of(new DiffId("diff-1")), List.of());

        String first = ChainActionProgressIdentity.receipt(
                signature, baseline, List.of("b".repeat(64)));
        String changed = ChainActionProgressIdentity.receipt(
                signature, outputChanged, List.of("c".repeat(64)));

        assertNotEquals(first, changed);
        assertNotEquals(
                ChainActionProgressIdentity.receipt(
                        signature, baseline, List.of("b".repeat(64))),
                ChainActionProgressIdentity.receipt(
                        signature, baseline, List.of("c".repeat(64))));
    }

    private static ExecutionReceipt receipt(
            String actionId, String receiptId, String resultCode, int exitCode) {
        return new ExecutionReceipt(
                new ReceiptId(receiptId), new ToolCallId(actionId),
                ReceiptStatus.FAILURE, NOW, NOW.plusSeconds(1),
                Optional.of(exitCode), Optional.of(resultCode),
                OutputCapture.empty(), OutputCapture.empty(), List.of(),
                Optional.empty(), List.of());
    }

}
