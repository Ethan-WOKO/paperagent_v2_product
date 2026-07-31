package com.yanban.api.agent.v2.adaptive;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_v2_adaptive_turns")
class V2AdaptiveTurnEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "intake_id", nullable = false) private Long intakeId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "client_request_id", nullable = false, length = 128)
    private String clientRequestId;
    @Column(nullable = false, length = 32) private String route;
    @Column(name = "plan_id", length = 128) private String planId;
    @Column(name = "project_version") private String projectVersion;
    @Column(nullable = false, length = 32) private String status;
    @Lob @Column(name = "steps_json", nullable = false, columnDefinition = "LONGTEXT")
    private String stepsJson;
    @Lob @Column(name = "final_text", columnDefinition = "LONGTEXT")
    private String finalText;
    @Column(name = "candidate_artifact_id")
    private Long candidateArtifactId;
    @Lob @Column(name = "output_paths_json", nullable = false, columnDefinition = "LONGTEXT")
    private String outputPathsJson;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "reflection_count", nullable = false) private int reflectionCount;
    @Column(name = "replan_count", nullable = false) private int replanCount;
    @Column(name = "repair_count", nullable = false) private int repairCount;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected V2AdaptiveTurnEntity() {}
    V2AdaptiveTurnEntity(
            Long intakeId, Long userId, Long sessionId,
            String clientRequestId, String route, String planId,
            String projectVersion, String status, String stepsJson,
            String finalText, Long candidateArtifactId,
            String outputPathsJson, String errorCode, Instant now) {
        this.intakeId = intakeId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.clientRequestId = clientRequestId;
        this.route = route;
        this.planId = planId;
        this.projectVersion = projectVersion;
        this.status = status;
        this.stepsJson = stepsJson;
        this.finalText = finalText;
        this.candidateArtifactId = candidateArtifactId;
        this.outputPathsJson = outputPathsJson;
        this.errorCode = errorCode;
        this.createdAt = now;
        this.updatedAt = now;
    }
    Long userId() { return userId; }
    Long sessionId() { return sessionId; }
    String clientRequestId() { return clientRequestId; }
    String route() { return route; }
    String planId() { return planId; }
    String projectVersion() { return projectVersion; }
    String status() { return status; }
    String stepsJson() { return stepsJson; }
    String finalText() { return finalText; }
    Long candidateArtifactId() { return candidateArtifactId; }
    String outputPathsJson() { return outputPathsJson; }
    String errorCode() { return errorCode; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }

    static V2AdaptiveTurnEntity running(
            Long intakeId, Long userId, Long sessionId,
            String requestId, String planId, String projectVersion,
            String stepsJson, Instant now) {
        return new V2AdaptiveTurnEntity(
                intakeId, userId, sessionId, requestId,
                "PERSISTENT_PLAN_EXECUTE", planId, projectVersion,
                "RUNNING", stepsJson, null, null, "[]", null, now);
    }

    void finish(
            String newStatus, String newStepsJson, String text,
            Long artifactId, String pathsJson, String code,
            int reflections, int replans, int repairs, Instant now) {
        if (!"RUNNING".equals(status)) {
            throw new IllegalStateException("adaptive turn is terminal");
        }
        status = newStatus;
        stepsJson = newStepsJson;
        finalText = text;
        candidateArtifactId = artifactId;
        outputPathsJson = pathsJson;
        errorCode = code;
        reflectionCount = reflections;
        replanCount = replans;
        repairCount = repairs;
        updatedAt = now;
    }

    void progress(
            String newStepsJson, int reflections,
            int replans, int repairs, Instant now) {
        if (!"RUNNING".equals(status)) {
            throw new IllegalStateException("adaptive turn is terminal");
        }
        stepsJson = newStepsJson;
        reflectionCount = reflections;
        replanCount = replans;
        repairCount = repairs;
        updatedAt = now;
    }
}
