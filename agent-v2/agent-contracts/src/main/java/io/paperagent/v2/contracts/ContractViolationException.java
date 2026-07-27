package io.paperagent.v2.contracts;

import java.util.List;

public final class ContractViolationException extends IllegalArgumentException {
    private final List<ContractViolation> violations;

    public ContractViolationException(List<ContractViolation> violations) {
        super(summary(violations));
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("at least one violation is required");
        }
        this.violations = List.copyOf(violations);
    }

    public List<ContractViolation> violations() {
        return violations;
    }

    public ViolationCode primaryCode() {
        return violations.get(0).code();
    }

    private static String summary(List<ContractViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "contract validation failed";
        }
        ContractViolation first = violations.get(0);
        return first.code() + " at " + first.path() + ": " + first.message();
    }
}
