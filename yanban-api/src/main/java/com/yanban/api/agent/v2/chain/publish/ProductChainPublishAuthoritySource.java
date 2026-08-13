package com.yanban.api.agent.v2.chain.publish;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.chain.recovery.ProductChainFinalizationRecoverySource;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRetainedAuthoritySource;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable lookup of Project revision publish success and failure authority. */
@Component
public final class ProductChainPublishAuthoritySource implements
        ProductChainFinalizationRecoverySource.PublishFailureLookup,
        ProductChainRetainedAuthoritySource.PublishAttemptLookup {
    public static final String REF_PREFIX = "project-revision-operation:";
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainPublishCandidateAuthority candidateAuthority;
    private final CandidateChangeArtifactService candidates;

    public ProductChainPublishAuthoritySource(
            NamedParameterJdbcTemplate jdbc,
            ProductChainPublishCandidateAuthority candidateAuthority,
            CandidateChangeArtifactService candidates) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.candidateAuthority = Objects.requireNonNull(
                candidateAuthority, "candidateAuthority");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    public Optional<Operation> findByIdempotencyKey(
            long userId, long projectId, String idempotencyKey) {
        return query("""
                SELECT id, user_id, project_id, operation_type,
                       idempotency_key, request_hash, base_revision_id,
                       base_version, candidate_artifact_id,
                       candidate_fingerprint, outcome, error_code,
                       result_revision_id, result_version,
                       accepted_change_indexes, rejected_change_indexes
                  FROM project_revision_operations
                 WHERE user_id = :userId
                   AND project_id = :projectId
                   AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("projectId", projectId)
                .addValue("idempotencyKey", idempotencyKey));
    }

    public Optional<Operation> findByFormalRef(String formalRef) {
        long operationId = operationId(formalRef);
        return query("""
                SELECT id, user_id, project_id, operation_type,
                       idempotency_key, request_hash, base_revision_id,
                       base_version, candidate_artifact_id,
                       candidate_fingerprint, outcome, error_code,
                       result_revision_id, result_version,
                       accepted_change_indexes, rejected_change_indexes
                  FROM project_revision_operations
                 WHERE id = :operationId
                """, new MapSqlParameterSource(
                "operationId", operationId));
    }

    public Operation requireExactOperation(
            Operation operation,
            ChainProjectPublishPort.PublishCommand command) {
        Objects.requireNonNull(operation, "operation");
        ExactContext context = requireExactBinding(operation, command);
        require(operation.succeeded() || operation.failed(),
                "publish operation has no terminal outcome");
        String expectedHash;
        if (operation.succeeded()) {
            require(operation.errorCode() == null,
                    "successful publish operation carries an error code");
            requireBaseRevision(operation);
            requireRevision(operation);
            expectedHash = automaticRequestHash(context.receiptRef(),
                    command.artifactId(), command.baseProjectVersion(),
                    context.acceptedChangeIndexes());
        } else {
            publishErrorCode(operation);
            require(operation.baseRevisionId() == null,
                    "chain-recorded publish failure changed its base proof shape");
            require(operation.resultRevisionId() == null
                            && operation.resultVersion() == null,
                    "failed publish operation carries a result identity");
            expectedHash = chainFailureHash(command, operation.errorCode(),
                    operation.acceptedChangeIndexes());
        }
        require(operation.requestHash().equals(expectedHash),
                "publish request hash does not bind Validation receipt");
        return operation;
    }

    Operation requireConvertibleWorkflowFailure(
            Operation operation,
            ChainProjectPublishPort.PublishCommand command,
            ChainProjectPublishPort.ErrorCode expectedError) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(expectedError, "expectedError");
        ExactContext context = requireExactBinding(operation, command);
        require(operation.failed(),
                "workflow publish operation is not a failed operation");
        require(operation.resultRevisionId() == null
                        && operation.resultVersion() == null,
                "workflow publish failure carries a result identity");
        require(workflowErrorCode(operation.errorCode()) == expectedError,
                "workflow publish failure classification changed");
        requireBaseRevision(operation);
        require(operation.requestHash().equals(automaticRequestHash(
                        context.receiptRef(), command.artifactId(),
                        command.baseProjectVersion(),
                        context.acceptedChangeIndexes())),
                "workflow publish failure does not bind the automatic request");
        return operation;
    }

    private ExactContext requireExactBinding(
            Operation operation,
            ChainProjectPublishPort.PublishCommand command) {
        boolean fixedChainFailure = operation.failed()
                && operation.errorCode() != null
                && operation.errorCode().startsWith("CHAIN_PUBLISH_");
        ExactContext context = fixedChainFailure
                ? failureContext(command, operation.acceptedChangeIndexes())
                : context(command);
        require(operation.userId() == context.userId()
                        && operation.projectId() == context.projectId()
                        && "APPLICATION".equals(operation.operationType())
                        && operation.idempotencyKey().equals(
                        command.idempotencyKey())
                        && operation.baseVersion().equals(
                        command.baseProjectVersion())
                        && Objects.equals(operation.candidateArtifactId(),
                        command.artifactId())
                        && context.candidateFingerprint().equals(
                        operation.candidateFingerprint())
                        && operation.acceptedChangeIndexes().equals(
                        context.acceptedChangeIndexes())
                        && operation.rejectedChangeIndexes().equals(List.of()),
                "publish operation does not bind exact chain authority");
        return context;
    }

    private void requireBaseRevision(Operation operation) {
        require(operation.baseRevisionId() != null
                        && operation.baseRevisionId() > 0,
                "workflow publish operation lacks base revision identity");
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM project_revisions revision
                 WHERE revision.id = :baseRevisionId
                   AND revision.project_id = :projectId
                   AND revision.user_id = :userId
                   AND revision.project_version = :baseVersion
                """, new MapSqlParameterSource()
                .addValue("baseRevisionId", operation.baseRevisionId())
                .addValue("projectId", operation.projectId())
                .addValue("userId", operation.userId())
                .addValue("baseVersion", operation.baseVersion()),
                Long.class);
        require(count != null && count == 1L,
                "workflow publish base revision is not exact authority");
    }

    public Operation requireExactSuccess(
            String formalRef,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        Operation operation = findByFormalRef(formalRef).orElseThrow(() ->
                new IllegalStateException("publish operation is missing"));
        ChainProjectPublishPort.PublishCommand command = command(
                readiness, check, attempt(operation.idempotencyKey(),
                        readiness, check));
        requireExactOperation(operation, command);
        require(operation.succeeded(), "publish operation did not succeed");
        return operation;
    }

    public Operation requireExactFailure(
            String formalRef,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        Operation operation = findByFormalRef(formalRef).orElseThrow(() ->
                new IllegalStateException("publish failure is missing"));
        int attempt = attempt(operation.idempotencyKey(), readiness, check);
        ChainProjectPublishPort.PublishCommand command = command(
                readiness, check, attempt);
        requireExactOperation(operation, command);
        require(operation.failed(), "publish operation did not fail");
        require(publishErrorCode(operation) != ChainProjectPublishPort
                        .ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
                        || attempt == ChainRuntimePolicy.requireVersion(
                        check.runtimePolicyVersion())
                        .finalizationMechanicalAttemptsTotal(),
                "retryable publish attempt is not a terminal handoff");
        return operation;
    }

    public static ChainProjectPublishPort.ErrorCode publishErrorCode(
            Operation operation) {
        String value = operation.errorCode();
        if (value != null && value.startsWith("CHAIN_PUBLISH_")) {
            try {
                return ChainProjectPublishPort.ErrorCode.valueOf(
                        value.substring("CHAIN_PUBLISH_".length()));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException(
                        "unknown fixed publish error code", unknown);
            }
        }
        throw new IllegalStateException(
                "publish failure has no known fixed CHAIN_PUBLISH error code");
    }

    private static ChainProjectPublishPort.ErrorCode workflowErrorCode(
            String value) {
        if ("HTTP_409".equals(value) || "HTTP_412".equals(value)
                || "HTTP_428".equals(value)) {
            return ChainProjectPublishPort.ErrorCode.STALE_VERSION_FENCE;
        }
        if (value != null && value.matches("HTTP_4\\d\\d")) {
            return ChainProjectPublishPort.ErrorCode
                    .VALIDATION_BINDING_MISMATCH;
        }
        if (value != null && value.matches("HTTP_5\\d\\d")) {
            return ChainProjectPublishPort.ErrorCode
                    .AUTHORITY_TEMPORARILY_UNAVAILABLE;
        }
        throw new IllegalStateException(
                "workflow publish failure has no recognized HTTP error code");
    }

    public Optional<Operation> findExactSuccess(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        if (readiness.publishRequirement()
                == io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED) {
            return Optional.empty();
        }
        return exactSequence(readiness, check).success();
    }

    @Override
    public Optional<ProductChainRetainedAuthoritySource.PublishAttempt> find(
            ProductChainRetainedAuthoritySource.PublishAttemptQuery query) {
        Objects.requireNonNull(query, "query");
        requireExactAttemptQuery(query);
        if (query.readiness().publishRequirement()
                == io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED) {
            return Optional.empty();
        }
        List<Operation> attempts = exactAttempts(
                query.readiness(), query.check());
        if (attempts.isEmpty()) return Optional.empty();
        require(attempts.stream().allMatch(operation ->
                        operation.userId() == query.task().userId()
                                && operation.projectId()
                                == query.task().projectId()),
                "publish attempt does not bind the queried task owner");
        Operation latest = attempts.get(attempts.size() - 1);
        return Optional.of(new ProductChainRetainedAuthoritySource
                .PublishAttempt(latest.formalRef(),
                operationIdentityDigest(latest), latest.outcome()));
    }

    @Override
    public Optional<ProductChainFinalizationRecoverySource.PublishFailure> find(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(check, "check");
        require(transition.taskId().equals(readiness.taskId())
                        && transition.taskId().equals(check.taskId())
                        && transition.transitionId().equals(
                        check.transitionId())
                        && transition.transitionType()
                        == ChainTransitionType.FINALIZATION
                        && transition.sourceDecisionId().equals(
                        readiness.reviewDecisionId())
                        && transition.targetIdentityDigest().equals(
                        readinessDigest(readiness))
                        && readiness.readinessId().equals(
                        check.readinessId())
                        && check.resultStatus()
                        == io.paperagent.v2.chain.ChainFinalization.Outcome.PASSED,
                "publish recovery lookup authority is not exact");

        if (readiness.publishRequirement()
                == io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED) {
            return Optional.empty();
        }

        PublishSequence sequence = exactSequence(readiness, check);
        if (sequence.success().isPresent()) return Optional.empty();
        Operation terminalFailure = sequence.failure().orElse(null);
        if (terminalFailure == null) return Optional.empty();
        int attempts = ChainRuntimePolicy.requireVersion(
                check.runtimePolicyVersion())
                .finalizationMechanicalAttemptsTotal();
        ChainProjectPublishPort.ErrorCode error = publishErrorCode(
                terminalFailure);
        return Optional.of(new ProductChainFinalizationRecoverySource
                .PublishFailure(terminalFailure.formalRef(), error,
                error == ChainProjectPublishPort.ErrorCode
                        .AUTHORITY_TEMPORARILY_UNAVAILABLE
                        && attempt(terminalFailure.idempotencyKey(),
                        readiness, check) < attempts));
    }

    private PublishSequence exactSequence(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        List<Operation> attempts = exactAttempts(readiness, check);
        Operation success = null;
        Operation failure = null;
        int maximumAttempts = ChainRuntimePolicy.requireVersion(
                check.runtimePolicyVersion())
                .finalizationMechanicalAttemptsTotal();
        for (Operation operation : attempts) {
            int attempt = attempt(operation.idempotencyKey(), readiness, check);
            if (operation.succeeded()) {
                success = operation;
                continue;
            }
            ChainProjectPublishPort.ErrorCode error = publishErrorCode(
                    operation);
            boolean onlyAllowedContinuation = attempt == 1
                    && maximumAttempts > 1
                    && error == ChainProjectPublishPort.ErrorCode
                    .AUTHORITY_TEMPORARILY_UNAVAILABLE;
            if (!onlyAllowedContinuation) failure = operation;
        }
        require(success == null || failure == null,
                "publish attempt history has multiple terminal outcomes");
        return new PublishSequence(Optional.ofNullable(success),
                Optional.ofNullable(failure));
    }

    private List<Operation> exactAttempts(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(check, "check");
        int attempts = ChainRuntimePolicy.requireVersion(
                check.runtimePolicyVersion())
                .finalizationMechanicalAttemptsTotal();
        java.util.ArrayList<Operation> found = new java.util.ArrayList<>();
        boolean missingEarlierAttempt = false;
        boolean terminalSeen = false;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ChainProjectPublishPort.PublishCommand command = command(
                    readiness, check, attempt);
            Operation operation = findByIdempotencyKeyForTask(
                    readiness.taskId(), command.idempotencyKey())
                    .orElse(null);
            if (operation == null) {
                missingEarlierAttempt = true;
                continue;
            }
            require(!missingEarlierAttempt,
                    "publish attempt history contains a sequence gap");
            require(!terminalSeen,
                    "publish attempt exists after a terminal operation");
            requireExactOperation(operation, command);
            require(operation.succeeded() || operation.failed(),
                    "publish operation has no terminal outcome");
            found.add(operation);
            if (operation.succeeded()) {
                terminalSeen = true;
                continue;
            }
            ChainProjectPublishPort.ErrorCode error = publishErrorCode(
                    operation);
            boolean onlyAllowedContinuation = attempt == 1
                    && attempts > 1
                    && error == ChainProjectPublishPort.ErrorCode
                    .AUTHORITY_TEMPORARILY_UNAVAILABLE;
            if (!onlyAllowedContinuation) {
                terminalSeen = true;
            }
        }
        return List.copyOf(found);
    }

    private static void requireExactAttemptQuery(
            ProductChainRetainedAuthoritySource.PublishAttemptQuery query) {
        ChainPersistenceRecords.TaskRecord task = query.task();
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                query.readiness();
        ChainPersistenceRecords.FinalizationCheckRecord check = query.check();
        require(task.projectId() != null
                        && task.taskId().equals(readiness.taskId())
                        && task.taskId().equals(check.taskId())
                        && task.initialProjectVersion().equals(
                        readiness.projectVersion())
                        && readiness.readinessId().equals(check.readinessId())
                        && readiness.taskFrameId().equals(check.taskFrameId())
                        && readiness.finalPlanRevisionId().equals(
                        check.finalPlanRevisionId())
                        && readiness.acceptedSet().sha256().equals(
                        check.acceptedSetSha256())
                        && readiness.candidateKey().equals(
                        check.candidateKey())
                        && readiness.workspaceId().equals(check.workspaceId())
                        && readiness.validationId().equals(
                        check.validationId())
                        && Objects.equals(readiness.validationRequestDigest(),
                        check.validationRequestDigest())
                        && Objects.equals(readiness.validationReceiptDigest(),
                        check.validationReceiptDigest())
                        && readiness.publishRequirementDigest().equals(
                        check.publishRequirementDigest())
                        && readiness.instructionId().equals(
                        check.instructionId())
                        && readiness.projectVersion().equals(
                        check.projectVersion())
                        && check.resultStatus()
                        == io.paperagent.v2.chain.ChainFinalization.Outcome.PASSED
                        && readiness.artifactId() != null,
                "publish attempt lookup authority is not exact");
    }

    private static String operationIdentityDigest(Operation operation) {
        return sha256(operation.operationId() + "\0" + operation.userId()
                + "\0" + operation.projectId() + "\0"
                + operation.operationType() + "\0"
                + operation.idempotencyKey() + "\0"
                + operation.requestHash() + "\0"
                + Objects.toString(operation.baseRevisionId(), "NONE")
                + "\0" + operation.baseVersion() + "\0"
                + Objects.toString(operation.candidateArtifactId(), "NONE")
                + "\0" + Objects.toString(
                operation.candidateFingerprint(), "NONE") + "\0"
                + operation.outcome() + "\0"
                + Objects.toString(operation.errorCode(), "NONE") + "\0"
                + Objects.toString(operation.resultRevisionId(), "NONE")
                + "\0" + Objects.toString(
                operation.resultVersion(), "NONE") + "\0"
                + operation.acceptedChangeIndexes() + "\0"
                + operation.rejectedChangeIndexes());
    }

    public static String formalRef(long operationId) {
        if (operationId < 1) {
            throw new IllegalArgumentException(
                    "operationId must be positive");
        }
        return REF_PREFIX + operationId;
    }

    public static String chainFailureHash(
            ChainProjectPublishPort.PublishCommand command,
            String fixedErrorCode,
            List<Integer> acceptedChangeIndexes) {
        List<Integer> canonical = canonicalAcceptedIndexes(
                acceptedChangeIndexes);
        return sha256("CHAIN_FAILURE\0" + command.taskId() + "\0"
                + command.readinessId() + "\0"
                + command.finalizationCheckId() + "\0"
                + command.attemptNo() + "\0" + command.idempotencyKey()
                + "\0" + command.baseProjectVersion() + "\0"
                + command.artifactId() + "\0" + command.candidateKey()
                + "\0" + command.validationId() + "\0"
                + command.validationRequestDigest() + "\0"
                + command.validationReceiptDigest() + "\0"
                + fixedErrorCode + "\0" + canonical);
    }

    static List<Integer> canonicalAcceptedIndexes(List<Integer> indexes) {
        Objects.requireNonNull(indexes, "acceptedChangeIndexes");
        require(indexes.stream().allMatch(Objects::nonNull),
                "accepted change indexes contain null");
        require(indexes.stream().allMatch(value -> value >= 0),
                "accepted change indexes must be non-negative");
        List<Integer> canonical = indexes.stream().sorted().distinct().toList();
        require(canonical.size() == indexes.size(),
                "accepted change indexes must be unique");
        require(canonical.equals(indexes),
                "accepted change indexes must use canonical order");
        return canonical;
    }

    private Optional<Operation> findByIdempotencyKeyForTask(
            String taskId, String idempotencyKey) {
        var rows = jdbc.queryForList("""
                SELECT operation.id, operation.user_id,
                       operation.project_id, operation.operation_type,
                       operation.idempotency_key, operation.request_hash,
                       operation.base_revision_id, operation.base_version,
                       operation.candidate_artifact_id,
                       operation.candidate_fingerprint, operation.outcome,
                       operation.error_code, operation.result_revision_id,
                       operation.result_version,
                       operation.accepted_change_indexes,
                       operation.rejected_change_indexes
                  FROM agent_v2_chain_tasks task
                  JOIN project_revision_operations operation
                    ON operation.user_id = task.user_id
                   AND operation.project_id = task.project_id
                 WHERE task.task_id = :taskId
                   AND operation.idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("idempotencyKey", idempotencyKey));
        require(rows.size() <= 1, "publish operation authority is ambiguous");
        return rows.stream().findFirst().map(
                ProductChainPublishAuthoritySource::operation);
    }

    private ExactContext context(
            ChainProjectPublishPort.PublishCommand command) {
        ProductChainPublishCandidateAuthority.Proof proof =
                candidateAuthority.requireExact(command);
        CandidateArtifactResponse candidate = candidates.getCurrent(
                proof.userId(), proof.artifactId());
        require(candidate.projectId() == proof.projectId()
                        && candidate.projectVersion().value().equals(
                        proof.baseProjectVersion())
                        && candidate.fingerprint().sha256().equals(
                        proof.candidateFingerprint()),
                "publish Candidate artifact does not bind typed authority");
        List<Integer> allChanges = java.util.stream.IntStream.range(
                0, candidate.changes().size()).boxed().toList();
        return new ExactContext(proof.userId(), proof.projectId(),
                proof.candidateFingerprint(), proof.receiptId(), allChanges);
    }

    private ExactContext failureContext(
            ChainProjectPublishPort.PublishCommand command,
            List<Integer> acceptedChangeIndexes) {
        var rows = jdbc.queryForList("""
                SELECT task.user_id, task.project_id,
                       candidate.candidate_fingerprint
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_finalization_readiness readiness
                    ON readiness.task_id = task.task_id
                   AND readiness.readiness_id = :readinessId
                  JOIN agent_v2_chain_finalization_checks final_check
                    ON final_check.task_id = task.task_id
                   AND final_check.readiness_id = readiness.readiness_id
                   AND final_check.finalization_check_id = :checkId
                   AND final_check.result_status = 'PASSED'
                  JOIN agent_v2_chain_workspace_candidates candidate
                    ON candidate.task_id = task.task_id
                   AND candidate.workspace_candidate_id = :candidateKey
                   AND candidate.artifact_id = :artifactId
                   AND candidate.base_project_version = :baseVersion
                 WHERE task.task_id = :taskId
                   AND readiness.task_frame_id = final_check.task_frame_id
                   AND readiness.final_plan_revision_id =
                       final_check.final_plan_revision_id
                   AND readiness.candidate_key = final_check.candidate_key
                   AND readiness.workspace_id = final_check.workspace_id
                   AND readiness.validation_id = final_check.validation_id
                   AND readiness.validation_request_digest =
                       final_check.validation_request_digest
                   AND readiness.validation_receipt_digest =
                       final_check.validation_receipt_digest
                   AND readiness.project_version = final_check.project_version
                   AND readiness.project_version = :baseVersion
                   AND readiness.artifact_id = :artifactId
                   AND readiness.candidate_key = :candidateKey
                   AND readiness.validation_id = :validationId
                   AND readiness.validation_request_digest = :requestDigest
                   AND readiness.validation_receipt_digest = :receiptDigest
                """, new MapSqlParameterSource()
                .addValue("taskId", command.taskId())
                .addValue("readinessId", command.readinessId())
                .addValue("checkId", command.finalizationCheckId())
                .addValue("baseVersion", command.baseProjectVersion())
                .addValue("artifactId", command.artifactId())
                .addValue("candidateKey", command.candidateKey())
                .addValue("validationId", command.validationId())
                .addValue("requestDigest", command.validationRequestDigest())
                .addValue("receiptDigest", command.validationReceiptDigest()));
        require(rows.size() == 1,
                "failed publish chain authority is missing or ambiguous");
        Map<String, Object> row = rows.get(0);
        return new ExactContext(number(row, "user_id"),
                number(row, "project_id"),
                text(row, "candidate_fingerprint"), "FAILED_NOT_READ",
                acceptedChangeIndexes);
    }

    private void requireRevision(Operation operation) {
        require(operation.resultRevisionId() != null
                        && operation.resultRevisionId() > 0
                        && operation.resultVersion() != null
                        && !operation.resultVersion().isBlank()
                        && !operation.resultVersion().equals(
                        operation.baseVersion()),
                "successful publish result identity is incomplete");
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM project_revisions revision
                 WHERE revision.id = :revisionId
                   AND revision.project_id = :projectId
                   AND revision.user_id = :userId
                   AND revision.project_version = :resultVersion
                   AND revision.source_type = 'APPLICATION'
                   AND revision.source_operation_id = :operationId
                """, new MapSqlParameterSource()
                .addValue("revisionId", operation.resultRevisionId())
                .addValue("projectId", operation.projectId())
                .addValue("userId", operation.userId())
                .addValue("resultVersion", operation.resultVersion())
                .addValue("operationId", operation.operationId()),
                Long.class);
        require(count != null && count == 1L,
                "publish result revision is not exact authority");
    }

    private static ChainProjectPublishPort.PublishCommand command(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            int attempt) {
        String key = ChainProjectPublishPort.stableIdempotencyKey(
                readiness.taskId(), readiness.readinessId(),
                check.finalizationCheckId(), attempt,
                readiness.projectVersion(), readiness.artifactId(),
                readiness.candidateKey(), readiness.validationId(),
                check.runtimePolicyVersion(),
                readiness.validationRequestDigest(),
                readiness.validationReceiptDigest());
        return new ChainProjectPublishPort.PublishCommand(
                readiness.taskId(), readiness.readinessId(),
                check.finalizationCheckId(), attempt, key,
                readiness.projectVersion(), readiness.artifactId(),
                readiness.candidateKey(), readiness.validationId(),
                check.runtimePolicyVersion(),
                readiness.validationRequestDigest(),
                readiness.validationReceiptDigest());
    }

    private static int attempt(
            String idempotencyKey,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        for (int attempt = 1; attempt <= ChainRuntimePolicy.requireVersion(
                check.runtimePolicyVersion())
                .finalizationMechanicalAttemptsTotal(); attempt++) {
            if (command(readiness, check, attempt).idempotencyKey()
                    .equals(idempotencyKey)) return attempt;
        }
        throw new IllegalStateException(
                "publish operation key is not a stable attempt identity");
    }

    private Optional<Operation> query(
            String sql, MapSqlParameterSource parameters) {
        var rows = jdbc.queryForList(sql, parameters);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "publish operation authority is ambiguous");
        }
        return rows.stream().findFirst().map(ProductChainPublishAuthoritySource
                ::operation);
    }

    private static Operation operation(Map<String, Object> row) {
        return new Operation(
                number(row, "id"), number(row, "user_id"),
                number(row, "project_id"), text(row, "idempotency_key"),
                text(row, "operation_type"),
                text(row, "request_hash"), nullableNumber(
                row, "base_revision_id"), text(row, "base_version"),
                nullableNumber(
                row, "candidate_artifact_id"),
                nullableText(row, "candidate_fingerprint"),
                text(row, "outcome"), nullableText(row, "error_code"),
                nullableNumber(row, "result_revision_id"),
                nullableText(row, "result_version"),
                indexes(text(row, "accepted_change_indexes")),
                indexes(text(row, "rejected_change_indexes")));
    }

    private static long operationId(String formalRef) {
        Objects.requireNonNull(formalRef, "formalRef");
        if (!formalRef.startsWith(REF_PREFIX)) {
            throw new IllegalArgumentException(
                    "unsupported publish authority ref");
        }
        try {
            long value = Long.parseLong(formalRef.substring(
                    REF_PREFIX.length()));
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "invalid publish authority ref", invalid);
        }
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key));
    }

    private static String nullableText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static List<Integer> indexes(String json) {
        String value = Objects.requireNonNull(json, "indexes").trim();
        if (!value.startsWith("[") || !value.endsWith("]")) {
            throw new IllegalStateException("invalid persisted index authority");
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) return List.of();
        try {
            return List.of(body.split(",")).stream()
                    .map(String::trim).map(Integer::valueOf).toList();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "invalid persisted index authority", invalid);
        }
    }

    private static String automaticRequestHash(
            String receiptId, long artifactId, String baseVersion,
            List<Integer> accepted) {
        return sha256("AUTOMATIC_APPLICATION:" + receiptId + "\0"
                + artifactId + "\0" + baseVersion + "\0" + accepted);
    }

    /** Must stay byte-for-byte equivalent to the runtime transition digest. */
    private static String readinessDigest(
            ChainPersistenceRecords.FinalizationReadinessRecord value) {
        return sha256(value.readinessId() + "\0" + value.taskId() + "\0"
                + value.transitionId() + "\0" + value.taskFrameId() + "\0"
                + value.finalPlanId() + "\0" + value.finalPlanRevisionId()
                + "\0" + value.finalPlanRevisionNumber() + "\0"
                + value.finalStepId() + "\0" + value.reviewDecisionId()
                + "\0" + value.acceptedSet().sha256() + "\0"
                + value.applicabilityCutEventSequence() + "\0"
                + Objects.toString(value.artifactId(), "NONE") + "\0"
                + value.candidateKey() + "\0" + value.workspaceId() + "\0"
                + value.validationId() + "\0"
                + Objects.toString(
                value.validationRequestDigest(), "NONE") + "\0"
                + Objects.toString(
                value.validationReceiptDigest(), "NONE") + "\0"
                + value.coverage().sha256() + "\0"
                + value.publishRequirement() + "\0"
                + value.publishRequirementDigest() + "\0"
                + value.instructionId() + "\0" + value.projectVersion());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record Operation(
            long operationId,
            long userId,
            long projectId,
            String idempotencyKey,
            String operationType,
            String requestHash,
            Long baseRevisionId,
            String baseVersion,
            Long candidateArtifactId,
            String candidateFingerprint,
            String outcome,
            String errorCode,
            Long resultRevisionId,
            String resultVersion,
            List<Integer> acceptedChangeIndexes,
            List<Integer> rejectedChangeIndexes) {
        public Operation {
            acceptedChangeIndexes = List.copyOf(acceptedChangeIndexes);
            rejectedChangeIndexes = List.copyOf(rejectedChangeIndexes);
        }
        public boolean succeeded() {
            return "SUCCEEDED".equals(outcome);
        }

        public boolean failed() {
            return "FAILED".equals(outcome);
        }

        public String formalRef() {
            return ProductChainPublishAuthoritySource.formalRef(operationId);
        }
    }

    private record ExactContext(
            long userId, long projectId, String candidateFingerprint,
            String receiptRef, List<Integer> acceptedChangeIndexes) {
        private ExactContext {
            acceptedChangeIndexes = List.copyOf(acceptedChangeIndexes);
        }
    }

    private record PublishSequence(
            Optional<Operation> success,
            Optional<Operation> failure) {
    }
}
