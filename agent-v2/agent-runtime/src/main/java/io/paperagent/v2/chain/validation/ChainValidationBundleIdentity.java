package io.paperagent.v2.chain.validation;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Shared canonical identity rules for plan-level ValidationBundle closure. */
public final class ChainValidationBundleIdentity {
    private ChainValidationBundleIdentity() {
    }

    public static Aggregate aggregate(Scope scope, List<Member> members) {
        Objects.requireNonNull(scope, "scope");
        members = List.copyOf(members).stream()
                .sorted(Comparator.comparing(Member::stepId)
                        .thenComparing(Member::validationId))
                .toList();
        if (members.isEmpty()
                || new HashSet<>(members.stream().map(Member::stepId).toList())
                .size() != members.size()) {
            throw new IllegalArgumentException(
                    "ValidationBundle members must contain unique Steps");
        }
        String request = ChainValidationIdentity.sha256(scopeIdentity(scope)
                + "\0" + join(members, Member::requestIdentity));
        String receipts = ChainValidationIdentity.sha256(
                join(members, Member::receiptIdentity));
        String conclusions = ChainValidationIdentity.sha256(
                join(members, Member::conclusionIdentity));
        return new Aggregate(request, receipts, conclusions);
    }

    public static String bundleId(Scope scope, Aggregate aggregate) {
        return "validation-bundle." + ChainValidationIdentity.sha256(
                scopeIdentity(scope) + "\0" + aggregate.requestDigest()
                        + "\0" + aggregate.receiptSetDigest() + "\0"
                        + aggregate.conclusionDigest());
    }

    public static String eventSourceIdentity(
            String validationBundleId, Aggregate aggregate) {
        return ChainValidationIdentity.sha256(validationBundleId + "\0"
                + aggregate.requestDigest() + "\0"
                + aggregate.receiptSetDigest() + "\0"
                + aggregate.conclusionDigest());
    }

    private static String scopeIdentity(Scope value) {
        return value.taskId() + "\0" + value.taskFrameId() + "\0"
                + value.planId() + "\0" + value.planRevisionId() + "\0"
                + value.planRevisionNumber() + "\0" + value.instructionId()
                + "\0" + value.finalStepId();
    }

    private static String join(
            List<Member> members,
            java.util.function.Function<Member, String> identity) {
        return members.stream().map(identity)
                .reduce((left, right) -> left + "\0" + right)
                .orElseThrow();
    }

    public record Scope(
            String taskId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber,
            String instructionId, String finalStepId) {
    }

    public record Member(
            String stepId, String validationId, String requestDigest,
            String receiptSetDigest, String conclusionDigest) {
        private String requestIdentity() {
            return stepId + "\0" + validationId + "\0" + requestDigest;
        }

        private String receiptIdentity() {
            return stepId + "\0" + validationId + "\0" + receiptSetDigest;
        }

        private String conclusionIdentity() {
            return stepId + "\0" + validationId + "\0" + conclusionDigest
                    + "\0PASSED";
        }
    }

    public record Aggregate(
            String requestDigest, String receiptSetDigest,
            String conclusionDigest) {
    }
}
