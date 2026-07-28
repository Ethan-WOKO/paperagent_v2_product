package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.persistence.StepCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicHeadActiveStepCompletionMaterializerTest {
    @Test
    void derivesCompletionFromLaterActivationHead() {
        var recovered =
                ActiveStepCompletionMaterializationFixture.laterRecovered();
        StepCompletionRequest result =
                new DeterministicActiveStepCompletionMaterializer()
                        .materialize(
                                ActiveStepCompletionMaterializationFixture
                                        .request(recovered, List.of()));

        assertEquals(5, result.expectedCheckpointVersion());
        assertEquals(4, result.expectedEventHeadSequence());
        assertEquals(5, result.completionEvent().sequence());
        assertEquals(5, result.completedCheckpoint().lastEventSequence());
        assertEquals(3, result.completedRevision().number());
    }
}
