package io.paperagent.v2.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChainRuntimePolicyTest {
    @Test
    void resolvesTheCurrentAndStoredV1Policy() {
        assertSame(ChainRuntimePolicy.V1, ChainRuntimePolicy.current());
        assertSame(ChainRuntimePolicy.V1, ChainRuntimePolicy.requireVersion(
                ChainRuntimePolicy.V1.policyVersion()));
        assertEquals(1_000_000,
                ChainRuntimePolicy.V1.contextRequestCharactersMax());
        assertEquals(1_000,
                ChainRuntimePolicy.V1.contextBodyPageItemsMax());
    }

    @Test
    void rejectsAnUnknownStoredPolicyVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> ChainRuntimePolicy.requireVersion(
                        "chain-runtime-policy-unknown"));
    }
}
