package com.yanban.api.quota;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageRecordRepository extends JpaRepository<AiUsageRecord, Long> {
    List<AiUsageRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
