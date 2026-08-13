package io.paperagent.v2.chain;

import java.util.List;
import java.util.Objects;

public record GapValidation(String gapId, List<Check> checks, Outcome outcome) {
    public GapValidation {
        gapId = ChainValues.required(gapId, "gapId");
        checks = ChainValues.nonEmptyCopy(checks, "checks");
        outcome = Objects.requireNonNull(outcome, "outcome");
        boolean allSatisfied = checks.stream().allMatch(Check::satisfied);
        if ((outcome == Outcome.RESOLVED) != allSatisfied) {
            throw new IllegalArgumentException("gap outcome must match its closing-condition checks");
        }
    }

    public record Check(String closingCondition, boolean satisfied, String factRef) {
        public Check {
            closingCondition = ChainValues.required(closingCondition, "closingCondition");
            factRef = ChainValues.required(factRef, "factRef");
        }
    }

    public enum Outcome {
        RESOLVED,
        STILL_PENDING
    }
}
