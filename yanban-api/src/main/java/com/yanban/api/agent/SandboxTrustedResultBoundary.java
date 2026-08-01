package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlanStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.util.StringUtils;

/** Prevents persisted sandbox display output from becoming trusted model input. */
public final class SandboxTrustedResultBoundary {
    private SandboxTrustedResultBoundary() { }

    public static String trusted(AgentPlanStep step) {
        return step == null ? null : trusted(step.getType(), step.getStatus(), step.getResult());
    }

    public static String trusted(String type, String stepStatus, String persistedResult) {
        if (!"SANDBOX_EXECUTE".equals(type)) return persistedResult;
        if (!StringUtils.hasText(persistedResult)) return null;
        StringBuilder value = new StringBuilder("Sandbox trusted execution fact");
        // step.result is a display-only field and may contain arbitrary sandbox stdout. No
        // syntax within it can establish provenance, including text that resembles a receipt.
        append(value, "stepStatus", safeStepStatus(stepStatus));
        append(value, "legacyResultSha256", sha256(persistedResult));
        return value.append("; candidate=NOT_APPLIED; outputTrust=UNTRUSTED_DIGEST_ONLY").toString();
    }

    public static String trustedPlanFinalAnswer(AgentPlanResponse plan) {
        if (plan == null || plan.steps() == null) return null;
        boolean containsSandbox = plan.steps().stream().anyMatch(step -> step != null
                && "SANDBOX_EXECUTE".equals(step.type()));
        if (!containsSandbox) return plan.finalAnswer();
        if (StringUtils.hasText(plan.finalAnswer())) return plan.finalAnswer();
        StringBuilder summary = new StringBuilder("Plan trusted execution summary:");
        for (AgentPlanStepResponse step : plan.steps()) {
            if (step == null) continue;
            String result = trusted(step.type(), step.status(), step.result());
            summary.append("\n- ").append(step.stepKey()).append(" [").append(step.status()).append("]: ")
                    .append(StringUtils.hasText(result) ? result : "No trusted result");
        }
        return summary.toString();
    }

    private static String safeStepStatus(String value) {
        return value != null && value.matches("PENDING|RUNNING|COMPLETED|FAILED|DEGRADED|SKIPPED|SUPERSEDED")
                ? value : "UNKNOWN";
    }

    private static void append(StringBuilder target, String name, String value) {
        if (StringUtils.hasText(value)) target.append("; ").append(name).append('=').append(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
