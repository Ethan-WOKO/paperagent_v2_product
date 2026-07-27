package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface ProductReceiptToolCallClaimJpaRepository
        extends JpaRepository<ProductReceiptToolCallClaimEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT claim
              FROM ProductReceiptToolCallClaimEntity claim
             WHERE claim.toolCallId = :toolCallId
            """)
    Optional<ProductReceiptToolCallClaimEntity> lockByToolCallId(
            @Param("toolCallId") String toolCallId);
}
