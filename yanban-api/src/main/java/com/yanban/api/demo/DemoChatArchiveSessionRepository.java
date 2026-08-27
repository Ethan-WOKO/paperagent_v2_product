package com.yanban.api.demo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoChatArchiveSessionRepository extends JpaRepository<DemoChatArchiveSession, Long> {
    boolean existsBySourceSessionId(Long sourceSessionId);
    List<DemoChatArchiveSession> findByUserIdOrderBySessionUpdatedAtDesc(Long userId);
    long countByUserId(Long userId);
}
