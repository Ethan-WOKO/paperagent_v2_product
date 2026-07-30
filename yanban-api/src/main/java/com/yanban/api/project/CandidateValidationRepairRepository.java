package com.yanban.api.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface CandidateValidationRepairRepository extends JpaRepository<CandidateValidationRepair, Long> {
    @Query("select r from CandidateValidationRepair r where r.sourceValidationId=:id")
    Optional<CandidateValidationRepair> findBySourceValidationId(@Param("id") String validationId);
}
