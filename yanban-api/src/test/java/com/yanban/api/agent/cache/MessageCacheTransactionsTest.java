package com.yanban.api.agent.cache;

import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentMessageCacheService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({AgentMessageCacheService.class, ObjectMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MessageCacheTransactionsTest {
    @Autowired AgentMessageRepository messages;
    @Autowired PlatformTransactionManager manager;
    @MockBean ConversationSnapshotCache snapshots;

    @Test void persistUpdateAndDeleteInvalidateOnlyAfterCommit() {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        Long id = transaction.execute(status -> {
            var message = messages.saveAndFlush(new AgentMessage(11L,7L,"assistant","original",null,null));
            messages.saveAndFlush(new AgentMessage(11L,7L,"user","second",null,null));
            verifyNoInteractions(snapshots);
            return message.getId();
        });
        verify(snapshots).invalidate(7L,11L);
        clearInvocations(snapshots);
        transaction.executeWithoutResult(status -> {
            messages.findById(id).orElseThrow().replaceContent("edited");
            messages.flush();
            verifyNoInteractions(snapshots);
        });
        verify(snapshots).invalidate(7L,11L);
        clearInvocations(snapshots);
        transaction.executeWithoutResult(status -> {
            messages.deleteBySessionId(11L);
            messages.flush();
            verifyNoInteractions(snapshots);
        });
        verify(snapshots).invalidate(7L,11L);
    }

    @Test void rolledBackWritesDoNotInvalidate() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            messages.saveAndFlush(new AgentMessage(21L,8L,"user","rollback",null,null));
            status.setRollbackOnly();
        });
        verifyNoInteractions(snapshots);
    }
}
