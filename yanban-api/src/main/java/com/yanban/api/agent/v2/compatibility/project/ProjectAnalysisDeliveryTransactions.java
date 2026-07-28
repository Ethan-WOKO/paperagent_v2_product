package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ProjectAnalysisDeliveryTransactions {
    private final ProjectAnalysisDeliveryJpaRepository deliveries;
    private final ProjectAnalysisStepAuthorityJpaRepository steps;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final ObjectMapper json;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNew;

    ProjectAnalysisDeliveryTransactions(
            ProjectAnalysisDeliveryJpaRepository deliveries,
            ProjectAnalysisStepAuthorityJpaRepository steps,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            ObjectMapper json,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.steps = steps;
        this.messages = messages;
        this.turns = turns;
        this.json = json;
        this.entityManager = entityManager;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    ProjectAnalysisDeliveryEntity open(
            Long userId, Long projectId, Long sessionId, String requestId,
            String requestHash, String objective, List<String> paths,
            String searchQuery, int maxSearchResults, String projectVersion,
            String leaseOwner, String leaseToken, Instant leaseExpiresAt) {
        ProjectAnalysisDeliveryKey key = new ProjectAnalysisDeliveryKey(
                userId, projectId, sessionId, requestId);
        var existing = deliveries.findById(key);
        if (existing.isPresent()) return same(existing.orElseThrow(), requestHash);
        try {
            return requiresNew.execute(status -> {
                AgentSession session = entityManager.find(
                        AgentSession.class, sessionId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (session == null || !userId.equals(session.getUserId())
                        || !projectId.equals(session.getProjectId())) {
                    throw new IllegalArgumentException(
                            "project agent session was not found");
                }
                var winner = deliveries.findById(key);
                if (winner.isPresent()) {
                    return same(winner.orElseThrow(), requestHash);
                }
                AgentMessage userMessage = messages.saveAndFlush(
                        new AgentMessage(sessionId, userId, "user",
                                "V2 read-only Project analysis: " + objective,
                                null, null));
                AgentTurn turn = turns.saveAndFlush(
                        new AgentTurn(sessionId, userId, userMessage.getId()));
                ProjectAnalysisDeliveryEntity created =
                        new ProjectAnalysisDeliveryEntity(
                                key, requestHash, objective, encode(paths),
                                searchQuery, maxSearchResults, projectVersion,
                                userMessage.getId(), turn.getId(), leaseOwner,
                                leaseToken, leaseExpiresAt, Instant.now());
                entityManager.persist(created);
                entityManager.flush();
                return created;
            });
        } catch (RuntimeException conflict) {
            ProjectAnalysisDeliveryEntity winner = requiresNew.execute(
                    ignored -> deliveries.findById(key).orElse(null));
            if (winner == null) throw conflict;
            return same(winner, requestHash);
        }
    }

    @Transactional
    ProjectAnalysisDeliveryEntity bindPlanAndSteps(
            ProjectAnalysisDeliveryKey key, String planId,
            List<StepAuthority> authorities) {
        ProjectAnalysisDeliveryEntity delivery = locked(key);
        delivery.bindPlan(planId);
        deliveries.saveAndFlush(delivery);
        for (StepAuthority authority : authorities) {
            ProjectAnalysisStepAuthorityKey stepKey =
                    new ProjectAnalysisStepAuthorityKey(
                            planId, authority.stepId());
            var existing = steps.findById(stepKey);
            if (existing.isPresent()) {
                ProjectAnalysisStepAuthorityEntity value =
                        existing.orElseThrow();
                if (!value.effectKind().equals(authority.kind())
                        || !value.argumentSha256().equals(authority.hash())
                        || !value.argumentJson().equals(authority.arguments())) {
                    throw new IllegalStateException(
                            "project analysis step authority conflict");
                }
            } else {
                steps.save(new ProjectAnalysisStepAuthorityEntity(
                        planId, authority.stepId(), authority.kind(),
                        authority.arguments(), authority.hash()));
            }
        }
        steps.flush();
        return delivery;
    }

    @Transactional
    ProjectAnalysisDeliveryEntity bindWorkspace(
            ProjectAnalysisDeliveryKey key, String workspaceId) {
        ProjectAnalysisDeliveryEntity delivery = locked(key);
        delivery.bindWorkspace(workspaceId);
        return deliveries.saveAndFlush(delivery);
    }

    @Transactional
    ProjectAnalysisDeliveryEntity deliver(
            ProjectAnalysisDeliveryKey key, String planId,
            String synthesisId, String narrative) {
        ProjectAnalysisDeliveryEntity delivery = locked(key);
        if ("SUCCEEDED".equals(delivery.status())) return delivery;
        AgentMessage assistant = messages.saveAndFlush(new AgentMessage(
                key.sessionId(), key.userId(), "assistant",
                narrative, null, null));
        AgentTurn turn = turns.findById(delivery.turnId())
                .orElseThrow(() -> new IllegalStateException(
                        "project analysis turn disappeared"));
        turn.complete(assistant.getId());
        turns.saveAndFlush(turn);
        delivery.complete(planId, synthesisId, assistant.getId());
        return deliveries.saveAndFlush(delivery);
    }

    @Transactional(readOnly = true)
    ProjectAnalysisDeliveryEntity find(
            ProjectAnalysisDeliveryKey key) {
        return deliveries.findById(key).orElseThrow(() ->
                new IllegalArgumentException(
                        "V2 Project analysis turn was not found"));
    }

    @Transactional(readOnly = true)
    ProjectAnalysisStepAuthorityEntity authority(
            String planId, String stepId) {
        return steps.findByPlanIdAndStepId(planId, stepId)
                .orElseThrow(() -> new IllegalStateException(
                        "project effect authority is unavailable"));
    }

    @Transactional(readOnly = true)
    List<String> paths(ProjectAnalysisDeliveryEntity delivery) {
        try {
            return json.readValue(
                    delivery.pathsJson(), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "project analysis delivery is invalid");
        }
    }

    private ProjectAnalysisDeliveryEntity locked(
            ProjectAnalysisDeliveryKey key) {
        return deliveries.findLocked(key).orElseThrow(() ->
                new IllegalStateException(
                        "project analysis delivery disappeared"));
    }

    private static ProjectAnalysisDeliveryEntity same(
            ProjectAnalysisDeliveryEntity value, String hash) {
        if (!value.requestSha256().equals(hash)) {
            throw new IllegalArgumentException(
                    "clientRequestId was already used for another payload");
        }
        return value;
    }

    private String encode(List<String> paths) {
        try {
            return json.writeValueAsString(paths);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "project paths are not encodable");
        }
    }

    record StepAuthority(
            String stepId, String kind, String arguments, String hash) {
    }
}
