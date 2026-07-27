package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.TaskFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskFrameDraftValidationTest {
    @Test
    void structuralValidationCodesContainExactlyTheFrozenValues() {
        assertEquals(
                Set.of(
                        TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                        TaskFrameFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                        TaskFrameFreezeValidationCode.ROUTE_NOT_PERSISTENT),
                Set.of(TaskFrameFreezeValidationCode.values()));
    }

    @Test
    void draftRejectsEveryMissingFieldWithStablePaths() {
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameDraft.objective",
                () -> new TaskFrameDraft(null, List.of(), List.of(), List.of()));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameDraft.targets",
                () -> new TaskFrameDraft("objective", null, List.of(), List.of()));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameDraft.deliverables",
                () -> new TaskFrameDraft("objective", List.of(), null, List.of()));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameDraft.constraints",
                () -> new TaskFrameDraft("objective", List.of(), List.of(), null));
    }

    @Test
    void draftRejectsNullElementsInEveryCollectionWithStablePaths() {
        assertViolation(
                TaskFrameFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                "taskFrameDraft.targets",
                () -> new TaskFrameDraft(
                        "objective",
                        withNull(),
                        List.of(),
                        List.of()));
        assertViolation(
                TaskFrameFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                "taskFrameDraft.deliverables",
                () -> new TaskFrameDraft(
                        "objective",
                        List.of(),
                        withNull(),
                        List.of()));
        assertViolation(
                TaskFrameFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                "taskFrameDraft.constraints",
                () -> new TaskFrameDraft(
                        "objective",
                        List.of(),
                        List.of(),
                        withNull()));
    }

    @Test
    void freezeRequestRejectsEveryMissingFieldWithStablePaths() {
        var decision =
                TaskFrameTestFixtures.declaredRequirementDecision("routing-request-validation");
        var taskFrameId = TaskFrameTestFixtures.TASK_FRAME_ID;
        var draft = TaskFrameTestFixtures.draft();
        var projectVersion = Optional.of(TaskFrameTestFixtures.PROJECT_VERSION);
        var profile = TaskFrameTestFixtures.executionProfile();
        var createdAt = TaskFrameTestFixtures.CREATED_AT;

        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest.routingDecision",
                () -> new TaskFrameFreezeRequest(
                        null, taskFrameId, draft, projectVersion, profile, createdAt));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest.taskFrameId",
                () -> new TaskFrameFreezeRequest(
                        decision, null, draft, projectVersion, profile, createdAt));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest.draft",
                () -> new TaskFrameFreezeRequest(
                        decision, taskFrameId, null, projectVersion, profile, createdAt));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest.sourceProjectVersion",
                () -> new TaskFrameFreezeRequest(
                        decision, taskFrameId, draft, null, profile, createdAt));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest.executionProfile",
                () -> new TaskFrameFreezeRequest(
                        decision, taskFrameId, draft, projectVersion, null, createdAt));
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest.createdAt",
                () -> new TaskFrameFreezeRequest(
                        decision, taskFrameId, draft, projectVersion, profile, null));
    }

    @Test
    void freezerRejectsMissingTopLevelRequestWithStablePath() {
        assertViolation(
                TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "taskFrameFreezeRequest",
                () -> new DeterministicTaskFrameFreezer().freeze(null));
    }

    @Test
    void draftRequestAndResultKeepImmutableSnapshotsAcrossCallerMutation() {
        ArrayList<String> targets = new ArrayList<>(List.of("paper"));
        ArrayList<String> deliverables = new ArrayList<>(List.of("diff"));
        ArrayList<String> constraints = new ArrayList<>(List.of("preserve meaning"));
        TaskFrameDraft draft = new TaskFrameDraft(
                "Update the paper",
                targets,
                deliverables,
                constraints);
        TaskFrameFreezeRequest request = TaskFrameTestFixtures.request(draft);

        targets.add("late target");
        deliverables.clear();
        constraints.add("late constraint");
        TaskFrame result = new DeterministicTaskFrameFreezer().freeze(request);
        targets.clear();
        constraints.clear();

        assertEquals(List.of("paper"), draft.targets());
        assertEquals(List.of("diff"), draft.deliverables());
        assertEquals(List.of("preserve meaning"), draft.constraints());
        assertEquals(draft.targets(), request.draft().targets());
        assertEquals(draft.targets(), result.targets());
        assertEquals(draft.deliverables(), result.deliverables());
        assertEquals(draft.constraints(), result.constraints());
        assertThrows(
                UnsupportedOperationException.class,
                () -> draft.targets().add("mutation"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.draft().deliverables().add("mutation"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.targets().add("mutation"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.deliverables().add("mutation"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.constraints().add("mutation"));
    }

    private static List<String> withNull() {
        ArrayList<String> values = new ArrayList<>();
        values.add("value");
        values.add(null);
        return values;
    }

    private static TaskFrameFreezeValidationException assertViolation(
            TaskFrameFreezeValidationCode expectedCode,
            String expectedPath,
            Runnable action) {
        TaskFrameFreezeValidationException exception =
                assertThrows(TaskFrameFreezeValidationException.class, action::run);
        assertEquals(expectedCode, exception.code());
        assertEquals(expectedPath, exception.path());
        return exception;
    }
}
