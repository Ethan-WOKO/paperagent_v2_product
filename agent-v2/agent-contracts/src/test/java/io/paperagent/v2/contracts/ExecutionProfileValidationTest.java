package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ExecutionProfileValidationTest {
    @Test
    void absentCapabilityIsDenied() {
        ExecutionProfile profile = ContractFixtures.profile();
        assertFalse(profile.allows(Capability.ACCESS_NETWORK));
        assertFalse(profile.allows(Capability.EXECUTE_COMMAND));
    }

    @Test
    void rejectsNetworkPolicyWithoutExplicitCapability() {
        ContractViolationException exception = ContractFixtures.violation(() -> new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(),
                NetworkPolicy.ALLOWLIST_ONLY,
                List.of("repo.maven.apache.org"),
                limits(),
                Set.of()));
        assertEquals(ViolationCode.CAPABILITY_NOT_GRANTED, exception.primaryCode());
    }

    @Test
    void rejectsSecretReferenceWithoutExplicitCapability() {
        ContractViolationException exception = ContractFixtures.violation(() -> new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(),
                NetworkPolicy.DENY_ALL,
                List.of(),
                limits(),
                Set.of(new SecretRef("github/token"))));
        assertEquals(ViolationCode.CAPABILITY_NOT_GRANTED, exception.primaryCode());
    }

    @Test
    void rejectsLikelySecretValueInReferenceType() {
        ContractViolationException exception =
                ContractFixtures.violation(() -> new SecretRef("embedded secret material"));
        assertEquals(ViolationCode.SECRET_VALUE_NOT_ALLOWED, exception.primaryCode());
    }

    private static ResourceLimits limits() {
        return new ResourceLimits(Duration.ofMinutes(1), Duration.ofSeconds(30), 1024, 1024, 1);
    }
}
