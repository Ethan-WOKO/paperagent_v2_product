package com.yanban.api.project;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection of the existing Candidate confirmation validation. */
@Service
public class CandidateValidationStatusProjectionService {
    private final CandidateSandboxValidationRepository validations;
    private final ProjectRevisionOperationRepository operations;

    public CandidateValidationStatusProjectionService(
            CandidateSandboxValidationRepository validations,
            ProjectRevisionOperationRepository operations) {
        this.validations = validations;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public Optional<Status> latest(
            Long userId, Long sessionId, Long artifactId) {
        if (userId == null || sessionId == null || artifactId == null) {
            return Optional.empty();
        }
        Optional<Status> validation = validations
                .findFirstByUserIdAndSessionIdAndArtifactIdOrderByCreatedAtDescIdDesc(
                        userId, sessionId, artifactId)
                        .map(value -> status(userId, artifactId, value));
        if (validation.isPresent()
                && "APPLIED".equals(
                        validation.orElseThrow().decisionStatus())) {
            return validation;
        }
        Optional<Status> automatic = operations
                .findFirstByUserIdAndCandidateArtifactIdAndOperationTypeAndOutcomeOrderByIdDesc(
                        userId, artifactId,
                        ProjectRevisionOperation.Type.APPLICATION,
                        ProjectRevisionOperation.Outcome.SUCCEEDED)
                .map(operation -> new Status(
                        "SUCCEEDED", "APPLIED", operation.getId(),
                        operation.getResultRevisionId(),
                        operation.getResultVersion()));
        return automatic.isPresent() ? automatic : validation;
    }

    private Status status(
            Long userId,
            Long artifactId,
            CandidateSandboxValidation value) {
        if (!"APPLIED".equals(value.decisionStatus())) {
            return new Status(
                    value.status(), value.decisionStatus(),
                    null, null, null);
        }
        if (value.applicationOperationId() == null
                || value.appliedRevisionId() == null) {
            throw new IllegalStateException(
                    "Applied Candidate validation is incomplete");
        }
        ProjectRevisionOperation operation = operations
                .findByIdAndUserIdAndProjectId(
                        value.applicationOperationId(), userId,
                        value.projectId())
                .filter(candidate -> candidate.getOperationType()
                        == ProjectRevisionOperation.Type.APPLICATION)
                .filter(candidate -> candidate.getOutcome()
                        == ProjectRevisionOperation.Outcome.SUCCEEDED)
                .filter(candidate -> artifactId.equals(
                        candidate.getCandidateArtifactId()))
                .filter(candidate -> value.appliedRevisionId().equals(
                        candidate.getResultRevisionId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Applied Candidate operation is inconsistent"));
        return new Status(
                value.status(), value.decisionStatus(),
                operation.getId(), operation.getResultRevisionId(),
                operation.getResultVersion());
    }

    public record Status(
            String status,
            String decisionStatus,
            Long applicationOperationId,
            Long appliedRevisionId,
            String appliedProjectVersion) {
    }
}
