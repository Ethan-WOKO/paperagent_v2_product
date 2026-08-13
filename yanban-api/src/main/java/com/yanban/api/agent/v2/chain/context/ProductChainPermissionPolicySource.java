package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ResourceLimits;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned product permission policy shared by planning and Plan commit. */
public final class ProductChainPermissionPolicySource {
    public static final String POLICY_VERSION =
            "product-chain-project-permission-v1";
    public static final String SUPPORTED_PERMISSION_TIER =
            "SANDBOX_STANDARD";

    private static final ResourceLimits LIMITS = new ResourceLimits(
            Duration.ofMinutes(30), Duration.ofMinutes(10),
            512L * 1024 * 1024, 8L * 1024 * 1024, 8);

    private ProductChainPermissionPolicySource() {
    }

    public static boolean supports(String permissionTier) {
        return SUPPORTED_PERMISSION_TIER.equals(permissionTier);
    }

    /** Exact profile committed after the accepted Plan determines write need. */
    public static ExecutionProfile executionProfile(
            boolean candidateModificationRequired) {
        Set<Capability> capabilities = candidateModificationRequired
                ? Set.of(Capability.READ_PROJECT,
                        Capability.EXECUTE_COMMAND,
                        Capability.INSTALL_DEPENDENCY,
                        Capability.WRITE_WORKSPACE)
                : Set.of(Capability.READ_PROJECT,
                        Capability.EXECUTE_COMMAND,
                        Capability.INSTALL_DEPENDENCY);
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                capabilities,
                NetworkPolicy.DENY_ALL,
                List.of(), LIMITS, Set.of());
    }

    /** Maximum planning view; conditional write does not grant runtime access. */
    public static ExecutionProfile planningProfile() {
        return executionProfile(true);
    }

    /** DIRECT answers cannot discover, execute, read Project files, or write. */
    public static ExecutionProfile directAnswerProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD, Set.of(),
                NetworkPolicy.DENY_ALL, List.of(), LIMITS, Set.of());
    }

    public static Projection policy() {
        ChainContextValue value = ChainContextValue.object(Map.of(
                "policyVersion", ChainContextValue.text(POLICY_VERSION),
                "permissionTier", ChainContextValue.text(
                        SUPPORTED_PERMISSION_TIER),
                "alwaysGrantedCapabilities", strings(List.of(
                        Capability.EXECUTE_COMMAND.name(),
                        Capability.INSTALL_DEPENDENCY.name(),
                        Capability.READ_PROJECT.name())),
                "conditionalCapabilities", ChainContextValue.object(Map.of(
                        Capability.WRITE_WORKSPACE.name(),
                        ChainContextValue.text(
                                "granted only when an accepted Plan Step may change a Candidate"))),
                "networkPolicy", ChainContextValue.text(
                        NetworkPolicy.DENY_ALL.name()),
                "networkAllowlist", ChainContextValue.array(List.of()),
                "resourceLimits", limits(),
                "hardBoundary", ChainContextValue.text(
                        "Planner describes required work; only the committed TaskFrame grants tools, networking, or Workspace writes.")));
        return projection(value);
    }

    public static Projection profile(ExecutionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        ChainContextValue value = ChainContextValue.object(Map.of(
                "policyVersion", ChainContextValue.text(POLICY_VERSION),
                "tier", ChainContextValue.text(profile.tier().name()),
                "capabilities", strings(profile.capabilities().stream()
                        .map(Enum::name).sorted().toList()),
                "networkPolicy", ChainContextValue.text(
                        profile.networkPolicy().name()),
                "networkAllowlist", strings(profile.networkAllowlist().stream()
                        .sorted().toList()),
                "resourceLimits", ChainContextValue.object(Map.of(
                        "wallTimeSeconds", ChainContextValue.number(
                                profile.resourceLimits().wallTime().toSeconds()),
                        "cpuTimeSeconds", ChainContextValue.number(
                                profile.resourceLimits().cpuTime().toSeconds()),
                        "memoryBytes", ChainContextValue.number(
                                profile.resourceLimits().memoryBytes()),
                        "outputBytes", ChainContextValue.number(
                                profile.resourceLimits().outputBytes()),
                        "processCount", ChainContextValue.number(
                                profile.resourceLimits().processCount()))),
                "secretReferences", strings(profile.secretReferences().stream()
                        .map(secret -> secret.name()).sorted().toList())));
        return projection(value);
    }

    private static ChainContextValue limits() {
        return ChainContextValue.object(Map.of(
                "wallTimeSeconds", ChainContextValue.number(
                        LIMITS.wallTime().toSeconds()),
                "cpuTimeSeconds", ChainContextValue.number(
                        LIMITS.cpuTime().toSeconds()),
                "memoryBytes", ChainContextValue.number(LIMITS.memoryBytes()),
                "outputBytes", ChainContextValue.number(LIMITS.outputBytes()),
                "processCount", ChainContextValue.number(LIMITS.processCount())));
    }

    private static ChainContextValue strings(List<String> values) {
        return ChainContextValue.array(values.stream().sorted()
                .map(ChainContextValue::text).toList());
    }

    private static Projection projection(ChainContextValue value) {
        String canonical = ProductChainContractProjectionCodec
                .canonicalJson(value);
        return new Projection(value,
                ProductChainContractProjectionCodec.sha256(canonical));
    }

    public record Projection(ChainContextValue value, String sha256) {
        public Projection {
            Objects.requireNonNull(value, "value");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be canonical");
            }
        }
    }
}
