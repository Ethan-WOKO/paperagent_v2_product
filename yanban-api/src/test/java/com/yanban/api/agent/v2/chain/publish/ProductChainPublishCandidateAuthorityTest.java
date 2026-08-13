package com.yanban.api.agent.v2.chain.publish;

import com.yanban.api.agent.v2.chain.recovery.ProductChainReadinessAuthority;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainPublishCandidateAuthorityTest {
    private static final String HASH = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final String BASE = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

    @Test
    void resolvesCandidateFromAnEarlierBundleStepAndOriginalReceipt() {
        Fixture fixture = new Fixture();

        ProductChainPublishCandidateAuthority.Proof proof = fixture.authority
                .requireExact(fixture.command);

        assertThat(proof.receiptId()).isEqualTo("receipt-1");
        assertThat(proof.candidateActionId()).isEqualTo("candidate-action-1");
        assertThat(proof.validationActionId()).isEqualTo("validation-action-1");
        assertThat(proof.workspaceCandidateId()).isEqualTo("candidate-1");
        assertThat(fixture.readiness.finalStepId()).isEqualTo("final-step");
        assertThat(fixture.member.stepId()).isEqualTo("earlier-step");
        assertThat(fixture.candidateAction.stepId()).isEqualTo("candidate-step");
        assertThat(fixture.validationAction.stepId()).isEqualTo("earlier-step");
    }

    @Test
    void rejectsMissingRequiredCandidateItem() {
        Fixture fixture = new Fixture();
        when(fixture.validations.findCandidateItems("validation-set-1"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> fixture.authority.requireExact(
                fixture.command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish Candidate Validation item is missing or ambiguous");
    }

    @Test
    void rejectsAmbiguousRequiredCandidateItems() {
        Fixture fixture = new Fixture();
        when(fixture.validations.findCandidateItems("validation-set-1"))
                .thenReturn(List.of(fixture.item, fixture.item));

        assertThatThrownBy(() -> fixture.authority.requireExact(
                fixture.command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish Candidate Validation item is missing or ambiguous");
    }

    private static final class Fixture {
        private final ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        private final ChainFinalizationRepository finalization = mock(
                ChainFinalizationRepository.class);
        private final ChainValidationBundleRepository bundles = mock(
                ChainValidationBundleRepository.class);
        private final ChainValidationRepository validations = mock(
                ChainValidationRepository.class);
        private final ChainWorkflowRepository workflow = mock(
                ChainWorkflowRepository.class);
        private final ProductChainReadinessAuthority readinessAuthority =
                mock(ProductChainReadinessAuthority.class);
        private final NamedParameterJdbcTemplate jdbc;
        private final ChainPersistenceRecords.FinalizationReadinessRecord
                readiness;
        private final ChainPersistenceRecords.ValidationBundleSetRecord member;
        private final ChainPersistenceRecords.CandidateValidationItemRecord item;
        private final ChainPersistenceRecords.ActionBindingRecord candidateAction;
        private final ChainPersistenceRecords.ActionBindingRecord validationAction;
        private final ChainProjectPublishPort.PublishCommand command;
        private final ProductChainPublishCandidateAuthority authority;

        private Fixture() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:publish-candidate-" + System.nanoTime()
                            + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new NamedParameterJdbcTemplate(dataSource);
            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE agent_v2_receipts(
                        receipt_id VARCHAR(128), tool_call_id VARCHAR(128),
                        payload_sha256 VARCHAR(64), payload_json CLOB,
                        tool_call_claim_owner_kind VARCHAR(64),
                        receipt_owner_kind VARCHAR(64))
                    """);
            jdbc.getJdbcTemplate().execute("""
                    CREATE TABLE agent_v2_effect_results(
                        receipt_id VARCHAR(128), tool_call_id VARCHAR(128))
                    """);
            String payload = "{\"status\":\"SUCCESS\"}";
            String payloadSha = sha256(payload);
            jdbc.update("""
                    INSERT INTO agent_v2_receipts VALUES(
                        'receipt-1','validation-action-1',:sha,:payload,
                        'EFFECT_INTENT','EFFECT_OUTCOME')
                    """, new MapSqlParameterSource()
                    .addValue("sha", payloadSha).addValue("payload", payload));
            jdbc.getJdbcTemplate().execute("""
                    INSERT INTO agent_v2_effect_results VALUES(
                        'receipt-1','validation-action-1')
                    """);

            readiness = new ChainPersistenceRecords.FinalizationReadinessRecord(
                    "readiness-1", "task-1", "readiness-event-1",
                    "transition-1", HASH, "frame-1", "plan-1", "revision-2",
                    2, "final-step", "review-1", canonical("[1]"), 3,
                    41L, "candidate-1", "workspace-1", "bundle-1", HASH,
                    HASH, canonical("[]"), ChainPublishRequirement.REQUIRED,
                    HASH, "instruction-1", BASE, NOW);
            var check = new ChainPersistenceRecords.FinalizationCheckRecord(
                    "check-1", "task-1", "check-event-1", "readiness-1",
                    "transition-1", 1, "frame-1", "revision-2", HASH,
                    "candidate-1", "workspace-1", "bundle-1", HASH, HASH,
                    HASH, "instruction-1", BASE, HASH, HASH, HASH,
                    ChainFinalization.Outcome.PASSED, null,
                    ChainFinalization.FailureHandling.NONE,
                    io.paperagent.v2.chain.ChainRuntimePolicy.V1
                            .policyVersion(), NOW);
            var bundle = new ChainPersistenceRecords.ValidationBundleRecord(
                    "bundle-1", "task-1", "bundle-event-1", "frame-1",
                    "plan-1", "revision-2", 2, "instruction-1", "final-step",
                    HASH, HASH, HASH, ChainValidationConclusion.PASSED,
                    "bundle-key-1", NOW);
            member = new ChainPersistenceRecords.ValidationBundleSetRecord(
                    "bundle-1", "task-1", "earlier-step", "activation-1",
                    "validation-set-1", HASH, HASH, HASH);
            item = new ChainPersistenceRecords.CandidateValidationItemRecord(
                    "validation-set-1", "candidate-requirement-1", "task-1",
                    HASH, "candidate-action-1", "validation-action-1",
                    "receipt-1", payloadSha, HASH, "candidate-1",
                    "workspace-1", 41L, FINGERPRINT, BASE,
                    ChainValidationConclusion.PASSED);
            var candidate = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                    "candidate-1", "task-1", "candidate-event-1",
                    "candidate-action-1", "workspace-1", BASE, 41L,
                    FINGERPRINT, HASH, HASH, NOW);
            candidateAction = action(
                    "candidate-action-1", "candidate-step",
                    "candidate-activation", HASH);
            validationAction = action(
                    "validation-action-1", "earlier-step", "activation-1",
                    FINGERPRINT);
            var task = new ChainPersistenceRecords.TaskRecord(
                    "task-1", "command-1", "instruction-1", null,
                    7L, 11L, 13L, 17L, "request-1", HASH, 13L, BASE,
                    0L, NOW);
            String publishKey = ChainProjectPublishPort.stableIdempotencyKey(
                    "task-1", "readiness-1", "check-1", 1, BASE, 41L,
                    "candidate-1", "bundle-1",
                    io.paperagent.v2.chain.ChainRuntimePolicy.V1
                            .policyVersion(), HASH, HASH);
            command = new ChainProjectPublishPort.PublishCommand(
                    "task-1", "readiness-1", "check-1", 1, publishKey,
                    BASE, 41L, "candidate-1", "bundle-1",
                    io.paperagent.v2.chain.ChainRuntimePolicy.V1
                            .policyVersion(), HASH, HASH);

            when(finalization.findReadinessById("readiness-1"))
                    .thenReturn(Optional.of(readiness));
            when(finalization.findFinalizationChecks("readiness-1"))
                    .thenReturn(List.of(check));
            when(bundles.findBundle("bundle-1")).thenReturn(Optional.of(bundle));
            when(bundles.findBundleSets("bundle-1")).thenReturn(List.of(member));
            when(validations.findCandidateItems("validation-set-1"))
                    .thenReturn(List.of(item));
            when(workflow.findWorkspaceCandidates("task-1"))
                    .thenReturn(List.of(candidate));
            when(workflow.findActionBindings("task-1"))
                    .thenReturn(List.of(candidateAction, validationAction));
            when(foundations.findTask("task-1")).thenReturn(Optional.of(task));
            authority = new ProductChainPublishCandidateAuthority(
                    foundations, finalization, bundles, validations, workflow,
                    readinessAuthority, jdbc);
        }

        private static ChainPersistenceRecords.ActionBindingRecord action(
                String actionId, String stepId, String activationEventId,
                String baseCandidateKey) {
            return new ChainPersistenceRecords.ActionBindingRecord(
                    actionId, "task-1", actionId + "-event", "proposal-1", 1,
                    HASH, actionId + "-key", "instruction-1", "frame-1",
                    "plan-1", "revision-2", stepId, activationEventId,
                    "workspace-1", baseCandidateKey, "intent-1", "dispatch-1",
                    null, null, HASH, NOW);
        }
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, json);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
