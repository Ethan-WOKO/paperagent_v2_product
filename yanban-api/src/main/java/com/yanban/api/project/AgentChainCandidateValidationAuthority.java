package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts an already verified, exact Candidate-bound Agent sandbox receipt
 * into the retained Candidate Validation authority.  It never trusts model
 * claims: the formal chain proof mechanically checks the exact Candidate
 * action, action order, Candidate paths and the final validation Step's sandbox
 * input fingerprint before this service can append a successful validation.
 */
@Service
public class AgentChainCandidateValidationAuthority {
    static final String POLICY_VERSION = "candidate-validation-agent-chain-v1";

    private final CandidateSandboxValidationRepository validations;
    private final CandidateSandboxValidationService responses;
    private final CandidateChangeArtifactService candidates;
    private final AgentArtifactService artifacts;
    private final AgentCandidateAutoApplicationService autoApplications;
    private final SandboxExecutionProperties sandbox;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public AgentChainCandidateValidationAuthority(
            CandidateSandboxValidationRepository validations,
            CandidateSandboxValidationService responses,
            CandidateChangeArtifactService candidates,
            AgentArtifactService artifacts,
            AgentCandidateAutoApplicationService autoApplications,
            SandboxExecutionProperties sandbox,
            ObjectMapper json,
            JdbcTemplate jdbc) {
        this.validations = Objects.requireNonNull(validations, "validations");
        this.responses = Objects.requireNonNull(responses, "responses");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.autoApplications = Objects.requireNonNull(
                autoApplications, "autoApplications");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.json = Objects.requireNonNull(json, "json");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public Binding bind(
            long userId,
            long projectId,
            long artifactId,
            String projectVersion,
            String taskId,
            String planId,
            String stepId,
            String candidateActionId,
            String candidateWorkspaceId,
            String idempotencyKey) {
        requiredVersion(projectVersion);
        required(taskId, "taskId");
        required(planId, "planId");
        required(stepId, "stepId");
        required(candidateActionId, "candidateActionId");
        required(candidateWorkspaceId, "candidateWorkspaceId");
        requiredKey(idempotencyKey);

        CandidateArtifactResponse candidate = candidates.getCurrent(
                userId, artifactId);
        requireApplicable(projectId, projectVersion, candidate);
        List<Integer> accepted = IntStream.range(
                0, candidate.changes().size()).boxed().toList();
        if (accepted.isEmpty()) {
            throw invalid("Agent Candidate validation requires at least one change");
        }
        var artifact = artifacts.getArtifact(userId, artifactId);
        if (artifact.sessionId() == null || artifact.sessionId() < 1) {
            throw invalid("Candidate is not bound to a Project session");
        }

        AgentCandidateAutoApplicationService.VerificationProof proof =
                autoApplications.proofChain(
                        userId, projectId, projectVersion, taskId, planId,
                        candidateActionId, candidateWorkspaceId, stepId,
                        artifactId);
        String acceptedJson = write(accepted);
        String policyDigest = sha256(POLICY_VERSION);
        String requestHash = sha256(POLICY_VERSION + "\n" + userId + "\n"
                + projectId + "\n" + artifactId + "\n" + projectVersion
                + "\n" + candidate.fingerprint().sha256() + "\n" + planId
                + "\n" + stepId + "\n" + candidateActionId
                + "\n" + candidateWorkspaceId
                + "\n" + proof.receiptId()
                + "\n" + acceptedJson);
        CandidateSandboxValidation replay = validations
                .findByUserIdAndProjectIdAndIdempotencyKey(
                        userId, projectId, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            return binding(replay(replay, requestHash), proof.receiptId());
        }

        String requestDigest = sha256(requestHash + "\n" + policyDigest
                + "\n" + write(proof.paths()) + "\n" + write(proof.argv()));
        String brokerIdentity = sha256("agent-chain-validation\0"
                + proof.receiptId());
        String validationId = UUID.randomUUID().toString();
        SandboxReceipt receipt = new SandboxReceipt(
                proof.receiptId(), idempotencyKey, requestDigest,
                userId, projectId, artifact.sessionId(), artifactId,
                positiveCorrelation(validationId), 1L, projectVersion,
                policyDigest, sandbox.getProvider(),
                SandboxExecutionStatus.SUCCEEDED, proof.exitCode(),
                "", "", false, Map.of(), proof.startedAt(),
                proof.endedAt(), null);
        String receiptJson = write(receipt);
        LocalDateTime now = dbNow();
        CandidateSandboxValidation created = new CandidateSandboxValidation(
                validationId, userId, projectId, artifact.sessionId(),
                artifactId, projectVersion,
                candidate.fingerprint().sha256(), acceptedJson,
                sha256(acceptedJson),
                CandidateValidationProfile.AGENT_CHAIN_EXACT_CANDIDATE.name(),
                idempotencyKey, requestHash, requestDigest, policyDigest,
                null, now);
        created.dispatched(brokerIdentity, "RUNNING", now);
        created.complete("SUCCEEDED", sha256(receiptJson), receiptJson,
                null, now);
        try {
            return binding(responses.response(
                    validations.saveAndFlush(created)), proof.receiptId());
        } catch (DataIntegrityViolationException race) {
            CandidateSandboxValidation winner = validations
                    .findByUserIdAndProjectIdAndIdempotencyKey(
                            userId, projectId, idempotencyKey)
                    .orElseThrow(() -> race);
            return binding(replay(winner, requestHash), proof.receiptId());
        }
    }

    private static Binding binding(
            CandidateValidationResponse validation, String receiptRef) {
        return new Binding(validation.validationId(),
                validation.requestDigest(), validation.receiptDigest(),
                receiptRef);
    }

    private CandidateValidationResponse replay(
            CandidateSandboxValidation value, String requestHash) {
        if (!value.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Agent validation idempotency key is bound to another proof");
        }
        return responses.response(value);
    }

    private static void requireApplicable(
            long projectId,
            String projectVersion,
            CandidateArtifactResponse candidate) {
        if (candidate.projectId() != projectId
                || !candidate.projectVersion().value().equals(projectVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Candidate does not match the frozen Project version");
        }
        if (candidate.governanceStatus()
                        != CandidateChangeSet.GovernanceStatus.VALIDATED
                || !candidate.validation().valid()
                || candidate.applicationStatus()
                        != CandidateChangeSet.ApplicationStatus.NOT_APPLIED) {
            throw invalid("Candidate is stale or invalid");
        }
    }

    private static long positiveCorrelation(String validationId) {
        long value = UUID.fromString(validationId).getMostSignificantBits()
                & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Agent validation proof serialization failed", failure);
        }
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp",
                LocalDateTime.class);
    }

    private static String sha256(String value) {
        return CandidateSandboxValidationService.sha256(value);
    }

    private static void requiredVersion(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "projectVersion must be one SHA-256 digest");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requiredKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("invalid idempotencyKey");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public record Binding(
            String validationId,
            String requestDigest,
            String receiptDigest,
            String sandboxReceiptRef) {
        public Binding {
            required(validationId, "validationId");
            required(requestDigest, "requestDigest");
            required(receiptDigest, "receiptDigest");
            required(sandboxReceiptRef, "sandboxReceiptRef");
        }
    }
}
