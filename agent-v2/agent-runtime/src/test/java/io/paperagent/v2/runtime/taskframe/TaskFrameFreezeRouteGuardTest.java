package io.paperagent.v2.runtime.taskframe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskFrameFreezeRouteGuardTest {
    @Test
    void directRouteFailsBeforeCanonicalTaskFrameConstruction() {
        TaskFrameDraft semanticallyInvalidDraft =
                new TaskFrameDraft("", List.of(), List.of(), List.of(" "));
        TaskFrameFreezeRequest request = TaskFrameTestFixtures.request(
                TaskFrameTestFixtures.directDecision("routing-direct-rejected"),
                TaskFrameTestFixtures.TASK_FRAME_ID,
                semanticallyInvalidDraft);

        TaskFrameFreezeValidationException exception = assertThrows(
                TaskFrameFreezeValidationException.class,
                () -> new DeterministicTaskFrameFreezer().freeze(request));

        assertEquals(
                TaskFrameFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                exception.code());
        assertEquals(
                "taskFrameFreezeRequest.routingDecision.route",
                exception.path());
    }
}
