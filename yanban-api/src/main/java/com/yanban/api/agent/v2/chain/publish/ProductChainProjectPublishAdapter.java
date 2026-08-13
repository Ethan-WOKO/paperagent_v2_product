package com.yanban.api.agent.v2.chain.publish;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectRevisionWorkflowService;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/** Exact Candidate/Validation/ProjectVersion fence for automatic publish. */
@Component
public final class ProductChainProjectPublishAdapter
        implements ChainProjectPublishPort {
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final CandidateChangeArtifactService candidates;
    private final ProductChainPublishCandidateAuthority candidateAuthority;
    private final ProjectRevisionWorkflowService revisions;
    private final ProductChainPublishAuthoritySource authority;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate durableFailures;
    private final ThreadLocal<DeferredFailure> deferredFailure =
            new ThreadLocal<>();

    public ProductChainProjectPublishAdapter(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            CandidateChangeArtifactService candidates,
            ProductChainPublishCandidateAuthority candidateAuthority,
            ProjectRevisionWorkflowService revisions,
            ProductChainPublishAuthoritySource authority,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.candidateAuthority = Objects.requireNonNull(
                candidateAuthority, "candidateAuthority");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
                transactions, "transactions"));
        this.durableFailures = new TransactionTemplate(transactions);
        this.durableFailures.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public PublishResult publish(PublishCommand command) {
        Objects.requireNonNull(command, "command");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "publish task does not exist"));
        if (task.projectId() == null
                || task.initialProjectVersion() == null) {
            return failed(command, task, ErrorCode.STALE_VERSION_FENCE,
                    false, List.of());
        }
        ProductChainPublishAuthoritySource.Operation replay = authority
                .findByIdempotencyKey(task.userId(), task.projectId(),
                        command.idempotencyKey()).orElse(null);
        if (replay != null) {
            return fromOperation(command, replay, true);
        }

        ProductChainPublishCandidateAuthority.Proof proof;
        try {
            proof = candidateAuthority.requireExact(command);
        } catch (RuntimeException failure) {
            ErrorCode code = classify(failure);
            if (code == null && failure instanceof
                    ProductChainPublishCandidateAuthority
                            .BindingMismatchException) {
                code = ErrorCode.VALIDATION_BINDING_MISMATCH;
            }
            if (code == null) throw failure;
            return failed(command, task, code,
                    retryable(command, code), List.of());
        }
        CandidateArtifactResponse candidate;
        try {
            candidate = candidates.getCurrent(
                    task.userId(), command.artifactId());
        } catch (RuntimeException failure) {
            ErrorCode code = classify(failure);
            if (code == null) throw failure;
            return failed(command, task, code,
                    retryable(command, code), List.of());
        }
        if (candidate.projectId() != task.projectId()
                || !candidate.projectVersion().value().equals(
                        command.baseProjectVersion())
                || !candidate.fingerprint().sha256().equals(
                        proof.candidateFingerprint())) {
            return failed(command, task,
                    ErrorCode.CANDIDATE_BINDING_MISMATCH, false, List.of());
        }
        List<Integer> allChanges = IntStream.range(
                0, candidate.changes().size()).boxed().toList();
        try {
            revisions.applyAutomatically(
                    task.userId(), task.projectId(), command.artifactId(),
                    command.idempotencyKey(), command.baseProjectVersion(),
                    proof.candidateFingerprint(), proof.receiptId());
            ProductChainPublishAuthoritySource.Operation stored = authority
                    .findByIdempotencyKey(task.userId(), task.projectId(),
                            command.idempotencyKey()).orElse(null);
            if (stored == null) {
                return failed(command, task, ErrorCode.VERSION_CONFLICT,
                        false, allChanges);
            }
            return fromOperation(command, stored, false);
        } catch (RuntimeException failure) {
            ErrorCode code = classify(failure);
            if (code == null) throw failure;
            deferredFailure.set(new DeferredFailure(
                    command, task, code,
                    allChanges));
            ProductChainPublishAuthoritySource.Operation stored = authority
                    .findByIdempotencyKey(task.userId(), task.projectId(),
                            command.idempotencyKey()).orElse(null);
            if (stored != null && stored.failed()) {
                stored = normalizeCurrentWorkflowFailure(
                        command, stored, code);
            }
            if (stored != null) return fromOperation(command, stored, false);
            return failed(command, task, code, retryable(command, code),
                    allChanges);
        }
    }

    /** Clears request-local compensation state at the coordinator boundary. */
    public void clearDeferredFailure() {
        deferredFailure.remove();
    }

    public Optional<ProductChainPublishAuthoritySource.Operation>
            findExactSuccessfulReplay(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        return authority.findExactSuccess(readiness, check);
    }

    /** Persists only an already classified publish failure after outer rollback. */
    public Failed persistDeferredFailureAfterRollback() {
        DeferredFailure deferred = deferredFailure.get();
        if (deferred == null) {
            throw new IllegalStateException(
                    "no classified publish failure awaits compensation");
        }
        return Objects.requireNonNull(durableFailures.execute(status -> {
            ProductChainPublishAuthoritySource.Operation operation =
                    recordFailure(deferred.command(), deferred.task(),
                            deferred.code(), deferred.accepted());
            PublishResult replay = fromOperation(
                    deferred.command(), operation, true);
            if (!(replay instanceof Failed failed)
                    || failed.errorCode() != deferred.code()) {
                throw new IllegalStateException(
                        "durable publish failure changed compensation identity");
            }
            return failed;
        }), "durable publish failure transaction");
    }

    private PublishResult failed(
            PublishCommand command,
            ChainPersistenceRecords.TaskRecord task,
            ErrorCode code,
            boolean retryable,
            List<Integer> accepted) {
        if (task.projectId() == null) {
            throw new IllegalStateException(
                    "publish failure cannot be recorded without Project");
        }
        ProductChainPublishAuthoritySource.Operation operation =
                recordFailure(command, task, code, accepted);
        PublishResult replay = fromOperation(command, operation, false);
        if (replay instanceof Failed failed) {
            return new Failed(failed.errorCode(), failed.formalFailureRef(),
                    failed.attemptNo(), failed.idempotencyKey(), retryable,
                    false);
        }
        return replay;
    }

    private ProductChainPublishAuthoritySource.Operation recordFailure(
            PublishCommand command,
            ChainPersistenceRecords.TaskRecord task,
            ErrorCode code,
            List<Integer> accepted) {
        List<Integer> canonicalAccepted = ProductChainPublishAuthoritySource
                .canonicalAcceptedIndexes(accepted);
        ProductChainPublishAuthoritySource.Operation existing = authority
                .findByIdempotencyKey(task.userId(), task.projectId(),
                        command.idempotencyKey()).orElse(null);
        if (existing != null) return existing;
        try {
            transactions.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO project_revision_operations(
                        project_id, user_id, operation_type, idempotency_key,
                        request_hash, base_revision_id, base_version,
                        result_revision_id, result_version,
                        candidate_artifact_id, candidate_fingerprint,
                        accepted_change_indexes, rejected_change_indexes,
                        outcome, error_code, created_at, completed_at)
                    VALUES(:projectId, :userId, 'APPLICATION', :key,
                        :requestHash, NULL, :baseVersion,
                        NULL, NULL, :artifactId, :candidateFingerprint,
                        :accepted, '[]', 'FAILED', :errorCode,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("projectId", task.projectId())
                    .addValue("userId", task.userId())
                    .addValue("key", command.idempotencyKey())
                    .addValue("requestHash",
                            ProductChainPublishAuthoritySource.chainFailureHash(
                                    command, fixedError(code),
                                    canonicalAccepted))
                    .addValue("baseVersion", command.baseProjectVersion())
                    .addValue("artifactId", command.artifactId())
                    .addValue("candidateFingerprint", candidateFingerprint(
                            command.taskId(), command.candidateKey()))
                    .addValue("accepted", canonicalAccepted.toString())
                    .addValue("errorCode", fixedError(code))));
        } catch (DataIntegrityViolationException race) {
            // The unique idempotency authority is read below.
        }
        return authority.findByIdempotencyKey(
                        task.userId(), task.projectId(),
                        command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "formal publish failure was not persisted"));
    }

    private ProductChainPublishAuthoritySource.Operation
            normalizeCurrentWorkflowFailure(
            PublishCommand command,
            ProductChainPublishAuthoritySource.Operation operation,
            ErrorCode code) {
        authority.requireConvertibleWorkflowFailure(
                operation, command, code);
        String fixedError = fixedError(code);
        String fixedHash = ProductChainPublishAuthoritySource
                .chainFailureHash(command, fixedError,
                        operation.acceptedChangeIndexes());
        int updated = jdbc.update("""
                UPDATE project_revision_operations
                   SET request_hash = :fixedHash,
                       base_revision_id = NULL,
                       error_code = :fixedError
                 WHERE id = :operationId
                   AND outcome = 'FAILED'
                   AND request_hash = :observedHash
                   AND base_revision_id = :observedBaseRevisionId
                   AND error_code = :observedError
                   AND result_revision_id IS NULL
                   AND result_version IS NULL
                """, new MapSqlParameterSource()
                .addValue("fixedHash", fixedHash)
                .addValue("fixedError", fixedError)
                .addValue("operationId", operation.operationId())
                .addValue("observedHash", operation.requestHash())
                .addValue("observedBaseRevisionId",
                        operation.baseRevisionId())
                .addValue("observedError", operation.errorCode()));
        if (updated != 1) {
            throw new IllegalStateException(
                    "workflow publish failure changed before normalization");
        }
        ProductChainPublishAuthoritySource.Operation normalized = authority
                .findByIdempotencyKey(operation.userId(),
                        operation.projectId(), operation.idempotencyKey())
                .filter(value -> value.operationId()
                        == operation.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "normalized publish failure is missing"));
        return authority.requireExactOperation(normalized, command);
    }

    private PublishResult fromOperation(
            PublishCommand command,
            ProductChainPublishAuthoritySource.Operation operation,
            boolean replayed) {
        authority.requireExactOperation(operation, command);
        if (operation.succeeded()) {
            return new Published(operation.formalRef(), command.attemptNo(),
                    command.idempotencyKey(), replayed,
                    command.baseProjectVersion(), command.candidateKey(),
                    command.validationId(), operation.resultVersion(),
                    operation.resultRevisionId(), operation.formalRef());
        }
        ErrorCode code = fromFixedError(operation.errorCode());
        return new Failed(code, operation.formalRef(), command.attemptNo(),
                command.idempotencyKey(), retryable(command, code), replayed);
    }

    private String candidateFingerprint(String taskId, String candidateKey) {
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> matches =
                workflow.findWorkspaceCandidates(taskId).stream()
                .filter(value -> taskId.equals(value.taskId()))
                .filter(value -> candidateKey.equals(
                        value.workspaceCandidateId()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "publish failure Workspace Candidate is missing or ambiguous");
        }
        return matches.get(0).candidateFingerprint();
    }

    private static ErrorCode classify(RuntimeException failure) {
        if (failure instanceof ResponseStatusException response) {
            HttpStatusCode status = response.getStatusCode();
            if (status.value() == 409 || status.value() == 412
                    || status.value() == 428) {
                return ErrorCode.STALE_VERSION_FENCE;
            }
            if (status.is4xxClientError()) {
                return ErrorCode.VALIDATION_BINDING_MISMATCH;
            }
            if (status.is5xxServerError()) {
                return ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE;
            }
        }
        if (failure instanceof TransientDataAccessException) {
            return ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE;
        }
        return null;
    }

    private static boolean retryable(
            PublishCommand command, ErrorCode code) {
        return code == ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
                && command.attemptNo() < ChainRuntimePolicy.requireVersion(
                        command.runtimePolicyVersion())
                .finalizationMechanicalAttemptsTotal();
    }

    private static String fixedError(ErrorCode code) {
        return "CHAIN_PUBLISH_" + code.name();
    }

    private static ErrorCode fromFixedError(String value) {
        if (value != null && value.startsWith("CHAIN_PUBLISH_")) {
            try {
                return ErrorCode.valueOf(value.substring(
                        "CHAIN_PUBLISH_".length()));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException(
                        "unknown fixed publish error code", unknown);
            }
        }
        throw new IllegalStateException(
                "publish failure has no known fixed CHAIN_PUBLISH error code");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record DeferredFailure(
            PublishCommand command,
            ChainPersistenceRecords.TaskRecord task,
            ErrorCode code,
            List<Integer> accepted) {
        private DeferredFailure {
            accepted = List.copyOf(accepted);
        }
    }
}
