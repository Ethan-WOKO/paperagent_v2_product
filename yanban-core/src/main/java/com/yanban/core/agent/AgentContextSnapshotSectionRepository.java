package com.yanban.core.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentContextSnapshotSectionRepository
        extends JpaRepository<AgentContextSnapshotSection, Long> {
    List<AgentContextSnapshotSection> findBySnapshotIdOrderBySectionOrdinalAsc(
            Long snapshotId);
}
