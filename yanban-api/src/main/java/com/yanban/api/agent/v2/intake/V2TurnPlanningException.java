package com.yanban.api.agent.v2.intake;

final class V2TurnPlanningException extends RuntimeException {
    private final String diagnostic;
    private final String outputDigest;

    V2TurnPlanningException(String message) {
        this("REJECTED", null, message);
    }

    V2TurnPlanningException(String diagnostic, String message) {
        this(diagnostic, null, message);
    }

    private V2TurnPlanningException(
            String diagnostic, String outputDigest, String message) {
        super(message);
        if (diagnostic == null
                || !diagnostic.matches("[A-Z0-9_]{1,40}")) {
            throw new IllegalArgumentException(
                    "planner diagnostic is invalid");
        }
        this.diagnostic = diagnostic;
        this.outputDigest = outputDigest;
    }

    V2TurnPlanningException withOutputDigest(String digest) {
        if (outputDigest != null || digest == null || digest.length() < 12) {
            return this;
        }
        return new V2TurnPlanningException(
                diagnostic, digest.substring(0, 12), getMessage());
    }

    String failureCode() {
        String value = "PLANNER_" + diagnostic
                + (outputDigest == null ? "" : "_" + outputDigest);
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
