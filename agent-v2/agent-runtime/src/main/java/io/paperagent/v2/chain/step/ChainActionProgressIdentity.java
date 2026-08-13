package io.paperagent.v2.chain.step;

import io.paperagent.v2.contracts.ExecutionReceipt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Stable semantic identity for comparing terminal action outcomes. */
public final class ChainActionProgressIdentity {
    private ChainActionProgressIdentity() {
    }

    public static String receipt(
            String actionSignatureSha256,
            ExecutionReceipt receipt) {
        return receipt(actionSignatureSha256, receipt, List.of());
    }

    /** Includes typed output/evidence while excluding per-attempt identities. */
    public static String receipt(
            String actionSignatureSha256,
            ExecutionReceipt receipt,
            List<String> additionalEvidenceDigests) {
        if (actionSignatureSha256 == null
                || !actionSignatureSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "actionSignatureSha256 must be lowercase SHA-256");
        }
        ExecutionReceipt value = Objects.requireNonNull(receipt, "receipt");
        List<String> evidence = List.copyOf(Objects.requireNonNull(
                additionalEvidenceDigests, "additionalEvidenceDigests"))
                .stream().sorted().toList();
        if (evidence.stream().anyMatch(digest -> digest == null
                || !digest.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "additional evidence must be lowercase SHA-256");
        }
        return sha256(String.join("\0",
                actionSignatureSha256,
                value.status().name(),
                value.resultCode().orElse("NONE"),
                value.exitCode().map(String::valueOf).orElse("NONE"),
                output(value.standardOutput()),
                output(value.standardError()),
                value.artifactReferences().stream()
                        .map(ref -> ref.value()).reduce((left, right) ->
                                left + "\n" + right).orElse("NONE"),
                value.resultingDiff().map(diff -> diff.value())
                        .orElse("NONE"),
                value.eventReferences().stream()
                        .map(event -> event.value()).reduce((left, right) ->
                                left + "\n" + right).orElse("NONE"),
                String.join("\n", evidence)));
    }

    private static String output(
            io.paperagent.v2.contracts.OutputCapture output) {
        return String.join("\0",
                output.inlineText().orElse("NONE"),
                output.artifactRef().map(ref -> ref.value())
                        .orElse("NONE"),
                Boolean.toString(output.truncated()));
    }

    /** Minimal typed identity for a formal Candidate materialization failure. */
    public static String candidateFailure(
            String actionSignatureSha256, String failureCode) {
        if (actionSignatureSha256 == null
                || !actionSignatureSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "actionSignatureSha256 must be lowercase SHA-256");
        }
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException(
                    "failureCode must not be blank");
        }
        return sha256(String.join("\0", actionSignatureSha256,
                "CANDIDATE", failureCode));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
