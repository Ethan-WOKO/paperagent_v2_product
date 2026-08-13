package io.paperagent.v2.chain.validation;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Shared canonical identity rules for ValidationSet writers and verifiers. */
public final class ChainValidationIdentity {
    private ChainValidationIdentity() {
    }

    public static String requestDigest(
            SetScope scope, List<RequestIdentity> identities) {
        String items = identities.stream()
                .sorted(Comparator.comparing(RequestIdentity::requirementId))
                .map(value -> value.requirementId() + "\0"
                        + value.requirementDigest() + "\0"
                        + value.subject() + "\0" + value.subjectIdentity())
                .reduce((left, right) -> left + "\0" + right)
                .orElseThrow();
        return sha256(scope.taskId() + "\0" + scope.taskFrameId() + "\0"
                + scope.planId() + "\0" + scope.planRevisionId() + "\0"
                + scope.planRevisionNumber() + "\0" + scope.stepId() + "\0"
                + scope.activationEventId() + "\0" + items);
    }

    public static String receiptSetDigest(List<ReceiptIdentity> identities) {
        return sha256(identities.stream()
                .sorted(Comparator.comparing(ReceiptIdentity::requirementId))
                .map(value -> value.requirementId() + "\0" + value.receiptId()
                        + "\0" + value.originalPayloadSha256())
                .reduce((left, right) -> left + "\0" + right)
                .orElseThrow());
    }

    public static String conclusionDigest(List<ConclusionIdentity> identities) {
        return sha256(identities.stream()
                .sorted(Comparator.comparing(ConclusionIdentity::requirementId))
                .map(value -> value.requirementId() + "\0"
                        + value.conclusion())
                .reduce((left, right) -> left + "\0" + right)
                .orElseThrow());
    }

    public static String requirementDigest(ValidationRequirement value) {
        return sha256(value.requirementId() + "\0" + value.subject() + "\0"
                + value.completionCondition());
    }

    public static String candidateSubject(
            ChainValidationAuthorityPort.VerifiedCandidate value) {
        return value.candidateActionId() + "\0" + value.validationActionId()
                + "\0" + value.receiptId() + "\0"
                + value.receiptPayloadSha256() + "\0"
                + value.actionSignatureSha256() + "\0"
                + value.workspaceCandidateId() + "\0" + value.workspaceId()
                + "\0" + value.artifactId() + "\0"
                + value.candidateFingerprint() + "\0"
                + value.baseProjectVersion();
    }

    public static String candidateSubject(
            ChainPersistenceRecords.CandidateValidationItemRecord value) {
        return value.candidateActionId() + "\0" + value.validationActionId()
                + "\0" + value.receiptId() + "\0"
                + value.receiptPayloadSha256() + "\0"
                + value.actionSignatureSha256() + "\0"
                + value.workspaceCandidateId() + "\0" + value.workspaceId()
                + "\0" + value.artifactId() + "\0"
                + value.candidateFingerprint() + "\0"
                + value.baseProjectVersion();
    }

    public static String actionSubject(
            ChainValidationAuthorityPort.VerifiedActionReceipt value) {
        return value.actionId() + "\0" + value.receiptId() + "\0"
                + value.receiptPayloadSha256() + "\0"
                + value.actionSignatureSha256();
    }

    public static String actionSubject(
            ChainPersistenceRecords.ActionReceiptValidationItemRecord value) {
        return value.actionId() + "\0" + value.receiptId() + "\0"
                + value.receiptPayloadSha256() + "\0"
                + value.actionSignatureSha256();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record SetScope(
            String taskId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber, String stepId,
            String activationEventId) {
    }

    public record RequestIdentity(
            String requirementId, String requirementDigest,
            ValidationSubject subject, String subjectIdentity) {
    }

    public record ReceiptIdentity(
            String requirementId, String receiptId,
            String originalPayloadSha256) {
    }

    public record ConclusionIdentity(
            String requirementId, ChainValidationConclusion conclusion) {
    }
}
