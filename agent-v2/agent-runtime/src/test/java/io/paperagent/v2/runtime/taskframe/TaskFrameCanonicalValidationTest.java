package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskFrameCanonicalValidationTest {
    @Test
    void blankObjectiveIsRejectedOnlyByCanonicalTaskFrameValidation() {
        assertCanonicalViolation(
                new TaskFrameDraft(" ", List.of("target"), List.of("deliverable"), List.of()),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "taskFrame.objective");
    }

    @Test
    void emptyRequiredListsAreRejectedOnlyByCanonicalTaskFrameValidation() {
        assertCanonicalViolation(
                new TaskFrameDraft("objective", List.of(), List.of("deliverable"), List.of()),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "taskFrame.targets");
        assertCanonicalViolation(
                new TaskFrameDraft("objective", List.of("target"), List.of(), List.of()),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "taskFrame.deliverables");
    }

    @Test
    void blankElementsInEveryListKeepCanonicalCodesAndPaths() {
        assertCanonicalViolation(
                new TaskFrameDraft("objective", List.of(" "), List.of("deliverable"), List.of()),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "taskFrame.targets[]");
        assertCanonicalViolation(
                new TaskFrameDraft("objective", List.of("target"), List.of(" "), List.of()),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "taskFrame.deliverables[]");
        assertCanonicalViolation(
                new TaskFrameDraft(
                        "objective",
                        List.of("target"),
                        List.of("deliverable"),
                        List.of(" ")),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "taskFrame.constraints[]");
    }

    private static ContractViolationException assertCanonicalViolation(
            TaskFrameDraft draft,
            ViolationCode expectedCode,
            String expectedPath) {
        ContractViolationException exception = assertThrows(
                ContractViolationException.class,
                () -> new DeterministicTaskFrameFreezer().freeze(
                        TaskFrameTestFixtures.request(draft)));
        assertEquals(expectedCode, exception.primaryCode());
        assertEquals(expectedPath, exception.violations().get(0).path());
        return exception;
    }
}
