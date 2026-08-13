package com.yanban.api.agent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainRuntimePolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.chain.publish.ProductChainProjectPublishAdapter;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishCandidateAuthority;
import com.yanban.api.project.CandidateSandboxValidationService;
import com.yanban.api.project.CandidateValidationProfile;
import com.yanban.api.project.CandidateValidationResponse;
import com.yanban.api.project.ProjectRevisionWorkflowService;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxReceipt;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.research.ProjectVersionRef;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import io.paperagent.v2.chain.finalization.ChainFinalizationRuntime;
import io.paperagent.v2.chain.finalization.ChainFinalizationTransitionPort;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectChainFinalizationTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/yanban/api/agent/v2/chain");
    private static final String BASE = "1".repeat(64);
    private static final String FINGERPRINT = "2".repeat(64);
    private static final String REQUEST = "3".repeat(64);
    private static final String RECEIPT_REF = "receipt-identity-1";
    private static final String RECEIPT_JSON = receiptJson();
    private static final String RECEIPT = java.util.HexFormat.of()
            .formatHex(digest(RECEIPT_JSON));
    private static final String BROKER_IDENTITY = java.util.HexFormat.of()
            .formatHex(digest("agent-chain-validation\0" + RECEIPT_REF));

    @Test
    void exposesOneTypedTaskLockedMechanicalFinalizationEntry() throws Exception {
        var method = ProductChainFinalizationCoordinator.class.getMethod(
                "finalizeReadiness", String.class, Instant.class);
        assertEquals(ChainFinalizationRuntime.Result.class,
                method.getReturnType());

        String coordinator = source("finalization",
                "ProductChainFinalizationCoordinator.java");
        assertTrue(coordinator.contains("FOR UPDATE"));
        assertTrue(coordinator.contains("instructions.read"));
        assertTrue(coordinator.contains("findTaskOutcome"));
        assertTrue(coordinator.contains("projects.manifest"));
        assertTrue(coordinator.indexOf("lockTask(readiness.taskId())")
                < coordinator.indexOf("finalizeReadiness(readinessId"));
    }

    @Test
    void persistedPublishErrorCodesFailClosed() throws Exception {
        for (ChainProjectPublishPort.ErrorCode code
                : ChainProjectPublishPort.ErrorCode.values()) {
            assertEquals(code, ProductChainPublishAuthoritySource
                    .publishErrorCode(publishOperationWithError(
                            "CHAIN_PUBLISH_" + code.name())));
        }
        for (String invalid : java.util.Arrays.asList(
                null, "UNKNOWN", "HTTP_422", "HTTP_503", "HTTP_5XX",
                "CHAIN_PUBLISH_UNKNOWN")) {
            assertThrows(IllegalStateException.class, () ->
                    ProductChainPublishAuthoritySource.publishErrorCode(
                            publishOperationWithError(invalid)));
        }

        var fromFixed = ProductChainProjectPublishAdapter.class
                .getDeclaredMethod("fromFixedError", String.class);
        fromFixed.setAccessible(true);
        for (String invalid : java.util.Arrays.asList(
                null, "UNKNOWN", "HTTP_422", "HTTP_503", "HTTP_5XX",
                "CHAIN_PUBLISH_UNKNOWN")) {
            var thrown = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> fromFixed.invoke(null, invalid));
            assertTrue(thrown.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void publishUsesServerOnlyReceiptFenceAndExactReplayIdentity()
            throws Exception {
        String publish = source("publish",
                "ProductChainProjectPublishAdapter.java");
        assertTrue(publish.contains("applyAutomatically("));
        assertFalse(publish.contains(".applyCandidate("));
        String authority = source("publish",
                "ProductChainPublishAuthoritySource.java");
        assertTrue(authority.contains("candidateAuthority.requireExact"));
        assertFalse(authority.contains("candidate_sandbox_validations"));
        assertFalse(authority.contains("receipt_json"));
        assertTrue(authority.contains("operation.operationType()"));
        assertTrue(authority.contains("operation.candidateFingerprint()"));
        assertTrue(authority.contains("operation.resultRevisionId()"));
        assertTrue(authority.contains("operation.resultVersion()"));
        assertTrue(publish.contains("PROPAGATION_REQUIRES_NEW"));
        assertTrue(publish.contains(
                "persistDeferredFailureAfterRollback()"));
        assertTrue(publish.contains("fromOperation("));
    }

    @Test
    void rollbackOnlyPublishFailureIsDurableThenReplayedOnce()
            throws Exception {
        String coordinator = source("finalization",
                "ProductChainFinalizationCoordinator.java");
        assertTrue(coordinator.contains("status.isRollbackOnly()"));
        assertTrue(coordinator.contains(
                "persistDeferredFailureAfterRollback()"));
        assertTrue(coordinator.contains("while (true)"));
        assertTrue(coordinator.contains(
                "execute(readinessId, committedAt, true)"));
        assertTrue(coordinator.contains("finally"));
        assertTrue(coordinator.contains("clearDeferredFailure()"));

        String runtime = Files.readString(Path.of("..", "agent-v2",
                "agent-runtime", "src", "main", "java", "io",
                "paperagent", "v2", "chain", "finalization",
                "ChainFinalizationRuntime.java"));
        assertTrue(runtime.indexOf("if (result instanceof"
                        + " ChainProjectPublishPort.Failed")
                < runtime.indexOf("outcomes.complete("));
    }

    @Test
    void rolledBackPublishFailureBecomesDurableExactReplay() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:publish-compensation;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE project_revision_operations(
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    project_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
                    operation_type VARCHAR(32) NOT NULL,
                    idempotency_key VARCHAR(128) NOT NULL,
                    request_hash CHAR(64) NOT NULL,
                    base_revision_id BIGINT,
                    base_version VARCHAR(64) NOT NULL,
                    result_revision_id BIGINT, result_version VARCHAR(64),
                    candidate_artifact_id BIGINT,
                    candidate_fingerprint VARCHAR(64),
                    accepted_change_indexes VARCHAR(255) NOT NULL,
                    rejected_change_indexes VARCHAR(255) NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    error_code VARCHAR(64), created_at TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP,
                    UNIQUE(user_id, project_id, idempotency_key))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE candidate_sandbox_validations(
                    validation_id VARCHAR(128), user_id BIGINT,
                    project_id BIGINT, artifact_id BIGINT,
                    project_version VARCHAR(64),
                    candidate_fingerprint VARCHAR(64),
                    accepted_change_indexes_json VARCHAR(255),
                    request_digest VARCHAR(64), receipt_digest VARCHAR(64),
                    profile VARCHAR(64), status VARCHAR(32),
                    broker_execution_id VARCHAR(128), receipt_json CLOB)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_tasks(
                    task_id VARCHAR(128) PRIMARY KEY, user_id BIGINT,
                    project_id BIGINT)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_finalization_readiness(
                    readiness_id VARCHAR(128), task_id VARCHAR(128),
                    instruction_id VARCHAR(128), task_frame_id VARCHAR(128),
                    final_plan_revision_id VARCHAR(128),
                    project_version VARCHAR(64), artifact_id BIGINT,
                    candidate_key VARCHAR(128), workspace_id VARCHAR(128),
                    validation_id VARCHAR(128),
                    validation_request_digest VARCHAR(64),
                    validation_receipt_digest VARCHAR(64),
                    publish_requirement_digest VARCHAR(64))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_finalization_checks(
                    finalization_check_id VARCHAR(128), task_id VARCHAR(128),
                    readiness_id VARCHAR(128), result_status VARCHAR(16),
                    instruction_id VARCHAR(128), task_frame_id VARCHAR(128),
                    final_plan_revision_id VARCHAR(128),
                    candidate_key VARCHAR(128), workspace_id VARCHAR(128),
                    validation_id VARCHAR(128),
                    validation_request_digest VARCHAR(64),
                    validation_receipt_digest VARCHAR(64),
                    publish_requirement_digest VARCHAR(64),
                    project_version VARCHAR(64))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_workspace_candidates(
                    task_id VARCHAR(128), workspace_candidate_id VARCHAR(128),
                    artifact_id BIGINT, base_project_version VARCHAR(64),
                    candidate_fingerprint VARCHAR(64))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE project_revisions(
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    project_id BIGINT, user_id BIGINT,
                    project_version VARCHAR(64), source_type VARCHAR(32),
                    source_operation_id BIGINT)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_task_outcomes(
                    outcome_id VARCHAR(128) PRIMARY KEY)
                """);
        jdbc.update("""
                INSERT INTO candidate_sandbox_validations VALUES(
                    'validation-1',7,13,41,:base,:fingerprint,
                    '[0]',:request,:receipt,'AGENT_CHAIN_EXACT_CANDIDATE',
                    'SUCCEEDED',:brokerIdentity,:receiptJson)
                """, new MapSqlParameterSource()
                .addValue("base", BASE)
                .addValue("fingerprint", FINGERPRINT)
                .addValue("request", REQUEST)
                .addValue("receipt", RECEIPT)
                .addValue("brokerIdentity", BROKER_IDENTITY)
                .addValue("receiptJson", RECEIPT_JSON));
        jdbc.update("""
                INSERT INTO agent_v2_chain_tasks VALUES('task-1',7,13)
                """, new MapSqlParameterSource());
        jdbc.update("""
                INSERT INTO agent_v2_chain_finalization_readiness VALUES(
                    'readiness-1','task-1','instruction-1','frame-1',
                    'revision-1',:base,41,'candidate-1','workspace-1',
                    'validation-1',:request,:receipt,:publishDigest)
                """, new MapSqlParameterSource()
                .addValue("base", BASE)
                .addValue("request", REQUEST)
                .addValue("receipt", RECEIPT)
                .addValue("publishDigest", "8".repeat(64)));
        jdbc.update("""
                INSERT INTO agent_v2_chain_finalization_checks VALUES(
                    'check-1','task-1','readiness-1','PASSED',
                    'instruction-1','frame-1','revision-1','candidate-1',
                    'workspace-1','validation-1',:request,:receipt,
                    :publishDigest,:base)
                """, new MapSqlParameterSource()
                .addValue("base", BASE)
                .addValue("request", REQUEST)
                .addValue("receipt", RECEIPT)
                .addValue("publishDigest", "8".repeat(64)));
        jdbc.update("""
                INSERT INTO agent_v2_chain_workspace_candidates VALUES(
                    'task-1','candidate-1',41,:base,:fingerprint)
                """, new MapSqlParameterSource()
                .addValue("base", BASE)
                .addValue("fingerprint", FINGERPRINT)
                );
        var manager = new DataSourceTransactionManager(dataSource);
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        CandidateSandboxValidationService validations = mock(
                CandidateSandboxValidationService.class);
        ProjectRevisionWorkflowService revisions = mock(
                ProjectRevisionWorkflowService.class);
        ProductChainPublishCandidateAuthority publishCandidateAuthority =
                mock(ProductChainPublishCandidateAuthority.class);
        ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        "task-1", "command-1", "instruction-1", null,
                        7, 8, 9, null, "request-1", "5".repeat(64),
                        13L, BASE, 0, Instant.parse(
                        "2026-08-07T10:00:00Z"));
        ChainPersistenceRecords.WorkspaceCandidateRecord binding =
                new ChainPersistenceRecords.WorkspaceCandidateRecord(
                        "candidate-1", "task-1", "candidate-event-1",
                        "action-1", "workspace-1", BASE, 41,
                        FINGERPRINT, "6".repeat(64), "7".repeat(64),
                        Instant.parse("2026-08-07T10:00:00Z"));
        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        when(foundations.findTask("task-1")).thenReturn(
                java.util.Optional.of(task));
        when(workflow.findWorkspaceCandidates("task-1")).thenReturn(
                List.of(binding));
        when(candidates.getCurrent(7L, 41L)).thenReturn(candidate);
        when(candidate.projectId()).thenReturn(13L);
        when(candidate.projectVersion()).thenReturn(
                new ProjectVersionRef(BASE));
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint(FINGERPRINT));
        when(candidate.changes()).thenReturn(List.of(mock(
                com.yanban.core.agent.sandbox.CandidateFileChange.class)));
        CandidateValidationResponse validation = new CandidateValidationResponse(
                "validation-1", 13, 41, BASE, FINGERPRINT, List.of(0),
                CandidateValidationProfile.AGENT_CHAIN_EXACT_CANDIDATE,
                "SUCCEEDED", 0,
                false, "provider", "", "", false, REQUEST, RECEIPT,
                null, null, null, "PENDING", null, null, null, null);
        when(validations.list(7L, 13L, 41L)).thenReturn(List.of(validation));
        when(validations.successfulReceiptRef(
                7L, 13L, 41L, "validation-1", BASE, FINGERPRINT,
                REQUEST, RECEIPT)).thenReturn(RECEIPT_REF);
        when(publishCandidateAuthority.requireExact(
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new ProductChainPublishCandidateAuthority.Proof(
                        7L, 13L, RECEIPT_REF, "candidate-1", "workspace-1",
                        41L, FINGERPRINT, BASE, "action-1",
                        "validation-action-1", "validation-set-1",
                        "candidate-requirement-1"));
        String publishKey = ChainProjectPublishPort.stableIdempotencyKey(
                "task-1", "readiness-1", "check-1", 1, BASE, 41,
                "candidate-1", "validation-1",
                ChainRuntimePolicy.V1.policyVersion(), REQUEST, RECEIPT);
        when(revisions.applyAutomatically(7L, 13L, 41L,
                publishKey, BASE, FINGERPRINT,
                RECEIPT_REF)).thenAnswer(invocation -> {
                    jdbc.update("""
                            INSERT INTO project_revisions(
                                id,project_id,user_id,project_version,
                                source_type,source_operation_id)
                            VALUES(50,13,7,:version,'BASE',NULL)
                            """, new MapSqlParameterSource(
                            "version", BASE));
                    jdbc.update("""
                            INSERT INTO project_revision_operations(
                                project_id,user_id,operation_type,
                                idempotency_key,request_hash,
                                base_revision_id,base_version,
                                result_revision_id,result_version,
                                candidate_artifact_id,candidate_fingerprint,
                                accepted_change_indexes,
                                rejected_change_indexes,outcome,error_code,
                                created_at,completed_at)
                            VALUES(13,7,'APPLICATION',:key,:requestHash,
                                50,:base,NULL,NULL,41,:fingerprint,
                                '[0]','[]','FAILED','HTTP_502',
                                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                            """, new MapSqlParameterSource()
                            .addValue("key", publishKey)
                            .addValue("requestHash", automaticRequestHash())
                            .addValue("base", BASE)
                            .addValue("fingerprint", FINGERPRINT));
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY);
                });
        var authority = new ProductChainPublishAuthoritySource(
                jdbc, publishCandidateAuthority, candidates);
        var adapter = new ProductChainProjectPublishAdapter(
                foundations, workflow, candidates, publishCandidateAuthority,
                revisions,
                authority, jdbc, manager);
        var command = new ChainProjectPublishPort.PublishCommand(
                "task-1", "readiness-1", "check-1", 1, publishKey,
                BASE, 41, "candidate-1", "validation-1",
                ChainRuntimePolicy.V1.policyVersion(),
                REQUEST, RECEIPT);
        org.mockito.Mockito.doThrow(
                new IllegalStateException("forged candidate failure"))
                .when(candidates).getCurrent(7L, 41L);
        assertThrows(IllegalStateException.class,
                () -> adapter.publish(command));
        assertEquals(0L, count(jdbc, "project_revision_operations"));

        org.mockito.Mockito.doThrow(
                new TransientDataAccessResourceException(
                        "candidate store unavailable"))
                .when(candidates).getCurrent(7L, 41L);
        var temporaryCandidate = (ChainProjectPublishPort.Failed)
                adapter.publish(command);
        assertEquals(ChainProjectPublishPort.ErrorCode
                        .AUTHORITY_TEMPORARILY_UNAVAILABLE,
                temporaryCandidate.errorCode());
        assertTrue(temporaryCandidate.retryable());
        jdbc.update("DELETE FROM project_revision_operations",
                new MapSqlParameterSource());

        org.mockito.Mockito.doThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST))
                .when(candidates).getCurrent(7L, 41L);
        var rejectedCandidate = (ChainProjectPublishPort.Failed)
                adapter.publish(command);
        assertEquals(ChainProjectPublishPort.ErrorCode
                        .VALIDATION_BINDING_MISMATCH,
                rejectedCandidate.errorCode());
        assertFalse(rejectedCandidate.retryable());
        jdbc.update("DELETE FROM project_revision_operations",
                new MapSqlParameterSource());
        org.mockito.Mockito.doReturn(candidate).when(candidates)
                .getCurrent(7L, 41L);

        org.mockito.Mockito.doThrow(
                new IllegalStateException("forged validation failure"))
                .when(publishCandidateAuthority).requireExact(command);
        assertThrows(IllegalStateException.class,
                () -> adapter.publish(command));
        assertEquals(0L, count(jdbc, "project_revision_operations"));
        org.mockito.Mockito.doThrow(
                new TransientDataAccessResourceException(
                        "validation store unavailable"))
                .when(publishCandidateAuthority).requireExact(command);
        var temporaryValidation = (ChainProjectPublishPort.Failed)
                adapter.publish(command);
        assertEquals(ChainProjectPublishPort.ErrorCode
                        .AUTHORITY_TEMPORARILY_UNAVAILABLE,
                temporaryValidation.errorCode());
        assertTrue(temporaryValidation.retryable());
        jdbc.update("DELETE FROM project_revision_operations",
                new MapSqlParameterSource());
        org.mockito.Mockito.doThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(publishCandidateAuthority).requireExact(command);
        var missingValidation = (ChainProjectPublishPort.Failed)
                adapter.publish(command);
        assertEquals(ChainProjectPublishPort.ErrorCode
                        .VALIDATION_BINDING_MISMATCH,
                missingValidation.errorCode());
        assertFalse(missingValidation.retryable());
        jdbc.update("DELETE FROM project_revision_operations",
                new MapSqlParameterSource());
        org.mockito.Mockito.doReturn(new ProductChainPublishCandidateAuthority
                .Proof(7L, 13L, RECEIPT_REF, "candidate-1", "workspace-1",
                41L, FINGERPRINT, BASE, "action-1", "validation-action-1",
                "validation-set-1", "candidate-requirement-1"))
                .when(publishCandidateAuthority).requireExact(command);

        org.mockito.Mockito.doThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(publishCandidateAuthority).requireExact(command);
        when(workflow.findWorkspaceCandidates("task-1")).thenReturn(List.of());
        var missingCandidate = assertThrows(IllegalStateException.class,
                () -> adapter.publish(command));
        assertEquals("publish failure Workspace Candidate is missing or ambiguous",
                missingCandidate.getMessage());
        assertEquals(0L, count(jdbc, "project_revision_operations"));
        when(workflow.findWorkspaceCandidates("task-1"))
                .thenReturn(List.of(binding, binding));
        var ambiguousCandidate = assertThrows(IllegalStateException.class,
                () -> adapter.publish(command));
        assertEquals("publish failure Workspace Candidate is missing or ambiguous",
                ambiguousCandidate.getMessage());
        assertEquals(0L, count(jdbc, "project_revision_operations"));
        when(workflow.findWorkspaceCandidates("task-1")).thenReturn(
                List.of(binding));
        org.mockito.Mockito.doReturn(new ProductChainPublishCandidateAuthority
                .Proof(7L, 13L, RECEIPT_REF, "candidate-1", "workspace-1",
                41L, FINGERPRINT, BASE, "action-1", "validation-action-1",
                "validation-set-1", "candidate-requirement-1"))
                .when(publishCandidateAuthority).requireExact(command);

        AtomicReference<ChainProjectPublishPort.PublishResult> first =
                new AtomicReference<>();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            first.set(adapter.publish(command));
            assertTrue(first.get() instanceof ChainProjectPublishPort.Failed);
            var normalized = jdbc.queryForMap("""
                    SELECT request_hash,base_revision_id,error_code,
                           result_revision_id,result_version
                      FROM project_revision_operations
                     WHERE idempotency_key=:key
                    """, new MapSqlParameterSource("key", publishKey));
            assertEquals("CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE",
                    normalized.get("error_code"));
            assertEquals(ProductChainPublishAuthoritySource.chainFailureHash(
                            command,
                            "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE",
                            List.of(0)),
                    normalized.get("request_hash"));
            assertNull(normalized.get("base_revision_id"));
            assertNull(normalized.get("result_revision_id"));
            assertNull(normalized.get("result_version"));
            status.setRollbackOnly();
        });
        assertEquals(0L, count(jdbc, "project_revision_operations"));
        assertEquals(0L, count(jdbc, "project_revisions"));
        assertEquals(0L, count(jdbc, "agent_v2_chain_task_outcomes"));

        ChainProjectPublishPort.Failed durable =
                adapter.persistDeferredFailureAfterRollback();
        assertEquals(1L, count(jdbc, "project_revision_operations"));
        assertEquals(0L, count(jdbc, "project_revisions"));
        assertEquals(0L, count(jdbc, "agent_v2_chain_task_outcomes"));
        ChainProjectPublishPort.Failed replay =
                (ChainProjectPublishPort.Failed) adapter.publish(command);
        assertEquals(durable, replay);
        verify(revisions, times(1)).applyAutomatically(7L, 13L, 41L,
                publishKey, BASE, FINGERPRINT,
                "receipt-identity-1");
        assertTrue(replay.retryable());

        jdbc.update("""
                INSERT INTO project_revisions(
                    id,project_id,user_id,project_version,source_type,
                    source_operation_id)
                VALUES(50,13,7,:version,'BASE',NULL)
                """, new MapSqlParameterSource("version", BASE));
        jdbc.update("""
                UPDATE project_revision_operations
                   SET request_hash=:requestHash,base_revision_id=50,
                       error_code='HTTP_502'
                 WHERE idempotency_key=:key
                """, new MapSqlParameterSource()
                .addValue("requestHash", automaticRequestHash())
                .addValue("key", publishKey));
        assertThrows(IllegalStateException.class,
                () -> adapter.publish(command),
                "an ordinary HTTP failure replay must not be normalized");
        jdbc.update("""
                UPDATE project_revision_operations
                   SET request_hash=:requestHash,base_revision_id=NULL,
                       error_code=:error
                 WHERE idempotency_key=:key
                """, new MapSqlParameterSource()
                .addValue("requestHash",
                        ProductChainPublishAuthoritySource.chainFailureHash(
                                command,
                                "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE",
                                List.of(0)))
                .addValue("error",
                        "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE")
                .addValue("key", publishKey));
        jdbc.update("DELETE FROM project_revisions WHERE id=50",
                new MapSqlParameterSource());
        adapter.clearDeferredFailure();

        var recovery = publishRecoveryFacts();
        assertFalse(recovery.transition().transitionId().equals(
                recovery.readiness().transitionId()),
                "readiness and finalization are distinct transitions");
        assertTrue(authority.find(recovery.transition(),
                recovery.readiness(), recovery.check()).isEmpty(),
                "an unexhausted temporary attempt must resume mechanically");
        var forgedTarget = transitionFor(
                recovery.readiness(), "9".repeat(64), "review-1");
        assertThrows(IllegalStateException.class, () -> authority.find(
                forgedTarget, recovery.readiness(), checkFor(
                        recovery.readiness(), forgedTarget.transitionId())));
        var forgedSource = transitionFor(
                recovery.readiness(), readinessDigest(
                        recovery.readiness()), "another-review");
        assertThrows(IllegalStateException.class, () -> authority.find(
                forgedSource, recovery.readiness(), checkFor(
                        recovery.readiness(), forgedSource.transitionId())));

        jdbc.update("""
                UPDATE project_revision_operations
                   SET error_code = :error,
                       request_hash = :requestHash
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource()
                .addValue("error", "CHAIN_PUBLISH_VERSION_CONFLICT")
                .addValue("requestHash",
                        ProductChainPublishAuthoritySource.chainFailureHash(
                                command, "CHAIN_PUBLISH_VERSION_CONFLICT",
                                List.of(0)))
                .addValue("key", publishKey));
        var fixedFailure = authority.find(recovery.transition(),
                recovery.readiness(), recovery.check()).orElseThrow();
        assertEquals(ChainProjectPublishPort.ErrorCode.VERSION_CONFLICT,
                fixedFailure.errorCode());
        assertFalse(fixedFailure.retryable());

        jdbc.update("""
                UPDATE project_revision_operations
                   SET result_revision_id=999, result_version=:version
                 WHERE idempotency_key=:key
                """, new MapSqlParameterSource()
                .addValue("version", "7".repeat(64))
                .addValue("key", publishKey));
        assertThrows(IllegalStateException.class, () -> authority.find(
                recovery.transition(), recovery.readiness(),
                recovery.check()),
                "a failed operation cannot carry a result identity");
        jdbc.update("""
                UPDATE project_revision_operations
                   SET result_revision_id=NULL, result_version=NULL
                 WHERE idempotency_key=:key
                """, new MapSqlParameterSource("key", publishKey));

        jdbc.update("""
                UPDATE project_revision_operations
                   SET error_code='HTTP_503'
                 WHERE idempotency_key=:key
                """, new MapSqlParameterSource("key", publishKey));
        assertThrows(IllegalStateException.class, () -> authority.find(
                recovery.transition(), recovery.readiness(),
                recovery.check()),
                "a failed operation requires a known fixed chain error");
        jdbc.update("""
                UPDATE project_revision_operations
                   SET error_code='CHAIN_PUBLISH_VERSION_CONFLICT'
                 WHERE idempotency_key=:key
                """, new MapSqlParameterSource("key", publishKey));

        jdbc.update("""
                UPDATE project_revision_operations
                   SET error_code = :error,
                       request_hash = :requestHash
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource()
                .addValue("error",
                        "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE")
                .addValue("requestHash",
                        ProductChainPublishAuthoritySource.chainFailureHash(
                                command,
                                "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE",
                                List.of(0)))
                .addValue("key", publishKey));
        var attempt2 = publishCommand(2);
        jdbc.update("""
                INSERT INTO project_revision_operations(
                    project_id,user_id,operation_type,idempotency_key,
                    request_hash,base_version,candidate_artifact_id,
                    candidate_fingerprint,accepted_change_indexes,
                    rejected_change_indexes,outcome,error_code,created_at)
                VALUES(13,7,'APPLICATION',:key,:requestHash,:base,41,
                    :fingerprint,'[0]','[]','FAILED',:error,CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("key", attempt2.idempotencyKey())
                .addValue("requestHash",
                        ProductChainPublishAuthoritySource.chainFailureHash(
                                attempt2,
                                "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE",
                                List.of(0)))
                .addValue("base", BASE)
                .addValue("fingerprint", FINGERPRINT)
                .addValue("error",
                        "CHAIN_PUBLISH_AUTHORITY_TEMPORARILY_UNAVAILABLE"));
        var exhausted = authority.find(recovery.transition(),
                recovery.readiness(), recovery.check()).orElseThrow();
        assertEquals(ChainProjectPublishPort.ErrorCode
                        .AUTHORITY_TEMPORARILY_UNAVAILABLE,
                exhausted.errorCode());
        assertFalse(exhausted.retryable());

        long operation2 = jdbc.queryForObject("""
                SELECT id FROM project_revision_operations
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource(
                "key", attempt2.idempotencyKey()), Long.class);
        jdbc.update("""
                INSERT INTO project_revisions(
                    id,project_id,user_id,project_version,source_type,
                    source_operation_id)
                VALUES(50,13,7,:version,'BASE',NULL)
                """, new MapSqlParameterSource("version", BASE));
        jdbc.update("""
                INSERT INTO project_revisions(
                    id,project_id,user_id,project_version,source_type,
                    source_operation_id)
                VALUES(77,13,7,:version,'APPLICATION',:operationId)
                """, new MapSqlParameterSource()
                .addValue("version", "9".repeat(64))
                .addValue("operationId", operation2));
        jdbc.update("""
                UPDATE project_revision_operations
                   SET outcome='SUCCEEDED', error_code=NULL,
                       request_hash=:requestHash,
                       base_revision_id=50,
                       result_revision_id=77, result_version=:resultVersion
                 WHERE id=:operationId
                """, new MapSqlParameterSource()
                .addValue("requestHash", automaticRequestHash())
                .addValue("resultVersion", "9".repeat(64))
                .addValue("operationId", operation2));
        assertTrue(authority.find(recovery.transition(),
                recovery.readiness(), recovery.check()).isEmpty(),
                "an exact success suppresses stale publish failure handoff");

        jdbc.update("""
                UPDATE project_revision_operations
                   SET error_code='CHAIN_PUBLISH_UNKNOWN'
                 WHERE id=:operationId
                """, new MapSqlParameterSource("operationId", operation2));
        assertThrows(IllegalStateException.class, () -> authority.find(
                recovery.transition(), recovery.readiness(),
                recovery.check()),
                "a succeeded operation cannot bypass an unknown error");
        jdbc.update("""
                UPDATE project_revision_operations SET error_code=NULL
                 WHERE id=:operationId
                """, new MapSqlParameterSource("operationId", operation2));

        jdbc.update("""
                UPDATE project_revision_operations SET result_version=NULL
                 WHERE id=:operationId
                """, new MapSqlParameterSource("operationId", operation2));
        assertThrows(IllegalStateException.class, () -> authority.find(
                recovery.transition(), recovery.readiness(),
                recovery.check()),
                "a succeeded operation requires a complete result identity");
        jdbc.update("""
                UPDATE project_revision_operations SET result_version=:version
                 WHERE id=:operationId
                """, new MapSqlParameterSource()
                .addValue("version", "9".repeat(64))
                .addValue("operationId", operation2));

        jdbc.update("""
                UPDATE project_revision_operations
                   SET base_revision_id=999
                 WHERE id=:operationId
                """, new MapSqlParameterSource("operationId", operation2));
        assertThrows(IllegalStateException.class, () -> authority.find(
                recovery.transition(), recovery.readiness(),
                recovery.check()),
                "a matching version string cannot replace exact base revision");
        jdbc.update("""
                UPDATE project_revision_operations
                   SET base_revision_id=50
                 WHERE id=:operationId
                """, new MapSqlParameterSource("operationId", operation2));

        jdbc.update("""
                UPDATE project_revision_operations
                   SET error_code = :error,
                       request_hash = :requestHash
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource()
                .addValue("error", "CHAIN_PUBLISH_VERSION_CONFLICT")
                .addValue("requestHash",
                        ProductChainPublishAuthoritySource.chainFailureHash(
                                command, "CHAIN_PUBLISH_VERSION_CONFLICT",
                                List.of(0)))
                .addValue("key", publishKey));
        assertThrows(IllegalStateException.class, () -> authority.find(
                recovery.transition(), recovery.readiness(),
                recovery.check()),
                "a non-temporary terminal failure cannot have attempt 2");

        long operation1 = jdbc.queryForObject("""
                SELECT id FROM project_revision_operations
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource("key", publishKey),
                Long.class);
        jdbc.update("""
                INSERT INTO project_revisions(
                    id,project_id,user_id,project_version,source_type,
                    source_operation_id)
                VALUES(78,13,7,:version,'APPLICATION',:operationId)
                """, new MapSqlParameterSource()
                .addValue("version", "a".repeat(64))
                .addValue("operationId", operation1));
        jdbc.update("""
                UPDATE project_revision_operations
                   SET outcome='SUCCEEDED', error_code=NULL,
                       request_hash=:requestHash, base_revision_id=50,
                       result_revision_id=78, result_version=:resultVersion
                 WHERE id=:operationId
                """, new MapSqlParameterSource()
                .addValue("requestHash", automaticRequestHash())
                .addValue("resultVersion", "a".repeat(64))
                .addValue("operationId", operation1));
        assertThrows(IllegalStateException.class, () -> authority
                .findExactSuccess(recovery.readiness(), recovery.check()),
                "a successful attempt cannot have a later attempt");
    }

    @Test
    void completedOutcomeHasOneWriterAndProjectsFormalReadyFacts()
            throws Exception {
        String completed = source("finalization",
                "ProductChainCompletedOutcomeAdapter.java");
        assertTrue(completed.contains("new ChainTaskOutcomeRuntime("));
        assertFalse(completed.contains("assessment.userVisibleFacts()"));
        assertTrue(completed.contains("knownLimitations()"));
        assertTrue(completed.contains("residualRisks()"));
        try (var files = Files.walk(MAIN)) {
            long writers = files.filter(path -> path.toString()
                            .endsWith(".java"))
                    .map(ProjectChainFinalizationTest::uncheckedRead)
                    .filter(text -> text.contains(
                            "new ChainTaskOutcomeRuntime("))
                    .count();
            assertEquals(1, writers);
        }

        var readiness = publishRecoveryFacts().readiness();
        Instant now = readiness.createdAt();
        String payloadJson = """
                {"review":{"reviewScope":"final","reviewedObjectRefs":["object-1"],"decisionReason":"ready","directFactRefs":["fact-1"],"knownLimitations":["only-limit"]},"finalization":{"requirementCoverage":[{"requirement":"deliverable","status":"SATISFIED","factRefs":["fact-1"]}],"finalArtifactAssessment":{"status":"BOUND","authorityRef":"artifact-41","reason":null},"finalCandidateAssessment":{"status":"BOUND","authorityRef":"candidate-1","reason":null},"validationAssessment":{"status":"BOUND","authorityRef":"frame-1","reason":null},"publishRequirementAssessment":{"status":"BOUND","authorityRef":"frame-1","reason":null},"userVisibleFacts":["visible-fact-is-not-a-limit"],"residualRisks":["only-risk"]}}
                """.trim();
        var review = new ChainPersistenceRecords.ReviewDecisionRecord(
                "review-1", "task-1", "review-event-1", "proposal-1",
                "STEP_RESULT", "result-1",
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE, "ready",
                canonicalJson("[\"fact-1\"]"), "1".repeat(64), now);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal-1", "task-1", "invocation-1", 1,
                ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE,
                canonicalJson(payloadJson), canonicalJson("[]"),
                null, null, now);
        var state = new ChainPersistenceRecords.ProposalStateEventRecord(
                "proposal-1", 1L, "task-1", "proposal-state-1",
                ChainProposalState.ACCEPTED, null, null, now);
        var bound = new ChainPersistenceRecords.ProposalStateEventRecord(
                "proposal-1", 2L, "task-1", "proposal-state-2",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "REVIEW_DECISION", "review-1", now);
        ChainWorkflowRepository outcomeWorkflow = mock(
                ChainWorkflowRepository.class);
        ChainModelRepository outcomeModels = mock(ChainModelRepository.class);
        when(outcomeWorkflow.findReviewDecisions("task-1"))
                .thenReturn(List.of(review));
        when(outcomeModels.findProposal("proposal-1"))
                .thenReturn(java.util.Optional.of(proposal));
        when(outcomeModels.findProposalStateEvents("proposal-1"))
                .thenReturn(List.of(state));
        var adapter = new ProductChainCompletedOutcomeAdapter(
                mock(io.paperagent.v2.chain.ChainFinalizationRepository.class),
                outcomeWorkflow, outcomeModels,
                mock(ChainTaskOutcomeWriter.class));
        var method = ProductChainCompletedOutcomeAdapter.class
                .getDeclaredMethod("finalFacts",
                        ChainPersistenceRecords
                                .FinalizationReadinessRecord.class);
        method.setAccessible(true);
        var interrupted = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(adapter, readiness));
        assertTrue(interrupted.getCause()
                instanceof IllegalStateException,
                "ACCEPTED-only proposal is an interrupted review commit");
        when(outcomeModels.findProposalStateEvents("proposal-1"))
                .thenReturn(List.of(state, bound));
        Object facts = method.invoke(adapter, readiness);
        var limitationsMethod = facts.getClass()
                .getDeclaredMethod("limitations");
        limitationsMethod.setAccessible(true);
        var limitations = (ChainPersistenceRecords.CanonicalJson)
                limitationsMethod.invoke(facts);
        assertEquals("[\"only-limit\"]", limitations.json());
        assertFalse(limitations.json().contains(
                "visible-fact-is-not-a-limit"));

        when(outcomeModels.findProposalStateEvents("proposal-1"))
                .thenReturn(List.of(state));
        var authorityAdapter = new ProductChainFinalizationAuthorityAdapter(
                mock(ChainFoundationRepository.class), outcomeWorkflow,
                mock(io.paperagent.v2.chain
                        .ChainFinalizationRepository.class), outcomeModels,
                explicitBootstrapAuthority(),
                mock(CandidateChangeArtifactService.class),
                mock(com.yanban.api.agent.v2.chain.recovery
                        .ProductChainReadinessAuthority.class),
                mock(com.yanban.api.project.ProjectService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        var assessmentMethod = ProductChainFinalizationAuthorityAdapter.class
                .getDeclaredMethod("finalAssessment",
                        ChainPersistenceRecords
                                .FinalizationReadinessRecord.class);
        assessmentMethod.setAccessible(true);
        var authorityInterrupted = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> assessmentMethod.invoke(authorityAdapter, readiness));
        assertTrue(authorityInterrupted.getCause()
                instanceof IllegalStateException,
                "finalization inspection cannot consume ACCEPTED-only state");
        when(outcomeModels.findProposalStateEvents("proposal-1"))
                .thenReturn(List.of(state, bound));
        assertTrue(assessmentMethod.invoke(authorityAdapter, readiness)
                != null);

        var temporaryMethod = ProductChainFinalizationAuthorityAdapter.class
                .getDeclaredMethod("temporarilyUnavailable",
                        RuntimeException.class);
        temporaryMethod.setAccessible(true);
        assertFalse((Boolean) temporaryMethod.invoke(null,
                new IllegalStateException("forged")));
        assertFalse((Boolean) temporaryMethod.invoke(null,
                new ResponseStatusException(HttpStatus.NOT_FOUND)));
        assertTrue((Boolean) temporaryMethod.invoke(null,
                new ResponseStatusException(HttpStatus.BAD_GATEWAY)));
        assertTrue((Boolean) temporaryMethod.invoke(null,
                new TransientDataAccessResourceException("transient")));
        var notFoundMethod = ProductChainFinalizationAuthorityAdapter.class
                .getDeclaredMethod("notFound", RuntimeException.class);
        notFoundMethod.setAccessible(true);
        assertTrue((Boolean) notFoundMethod.invoke(null,
                new ResponseStatusException(HttpStatus.NOT_FOUND)));
        assertFalse((Boolean) notFoundMethod.invoke(null,
                new ResponseStatusException(HttpStatus.FORBIDDEN)));
    }

    @Test
    void completedOutcomeUsesTheCheckNamedByTheFinalizationStage() {
        var facts = publishRecoveryFacts();
        var readiness = facts.readiness();
        var exactCheck = facts.check();
        var laterCheck = checkWithIdentity(readiness,
                facts.transition().transitionId(), "check-2", 2);
        var finalization = mock(io.paperagent.v2.chain
                .ChainFinalizationRepository.class);
        var workflow = mock(ChainWorkflowRepository.class);
        var models = mock(ChainModelRepository.class);
        var stored = new AtomicReference<
                ChainPersistenceRecords.TaskOutcomeRecord>();
        ChainTaskOutcomeWriter writer = authority -> {
            stored.set(authority.fact());
            var request = authority.event();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            request.eventId(), request.taskId(), 1L,
                            request.eventType(), request.transitionId(),
                            request.sourceIdentitySha256(),
                            request.committedAt()), authority.fact(), false);
        };
        when(workflow.findTransition(facts.transition().transitionId()))
                .thenReturn(java.util.Optional.of(facts.transition()));
        when(workflow.findTransitionStages(facts.transition().transitionId()))
                .thenReturn(completionStages(facts, "publish-receipt-1"));
        when(finalization.findReadinessById(readiness.readinessId()))
                .thenReturn(java.util.Optional.of(readiness));
        when(finalization.findFinalizationChecks(readiness.readinessId()))
                .thenReturn(List.of(laterCheck, exactCheck));
        when(finalization.findTaskOutcome(readiness.taskId())).thenAnswer(
                ignored -> java.util.Optional.ofNullable(stored.get()));
        stubReadyFinalFacts(workflow, models, readiness);
        var published = new ChainProjectPublishPort.Published(
                "publish-operation-1", 1, "publish-key-1", false,
                readiness.projectVersion(), readiness.candidateKey(),
                readiness.validationId(), "project-v2", 2L,
                "publish-receipt-1");
        var adapter = new ProductChainCompletedOutcomeAdapter(
                finalization, workflow, models, writer);

        var completed = adapter.complete(
                new io.paperagent.v2.chain.finalization
                        .ChainCompletedOutcomePort.CompletionCommand(
                        "command-1", facts.transition().transitionId(),
                        readiness, exactCheck, published));

        assertEquals(exactCheck.finalizationCheckId(),
                completed.outcome().finalizationCheckId());
        assertEquals(readiness.readinessId(),
                completed.outcome().finalizationReadinessId());
        assertEquals(readiness.validationRequestDigest(),
                completed.outcome().validationRequestDigest());
        assertEquals(readiness.publishRequirementDigest(),
                completed.outcome().publishRequirementDigest());

        assertThrows(IllegalStateException.class, () -> adapter.complete(
                new io.paperagent.v2.chain.finalization
                        .ChainCompletedOutcomePort.CompletionCommand(
                        "command-1", facts.transition().transitionId(),
                        readiness, laterCheck, published)));
    }

    @Test
    void finalizationInspectionRecomputesExactApplicabilityAndFinalAcceptance()
            throws Exception {
        var base = publishRecoveryFacts().readiness();
        var readiness = readinessWithAccepted(
                base, canonicalJson("[\"accepted-1\"]"), 7L);
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        var plan = mock(ChainPersistenceRecords.PlanBindingRecord.class);
        when(plan.taskFrameId()).thenReturn(readiness.taskFrameId());
        when(plan.planId()).thenReturn(readiness.finalPlanId());
        when(plan.planRevisionId()).thenReturn(
                readiness.finalPlanRevisionId());
        when(plan.planRevisionNumber()).thenReturn(
                readiness.finalPlanRevisionNumber());
        when(plan.instructionId()).thenReturn(readiness.instructionId());

        var candidate = mock(
                ChainPersistenceRecords.CandidateStepResultRecord.class);
        when(candidate.candidateResultId()).thenReturn("candidate-result-1");
        when(candidate.contentId()).thenReturn("content-1");
        when(candidate.taskFrameId()).thenReturn(readiness.taskFrameId());
        when(candidate.planId()).thenReturn(readiness.finalPlanId());
        when(candidate.planRevisionId()).thenReturn(
                readiness.finalPlanRevisionId());
        when(candidate.planRevisionNumber()).thenReturn(
                readiness.finalPlanRevisionNumber());
        when(candidate.instructionId()).thenReturn(readiness.instructionId());
        when(candidate.stepId()).thenReturn(readiness.finalStepId());
        when(candidate.activationEventId()).thenReturn("activation-1");

        var accepted = mock(
                ChainPersistenceRecords.AcceptedResultRecord.class);
        when(accepted.acceptedResultId()).thenReturn("accepted-1");
        when(accepted.candidateResultId()).thenReturn("candidate-result-1");
        when(accepted.reviewDecisionId()).thenReturn(
                readiness.reviewDecisionId());
        when(accepted.transitionId()).thenReturn(readiness.transitionId());
        when(accepted.contentId()).thenReturn("content-1");
        when(accepted.acceptedIdentitySha256()).thenReturn("a".repeat(64));

        var transition = new ChainPersistenceRecords.TransitionRecord(
                readiness.transitionId(), readiness.taskId(),
                "final-step-transition-event",
                ChainTransitionType.FINAL_STEP_READINESS,
                readiness.reviewDecisionId(), "a".repeat(64),
                readiness.createdAt());
        var applicability = mock(
                ChainPersistenceRecords.ResultApplicabilityRecord.class);
        when(applicability.eventId()).thenReturn("applicability-event-1");
        when(applicability.applicabilityId()).thenReturn("applicability-1");
        when(applicability.acceptedResultId()).thenReturn("accepted-1");
        when(applicability.taskId()).thenReturn(readiness.taskId());
        when(applicability.sourceDecisionId()).thenReturn(
                readiness.transitionId());
        when(applicability.sourceType()).thenReturn(
                io.paperagent.v2.chain.ChainApplicability.SourceType
                        .ACCEPT_STEP);
        when(applicability.targetTaskFrameId()).thenReturn(
                readiness.taskFrameId());
        when(applicability.targetPlanId()).thenReturn(
                readiness.finalPlanId());
        when(applicability.targetPlanRevisionId()).thenReturn(
                readiness.finalPlanRevisionId());
        when(applicability.targetCandidateKey()).thenReturn(
                readiness.candidateKey());
        when(applicability.targetInstructionVersionId()).thenReturn(
                readiness.instructionId());
        when(applicability.conclusion()).thenReturn(
                io.paperagent.v2.chain.ChainApplicability.Outcome.APPLICABLE);
        when(foundations.findAuthorityEvents(
                readiness.taskId(), Long.MAX_VALUE)).thenReturn(List.of(
                new ChainPersistenceRecords.AuthorityEventRecord(
                        "applicability-event-1", readiness.taskId(), 7L,
                        "RESULT_APPLICABILITY", null, "1".repeat(64),
                        readiness.createdAt())));
        when(workflow.findCandidateStepResults(readiness.taskId()))
                .thenReturn(List.of(candidate));
        when(workflow.findAcceptedResults(readiness.taskId()))
                .thenReturn(List.of(accepted));
        when(workflow.findTransition(readiness.transitionId()))
                .thenReturn(java.util.Optional.of(transition));
        when(workflow.findTransitionStages(readiness.transitionId()))
                .thenReturn(readinessStages(
                        transition, readiness, "accepted-1", false));
        when(workflow.findApplicabilityDecisions(readiness.taskId()))
                .thenReturn(List.of(applicability));
        var readinessReview = new ChainPersistenceRecords.ReviewDecisionRecord(
                readiness.reviewDecisionId(), readiness.taskId(),
                "review-event-ready", "proposal-ready",
                "CANDIDATE_STEP_RESULT", "candidate-result-1",
                ChainProposalKind
                        .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE,
                "ready", canonicalJson("[]"), "9".repeat(64),
                readiness.createdAt());
        when(workflow.findReviewDecisions(readiness.taskId()))
                .thenReturn(List.of(readinessReview));

        var adapter = new ProductChainFinalizationAuthorityAdapter(
                foundations, workflow,
                mock(io.paperagent.v2.chain.ChainFinalizationRepository.class),
                mock(ChainModelRepository.class),
                mock(com.yanban.api.agent.v2.persistence
                        .ProductPlanBootstrapRepositoryAdapter.class),
                mock(CandidateChangeArtifactService.class),
                mock(com.yanban.api.agent.v2.chain.recovery
                        .ProductChainReadinessAuthority.class),
                mock(com.yanban.api.project.ProjectService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        var finalStep = ProductChainFinalizationAuthorityAdapter.class
                .getDeclaredMethod("finalStepSatisfied",
                        ChainPersistenceRecords
                                .FinalizationReadinessRecord.class,
                        ChainPersistenceRecords.PlanBindingRecord.class,
                        List.class);
        finalStep.setAccessible(true);
        assertTrue((Boolean) finalStep.invoke(
                adapter, readiness, plan, List.of("accepted-1")));

        var wrongStepStages = new java.util.ArrayList<>(readinessStages(
                transition, readiness, "accepted-1", false));
        var validStepStage = wrongStepStages.get(3);
        wrongStepStages.set(3,
                new ChainPersistenceRecords.TransitionStageRecord(
                        validStepStage.transitionId(),
                        validStepStage.stageCode(), validStepStage.taskId(),
                        validStepStage.eventId(),
                        validStepStage.stageOrdinal(), "STEP_EVENT",
                        "step.completed.wrong", null, null,
                        validStepStage.committedAt()));
        when(workflow.findTransitionStages(readiness.transitionId()))
                .thenReturn(wrongStepStages);
        assertFalse((Boolean) finalStep.invoke(
                        adapter, readiness, plan, List.of("accepted-1")),
                "an arbitrary Step event cannot satisfy finalization");
        when(workflow.findTransitionStages(readiness.transitionId()))
                .thenReturn(readinessStages(
                        transition, readiness, "accepted-1", false));

        when(applicability.sourceType()).thenReturn(
                io.paperagent.v2.chain.ChainApplicability.SourceType
                        .PLAN_REVISION);
        assertFalse((Boolean) finalStep.invoke(
                        adapter, readiness, plan, List.of("accepted-1")),
                "readiness applicability must originate from ACCEPT_STEP");
        when(applicability.sourceType()).thenReturn(
                io.paperagent.v2.chain.ChainApplicability.SourceType
                        .ACCEPT_STEP);

        when(applicability.conclusion()).thenReturn(
                io.paperagent.v2.chain.ChainApplicability.Outcome
                        .NOT_APPLICABLE);
        assertFalse((Boolean) finalStep.invoke(
                        adapter, readiness, plan, List.of("accepted-1")),
                "the current AcceptedResult must remain applicable");
        when(applicability.conclusion()).thenReturn(
                io.paperagent.v2.chain.ChainApplicability.Outcome.APPLICABLE);

        when(accepted.acceptedIdentitySha256()).thenReturn("b".repeat(64));
        assertFalse((Boolean) finalStep.invoke(
                        adapter, readiness, plan, List.of("accepted-1")),
                "the readiness target must bind the exact AcceptedResult");
        when(accepted.acceptedIdentitySha256()).thenReturn("a".repeat(64));

        when(accepted.reviewDecisionId()).thenReturn("another-review");
        assertFalse((Boolean) finalStep.invoke(
                adapter, readiness, plan, List.of("accepted-1")),
                "a cross-review acceptance cannot satisfy the final Step");
        when(accepted.reviewDecisionId()).thenReturn(
                readiness.reviewDecisionId());
        assertFalse((Boolean) finalStep.invoke(
                adapter, readiness, plan, List.of()),
                "the final accepted result must be in the frozen set");

        String priorReviewId = "review-accepted-prior";
        String priorTransitionId = new io.paperagent.v2.chain.ChainIdentity
                .Transition(ChainTransitionType.ACCEPT_STEP,
                readiness.taskId(), priorReviewId,
                accepted.acceptedIdentitySha256()).transitionId();
        var priorReview = new ChainPersistenceRecords.ReviewDecisionRecord(
                priorReviewId, readiness.taskId(), "review-event-prior",
                "proposal-prior", "CANDIDATE_STEP_RESULT",
                "candidate-result-1", ChainProposalKind.REFLECTOR_ACCEPT_STEP,
                "accepted", canonicalJson("[]"), "8".repeat(64),
                readiness.createdAt());
        var pureReadyReview = new ChainPersistenceRecords.ReviewDecisionRecord(
                readiness.reviewDecisionId(), readiness.taskId(),
                "review-event-ready", "proposal-ready",
                "CANDIDATE_STEP_RESULT", "candidate-result-1",
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE,
                "ready", canonicalJson("[]"), "9".repeat(64),
                readiness.createdAt());
        var priorTransition = new ChainPersistenceRecords.TransitionRecord(
                priorTransitionId, readiness.taskId(),
                "transition-event-prior", ChainTransitionType.ACCEPT_STEP,
                priorReviewId, accepted.acceptedIdentitySha256(),
                readiness.createdAt());
        when(accepted.reviewDecisionId()).thenReturn(priorReviewId);
        when(accepted.transitionId()).thenReturn(priorTransitionId);
        when(workflow.findReviewDecisions(readiness.taskId()))
                .thenReturn(List.of(priorReview, pureReadyReview));
        when(workflow.findTransition(anyString())).thenAnswer(invocation -> {
            String transitionId = invocation.getArgument(0);
            if (readiness.transitionId().equals(transitionId)) {
                return java.util.Optional.of(transition);
            }
            if (priorTransitionId.equals(transitionId)) {
                return java.util.Optional.of(priorTransition);
            }
            return java.util.Optional.empty();
        });
        when(workflow.findTransitionStages(readiness.transitionId()))
                .thenReturn(readinessStages(
                        transition, readiness, "accepted-1", true));
        assertTrue((Boolean) finalStep.invoke(
                        adapter, readiness, plan, List.of("accepted-1")),
                "pure READY must verify the prior ACCEPT_STEP result");

        var acceptedProjection = ProductChainFinalizationAuthorityAdapter.class
                .getDeclaredMethod("accepted",
                        ChainPersistenceRecords
                                .FinalizationReadinessRecord.class,
                        List.class);
        acceptedProjection.setAccessible(true);
        assertTrue(acceptedProjection.invoke(
                adapter, readiness, List.of("accepted-1")) != null);
        var forgedCut = readinessWithAccepted(
                readiness, readiness.acceptedSet(), 999L);
        var rejected = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> acceptedProjection.invoke(
                        adapter, forgedCut, List.of("accepted-1")));
        assertTrue(rejected.getCause() instanceof IllegalStateException,
                "a caller-provided oversized cut cannot prove itself");
    }

    @Test
    void failedTransitionHandoffRequiresExactFormalFailureIdentity()
            throws Exception {
        String transition = source("finalization",
                "ProductChainFinalizationTransitionAdapter.java");
        assertTrue(transition.contains("\"FINALIZATION_CHECK\""));
        assertTrue(transition.contains("REFLECTOR_TASK_FAILED"));
        assertTrue(transition.contains("ChainTaskOutcomeStatus.FAILED"));
        assertTrue(transition.contains("check.finalizationCheckId()"));
        assertTrue(transition.contains("findInstruction("));
        assertFalse(transition.contains("task.createdByCommandId()"));

        var facts = publishRecoveryFacts();
        var passed = facts.check();
        var failed = new ChainPersistenceRecords.FinalizationCheckRecord(
                passed.finalizationCheckId(), passed.taskId(),
                passed.eventId(), passed.readinessId(),
                passed.transitionId(), passed.attemptNo(),
                passed.taskFrameId(), passed.finalPlanRevisionId(),
                passed.acceptedSetSha256(), passed.candidateKey(),
                passed.workspaceId(), passed.validationId(),
                passed.validationRequestDigest(),
                passed.validationReceiptDigest(),
                passed.publishRequirementDigest(), passed.instructionId(),
                passed.projectVersion(), passed.inputDigest(),
                passed.contentDigest(), passed.publishDigest(),
                ChainFinalization.Outcome.FAILED,
                ChainFinalization.ErrorCode.VALIDATION_NOT_SUCCESSFUL,
                ChainFinalization.FailureHandling.REFLECTOR_REQUIRED,
                passed.runtimePolicyVersion(), passed.createdAt());
        var crossTaskReview = new ChainPersistenceRecords
                .ReviewDecisionRecord(
                "handoff-1", "task-2", "handoff-event-1", "proposal-2",
                "FINALIZATION_CHECK", failed.finalizationCheckId(),
                ChainProposalKind.REFLECTOR_TASK_FAILED, "failed",
                canonicalJson("[\"fact-1\"]"), "2".repeat(64),
                facts.transition().createdAt());
        List<ChainFinalizationTransitionPort.StageAuthority> prefix = List.of(
                ChainFinalizationTransitionPort.StageAuthority.open(),
                ChainFinalizationTransitionPort.StageAuthority.predecessor(
                        ChainTransitionStage.READINESS_VERIFIED,
                        "FINALIZATION_READINESS",
                        facts.readiness().readinessId()),
                ChainFinalizationTransitionPort.StageAuthority.successor(
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                        "FINALIZATION_CHECK",
                        failed.finalizationCheckId()),
                ChainFinalizationTransitionPort.StageAuthority.successor(
                        ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED,
                        "REVIEW_DECISION",
                        crossTaskReview.reviewDecisionId()));
        List<ChainPersistenceRecords.TransitionStageRecord> stored = List.of(
                transitionStage(facts.transition(), 0, prefix.get(0)),
                transitionStage(facts.transition(), 1, prefix.get(1)),
                transitionStage(facts.transition(), 2, prefix.get(2)));
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        var finalization = mock(io.paperagent.v2.chain
                .ChainFinalizationRepository.class);
        when(workflow.findTransition(facts.transition().transitionId()))
                .thenReturn(java.util.Optional.of(facts.transition()));
        when(workflow.findTransitionStages(
                facts.transition().transitionId())).thenReturn(stored);
        when(workflow.findReviewDecisions("task-1"))
                .thenReturn(List.of(crossTaskReview));
        when(finalization.findReadiness("task-1"))
                .thenReturn(List.of(facts.readiness()));
        when(finalization.findFinalizationChecks(
                facts.readiness().readinessId())).thenReturn(List.of(failed));
        var adapter = new ProductChainFinalizationTransitionAdapter(
                workflow, mock(ChainFoundationRepository.class),
                mock(ChainTransitionWriter.class), finalization,
                mock(ProductChainPublishAuthoritySource.class));
        var command = new ChainFinalizationTransitionPort.AdvanceCommand(
                "task-1", facts.transition().transitionId(), "review-1",
                facts.transition().targetIdentityDigest(), prefix,
                facts.transition().createdAt());
        assertThrows(IllegalStateException.class,
                () -> adapter.advance(command),
                "a repository cannot smuggle a cross-task review handoff");
    }

    @Test
    void finalizationAuthorityRequiresExactReadinessBundleBeforeProjection() {
        var readiness = publishRecoveryFacts().readiness();
        var expected = new IllegalStateException(
                "ValidationBundle identity drift");
        var adapter = new ProductChainFinalizationAuthorityAdapter(
                mock(ChainFoundationRepository.class),
                mock(ChainWorkflowRepository.class),
                mock(io.paperagent.v2.chain
                        .ChainFinalizationRepository.class),
                mock(ChainModelRepository.class),
                mock(com.yanban.api.agent.v2.persistence
                        .ProductPlanBootstrapRepositoryAdapter.class),
                mock(CandidateChangeArtifactService.class),
                value -> {
                    assertEquals(readiness, value);
                    throw expected;
                },
                mock(com.yanban.api.project.ProjectService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals(expected, assertThrows(IllegalStateException.class,
                () -> adapter.inspect(readiness)));
    }

    @Test
    void finalizationReadsPlanBindingFromFrozenReadinessIdentity() throws Exception {
        var readiness = publishRecoveryFacts().readiness();
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        var matching = mock(
                ChainPersistenceRecords.PlanBindingRecord.class);
        when(matching.taskId()).thenReturn(readiness.taskId());
        when(matching.instructionId()).thenReturn(readiness.instructionId());
        when(matching.taskFrameId()).thenReturn(readiness.taskFrameId());
        when(matching.planId()).thenReturn(readiness.finalPlanId());
        when(matching.planRevisionId()).thenReturn(
                readiness.finalPlanRevisionId());
        when(matching.planRevisionNumber()).thenReturn(
                readiness.finalPlanRevisionNumber());
        var terminalGateAuthority = mock(
                ChainPersistenceRecords.PlanBindingRecord.class);
        when(terminalGateAuthority.taskId()).thenReturn(readiness.taskId());
        when(terminalGateAuthority.instructionId()).thenReturn(
                readiness.instructionId());
        when(terminalGateAuthority.taskFrameId()).thenReturn(
                readiness.taskFrameId());
        when(terminalGateAuthority.planId()).thenReturn(readiness.finalPlanId());
        when(terminalGateAuthority.planRevisionId()).thenReturn(
                "terminal-outcome-not-a-plan-revision");
        when(terminalGateAuthority.planRevisionNumber()).thenReturn(
                readiness.finalPlanRevisionNumber());
        when(workflow.findPlanBindings(readiness.taskId()))
                .thenReturn(List.of(terminalGateAuthority, matching));
        var adapter = new ProductChainFinalizationAuthorityAdapter(
                mock(ChainFoundationRepository.class), workflow,
                mock(io.paperagent.v2.chain
                        .ChainFinalizationRepository.class),
                mock(ChainModelRepository.class),
                mock(com.yanban.api.agent.v2.persistence
                        .ProductPlanBootstrapRepositoryAdapter.class),
                mock(CandidateChangeArtifactService.class),
                mock(com.yanban.api.agent.v2.chain.recovery
                        .ProductChainReadinessAuthority.class),
                mock(com.yanban.api.project.ProjectService.class),
                new ObjectMapper());
        var method = ProductChainFinalizationAuthorityAdapter.class
                .getDeclaredMethod("exactPlanBinding",
                        ChainPersistenceRecords
                                .FinalizationReadinessRecord.class);
        method.setAccessible(true);

        assertEquals(matching, method.invoke(adapter, readiness));
        when(workflow.findPlanBindings(readiness.taskId()))
                .thenReturn(List.of(matching, matching));
        var ambiguous = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(adapter, readiness));
        assertEquals("frozen Plan binding is missing or ambiguous",
                ambiguous.getCause().getMessage());
    }

    private static PublishRecoveryFacts publishRecoveryFacts() {
        Instant now = Instant.parse("2026-08-07T10:00:00Z");
        String readinessTransitionId = new ChainIdentity.Transition(
                ChainTransitionType.FINAL_STEP_READINESS, "task-1",
                "review-1", "a".repeat(64)).transitionId();
        var readiness = new ChainPersistenceRecords
                .FinalizationReadinessRecord(
                "readiness-1", "task-1", "readiness-event-1",
                readinessTransitionId, "b".repeat(64), "frame-1",
                "plan-1", "revision-1", 1L, "step-1", "review-1",
                new ChainPersistenceRecords.CanonicalJson(
                        1, "c".repeat(64), "[]"), 1L,
                41L, "candidate-1", "workspace-1", "validation-1",
                REQUEST, RECEIPT,
                new ChainPersistenceRecords.CanonicalJson(
                        1, "d".repeat(64), "[]"),
                ChainPublishRequirement.REQUIRED, "8".repeat(64),
                "instruction-1", BASE, now);
        var transition = transitionFor(
                readiness, readinessDigest(readiness), "review-1");
        var check = checkFor(readiness, transition.transitionId());
        return new PublishRecoveryFacts(transition, readiness, check);
    }

    private static com.yanban.api.agent.v2.persistence
            .ProductPlanBootstrapRepositoryAdapter explicitBootstrapAuthority() {
        var repository = mock(com.yanban.api.agent.v2.persistence
                .ProductPlanBootstrapRepositoryAdapter.class);
        var bootstrap = mock(io.paperagent.v2.persistence
                .PersistedPlanBootstrap.class);
        var taskFrame = mock(io.paperagent.v2.contracts.TaskFrame.class);
        when(repository.find(new io.paperagent.v2.contracts.PlanId("plan-1")))
                .thenReturn(java.util.Optional.of(bootstrap));
        when(bootstrap.taskFrame()).thenReturn(taskFrame);
        when(taskFrame.id()).thenReturn(
                new io.paperagent.v2.contracts.TaskFrameId("frame-1"));
        when(taskFrame.requirements()).thenReturn(
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(new io.paperagent.v2.contracts.ValidationRequirement(
                                "validation-1",
                                io.paperagent.v2.contracts.ValidationSubject.CANDIDATE,
                                "validated")),
                        io.paperagent.v2.contracts.PublishRequirement.REQUIRED));
        return repository;
    }

    private static ChainPersistenceRecords.TransitionRecord transitionFor(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String targetDigest,
            String sourceDecisionId) {
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.FINALIZATION, readiness.taskId(),
                sourceDecisionId, targetDigest).transitionId();
        return new ChainPersistenceRecords.TransitionRecord(
                transitionId, readiness.taskId(),
                "transition-event-" + sourceDecisionId,
                ChainTransitionType.FINALIZATION, sourceDecisionId,
                targetDigest, readiness.createdAt());
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord checkFor(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String transitionId) {
        return checkWithIdentity(readiness, transitionId, "check-1", 1);
    }

    private static ChainPersistenceRecords.FinalizationCheckRecord
            checkWithIdentity(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String transitionId, String checkId, int attemptNo) {
        return new ChainPersistenceRecords.FinalizationCheckRecord(
                checkId, "task-1", "check-event-" + attemptNo,
                readiness.readinessId(), transitionId, attemptNo,
                "frame-1", "revision-1", "c".repeat(64),
                "candidate-1", "workspace-1", "validation-1",
                REQUEST, RECEIPT, "8".repeat(64), "instruction-1", BASE,
                "e".repeat(64), "f".repeat(64), "0".repeat(64),
                ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                ChainRuntimePolicy.V1.policyVersion(), readiness.createdAt());
    }

    private static List<ChainPersistenceRecords.TransitionStageRecord>
            completionStages(PublishRecoveryFacts facts,
            String publishReceiptId) {
        Instant now = facts.transition().createdAt();
        String transitionId = facts.transition().transitionId();
        return List.of(
                new ChainPersistenceRecords.TransitionStageRecord(
                        transitionId, ChainTransitionStage.OPEN, "task-1",
                        "stage-event-0", 0, null, null, null, null, now),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transitionId,
                        ChainTransitionStage.READINESS_VERIFIED, "task-1",
                        "stage-event-1", 1, "FINALIZATION_READINESS",
                        facts.readiness().readinessId(), null, null, now),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transitionId,
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                        "task-1", "stage-event-2", 2, null, null,
                        "FINALIZATION_CHECK",
                        facts.check().finalizationCheckId(), now),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transitionId, ChainTransitionStage
                        .PUBLISH_COMMITTED_OR_NOT_REQUIRED, "task-1",
                        "stage-event-3", 3, null, null,
                        "PUBLISH_RECEIPT", publishReceiptId, now));
    }

    private static void stubReadyFinalFacts(
            ChainWorkflowRepository workflow, ChainModelRepository models,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        String payloadJson = """
                {"review":{"reviewScope":"final","reviewedObjectRefs":["object-1"],"decisionReason":"ready","directFactRefs":["fact-1"],"knownLimitations":[]},"finalization":{"requirementCoverage":[{"requirement":"deliverable","status":"SATISFIED","factRefs":["fact-1"]}],"finalArtifactAssessment":{"status":"BOUND","authorityRef":"artifact-41","reason":null},"finalCandidateAssessment":{"status":"BOUND","authorityRef":"candidate-1","reason":null},"validationAssessment":{"status":"BOUND","authorityRef":"validation-1","reason":null},"publishRequirementAssessment":{"status":"BOUND","authorityRef":"frame-1","reason":null},"userVisibleFacts":[],"residualRisks":[]}}
                """.trim();
        var review = new ChainPersistenceRecords.ReviewDecisionRecord(
                readiness.reviewDecisionId(), readiness.taskId(),
                "completion-review-event", "completion-proposal",
                "STEP_RESULT", "result-1",
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE, "ready",
                canonicalJson("[\"fact-1\"]"), "1".repeat(64),
                readiness.createdAt());
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "completion-proposal", readiness.taskId(),
                "completion-invocation", 1, ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_READY_TO_FINALIZE,
                canonicalJson(payloadJson), canonicalJson("[]"),
                null, null, readiness.createdAt());
        var bound = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1L, readiness.taskId(),
                "completion-proposal-state",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "REVIEW_DECISION", review.reviewDecisionId(),
                readiness.createdAt());
        when(workflow.findReviewDecisions(readiness.taskId()))
                .thenReturn(List.of(review));
        when(models.findProposal(proposal.proposalId()))
                .thenReturn(java.util.Optional.of(proposal));
        when(models.findProposalStateEvents(proposal.proposalId()))
                .thenReturn(List.of(bound));
    }

    private static String readinessDigest(
            ChainPersistenceRecords.FinalizationReadinessRecord value) {
        return java.util.HexFormat.of().formatHex(digest(
                value.readinessId() + "\0" + value.taskId() + "\0"
                        + value.transitionId() + "\0" + value.taskFrameId()
                        + "\0" + value.finalPlanId() + "\0"
                        + value.finalPlanRevisionId() + "\0"
                        + value.finalPlanRevisionNumber() + "\0"
                        + value.finalStepId() + "\0"
                        + value.reviewDecisionId() + "\0"
                        + value.acceptedSet().sha256() + "\0"
                        + value.applicabilityCutEventSequence() + "\0"
                        + java.util.Objects.toString(
                        value.artifactId(), "NONE") + "\0"
                        + value.candidateKey() + "\0" + value.workspaceId()
                        + "\0" + value.validationId() + "\0"
                        + java.util.Objects.toString(
                        value.validationRequestDigest(), "NONE") + "\0"
                        + java.util.Objects.toString(
                        value.validationReceiptDigest(), "NONE") + "\0"
                        + value.coverage().sha256() + "\0"
                        + value.publishRequirement() + "\0"
                        + value.publishRequirementDigest() + "\0"
                        + value.instructionId() + "\0"
                        + value.projectVersion()));
    }

    private static ChainPersistenceRecords.TransitionStageRecord
            transitionStage(
            ChainPersistenceRecords.TransitionRecord transition,
            int ordinal,
            ChainFinalizationTransitionPort.StageAuthority authority) {
        return new ChainPersistenceRecords.TransitionStageRecord(
                transition.transitionId(), authority.stage(),
                transition.taskId(), "stage-event-" + ordinal, ordinal,
                authority.predecessorAuthorityType(),
                authority.predecessorAuthorityRef(),
                authority.successorAuthorityType(),
                authority.successorAuthorityRef(), transition.createdAt());
    }

    private static List<ChainPersistenceRecords.TransitionStageRecord>
            readinessStages(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String acceptedResultId,
            boolean acceptedAsPredecessor) {
        return List.of(
                new ChainPersistenceRecords.TransitionStageRecord(
                        transition.transitionId(), ChainTransitionStage.OPEN,
                        transition.taskId(), "ready-stage-0", 0,
                        null, null, null, null, transition.createdAt()),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transition.transitionId(), ChainTransitionStage
                        .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                        transition.taskId(), "ready-stage-1", 1,
                        acceptedAsPredecessor ? "ACCEPTED_RESULT" : null,
                        acceptedAsPredecessor ? acceptedResultId : null,
                        acceptedAsPredecessor ? null : "ACCEPTED_RESULT",
                        acceptedAsPredecessor ? null : acceptedResultId,
                        transition.createdAt()),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transition.transitionId(), ChainTransitionStage
                        .APPLICABILITY_COMMITTED_OR_EMPTY,
                        transition.taskId(), "ready-stage-2", 2,
                        null, null, "RESULT_APPLICABILITY",
                        "applicability-1", transition.createdAt()),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transition.transitionId(), ChainTransitionStage
                        .STEP_COMPLETED_OR_VERIFIED,
                        transition.taskId(), "ready-stage-3", 3,
                        "STEP_EVENT", readinessStepEventId(
                        transition, readiness, "activation-1"), null, null,
                        transition.createdAt()),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transition.transitionId(), ChainTransitionStage
                        .READINESS_COMMITTED,
                        transition.taskId(), "ready-stage-4", 4,
                        null, null, "FINALIZATION_READINESS",
                        readiness.readinessId(), transition.createdAt()),
                new ChainPersistenceRecords.TransitionStageRecord(
                        transition.transitionId(), ChainTransitionStage.COMPLETE,
                        transition.taskId(), "ready-stage-5", 5,
                        null, null, null, null, transition.createdAt()));
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            readinessWithAccepted(
            ChainPersistenceRecords.FinalizationReadinessRecord value,
            ChainPersistenceRecords.CanonicalJson acceptedSet,
            long applicabilityCut) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                value.readinessId(), value.taskId(), value.eventId(),
                value.transitionId(), value.readinessScopeKey(),
                value.taskFrameId(), value.finalPlanId(),
                value.finalPlanRevisionId(), value.finalPlanRevisionNumber(),
                value.finalStepId(), value.reviewDecisionId(), acceptedSet,
                applicabilityCut, value.artifactId(), value.candidateKey(),
                value.workspaceId(), value.validationId(),
                value.validationRequestDigest(),
                value.validationReceiptDigest(), value.coverage(),
                value.publishRequirement(),
                value.publishRequirementDigest(), value.instructionId(),
                value.projectVersion(), value.createdAt());
    }

    private static String readinessStepEventId(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String activationEventId) {
        return "step.completed." + java.util.HexFormat.of().formatHex(digest(
                transition.taskId() + "\0" + readiness.finalPlanRevisionId()
                        + "\0" + readiness.finalStepId() + "\0"
                        + activationEventId + "\0"
                        + transition.transitionId()));
    }

    private static ChainProjectPublishPort.PublishCommand publishCommand(
            int attempt) {
        String key = ChainProjectPublishPort.stableIdempotencyKey(
                "task-1", "readiness-1", "check-1", attempt,
                BASE, 41, "candidate-1", "validation-1",
                ChainRuntimePolicy.V1.policyVersion(),
                REQUEST, RECEIPT);
        return new ChainProjectPublishPort.PublishCommand(
                "task-1", "readiness-1", "check-1", attempt, key,
                BASE, 41, "candidate-1", "validation-1",
                ChainRuntimePolicy.V1.policyVersion(),
                REQUEST, RECEIPT);
    }

    private static ProductChainPublishAuthoritySource.Operation
            publishOperationWithError(String errorCode) {
        return new ProductChainPublishAuthoritySource.Operation(
                1L, 7L, 13L, "key", "APPLICATION", "0".repeat(64),
                null, BASE, 41L, FINGERPRINT, "FAILED", errorCode,
                null, null, List.of(0), List.of());
    }

    private static String automaticRequestHash() {
        return java.util.HexFormat.of().formatHex(digest(
                "AUTOMATIC_APPLICATION:receipt-identity-1\0"
                        + "41\0" + BASE + "\0[0]"));
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalJson(
            String json) {
        return new ChainPersistenceRecords.CanonicalJson(1,
                java.util.HexFormat.of().formatHex(digest(json)), json);
    }

    private static String receiptJson() {
        try {
            return new ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(new SandboxReceipt(
                            RECEIPT_REF, "validation-key", REQUEST,
                            7L, 13L, 17L, 41L, 19L, 1L, BASE,
                            "9".repeat(64), "local",
                            SandboxExecutionStatus.SUCCEEDED, 0,
                            "", "", false, Map.of(), Instant.EPOCH,
                            Instant.EPOCH.plusSeconds(1), null));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record PublishRecoveryFacts(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
    }

    private static String source(String directory, String file)
            throws Exception {
        return Files.readString(MAIN.resolve(directory).resolve(file));
    }

    private static String uncheckedRead(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(token, index)) >= 0;
             index += token.length()) {
            count++;
        }
        return count;
    }

    private static long count(NamedParameterJdbcTemplate jdbc, String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                new MapSqlParameterSource(), Long.class);
    }
}
