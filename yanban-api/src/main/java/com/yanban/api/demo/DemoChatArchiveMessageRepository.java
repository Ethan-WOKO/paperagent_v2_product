package com.yanban.api.demo;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoChatArchiveMessageRepository extends JpaRepository<DemoChatArchiveMessage, Long> {
    List<DemoChatArchiveMessage> findByArchiveSessionIdInOrderByArchiveSessionIdAscMessageCreatedAtAscIdAsc(
            Collection<Long> archiveSessionIds);
}
