package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable Context projection of the product-owned tool catalog for one frozen
 * TaskFrame.
 *
 * <p>This projection only narrows the formal catalog through the TaskFrame's
 * execution tier, capabilities, and governed network allowlist. It grants no
 * authority and does not replace effect-time permission checks.
 */
public final class ProductChainToolContextProjection {
    public static final String SCHEMA_VERSION = "v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductChainToolContextProjection() {
    }

    public static Projection project(TaskFrame taskFrame) {
        return project(taskFrame, null);
    }

    /** Skill restrictions are applied only after the TaskFrame grant. */
    public static Projection project(
            TaskFrame taskFrame,
            ProductChainTaskSkillSnapshot skillSnapshot) {
        Objects.requireNonNull(taskFrame, "taskFrame");
        return project(taskFrame.id().value(), taskFrame.executionProfile(),
                skillSnapshot);
    }

    /** Planning-only catalog view from a versioned product permission policy. */
    public static Projection projectPolicy(
            String permissionPolicyRef,
            ExecutionProfile maximumProfile,
            ProductChainTaskSkillSnapshot skillSnapshot) {
        if (permissionPolicyRef == null || permissionPolicyRef.isBlank()) {
            throw new IllegalArgumentException(
                    "permissionPolicyRef must not be blank");
        }
        return project(permissionPolicyRef, maximumProfile, skillSnapshot);
    }

    private static Projection project(
            String permissionAuthorityRef,
            ExecutionProfile profile,
            ProductChainTaskSkillSnapshot skillSnapshot) {
        Objects.requireNonNull(profile, "profile");
        Set<String> skillToolIds = allowedToolIds(skillSnapshot);
        List<ToolSchema> schemas = V2ProductToolCatalog.entries().stream()
                .filter(entry -> granted(entry, profile))
                .filter(entry -> skillToolIds == null
                        || skillToolIds.contains(
                                entry.descriptor().id().value()))
                .map(ProductChainToolContextProjection::schema)
                .sorted(Comparator.comparing(value ->
                        value.descriptor().id().value()))
                .toList();
        List<String> permissionRefs = schemas.stream()
                .map(ToolSchema::permissionRef)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(TreeSet::new),
                        List::copyOf));
        return new Projection(
                SCHEMA_VERSION,
                permissionAuthorityRef,
                profile.tier().name(),
                schemas,
                permissionRefs,
                summary(schemas));
    }

    private static Set<String> allowedToolIds(
            ProductChainTaskSkillSnapshot snapshot) {
        if (snapshot == null || snapshot.selectionKind()
                == ProductChainTaskSkillSnapshot.SelectionKind.NONE) {
            return null;
        }
        final List<String> identifiers;
        try {
            identifiers = JSON.readValue(snapshot.allowedTools().json(),
                    new TypeReference<List<String>>() { });
        } catch (Exception invalidSnapshot) {
            throw ProductChainContextProjectionSupport.blocked(
                    ChainContextModule
                            .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                    "Skill tool authority is not valid canonical JSON");
        }
        TreeSet<String> resolved = new TreeSet<>();
        for (String identifier : identifiers) {
            if (identifier == null || identifier.isBlank()) {
                throw ProductChainContextProjectionSupport.blocked(
                        ChainContextModule
                                .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                        "Skill tool authority contains a blank identifier");
            }
            String exact = identifier.trim();
            var byAlias = V2ProductToolCatalog.toolIdForPublicAlias(exact);
            String toolId = byAlias.map(value -> value.value())
                    .orElseGet(() -> V2ProductToolCatalog.supports(exact)
                            ? exact : null);
            if (toolId == null) {
                throw ProductChainContextProjectionSupport.blocked(
                        ChainContextModule
                                .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                        "Skill references an unknown formal tool identifier");
            }
            resolved.add(toolId);
        }
        return Set.copyOf(resolved);
    }

    private static boolean granted(
            V2ProductToolCatalog.Entry entry,
            ExecutionProfile profile) {
        if (!entry.allowedExecutionTiers().contains(profile.tier())
                || !profile.capabilities().containsAll(
                entry.descriptor().requiredCapabilities())) {
            return false;
        }
        if (entry.requiredNetworkAllowlistEntries().isEmpty()) {
            return true;
        }
        return profile.networkPolicy() == NetworkPolicy.ALLOWLIST_ONLY
                && profile.networkAllowlist().containsAll(
                entry.requiredNetworkAllowlistEntries());
    }

    private static ToolSchema schema(V2ProductToolCatalog.Entry entry) {
        List<Capability> capabilities = entry.descriptor()
                .requiredCapabilities().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        List<ExecutionTier> tiers = entry.allowedExecutionTiers().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        List<String> networkAllowlistEntries = entry
                .requiredNetworkAllowlistEntries().stream()
                .sorted()
                .toList();
        List<RoutingRequirement> routingRequirements = entry
                .routingRequirements().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        return new ToolSchema(
                entry.publicAlias(),
                entry.publicDescription(),
                entry.descriptor(),
                entry.permissionRef(),
                tiers,
                capabilities,
                networkAllowlistEntries,
                routingRequirements,
                entry.executionTarget());
    }

    private static String summary(List<ToolSchema> schemas) {
        if (schemas.isEmpty()) {
            return "No product tools are granted by the frozen permission authority and profile.";
        }
        return schemas.size()
                + " product tools granted by the frozen permission authority and profile: "
                + String.join(", ", schemas.stream()
                .map(value -> value.descriptor().id().value())
                .toList());
    }

    public record Projection(
            String schemaVersion,
            String taskFrameRef,
            String permissionTierRef,
            List<ToolSchema> completeToolSchemas,
            List<String> permissionRefs,
            String summary) {
        public Projection {
            required(schemaVersion, "schemaVersion");
            required(taskFrameRef, "taskFrameRef");
            required(permissionTierRef, "permissionTierRef");
            completeToolSchemas = List.copyOf(Objects.requireNonNull(
                    completeToolSchemas, "completeToolSchemas"));
            permissionRefs = List.copyOf(Objects.requireNonNull(
                    permissionRefs, "permissionRefs"));
            required(summary, "summary");
        }
    }

    /** Complete formal descriptor plus product routing and permission metadata. */
    public record ToolSchema(
            String publicAlias,
            String summary,
            ToolDescriptor descriptor,
            String permissionRef,
            List<ExecutionTier> allowedExecutionTiers,
            List<Capability> requiredCapabilities,
            List<String> requiredNetworkAllowlistEntries,
            List<RoutingRequirement> routingRequirements,
            V2ProductToolCatalog.ExecutionTarget executionTarget) {
        public ToolSchema {
            required(publicAlias, "publicAlias");
            required(summary, "summary");
            Objects.requireNonNull(descriptor, "descriptor");
            required(permissionRef, "permissionRef");
            allowedExecutionTiers = List.copyOf(Objects.requireNonNull(
                    allowedExecutionTiers, "allowedExecutionTiers"));
            requiredCapabilities = List.copyOf(Objects.requireNonNull(
                    requiredCapabilities, "requiredCapabilities"));
            requiredNetworkAllowlistEntries = List.copyOf(
                    Objects.requireNonNull(requiredNetworkAllowlistEntries,
                            "requiredNetworkAllowlistEntries"));
            routingRequirements = List.copyOf(Objects.requireNonNull(
                    routingRequirements, "routingRequirements"));
            Objects.requireNonNull(executionTarget, "executionTarget");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
