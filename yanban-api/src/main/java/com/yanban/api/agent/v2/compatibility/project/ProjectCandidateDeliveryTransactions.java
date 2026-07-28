package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ProjectCandidateDeliveryTransactions {
    private final ProjectCandidateDeliveryJpaRepository deliveries;
    private final ProjectCandidateStepAuthorityJpaRepository steps;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final ObjectMapper json;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNew;

    ProjectCandidateDeliveryTransactions(ProjectCandidateDeliveryJpaRepository deliveries,
            ProjectCandidateStepAuthorityJpaRepository steps,
            AgentMessageRepository messages, AgentTurnRepository turns,
            ObjectMapper json, EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries; this.steps = steps; this.messages = messages;
        this.turns = turns; this.json = json; this.entityManager = entityManager;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    ProjectCandidateDeliveryEntity open(Long userId, Long projectId, Long sessionId,
            String requestId, String requestHash, String objective, List<String> paths,
            String version, String owner, String token, Instant expiresAt) {
        var key = new ProjectCandidateDeliveryKey(userId, projectId, sessionId, requestId);
        var found = deliveries.findById(key);
        if (found.isPresent()) return same(found.orElseThrow(), requestHash);
        try {
            return requiresNew.execute(ignored -> {
                AgentSession session = entityManager.find(AgentSession.class, sessionId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (session == null || !userId.equals(session.getUserId())
                        || !projectId.equals(session.getProjectId())) throw invalid();
                var winner = deliveries.findById(key);
                if (winner.isPresent()) return same(winner.orElseThrow(), requestHash);
                AgentMessage userMessage = messages.saveAndFlush(new AgentMessage(
                        sessionId, userId, "user",
                        "V2 Project Candidate proposal: " + objective, null, null));
                AgentTurn turn = turns.saveAndFlush(
                        new AgentTurn(sessionId, userId, userMessage.getId()));
                var created = new ProjectCandidateDeliveryEntity(key, requestHash,
                        objective, encode(paths), version, userMessage.getId(), turn.getId(),
                        owner, token, expiresAt, Instant.now());
                entityManager.persist(created); entityManager.flush();
                return created;
            });
        } catch (RuntimeException conflict) {
            var winner = requiresNew.execute(ignored -> deliveries.findById(key).orElse(null));
            if (winner == null) throw conflict;
            return same(winner, requestHash);
        }
    }

    @Transactional(readOnly = true)
    Optional<ProjectCandidateDeliveryEntity> findMatching(
            ProjectCandidateDeliveryKey key, String hash) {
        return deliveries.findById(key).map(value -> same(value, hash));
    }

    @Transactional
    ProjectCandidateDeliveryEntity bindPlanAndSteps(ProjectCandidateDeliveryKey key,
            String planId, List<StepAuthority> authorities) {
        var delivery = locked(key); delivery.bindPlan(planId); deliveries.saveAndFlush(delivery);
        for (var authority : authorities) {
            var id = new ProjectCandidateStepAuthorityKey(planId, authority.stepId());
            var found = steps.findById(id);
            if (found.isPresent()) {
                var value = found.orElseThrow();
                if (!value.effectKind().equals(authority.kind())
                        || !value.authorityJson().equals(authority.authority())
                        || !value.authoritySha256().equals(authority.hash())) throw conflict();
            } else {
                steps.save(new ProjectCandidateStepAuthorityEntity(planId, authority.stepId(),
                        authority.kind(), authority.authority(), authority.hash()));
            }
        }
        steps.flush(); return delivery;
    }

    @Transactional
    ProjectCandidateDeliveryEntity bindWorkspace(ProjectCandidateDeliveryKey key, String workspace) {
        var value = locked(key); value.bindWorkspace(workspace);
        return deliveries.saveAndFlush(value);
    }

    @Transactional
    ProjectCandidateDeliveryEntity rotateExpiredLease(ProjectCandidateDeliveryKey key,
            String token, Instant expiresAt, Instant now) {
        var value = locked(key);
        if (!"SUCCEEDED".equals(value.status()) && !"FAILED".equals(value.status())
                && !value.leaseExpiresAt().isAfter(now)) {
            value.rotateLease(token, expiresAt); deliveries.saveAndFlush(value);
        }
        return value;
    }

    @Transactional
    void bindCandidate(String planId, Long artifactId, String candidateFingerprint,
                       String diffFingerprint) {
        var delivery = deliveries.findByPlanId(planId).orElseThrow(
                () -> new IllegalStateException("project candidate delivery disappeared"));
        var locked = locked(delivery.id());
        locked.bindCandidate(artifactId, candidateFingerprint, diffFingerprint);
        deliveries.saveAndFlush(locked);
    }

    @Transactional
    ProjectCandidateDeliveryEntity deliver(ProjectCandidateDeliveryKey key) {
        var delivery = locked(key);
        if ("SUCCEEDED".equals(delivery.status()) || "FAILED".equals(delivery.status())) return delivery;
        AgentMessage assistant = messages.saveAndFlush(new AgentMessage(
                key.sessionId(), key.userId(), "assistant",
                "A reviewable Project Candidate is ready. Validate it in the Changes panel, "
                        + "select the intended changes, and explicitly confirm before applying.",
                null, null));
        AgentTurn turn = turns.findById(delivery.turnId()).orElseThrow();
        turn.complete(assistant.getId()); turns.saveAndFlush(turn);
        delivery.complete(assistant.getId());
        return deliveries.saveAndFlush(delivery);
    }

    @Transactional
    ProjectCandidateDeliveryEntity fail(ProjectCandidateDeliveryKey key, String code) {
        var delivery = locked(key);
        if ("FAILED".equals(delivery.status())) return delivery;
        if (delivery.artifactId() != null) return delivery;
        AgentTurn turn = turns.findById(delivery.turnId()).orElseThrow();
        if (AgentTurn.STATUS_RUNNING.equals(turn.getStatus())) {
            turn.fail(null, code); turns.saveAndFlush(turn);
        }
        delivery.fail(code); return deliveries.saveAndFlush(delivery);
    }

    @Transactional(readOnly = true)
    ProjectCandidateDeliveryEntity find(ProjectCandidateDeliveryKey key) {
        return deliveries.findById(key).orElseThrow(
                () -> new IllegalArgumentException("V2 Project Candidate turn was not found"));
    }

    @Transactional(readOnly = true)
    ProjectCandidateEffectAuthority authority(String planId, String stepId) {
        var step = steps.findByPlanIdAndStepId(planId, stepId).orElseThrow(
                () -> new IllegalStateException("project candidate authority unavailable"));
        var delivery = deliveries.findByPlanId(planId).orElseThrow(
                () -> new IllegalStateException("project candidate delivery unavailable"));
        return new ProjectCandidateEffectAuthority(step.effectKind(), step.authorityJson(),
                step.authoritySha256(), delivery.id().userId(), delivery.id().projectId(),
                delivery.id().sessionId(), delivery.turnId(), delivery.projectVersionId(),
                delivery.objective(), paths(delivery));
    }

    List<String> paths(ProjectCandidateDeliveryEntity delivery) {
        try {
            return json.readValue(delivery.pathsJson(), new TypeReference<>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("project candidate paths are invalid");
        }
    }

    private ProjectCandidateDeliveryEntity locked(ProjectCandidateDeliveryKey key) {
        return deliveries.findLocked(key).orElseThrow(
                () -> new IllegalStateException("project candidate delivery disappeared"));
    }
    private static ProjectCandidateDeliveryEntity same(
            ProjectCandidateDeliveryEntity value, String hash) {
        if (!value.requestSha256().equals(hash)) throw new IllegalArgumentException(
                "clientRequestId was already used for another payload");
        return value;
    }
    private String encode(List<String> paths) {
        try { return json.writeValueAsString(paths); }
        catch (Exception failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("project candidate request is invalid");
    }
    private static IllegalStateException conflict() {
        return new IllegalStateException("project candidate authority conflict");
    }
    record StepAuthority(String stepId, String kind, String authority, String hash) {}
}
