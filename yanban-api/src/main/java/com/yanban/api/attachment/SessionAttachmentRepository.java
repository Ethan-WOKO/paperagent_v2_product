package com.yanban.api.attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SessionAttachmentRepository extends JpaRepository<SessionAttachment,Long> {
    List<SessionAttachment> findByUserIdAndSessionIdAndActiveTrueOrderByIdAsc(Long userId,Long sessionId);
    long countByUserIdAndSessionId(Long userId, Long sessionId);
}
