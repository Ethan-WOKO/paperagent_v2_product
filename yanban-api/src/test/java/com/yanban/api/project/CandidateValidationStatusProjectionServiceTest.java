package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CandidateValidationStatusProjectionServiceTest {
    @Test
    void returnsLatestOwnerSessionQualifiedConfirmationState() {
        CandidateSandboxValidationRepository repository =
                mock(CandidateSandboxValidationRepository.class);
        ProjectRevisionOperationRepository operations =
                mock(ProjectRevisionOperationRepository.class);
        CandidateSandboxValidation validation =
                new CandidateSandboxValidation(
                        "validation-1", 7L, 91L, 9L, 42L,
                        "version-1", "a".repeat(64), "[0]",
                        "b".repeat(64), "JAVA", "request-1",
                        "c".repeat(64), "d".repeat(64),
                        "e".repeat(64), "{}", LocalDateTime.now());
        when(repository
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        7L, 9L, 42L))
                .thenReturn(Optional.of(validation));

        var result = new CandidateValidationStatusProjectionService(
                repository, operations).latest(7L, 9L, 42L);

        assertThat(result).contains(new CandidateValidationStatusProjectionService
                .Status("QUEUED", "PENDING", null, null, null));
        verify(repository)
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        7L, 9L, 42L);
    }

    @Test
    void projectsAppliedRevisionFromSuccessfulApplicationOperation() {
        CandidateSandboxValidationRepository repository =
                mock(CandidateSandboxValidationRepository.class);
        ProjectRevisionOperationRepository operations =
                mock(ProjectRevisionOperationRepository.class);
        CandidateSandboxValidation validation =
                new CandidateSandboxValidation(
                        "validation-1", 7L, 91L, 9L, 42L,
                        "version-1", "a".repeat(64), "[0]",
                        "b".repeat(64), "JAVA", "request-1",
                        "c".repeat(64), "d".repeat(64),
                        "e".repeat(64), "{}", LocalDateTime.now());
        validation.applied(101L, 29L, LocalDateTime.now());
        when(repository
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        7L, 9L, 42L))
                .thenReturn(Optional.of(validation));
        ProjectRevisionOperation operation =
                mock(ProjectRevisionOperation.class);
        when(operation.getId()).thenReturn(101L);
        when(operation.getOperationType()).thenReturn(
                ProjectRevisionOperation.Type.APPLICATION);
        when(operation.getOutcome()).thenReturn(
                ProjectRevisionOperation.Outcome.SUCCEEDED);
        when(operation.getCandidateArtifactId()).thenReturn(42L);
        when(operation.getResultRevisionId()).thenReturn(29L);
        when(operation.getResultVersion()).thenReturn("f".repeat(64));
        when(operations.findByIdAndUserIdAndProjectId(101L, 7L, 91L))
                .thenReturn(Optional.of(operation));

        var result = new CandidateValidationStatusProjectionService(
                repository, operations).latest(7L, 9L, 42L);

        assertThat(result).contains(new CandidateValidationStatusProjectionService
                .Status("QUEUED", "APPLIED", 101L, 29L,
                        "f".repeat(64)));
    }

    @Test
    void anotherOwnerReceivesNoConfirmationState() {
        CandidateSandboxValidationRepository repository =
                mock(CandidateSandboxValidationRepository.class);
        ProjectRevisionOperationRepository operations =
                mock(ProjectRevisionOperationRepository.class);
        when(repository
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        8L, 9L, 42L))
                .thenReturn(Optional.empty());

        assertThat(new CandidateValidationStatusProjectionService(
                repository, operations)
                .latest(8L, 9L, 42L)).isEmpty();
    }
}
