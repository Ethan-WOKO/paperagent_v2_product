package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded authoritative receipt facts exposed by one loop cycle. */
public record PersistentPlanAgentLoopReceiptFacts(
        String stepId,
        String receiptId,
        String toolKind,
        String authorityScope,
        String toolCallId,
        String status,
        Optional<String> resultCode,
        Optional<Integer> exitCode,
        String standardOutput,
        String standardError,
        List<String> artifactReferences,
        Optional<String> diffId) {
    private static final String TRUNCATION_MARKER =
            "\n[OUTPUT_TRUNCATED]";

    public PersistentPlanAgentLoopReceiptFacts {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId is required");
        }
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("receiptId is required");
        }
        if (toolKind == null || toolKind.isBlank()) {
            throw new IllegalArgumentException("toolKind is required");
        }
        if (authorityScope == null || authorityScope.isBlank()) {
            throw new IllegalArgumentException(
                    "authorityScope is required");
        }
        resultCode = Objects.requireNonNull(resultCode, "resultCode");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        artifactReferences = List.copyOf(artifactReferences);
        diffId = Objects.requireNonNull(diffId, "diffId");
    }

    static PersistentPlanAgentLoopReceiptFacts from(
            String stepId, String toolKind, ExecutionReceipt receipt) {
        return new PersistentPlanAgentLoopReceiptFacts(
                stepId,
                receipt.id().value(),
                toolKind,
                authorityScope(toolKind, receipt),
                receipt.toolCallId() == null
                        ? "unknown" : receipt.toolCallId().value(),
                receipt.status() == null
                        ? "UNKNOWN" : receipt.status().name(),
                receipt.resultCode() == null
                        ? Optional.empty() : receipt.resultCode(),
                receipt.exitCode() == null
                        ? Optional.empty() : receipt.exitCode(),
                receipt.standardOutput() == null
                        ? "" : capture(receipt.standardOutput()),
                receipt.standardError() == null
                        ? "" : capture(receipt.standardError()),
                receipt.artifactReferences() == null
                        ? List.of()
                        : receipt.artifactReferences().stream()
                        .map(value -> value.value()).toList(),
                receipt.resultingDiff() == null
                        ? Optional.empty()
                        : receipt.resultingDiff()
                                .map(value -> value.value()));
    }

    private static String authorityScope(
            String toolKind, ExecutionReceipt receipt) {
        if (receipt.status()
                != io.paperagent.v2.contracts.ReceiptStatus.SUCCESS) {
            return "FAILED_EFFECT_ONLY";
        }
        return switch (toolKind) {
            case "project.read" -> "PROJECT_CONTENT_READ_ONLY";
            case "project.search" -> "PROJECT_SEARCH_ONLY";
            case "project.bibtex.audit" ->
                    "PROJECT_BIBLIOGRAPHY_READ_ONLY";
            case "project.candidate.compose" ->
                    "REVIEWABLE_CANDIDATE_CREATED";
            case "sandbox.execute" -> "SANDBOX_EXECUTION_ONLY";
            case "literature.search" -> "LITERATURE_SEARCH_ONLY";
            default -> "UNCLASSIFIED_EFFECT";
        };
    }

    private static String capture(OutputCapture output) {
        String value = output.inlineText().orElseGet(() ->
                output.artifactRef()
                        .map(ref -> "[artifact:" + ref.value() + "]")
                        .orElse(""));
        if (!output.truncated()) {
            return value;
        }
        return value + TRUNCATION_MARKER;
    }
}
