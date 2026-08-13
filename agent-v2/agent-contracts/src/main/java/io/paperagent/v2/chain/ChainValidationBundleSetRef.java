package io.paperagent.v2.chain;

import java.util.Objects;

/** Body-free reference from a plan-level bundle to one Step ValidationSet. */
public record ChainValidationBundleSetRef(
        String stepId,
        String activationEventId,
        String validationId,
        String requestDigest,
        String receiptSetDigest,
        String conclusionDigest) {
    public ChainValidationBundleSetRef {
        stepId = required(stepId, "stepId");
        activationEventId = required(activationEventId,
                "activationEventId");
        validationId = required(validationId, "validationId");
        requestDigest = sha256(requestDigest, "requestDigest");
        receiptSetDigest = sha256(receiptSetDigest, "receiptSetDigest");
        conclusionDigest = sha256(conclusionDigest, "conclusionDigest");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        value = required(value, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be lowercase SHA-256");
        }
        return value;
    }
}
