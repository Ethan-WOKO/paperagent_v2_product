package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureWriter;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainContextRevisionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class ProductChainContextRepositoryAdapter
        implements ChainContextRepository, ChainContextRevisionWriter,
        ChainContextBuildFailureRepository, ChainContextBuildFailureWriter {
    private static final String REVISIONS =
            "agent_v2_chain_context_revisions";
    private static final String MODULES = "agent_v2_chain_context_modules";
    private static final String BUILD_FAILURES =
            "agent_v2_chain_context_build_failures";
    private static final Set<String> TERMINAL_COLUMNS = Set.of(
            "status", "module_count", "request_manifest_format_version",
            "request_manifest_json", "request_digest", "completion_token",
            "blocked_error_code", "input_digest", "created_at",
            "completed_at");

    private final ProductChainTransactions transactions;
    private final ProductChainContextManifestCodec manifests;

    public ProductChainContextRepositoryAdapter(
            ProductChainTransactions transactions,
            ProductChainContextManifestCodec manifests) {
        this.transactions = transactions;
        this.manifests = manifests;
    }

    @Override
    public AppendResult<ContextRevisionRecord> createContextRevision(
            ContextRevisionRecord revision) {
        if (revision.status() != ChainContextRevisionStatus.BUILDING
                || revision.moduleCount() != 0) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONTEXT_MUST_START_BUILDING");
        }
        return transactions.appendTaskScoped(REVISIONS,
                ContextRevisionRecord.class, revision,
                Map.of("context_revision_id", revision.contextRevisionId()),
                revision.taskId(),
                this::sameContextCreation);
    }

    @Override
    public AppendResult<ContextModuleRecord> appendContextModule(
            ContextModuleRecord module) {
        return transactions.inWrite(() -> {
            ContextRevisionRecord revision = transactions.findCurrent(
                    REVISIONS, ContextRevisionRecord.class,
                    Map.of("context_revision_id", module.contextRevisionId()),
                    true).orElseThrow(() ->
                    new ProductChainPersistenceException(
                            "CHAIN_CONTEXT_NOT_FOUND"));
            if (revision.status() != ChainContextRevisionStatus.BUILDING
                    || !revision.taskId().equals(module.taskId())) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_MODULE_NOT_APPENDABLE");
            }
            return transactions.appendCurrent(MODULES,
                    ContextModuleRecord.class,
                    module, ordered("context_revision_id",
                            module.contextRevisionId(), "module_kind",
                            module.module().wireName()));
        });
    }

    @Override
    public ContextRevisionRecord completeContextRevision(
            ContextRevisionRecord completeRevision) {
        return terminal(completeRevision, ChainContextRevisionStatus.COMPLETE);
    }

    @Override
    public ContextRevisionRecord blockContextRevision(
            ContextRevisionRecord blockedRevision) {
        return terminal(blockedRevision,
                ChainContextRevisionStatus.INPUT_BLOCKED);
    }

    @Override
    public Optional<ContextRevisionRecord> findContextRevision(
            String contextRevisionId) {
        return transactions.find(REVISIONS, ContextRevisionRecord.class,
                Map.of("context_revision_id", contextRevisionId));
    }

    @Override
    public List<ContextRevisionRecord> findContextRevisions(String taskId) {
        return transactions.findAll(REVISIONS, ContextRevisionRecord.class,
                Map.of("task_id", taskId),
                "created_at, context_revision_id");
    }

    @Override
    public List<ContextModuleRecord> findContextModules(
            String contextRevisionId) {
        return transactions.findAll(MODULES, ContextModuleRecord.class,
                Map.of("context_revision_id", contextRevisionId),
                "module_ordinal");
    }

    @Override
    public Optional<ContextBuildFailureRecord> findContextBuildFailure(
            String contextRevisionId) {
        return transactions.find(BUILD_FAILURES,
                ContextBuildFailureRecord.class,
                Map.of("context_revision_id", contextRevisionId));
    }

    public Optional<ContextBuildFailureRecord> findContextBuildFailureById(
            String contextBuildFailureId) {
        return transactions.find(BUILD_FAILURES,
                ContextBuildFailureRecord.class,
                Map.of("context_build_failure_id",
                        contextBuildFailureId));
    }

    @Override
    public AuthoritativeAppendResult<ContextBuildFailureRecord>
            appendContextBuildFailure(
                    AuthoritativeFact<ContextBuildFailureRecord> failure) {
        return transactions.appendAuthoritative(
                BUILD_FAILURES, ContextBuildFailureRecord.class, failure,
                Map.of("context_revision_id",
                        failure.fact().contextRevisionId()));
    }

    private ContextRevisionRecord terminal(
            ContextRevisionRecord requested,
            ChainContextRevisionStatus target) {
        if (requested.status() != target) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONTEXT_TERMINAL_STATUS_MISMATCH");
        }
        return transactions.inWrite(() -> {
            ContextRevisionRecord current = transactions.findCurrent(
                    REVISIONS, ContextRevisionRecord.class,
                    Map.of("context_revision_id",
                            requested.contextRevisionId()), true)
                    .orElseThrow(() ->
                            new ProductChainPersistenceException(
                                    "CHAIN_CONTEXT_NOT_FOUND"));
            if (current.status() != ChainContextRevisionStatus.BUILDING) {
                if (sameRecord(current, requested)) {
                    return current;
                }
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_TERMINAL_CONFLICT");
            }
            if (!sameImmutableIdentity(current, requested)) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_IDENTITY_CONFLICT");
            }
            List<ContextModuleRecord> modules =
                    transactions.findAllCurrent(
                            MODULES, ContextModuleRecord.class,
                            Map.of("context_revision_id",
                                    requested.contextRevisionId()),
                            "module_ordinal");
            if (requested.moduleCount() != 13) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_MODULES_INCOMPLETE");
            }
            if (modules.stream().anyMatch(module ->
                    !module.taskId().equals(requested.taskId()))) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_MODULE_TASK_MISMATCH");
            }
            var rebuiltManifest = manifests.manifest(modules);
            if (!rebuiltManifest.equals(requested.requestManifest())) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_MANIFEST_MISMATCH");
            }
            String rebuiltDigest = ProductChainRecordCodec.sha256(
                    manifests.canonicalPrompt(modules));
            String requestedDigest = target
                    == ChainContextRevisionStatus.COMPLETE
                    ? requested.requestDigest() : requested.inputDigest();
            if (!rebuiltDigest.equals(requestedDigest)) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_MANIFEST_DIGEST_MISMATCH");
            }
            Map<String, Object> encoded = transactions.codec().encode(requested);
            encoded.put("completed_at", java.sql.Timestamp.from(
                    transactions.auditTime().truncatedTo(
                            java.time.temporal.ChronoUnit.MICROS)));
            int changed = transactions.jdbc().update("""
                    UPDATE agent_v2_chain_context_revisions
                       SET status = :status,
                           module_count = :module_count,
                           request_manifest_format_version =
                               :request_manifest_format_version,
                           request_manifest_json = :request_manifest_json,
                           request_digest = :request_digest,
                           completion_token = :completion_token,
                           blocked_error_code = :blocked_error_code,
                           input_digest = :input_digest,
                           completed_at = :completed_at
                     WHERE context_revision_id = :context_revision_id
                       AND status = 'BUILDING'
                    """, new MapSqlParameterSource(encoded));
            if (changed != 1) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_TERMINAL_CAS_FAILED");
            }
            return transactions.findCurrent(REVISIONS,
                    ContextRevisionRecord.class,
                    Map.of("context_revision_id",
                            requested.contextRevisionId()), false)
                    .orElseThrow();
        });
    }

    private boolean sameImmutableIdentity(
            ContextRevisionRecord left, ContextRevisionRecord right) {
        Map<String, Object> leftFields = new LinkedHashMap<>(
                transactions.codec().encode(left));
        Map<String, Object> rightFields = new LinkedHashMap<>(
                transactions.codec().encode(right));
        TERMINAL_COLUMNS.forEach(column -> {
            leftFields.remove(column);
            rightFields.remove(column);
        });
        return leftFields.equals(rightFields);
    }

    private boolean sameContextCreation(
            ContextRevisionRecord stored,
            ContextRevisionRecord requested) {
        return sameImmutableIdentity(stored, requested);
    }

    private boolean sameRecord(Record left, Record right) {
        Map<String, Object> leftFields = new LinkedHashMap<>(
                transactions.codec().encode(left));
        Map<String, Object> rightFields = new LinkedHashMap<>(
                transactions.codec().encode(right));
        leftFields.remove("created_at");
        leftFields.remove("completed_at");
        rightFields.remove("created_at");
        rightFields.remove("completed_at");
        return leftFields.equals(rightFields);
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
