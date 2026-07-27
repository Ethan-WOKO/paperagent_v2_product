package com.yanban.agent.v2.adapter.bootstrap;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.WorkspaceId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Pure derivation of the persistent product Workspace identity.
 */
public final class ProductWorkspaceIdDerivation {
    private static final String WORKSPACE_PREFIX = "product-workspace.";
    private static final String WORKSPACE_DOMAIN = "workspace\0";

    public WorkspaceId derive(AgentRunIdentity identity) {
        AgentRunIdentity requiredIdentity =
                Objects.requireNonNull(identity, "identity");
        return new WorkspaceId(
                WORKSPACE_PREFIX
                        + sha256(WORKSPACE_DOMAIN + requiredIdentity.runId()));
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
