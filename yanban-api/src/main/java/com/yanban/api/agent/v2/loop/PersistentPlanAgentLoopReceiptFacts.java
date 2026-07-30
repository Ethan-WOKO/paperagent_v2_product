package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded authoritative receipt facts exposed by one loop cycle. */
public record PersistentPlanAgentLoopReceiptFacts(
        String toolCallId,
        String status,
        Optional<String> resultCode,
        Optional<Integer> exitCode,
        String standardOutput,
        String standardError,
        List<String> artifactReferences,
        Optional<String> diffId) {
    private static final int MAX_CAPTURE = 2_000;

    public PersistentPlanAgentLoopReceiptFacts {
        resultCode = Objects.requireNonNull(resultCode, "resultCode");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        artifactReferences = List.copyOf(artifactReferences);
        diffId = Objects.requireNonNull(diffId, "diffId");
    }

    static PersistentPlanAgentLoopReceiptFacts from(
            ExecutionReceipt receipt) {
        return new PersistentPlanAgentLoopReceiptFacts(
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

    private static String capture(OutputCapture output) {
        String value = output.inlineText().orElseGet(() ->
                output.artifactRef()
                        .map(ref -> "[artifact:" + ref.value() + "]")
                        .orElse(""));
        return value.length() <= MAX_CAPTURE
                ? value : value.substring(0, MAX_CAPTURE);
    }
}
