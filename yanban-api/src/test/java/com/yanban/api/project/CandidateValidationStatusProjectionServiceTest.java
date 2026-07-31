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
                repository).latest(7L, 9L, 42L);

        assertThat(result).contains(new CandidateValidationStatusProjectionService
                .Status("QUEUED", "PENDING"));
        verify(repository)
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        7L, 9L, 42L);
    }

    @Test
    void anotherOwnerReceivesNoConfirmationState() {
        CandidateSandboxValidationRepository repository =
                mock(CandidateSandboxValidationRepository.class);
        when(repository
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        8L, 9L, 42L))
                .thenReturn(Optional.empty());

        assertThat(new CandidateValidationStatusProjectionService(repository)
                .latest(8L, 9L, 42L)).isEmpty();
    }
}
