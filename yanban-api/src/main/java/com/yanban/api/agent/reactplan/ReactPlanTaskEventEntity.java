package com.yanban.api.agent.reactplan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "reactplan_task_events")
@IdClass(ReactPlanTaskEventEntity.Key.class)
final class ReactPlanTaskEventEntity {
    @Id
    @Column(name = "task_id", length = 69)
    private String taskId;
    @Id
    @Column(name = "sequence_number")
    private long sequenceNumber;
    @Lob
    @Column(name = "event_json", nullable = false, columnDefinition = "LONGTEXT")
    private String eventJson;
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected ReactPlanTaskEventEntity() { }

    ReactPlanTaskEventEntity(String taskId, long sequenceNumber,
                             String eventJson, LocalDateTime occurredAt) {
        this.taskId = taskId;
        this.sequenceNumber = sequenceNumber;
        this.eventJson = eventJson;
        this.occurredAt = occurredAt;
    }

    long sequenceNumber() { return sequenceNumber; }
    String eventJson() { return eventJson; }

    static final class Key implements Serializable {
        private String taskId;
        private long sequenceNumber;
        public Key() { }
        public boolean equals(Object other) {
            return other instanceof Key key
                    && sequenceNumber == key.sequenceNumber
                    && java.util.Objects.equals(taskId, key.taskId);
        }
        public int hashCode() { return java.util.Objects.hash(taskId, sequenceNumber); }
    }
}
