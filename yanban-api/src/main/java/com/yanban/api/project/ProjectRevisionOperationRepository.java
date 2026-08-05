package com.yanban.api.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRevisionOperationRepository extends JpaRepository<ProjectRevisionOperation, Long> {
    Optional<ProjectRevisionOperation> findByUserIdAndProjectIdAndIdempotencyKey(Long userId, Long projectId,
                                                                                 String idempotencyKey);
    Optional<ProjectRevisionOperation> findByIdAndUserIdAndProjectId(
            Long id, Long userId, Long projectId);
    Optional<ProjectRevisionOperation>
            findFirstByUserIdAndCandidateArtifactIdAndOperationTypeAndOutcomeOrderByIdDesc(
                    Long userId, Long candidateArtifactId,
                    ProjectRevisionOperation.Type operationType,
                    ProjectRevisionOperation.Outcome outcome);
}
