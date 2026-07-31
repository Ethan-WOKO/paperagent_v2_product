package com.yanban.api.project;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection of the existing Candidate confirmation validation. */
@Service
public class CandidateValidationStatusProjectionService {
    private final CandidateSandboxValidationRepository validations;

    public CandidateValidationStatusProjectionService(
            CandidateSandboxValidationRepository validations) {
        this.validations = validations;
    }

    @Transactional(readOnly = true)
    public Optional<Status> latest(
            Long userId, Long sessionId, Long artifactId) {
        if (userId == null || sessionId == null || artifactId == null) {
            return Optional.empty();
        }
        return validations
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        userId, sessionId, artifactId)
                .map(value -> new Status(
                        value.status(), value.decisionStatus()));
    }

    public record Status(String status, String decisionStatus) {
    }
}
