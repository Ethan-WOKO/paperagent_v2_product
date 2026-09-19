package com.yanban.core.agent;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Hibernate obtains this listener from Spring; no cache dependency belongs in the entity. */
@Component
public class AgentMessageChangeListener {
    private final ApplicationEventPublisher events;

    public AgentMessageChangeListener(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(AgentMessage message) {
        events.publishEvent(new Changed(message.getUserId(), message.getSessionId()));
    }

    public record Changed(Long userId, Long sessionId) {}
}
