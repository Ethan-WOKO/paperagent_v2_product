package com.yanban.api.agent.v2.intake;

import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnQueryService;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnResponse;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnSnapshot;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.project.CandidateValidationStatusProjectionService;
import com.yanban.core.agent.AgentArtifact;
import com.yanban.core.agent.AgentArtifactRepository;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ReceiptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner/session-qualified read model for newly visible V2 intakes only. */
@Service
public class V2TurnHistoryQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final V2TurnIntakeJpaRepository intakes;
    private final V2AdaptiveTurnQueryService adaptiveTurns;
    private final AgentMessageRepository messages;
    private final V2EffectHistorySource effectHistory;
    private final AgentArtifactRepository artifacts;
    private final CandidateValidationStatusProjectionService validations;

    public V2TurnHistoryQueryService(
            V2TurnIntakeJpaRepository intakes,
            V2AdaptiveTurnQueryService adaptiveTurns,
            AgentMessageRepository messages,
            V2EffectHistorySource effectHistory,
            AgentArtifactRepository artifacts,
            CandidateValidationStatusProjectionService validations) {
        this.intakes = intakes;
        this.adaptiveTurns = adaptiveTurns;
        this.messages = messages;
        this.effectHistory = effectHistory;
        this.artifacts = artifacts;
        this.validations = validations;
    }

    @Transactional(readOnly = true)
    public List<V2TurnHistoryResponse> list(
            Long userId, Long sessionId) {
        return list(userId, sessionId, DEFAULT_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<V2TurnHistoryResponse> list(
            Long userId, Long sessionId, Integer limit) {
        if (userId == null || sessionId == null) {
            throw new IllegalArgumentException(
                    "V2 turn history authority is required");
        }
        return intakes
                .findByUserIdAndSessionIdAndHistoryVisibleTrueOrderByCreatedAtDescIdDesc(
                        userId, sessionId,
                        PageRequest.of(0, boundedLimit(limit)))
                .stream()
                .map(value -> project(userId, sessionId, value))
                .toList();
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private V2TurnHistoryResponse project(
            Long userId,
            Long sessionId,
            V2TurnIntakeEntity intake) {
        Optional<V2AdaptiveTurnSnapshot> adaptive = adaptiveTurns.find(
                userId, sessionId, intake.clientRequestId());
        String status;
        String route = null;
        String planId = intake.planId();
        String projectVersion = null;
        List<V2AdaptiveTurnResponse.Step> steps = List.of();
        String finalText = null;
        Long candidateArtifactId = null;
        List<String> outputPaths = List.of();
        String errorCode = null;
        Instant updatedAt = intake.updatedAt();

        if (V2TurnIntakeEntity.DIRECT.equals(intake.status())) {
            status = "SUCCEEDED";
            route = "DIRECT";
            finalText = directAnswer(intake);
        } else if (V2TurnIntakeEntity.FAILED.equals(intake.status())) {
            status = "FAILED";
            errorCode = intake.failureCode();
        } else if (V2TurnIntakeEntity.RUNNING.equals(intake.status())) {
            status = "PLANNING";
        } else if (V2TurnIntakeEntity.PERSISTENT.equals(intake.status())) {
            route = "PERSISTENT_PLAN_EXECUTE";
            status = "RUNNING";
            if (adaptive.isPresent()) {
                V2AdaptiveTurnSnapshot snapshot = adaptive.orElseThrow();
                V2AdaptiveTurnResponse response = snapshot.response();
                status = response.status();
                route = response.route();
                planId = response.planId();
                projectVersion = response.projectVersion();
                steps = response.steps();
                finalText = response.finalText();
                candidateArtifactId = response.candidateArtifactId();
                outputPaths = response.outputPaths();
                errorCode = response.errorCode();
                updatedAt = later(updatedAt, snapshot.updatedAt());
            }
        } else {
            throw new IllegalStateException(
                    "V2 turn intake read model is invalid");
        }

        boolean candidateExists = candidateExists(
                userId, sessionId, candidateArtifactId);
        var automatic = candidateExists
                ? automaticValidation(planId) : null;
        var confirmation = candidateExists
                ? validations.latest(
                                userId, sessionId, candidateArtifactId)
                        .map(value -> new V2TurnHistoryResponse
                                .ConfirmationValidation(
                                value.status(), value.decisionStatus()))
                        .orElse(null)
                : null;
        return new V2TurnHistoryResponse(
                intake.clientRequestId(), intake.content(), status, route,
                planId, projectVersion, steps, finalText,
                candidateArtifactId, outputPaths, errorCode,
                intake.createdAt(), updatedAt, automatic, confirmation);
    }

    private String directAnswer(V2TurnIntakeEntity intake) {
        AgentMessage message = messages.findById(
                        intake.assistantMessageId())
                .filter(value -> intake.userId().equals(value.getUserId()))
                .filter(value -> intake.sessionId().equals(
                        value.getSessionId()))
                .filter(value -> "assistant".equalsIgnoreCase(
                        value.getRole()))
                .orElseThrow(() -> new IllegalStateException(
                        "V2 direct answer authority is invalid"));
        return message.getContent();
    }

    private boolean candidateExists(
            Long userId, Long sessionId, Long artifactId) {
        if (artifactId == null) {
            return false;
        }
        return artifacts.findByIdAndUserId(artifactId, userId)
                .filter(value -> sessionId.equals(value.getSessionId()))
                .filter(value -> AgentArtifact.STATUS_ACTIVE.equals(
                        value.getStatus()))
                .filter(value -> AgentArtifactService
                        .CANDIDATE_CHANGESET_SOURCE_TYPE.equals(
                                value.getSourceType()))
                .isPresent();
    }

    private V2TurnHistoryResponse.AgentAutomaticValidation
            automaticValidation(String planId) {
        if (planId == null) {
            return null;
        }
        List<V2EffectHistorySource.Entry> history = effectHistory.inspect(
                new PlanId(planId));
        V2EffectHistorySource.Entry latest = null;
        for (V2EffectHistorySource.Entry entry : history) {
            if ("sandbox.execute".equals(
                    entry.intent().intent().kind())) {
                latest = entry;
            }
        }
        if (latest == null || !latest.completed()) {
            return null;
        }
        var receipt = latest.result().receipt();
        if (receipt.status() != ReceiptStatus.SUCCESS
                || receipt.exitCode().orElse(-1) != 0) {
            return null;
        }
        return new V2TurnHistoryResponse.AgentAutomaticValidation(
                "PASSED", "E2B", 0, receipt.id().value());
    }

    private static Instant later(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }
}
