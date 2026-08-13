package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.NetworkPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainPermissionPolicySourceTest {
    @Test
    void suppliesStableVersionedPolicyAndExactCommittedProfiles() {
        assertTrue(ProductChainPermissionPolicySource.supports(
                "SANDBOX_STANDARD"));
        assertFalse(ProductChainPermissionPolicySource.supports("UNKNOWN"));
        assertEquals(ProductChainPermissionPolicySource.policy().sha256(),
                ProductChainPermissionPolicySource.policy().sha256());

        var readExecute = ProductChainPermissionPolicySource
                .executionProfile(false);
        assertEquals(NetworkPolicy.DENY_ALL,
                readExecute.networkPolicy());
        assertEquals(java.util.Set.of(
                        Capability.READ_PROJECT,
                        Capability.EXECUTE_COMMAND,
                        Capability.INSTALL_DEPENDENCY),
                readExecute.capabilities());
        assertFalse(readExecute.capabilities().contains(
                Capability.WRITE_WORKSPACE));
        assertTrue(ProductChainPermissionPolicySource.executionProfile(true)
                .capabilities().contains(Capability.WRITE_WORKSPACE));
    }
}
