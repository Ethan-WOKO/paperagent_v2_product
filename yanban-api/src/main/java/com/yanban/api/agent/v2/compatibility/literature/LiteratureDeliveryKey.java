package com.yanban.api.agent.v2.compatibility.literature;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class LiteratureDeliveryKey implements Serializable {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "client_request_id", nullable = false, length = 128)
    private String clientRequestId;

    protected LiteratureDeliveryKey() {
    }

    LiteratureDeliveryKey(Long userId, Long sessionId, String clientRequestId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.clientRequestId = clientRequestId;
    }

    Long userId() { return userId; }
    Long sessionId() { return sessionId; }
    String clientRequestId() { return clientRequestId; }

    @Override
    public boolean equals(Object other) {
        return other instanceof LiteratureDeliveryKey key
                && Objects.equals(userId, key.userId)
                && Objects.equals(sessionId, key.sessionId)
                && Objects.equals(clientRequestId, key.clientRequestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, sessionId, clientRequestId);
    }
}
