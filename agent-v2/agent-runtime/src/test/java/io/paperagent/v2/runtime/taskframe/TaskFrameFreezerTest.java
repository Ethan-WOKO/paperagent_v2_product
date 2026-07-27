package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TaskFrameFreezerTest {
    @Test
    void declaredRequirementRoutePreservesEveryFieldFromItsAuthority() {
        TaskFrameDraft draft = TaskFrameTestFixtures.draft();
        var routingDecision =
                TaskFrameTestFixtures.declaredRequirementDecision("routing-field-authority");
        TaskFrameId taskFrameId = new TaskFrameId("task-field-authority");
        ProjectVersionRef projectVersion =
                new ProjectVersionRef("project-authority", "version-authority");
        var executionProfile = TaskFrameTestFixtures.executionProfile();
        Instant createdAt = Instant.parse("2026-07-24T04:10:00Z");
        TaskFrameFreezeRequest request = new TaskFrameFreezeRequest(
                routingDecision,
                taskFrameId,
                draft,
                Optional.of(projectVersion),
                executionProfile,
                createdAt);

        TaskFrame result = new DeterministicTaskFrameFreezer().freeze(request);

        assertEquals(taskFrameId, result.id());
        assertEquals(draft.objective(), result.objective());
        assertEquals(draft.targets(), result.targets());
        assertEquals(draft.deliverables(), result.deliverables());
        assertEquals(draft.constraints(), result.constraints());
        assertEquals(Optional.of(projectVersion), result.sourceProjectVersion());
        assertEquals(executionProfile, result.executionProfile());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void incompleteAssessmentPersistentRouteIsAccepted() {
        TaskFrameFreezeRequest request = new TaskFrameFreezeRequest(
                TaskFrameTestFixtures.incompleteAssessmentDecision(
                        "routing-incomplete-accepted"),
                new TaskFrameId("task-incomplete-accepted"),
                TaskFrameTestFixtures.draft(),
                Optional.empty(),
                TaskFrameTestFixtures.executionProfile(),
                TaskFrameTestFixtures.CREATED_AT);

        TaskFrame result = new DeterministicTaskFrameFreezer().freeze(request);

        assertEquals(request.taskFrameId(), result.id());
        assertEquals(Optional.empty(), result.sourceProjectVersion());
        assertEquals(request.createdAt(), result.createdAt());
    }

    @Test
    void freezerInstancesCanBeInterleavedAndReplayedWithoutContamination() {
        TaskFrameFreezer firstFreezer = new DeterministicTaskFrameFreezer();
        TaskFrameFreezer secondFreezer = new DeterministicTaskFrameFreezer();
        TaskFrameFreezeRequest firstRequest = TaskFrameTestFixtures.request(
                TaskFrameTestFixtures.declaredRequirementDecision("routing-interleave-a"),
                new TaskFrameId("task-interleave-a"),
                TaskFrameTestFixtures.draft());
        TaskFrameFreezeRequest equalFirstRequest = new TaskFrameFreezeRequest(
                TaskFrameTestFixtures.declaredRequirementDecision("routing-interleave-a"),
                new TaskFrameId("task-interleave-a"),
                TaskFrameTestFixtures.draft(),
                Optional.of(new ProjectVersionRef("project-freeze-1", "version-freeze-1")),
                TaskFrameTestFixtures.executionProfile(),
                TaskFrameTestFixtures.CREATED_AT);
        TaskFrameFreezeRequest secondRequest = new TaskFrameFreezeRequest(
                TaskFrameTestFixtures.incompleteAssessmentDecision("routing-interleave-b"),
                new TaskFrameId("task-interleave-b"),
                new TaskFrameDraft(
                        "Verify the independent result",
                        List.of("independent target"),
                        List.of("independent deliverable"),
                        List.of()),
                Optional.empty(),
                TaskFrameTestFixtures.executionProfile(),
                TaskFrameTestFixtures.CREATED_AT.plusSeconds(1));

        TaskFrame firstResult = firstFreezer.freeze(firstRequest);
        TaskFrame secondResult = secondFreezer.freeze(secondRequest);
        TaskFrame firstCrossResult = secondFreezer.freeze(firstRequest);
        TaskFrame secondCrossResult = firstFreezer.freeze(secondRequest);
        TaskFrame firstReplay = firstFreezer.freeze(equalFirstRequest);
        TaskFrame secondReplay = secondFreezer.freeze(secondRequest);

        assertEquals(firstRequest, equalFirstRequest);
        assertEquals(firstResult, firstCrossResult);
        assertEquals(firstResult, firstReplay);
        assertEquals(secondResult, secondCrossResult);
        assertEquals(secondResult, secondReplay);
        assertNotEquals(firstResult, secondResult);
    }
}
