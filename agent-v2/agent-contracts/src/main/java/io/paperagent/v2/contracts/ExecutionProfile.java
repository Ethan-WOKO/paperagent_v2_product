package io.paperagent.v2.contracts;

import java.util.List;
import java.util.Set;

public record ExecutionProfile(
        ExecutionTier tier,
        Set<Capability> capabilities,
        NetworkPolicy networkPolicy,
        List<String> networkAllowlist,
        ResourceLimits resourceLimits,
        Set<SecretRef> secretReferences) {

    public ExecutionProfile {
        Contracts.required(tier, "executionProfile.tier");
        capabilities = Contracts.set(capabilities, "executionProfile.capabilities");
        Contracts.required(networkPolicy, "executionProfile.networkPolicy");
        networkAllowlist = Contracts.list(networkAllowlist, "executionProfile.networkAllowlist").stream()
                .map(value -> Contracts.text(value, "executionProfile.networkAllowlist[]"))
                .toList();
        Contracts.required(resourceLimits, "executionProfile.resourceLimits");
        secretReferences = Contracts.set(secretReferences, "executionProfile.secretReferences");

        if (networkPolicy == NetworkPolicy.DENY_ALL && !networkAllowlist.isEmpty()) {
            Contracts.fail(ViolationCode.CAPABILITY_NOT_GRANTED, "executionProfile.networkAllowlist",
                    "DENY_ALL cannot carry an allowlist");
        }
        if ((!networkAllowlist.isEmpty() || networkPolicy == NetworkPolicy.ALLOWLIST_ONLY)
                && !capabilities.contains(Capability.ACCESS_NETWORK)) {
            Contracts.fail(ViolationCode.CAPABILITY_NOT_GRANTED, "executionProfile.capabilities",
                    "network policy requires ACCESS_NETWORK");
        }
        if (!secretReferences.isEmpty() && !capabilities.contains(Capability.USE_SECRET_REFERENCE)) {
            Contracts.fail(ViolationCode.CAPABILITY_NOT_GRANTED, "executionProfile.capabilities",
                    "secret references require USE_SECRET_REFERENCE");
        }
    }

    public boolean allows(Capability capability) {
        return capabilities.contains(Contracts.required(capability, "capability"));
    }
}
