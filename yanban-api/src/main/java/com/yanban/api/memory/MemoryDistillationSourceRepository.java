package com.yanban.api.memory;

import com.yanban.core.agent.AgentMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface MemoryDistillationSourceRepository extends Repository<AgentMessage, Long> {
    @Query("select max(m.id) from AgentMessage m where m.userId = :userId and m.role in ('user', 'assistant')")
    Long latestId(@Param("userId") long userId);

    @Query("select count(m) from AgentMessage m where m.userId = :userId and m.id > :afterId and m.id <= :throughId and m.role in ('user', 'assistant')")
    long countWindow(@Param("userId") long userId, @Param("afterId") long afterId,
                     @Param("throughId") long throughId);

    @Query("select m from AgentMessage m where m.userId = :userId and m.id > :afterId and m.id <= :throughId and m.role in ('user', 'assistant') order by m.id asc")
    List<AgentMessage> window(@Param("userId") long userId, @Param("afterId") long afterId,
                              @Param("throughId") long throughId, Pageable page);
}
