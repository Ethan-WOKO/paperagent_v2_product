package com.yanban.api.agent.v2.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ProductReceiptJpaRepository
        extends JpaRepository<ProductReceiptEntity, String> {
    long countByToolCallId(String toolCallId);

    List<ProductReceiptEntity> findAllByToolCallId(String toolCallId);

    Optional<ProductReceiptEntity> findByReceiptIdAndToolCallId(
            String receiptId, String toolCallId);
}
