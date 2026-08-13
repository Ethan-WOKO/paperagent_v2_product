package io.paperagent.v2.chain.instruction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainInstructionDispositionRuntimeTest {
    @Test void exposesFormalCommitBoundary() {
        assertTrue(java.util.Arrays.stream(ChainInstructionDispositionRuntime.class
                .getDeclaredMethods()).anyMatch(method -> method.getName().equals("commit")));
    }
}
