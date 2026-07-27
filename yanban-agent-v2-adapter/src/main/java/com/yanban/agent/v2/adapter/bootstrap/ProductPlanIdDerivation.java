package com.yanban.agent.v2.adapter.bootstrap;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Pure derivation of the persistent product Plan identity.
 */
public final class ProductPlanIdDerivation {
    private static final String PLAN_PREFIX = "product-plan.";
    private static final String PLAN_DOMAIN = "plan\0";

    public PlanId derive(AgentRunIdentity identity) {
        AgentRunIdentity requiredIdentity =
                Objects.requireNonNull(identity, "identity");
        return new PlanId(
                PLAN_PREFIX + sha256(PLAN_DOMAIN + requiredIdentity.runId()));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK must provide SHA-256", exception);
        }
    }
}
