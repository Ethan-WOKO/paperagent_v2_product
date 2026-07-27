package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_v2_plan_bootstraps")
class ProductPlanBootstrapEntity {
    @Id
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "task_frame_id", nullable = false, unique = true, length = 128)
    private String taskFrameId;

    @Column(name = "payload_format_version", nullable = false)
    private int payloadFormatVersion;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductPlanBootstrapEntity() {
    }

    ProductPlanBootstrapEntity(
            String planId,
            String taskFrameId,
            int payloadFormatVersion,
            String payloadSha256,
            String payloadJson,
            Instant createdAt) {
        this.planId = planId;
        this.taskFrameId = taskFrameId;
        this.payloadFormatVersion = payloadFormatVersion;
        this.payloadSha256 = payloadSha256;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    String planId() {
        return planId;
    }

    String taskFrameId() {
        return taskFrameId;
    }

    int payloadFormatVersion() {
        return payloadFormatVersion;
    }

    String payloadSha256() {
        return payloadSha256;
    }

    String payloadJson() {
        return payloadJson;
    }
}
