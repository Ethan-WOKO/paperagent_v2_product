package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_v2_plan_execution_contexts")
class ProductPlanExecutionContextEntity {
    @Id
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "workspace_id", nullable = false, unique = true, length = 128)
    private String workspaceId;

    @Column(name = "reservation_lease_owner_id", nullable = false)
    private String reservationLeaseOwnerId;

    @Column(name = "reservation_fencing_token", nullable = false)
    private long reservationFencingToken;

    @Column(name = "reservation_request_format_version", nullable = false)
    private int reservationRequestFormatVersion;
    @Column(name = "reservation_request_sha256", nullable = false, length = 64)
    private String reservationRequestSha256;
    @Column(name = "reservation_request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String reservationRequestJson;

    @Column(name = "reservation_result_format_version", nullable = false)
    private int reservationResultFormatVersion;
    @Column(name = "reservation_result_sha256", nullable = false, length = 64)
    private String reservationResultSha256;
    @Column(name = "reservation_result_json", nullable = false, columnDefinition = "LONGTEXT")
    private String reservationResultJson;

    @Column(name = "confirmation_lease_owner_id")
    private String confirmationLeaseOwnerId;
    @Column(name = "confirmation_fencing_token")
    private Long confirmationFencingToken;
    @Column(name = "confirmation_request_format_version")
    private Integer confirmationRequestFormatVersion;
    @Column(name = "confirmation_request_sha256", length = 64)
    private String confirmationRequestSha256;
    @Column(name = "confirmation_request_json", columnDefinition = "LONGTEXT")
    private String confirmationRequestJson;
    @Column(name = "confirmation_result_format_version")
    private Integer confirmationResultFormatVersion;
    @Column(name = "confirmation_result_sha256", length = 64)
    private String confirmationResultSha256;
    @Column(name = "confirmation_result_json", columnDefinition = "LONGTEXT")
    private String confirmationResultJson;
    @Column(name = "source_manifest_fingerprint", length = 64)
    private String sourceManifestFingerprint;

    protected ProductPlanExecutionContextEntity() {
    }

    ProductPlanExecutionContextEntity(
            String planId, String workspaceId, String ownerId, long fence,
            ProductPlanExecutionContextCodec.EncodedPayload request,
            ProductPlanExecutionContextCodec.EncodedPayload result) {
        this.planId = planId;
        this.workspaceId = workspaceId;
        this.reservationLeaseOwnerId = ownerId;
        this.reservationFencingToken = fence;
        this.reservationRequestFormatVersion = request.formatVersion();
        this.reservationRequestSha256 = request.sha256();
        this.reservationRequestJson = request.json();
        this.reservationResultFormatVersion = result.formatVersion();
        this.reservationResultSha256 = result.sha256();
        this.reservationResultJson = result.json();
    }

    void confirm(
            String ownerId, long fence,
            ProductPlanExecutionContextCodec.EncodedPayload request,
            ProductPlanExecutionContextCodec.EncodedPayload result,
            String fingerprint) {
        confirmationLeaseOwnerId = ownerId;
        confirmationFencingToken = fence;
        confirmationRequestFormatVersion = request.formatVersion();
        confirmationRequestSha256 = request.sha256();
        confirmationRequestJson = request.json();
        confirmationResultFormatVersion = result.formatVersion();
        confirmationResultSha256 = result.sha256();
        confirmationResultJson = result.json();
        sourceManifestFingerprint = fingerprint;
    }

    String planId() { return planId; }
    String workspaceId() { return workspaceId; }
    String reservationLeaseOwnerId() { return reservationLeaseOwnerId; }
    long reservationFencingToken() { return reservationFencingToken; }
    int reservationRequestFormatVersion() { return reservationRequestFormatVersion; }
    String reservationRequestSha256() { return reservationRequestSha256; }
    String reservationRequestJson() { return reservationRequestJson; }
    int reservationResultFormatVersion() { return reservationResultFormatVersion; }
    String reservationResultSha256() { return reservationResultSha256; }
    String reservationResultJson() { return reservationResultJson; }
    String confirmationLeaseOwnerId() { return confirmationLeaseOwnerId; }
    Long confirmationFencingToken() { return confirmationFencingToken; }
    Integer confirmationRequestFormatVersion() { return confirmationRequestFormatVersion; }
    String confirmationRequestSha256() { return confirmationRequestSha256; }
    String confirmationRequestJson() { return confirmationRequestJson; }
    Integer confirmationResultFormatVersion() { return confirmationResultFormatVersion; }
    String confirmationResultSha256() { return confirmationResultSha256; }
    String confirmationResultJson() { return confirmationResultJson; }
    String sourceManifestFingerprint() { return sourceManifestFingerprint; }
}
