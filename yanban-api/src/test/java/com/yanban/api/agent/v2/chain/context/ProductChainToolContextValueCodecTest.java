package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainToolContextValueCodecTest {
    @Test
    void encodesEveryTypedToolFieldAndCanonicalDigest() {
        var typed = ProductChainToolContextProjection.project(frame());
        var encoded = ProductChainToolContextValueCodec.encode(typed);

        assertTrue(encoded.canonicalJson().contains(
                "\"completeToolSchemas\""));
        assertTrue(encoded.canonicalJson().contains(
                "\"parameterSchema\""));
        assertTrue(encoded.canonicalJson().contains(
                "\"routingRequirements\""));
        assertTrue(encoded.canonicalJson().contains(
                "\"executionTarget\""));
        assertTrue(encoded.canonicalJson().contains(
                "\"id\":\"sandbox.execute\""));
        assertEquals(ProductChainContractProjectionCodec.sha256(
                encoded.canonicalJson()), encoded.sha256());
        assertTrue(encoded.plannerCanonicalJson().contains(
                "\"availableCapabilities\""));
        assertTrue(encoded.plannerCanonicalJson().contains(
                "\"availableRoutingRequirements\""));
        assertTrue(encoded.plannerCanonicalJson().contains(
                "\"grantedOperationCount\""));
        assertFalse(encoded.plannerCanonicalJson().contains(
                "\"availableOperations\""));
        assertFalse(encoded.plannerCanonicalJson().contains(
                "\"requiredCapabilities\""));
        assertFalse(encoded.plannerCanonicalJson().contains(
                "\"completeToolSchemas\""));
        assertFalse(encoded.plannerCanonicalJson().contains(
                "\"parameterSchema\""));
        assertFalse(encoded.plannerCanonicalJson().contains(
                "\"permissionRef\""));
        assertFalse(encoded.plannerCanonicalJson().contains(
                "\"executionTarget\""));
        assertEquals(ProductChainContractProjectionCodec.sha256(
                        encoded.plannerCanonicalJson()),
                encoded.plannerSha256());
    }

    @Test
    void codecNormalizesToolAndPermissionOrdering() {
        var typed = ProductChainToolContextProjection.project(frame());
        List<ProductChainToolContextProjection.ToolSchema> tools =
                new ArrayList<>(typed.completeToolSchemas());
        List<String> permissions = new ArrayList<>(typed.permissionRefs());
        Collections.reverse(tools);
        Collections.reverse(permissions);
        var reordered = new ProductChainToolContextProjection.Projection(
                typed.schemaVersion(), typed.taskFrameRef(),
                typed.permissionTierRef(), tools, permissions,
                typed.summary());

        assertEquals(
                ProductChainToolContextValueCodec.encode(typed),
                ProductChainToolContextValueCodec.encode(reordered));
    }

    private static TaskFrame frame() {
        return new TaskFrame(
                new TaskFrameId("task.tools"),
                "Use every granted tool schema.",
                List.of("project"),
                List.of("result"),
                List.of(),
                Optional.of(new ProjectVersionRef("project-1", "version-1")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.values()),
                        NetworkPolicy.ALLOWLIST_ONLY,
                        List.of("product-literature-search"),
                        new ResourceLimits(
                                Duration.ofMinutes(1), Duration.ofSeconds(30),
                                1024, 512, 2),
                        Set.of()),
                Instant.parse("2026-08-08T00:00:00Z"));
    }
}
