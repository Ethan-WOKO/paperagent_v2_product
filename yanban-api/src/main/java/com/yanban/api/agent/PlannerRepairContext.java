package com.yanban.api.agent;

import java.util.List;

/**
 * Server-produced validation feedback for the single bounded planner retry.
 * It describes required plan shape only and never grants tool authority.
 */
public record PlannerRepairContext(
        PlannerFailureCode failureCode,
        Reason reason,
        String constraint,
        List<RequiredElement> requiredElements,
        List<String> requiredTools,
        List<String> requiredSandboxPaths,
        List<RequiredElement> missingElements,
        List<String> missingTools
) {
    public PlannerRepairContext {
        failureCode = failureCode == null ? PlannerFailureCode.INVALID_PLAN : failureCode;
        reason = reason == null ? Reason.INVALID_STRUCTURE : reason;
        constraint = constraint == null ? "" : constraint;
        requiredElements = requiredElements == null ? List.of() : List.copyOf(requiredElements);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        requiredSandboxPaths = requiredSandboxPaths == null ? List.of() : requiredSandboxPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.trim().replace('\\', '/'))
                .distinct()
                .sorted()
                .toList();
        missingElements = missingElements == null ? List.of() : List.copyOf(missingElements);
        missingTools = missingTools == null ? List.of() : List.copyOf(missingTools);
    }

    public enum Reason {
        EMPTY_RESPONSE,
        TRUNCATED_OUTPUT,
        MALFORMED_JSON,
        INVALID_STRUCTURE,
        NO_EXECUTABLE_STEPS,
        MISSING_REQUIRED_PLAN_ELEMENTS,
        MISSING_SANDBOX_TARGET,
        INVALID_DEPENDENCY_CHAIN,
        DUPLICATE_REQUIRED_STEP,
        UNAUTHORIZED_SANDBOX_REQUEST
    }

    public enum RequiredElement {
        TRUSTED_PROJECT_EVIDENCE,
        NOT_APPLIED_CANDIDATE,
        CONFIRMED_SANDBOX_EXECUTION,
        EXPLICIT_SANDBOX_TARGET,
        FINAL_SYNTHESIS,
        UNIQUE_CANDIDATE_STEP,
        UNIQUE_SANDBOX_STEP,
        CANDIDATE_DEPENDS_ON_TRUSTED_READ,
        SANDBOX_DEPENDS_ON_CANDIDATE,
        FINAL_SYNTHESIS_DEPENDS_ON_SANDBOX
    }
}
