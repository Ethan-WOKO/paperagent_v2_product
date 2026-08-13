package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainRuntimePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChainProgressPolicyTest {
    @Test
    void escalatesOnlyAtTheVersionedConsecutiveNoProgressThreshold() {
        ChainProgressPolicy policy = new ChainProgressPolicy(
                ChainRuntimePolicy.V1);

        assertEquals(
                ChainProgressPolicy.ProgressDecision.CONTINUE_EXECUTOR,
                policy.assess(List.of(
                        marker(1, '1'), marker(2, '2'), marker(3, '2')))
                        .decision());
        var blocked = policy.assess(List.of(
                marker(1, '1'), marker(2, '2'), marker(3, '2'),
                marker(4, '2')));
        assertEquals(
                ChainProgressPolicy.ProgressDecision.BLOCK_FOR_REFLECTOR,
                blocked.decision());
        assertEquals(ChainRuntimePolicy.V1.noProgressThreshold(),
                blocked.unchangedOccurrences());
    }

    private static ChainProgressPolicy.ProgressMarker marker(
            long sequence, char digest) {
        return new ChainProgressPolicy.ProgressMarker(
                sequence, String.valueOf(digest).repeat(64));
    }
}
