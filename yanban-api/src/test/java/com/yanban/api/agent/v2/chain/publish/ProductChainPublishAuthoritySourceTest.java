package com.yanban.api.agent.v2.chain.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRetainedAuthoritySource;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.research.ProjectVersionRef;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ProductChainPublishAuthoritySourceTest {
    private static final String HASH = "a".repeat(64);
    private static final String BASE_VERSION = "project-version-1";
    private static final String CANDIDATE_KEY = "candidate-1";
    private static final String VALIDATION_ID = "validation-1";
    private static final long ARTIFACT_ID = 41L;

    @Test
    void retainedLookupReturnsTheExactPersistedPublishAttempt()
            throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        var query = publishQuery();
        ChainProjectPublishPort.PublishCommand command = command(query, 1);
        Map<String, Object> operation = failedOperation(command);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> rows(invocation.getArgument(0),
                        invocation.getArgument(1), operation));

        ProductChainPublishAuthoritySource source =
                source(jdbc);
        var first = source.find(query).orElseThrow();
        var replay = source.find(query).orElseThrow();

        assertThat(first.authorityRef()).isEqualTo(
                ProductChainPublishAuthoritySource.formalRef(31L));
        assertThat(first.status()).isEqualTo("FAILED");
        assertThat(first.identityDigest()).hasSize(64);
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void retainedLookupReturnsEmptyWhenNoFormalAttemptExists() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());

        assertThat(source(jdbc)
                .find(publishQuery())).isEmpty();
    }

    @Test
    void retainedLookupRejectsAmbiguousFormalAttemptAuthority() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        var query = publishQuery();
        Map<String, Object> operation = failedOperation(command(query, 1));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(operation, operation));

        assertThatThrownBy(() -> source(jdbc)
                .find(query))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish operation authority is ambiguous");
    }

    @Test
    void retainedLookupRejectsAcceptedIndexDriftNotBoundByFailureHash() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        var query = publishQuery();
        Map<String, Object> operation = new java.util.HashMap<>(
                failedOperation(command(query, 1)));
        operation.put("accepted_change_indexes", "[1]");
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> rows(invocation.getArgument(0),
                        invocation.getArgument(1), operation));

        assertThatThrownBy(() -> source(jdbc).find(query))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish request hash does not bind Validation receipt");
    }

    @Test
    void failureHashRequiresCanonicalNonNegativeUniqueIndexes() {
        var command = command(publishQuery(), 1);
        assertThatThrownBy(() -> ProductChainPublishAuthoritySource
                .chainFailureHash(command, "CHAIN_PUBLISH_VERSION_CONFLICT",
                        List.of(1, 0)))
                .hasMessage("accepted change indexes must use canonical order");
        assertThatThrownBy(() -> ProductChainPublishAuthoritySource
                .chainFailureHash(command, "CHAIN_PUBLISH_VERSION_CONFLICT",
                        List.of(0, 0)))
                .hasMessage("accepted change indexes must be unique");
        assertThatThrownBy(() -> ProductChainPublishAuthoritySource
                .chainFailureHash(command, "CHAIN_PUBLISH_VERSION_CONFLICT",
                        List.of(-1)))
                .hasMessage("accepted change indexes must be non-negative");
    }

    @Test
    void retainedLookupRejectsDriftedTaskReadinessCheckIdentity() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        var exact = publishQuery();
        var task = new ChainPersistenceRecords.TaskRecord(
                exact.task().taskId(), exact.task().createdByCommandId(),
                exact.task().sourceInstructionId(), null, 7L, 11L, 13L,
                17L, "request-1", HASH, 13L, "different-version", 0L,
                Instant.EPOCH);
        var drifted = new ProductChainRetainedAuthoritySource
                .PublishAttemptQuery(task, exact.readiness(), exact.check());

        assertThatThrownBy(() -> source(jdbc)
                .find(drifted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish attempt lookup authority is not exact");
        verifyNoInteractions(jdbc);
    }

    @Test
    void notRequiredPublishDoesNotResolveCandidateAuthority() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        ProductChainPublishCandidateAuthority authority = mock(
                ProductChainPublishCandidateAuthority.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        var exact = publishQuery();
        var value = exact.readiness();
        var readiness = new ChainPersistenceRecords.FinalizationReadinessRecord(
                value.readinessId(), value.taskId(), value.eventId(),
                value.transitionId(), value.readinessScopeKey(),
                value.taskFrameId(), value.finalPlanId(),
                value.finalPlanRevisionId(), value.finalPlanRevisionNumber(),
                value.finalStepId(), value.reviewDecisionId(),
                value.acceptedSet(), value.applicabilityCutEventSequence(),
                value.artifactId(), value.candidateKey(), value.workspaceId(),
                value.validationId(), value.validationRequestDigest(),
                value.validationReceiptDigest(), value.coverage(),
                ChainPublishRequirement.NOT_REQUIRED,
                value.publishRequirementDigest(), value.instructionId(),
                value.projectVersion(), value.createdAt());
        var query = new ProductChainRetainedAuthoritySource.PublishAttemptQuery(
                exact.task(), readiness, exact.check());
        var source = new ProductChainPublishAuthoritySource(
                jdbc, authority, candidates);

        assertThat(source.find(query)).isEmpty();
        verifyNoInteractions(jdbc, authority, candidates);
    }

    private static List<Map<String, Object>> rows(
            String sql,
            MapSqlParameterSource parameters,
            Map<String, Object> operation) {
        if (sql.contains("JOIN project_revision_operations")) {
            return operation.get("idempotency_key").equals(
                    parameters.getValue("idempotencyKey"))
                    ? List.of(operation) : List.of();
        }
        if (sql.contains("JOIN agent_v2_chain_finalization_readiness")) {
            return List.of(Map.of(
                    "user_id", 7L,
                    "project_id", 13L,
                    "candidate_fingerprint", "f".repeat(64)));
        }
        throw new AssertionError("unexpected publish authority query");
    }

    private static ProductChainRetainedAuthoritySource.PublishAttemptQuery
            publishQuery() {
        String receiptDigest = HASH;
        var accepted = new ChainPersistenceRecords.CanonicalJson(
                1, HASH, "[0]");
        var coverage = new ChainPersistenceRecords.CanonicalJson(
                1, HASH, "[]");
        var task = new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                7L, 11L, 13L, 17L, "request-1", HASH,
                13L, BASE_VERSION, 0L, Instant.EPOCH);
        var readiness = new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "event-readiness-1",
                "transition-1", HASH, "frame-1", "plan-1", "revision-1",
                1L, "step-1", "review-1", accepted, 1L, ARTIFACT_ID,
                CANDIDATE_KEY, "workspace-1", VALIDATION_ID, HASH,
                receiptDigest, coverage, ChainPublishRequirement.REQUIRED, HASH,
                "instruction-1", BASE_VERSION, Instant.EPOCH);
        var check = new ChainPersistenceRecords.FinalizationCheckRecord(
                "check-1", "task-1", "event-check-1", "readiness-1",
                "finalization-transition-1", 1, "frame-1", "revision-1", HASH,
                CANDIDATE_KEY, "workspace-1", VALIDATION_ID, HASH,
                receiptDigest,
                HASH, "instruction-1", BASE_VERSION, HASH, HASH, HASH,
                ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE,
                ChainRuntimePolicy.V1.policyVersion(), Instant.EPOCH);
        return new ProductChainRetainedAuthoritySource.PublishAttemptQuery(
                task, readiness, check);
    }

    private static ChainProjectPublishPort.PublishCommand command(
            ProductChainRetainedAuthoritySource.PublishAttemptQuery query,
            int attempt) {
        var readiness = query.readiness();
        var check = query.check();
        String key = ChainProjectPublishPort.stableIdempotencyKey(
                readiness.taskId(), readiness.readinessId(),
                check.finalizationCheckId(), attempt, readiness.projectVersion(),
                readiness.artifactId(), readiness.candidateKey(),
                readiness.validationId(), check.runtimePolicyVersion(),
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

    private static Map<String, Object> failedOperation(
            ChainProjectPublishPort.PublishCommand command) {
        String error = "CHAIN_PUBLISH_STALE_VERSION_FENCE";
        return Map.ofEntries(
                Map.entry("id", 31L), Map.entry("user_id", 7L),
                Map.entry("project_id", 13L),
                Map.entry("operation_type", "APPLICATION"),
                Map.entry("idempotency_key", command.idempotencyKey()),
                Map.entry("request_hash", ProductChainPublishAuthoritySource
                        .chainFailureHash(command, error, List.of(0))),
                Map.entry("base_version", BASE_VERSION),
                Map.entry("candidate_artifact_id", ARTIFACT_ID),
                Map.entry("candidate_fingerprint", "f".repeat(64)),
                Map.entry("outcome", "FAILED"),
                Map.entry("error_code", error),
                Map.entry("accepted_change_indexes", "[0]"),
                Map.entry("rejected_change_indexes", "[]"));
    }

    private static ProductChainPublishAuthoritySource source(
            NamedParameterJdbcTemplate jdbc) {
        ProductChainPublishCandidateAuthority authority = mock(
                ProductChainPublishCandidateAuthority.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        when(authority.requireExact(any())).thenReturn(
                new ProductChainPublishCandidateAuthority.Proof(
                        7L, 13L, "receipt-1", CANDIDATE_KEY, "workspace-1",
                        ARTIFACT_ID, "f".repeat(64), BASE_VERSION,
                        "candidate-action-1", "validation-action-1",
                        "validation-set-1", "candidate-requirement-1"));
        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        when(candidate.projectId()).thenReturn(13L);
        ProjectVersionRef projectVersion = mock(ProjectVersionRef.class);
        when(projectVersion.value()).thenReturn(BASE_VERSION);
        when(candidate.projectVersion()).thenReturn(projectVersion);
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint("f".repeat(64)));
        when(candidate.changes()).thenReturn(List.of(
                mock(CandidateFileChange.class)));
        when(candidates.getCurrent(7L, ARTIFACT_ID)).thenReturn(candidate);
        return new ProductChainPublishAuthoritySource(
                jdbc, authority, candidates);
    }
}
