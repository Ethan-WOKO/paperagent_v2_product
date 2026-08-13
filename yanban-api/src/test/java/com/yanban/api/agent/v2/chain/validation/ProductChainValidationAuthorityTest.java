package com.yanban.api.agent.v2.chain.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductEffectIntentRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductEffectOutcomeRepositoryAdapter;
import com.yanban.api.project.AgentCandidateAutoApplicationService;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.validation.ChainValidationRuntime;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainValidationAuthorityTest {
    @Test
    void verifiesSuccessfulActionReceiptWithoutProject() throws Exception {
        Fixture fixture = new Fixture(null, null);

        var verified = fixture.authority.verifyActionReceipt(fixture.scope,
                fixture.actionRequirement, "receipt-validation");

        assertThat(verified.actionId()).isEqualTo("action-validation");
        assertThat(verified.receiptPayloadSha256()).isEqualTo(
                sha256(fixture.payload));
    }

    @Test
    void exposesTheOriginalReceiptOnlyForTheExactTypedItem()
            throws Exception {
        Fixture fixture = new Fixture(null, null);

        ExecutionReceipt exact = fixture.authority.exactReceiptBody(
                fixture.validationSet(), "action-validation",
                "receipt-validation", sha256(fixture.payload));

        assertThat(exact).isSameAs(fixture.receipt);
        assertThatThrownBy(() -> fixture.authority.exactReceiptBody(
                fixture.validationSet(), "action-validation",
                "receipt-validation", hash('f')))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "CHAIN_VALIDATION_RECEIPT_ITEM_IDENTITY_INVALID");
    }

    @Test
    void candidateSubjectCannotUseAReceiptOnlyTaskWithoutProject()
            throws Exception {
        Fixture fixture = new Fixture(null, null);

        assertThatThrownBy(() -> fixture.authority.verifyCandidate(
                fixture.scope, new ValidationRequirement("candidate-check",
                        ValidationSubject.CANDIDATE, "candidate runs"),
                "receipt-validation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_CANDIDATE_VALIDATION_PROJECT_MISSING");
    }

    @Test
    void verifiesCandidateThroughExistingExactProofChain() throws Exception {
        Fixture fixture = new Fixture(91L, hash('e'));
        var candidate = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "candidate-1", "task-1", "candidate-event",
                "action-candidate", "workspace-1", hash('e'), 17L,
                hash('c'), hash('d'), hash('f'), Instant.EPOCH);
        var candidateAction = fixture.action(
                "action-candidate", "workspace-1", "NONE");
        var validationAction = fixture.action(
                "action-validation", "workspace-1", hash('c'));
        when(fixture.workflow.findWorkspaceCandidates("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findActionBindings("task-1"))
                .thenReturn(List.of(candidateAction, validationAction));
        when(fixture.candidateProofs.proofChain(7L, 91L, hash('e'),
                "task-1", "plan-1", "action-candidate", "workspace-1",
                "step-1", 17L)).thenReturn(new AgentCandidateAutoApplicationService
                .VerificationProof("receipt-validation", List.of("Sort.java"),
                List.of("javac", "Sort.java"), "ok", "", 0,
                Instant.EPOCH, Instant.EPOCH, List.of(
                new AgentCandidateAutoApplicationService.VerifiedInputState(
                        "Sort.java",
                        AgentCandidateAutoApplicationService.InputPresence
                                .PRESENT,
                        hash('a')))));

        var verified = fixture.authority.verifyCandidate(fixture.scope,
                new ValidationRequirement("candidate-check",
                        ValidationSubject.CANDIDATE, "candidate runs"),
                "receipt-validation");

        assertThat(verified.workspaceCandidateId()).isEqualTo("candidate-1");
        assertThat(verified.validationActionId())
                .isEqualTo("action-validation");
    }

    @Test
    void verifiesCandidateProducedByCompletedDependencyStep()
            throws Exception {
        Fixture fixture = new Fixture(91L, hash('e'));
        fixture.effectScope("step-validate", "activation-validate");
        ChainValidationRuntime.Scope validationScope =
                new ChainValidationRuntime.Scope(
                        "task-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-validate", "activation-validate",
                        "validation-key", Instant.EPOCH);
        var candidate = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "candidate-1", "task-1", "candidate-event",
                "action-candidate", "workspace-1", hash('e'), 17L,
                hash('c'), hash('d'), hash('f'), Instant.EPOCH);
        var candidateAction = fixture.action(
                "action-candidate", "workspace-1", "NONE",
                "step-change", "activation-change");
        var validationAction = fixture.action(
                "action-validation", "workspace-1", hash('c'),
                "step-validate", "activation-validate");
        when(fixture.workflow.findWorkspaceCandidates("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findActionBindings("task-1"))
                .thenReturn(List.of(candidateAction, validationAction));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(new ChainStepAuthorityPort.PlanSnapshot(
                        "task-1", "frame-1", "plan-1", "revision-1",
                        "NONE", "instruction-1", List.of(
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-change", 1, Set.of()),
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-validate", 2, Set.of("step-change"))))));
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of(new ChainStepAuthorityPort.StepEvent(
                        new ChainStepAuthorityPort.StepEventCommand(
                                "completed-change", "task-1", "revision-1",
                                "step-change", "activation-change",
                                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                                "decision-1", "transition-1", Instant.EPOCH),
                        1)));
        when(fixture.candidateProofs.proofChain(7L, 91L, hash('e'),
                "task-1", "plan-1", "action-candidate", "workspace-1",
                "step-validate", 17L)).thenReturn(
                fixture.verificationProof());

        var verified = fixture.authority.verifyCandidate(validationScope,
                new ValidationRequirement("candidate-check",
                        ValidationSubject.CANDIDATE, "candidate runs"),
                "receipt-validation");

        assertThat(verified.candidateActionId())
                .isEqualTo("action-candidate");
        assertThat(verified.validationActionId())
                .isEqualTo("action-validation");
    }

    @Test
    void rejectsCandidateFromUnrelatedStep() throws Exception {
        Fixture fixture = new Fixture(91L, hash('e'));
        fixture.effectScope("step-validate", "activation-validate");
        ChainValidationRuntime.Scope validationScope =
                new ChainValidationRuntime.Scope(
                        "task-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-validate", "activation-validate",
                        "validation-key", Instant.EPOCH);
        var candidate = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "candidate-1", "task-1", "candidate-event",
                "action-candidate", "workspace-1", hash('e'), 17L,
                hash('c'), hash('d'), hash('f'), Instant.EPOCH);
        when(fixture.workflow.findWorkspaceCandidates("task-1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findActionBindings("task-1"))
                .thenReturn(List.of(fixture.action(
                        "action-candidate", "workspace-1", "NONE",
                        "step-change", "activation-change"),
                        fixture.action(
                                "action-validation", "workspace-1", hash('c'),
                                "step-validate", "activation-validate")));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(new ChainStepAuthorityPort.PlanSnapshot(
                        "task-1", "frame-1", "plan-1", "revision-1",
                        "NONE", "instruction-1", List.of(
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-change", 1, Set.of()),
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-validate", 2, Set.of())))));

        assertThatThrownBy(() -> fixture.authority.verifyCandidate(
                validationScope, new ValidationRequirement(
                        "candidate-check", ValidationSubject.CANDIDATE,
                        "candidate runs"), "receipt-validation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_VALIDATION_CANDIDATE_PREDECESSOR_INVALID");
    }

    @Test
    void rejectsCandidateFromDependencyStepThatIsNotCompleted()
            throws Exception {
        Fixture fixture = new Fixture(91L, hash('e'));
        fixture.effectScope("step-validate", "activation-validate");
        ChainValidationRuntime.Scope validationScope =
                new ChainValidationRuntime.Scope(
                        "task-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-validate", "activation-validate",
                        "validation-key", Instant.EPOCH);
        when(fixture.workflow.findWorkspaceCandidates("task-1"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                "candidate-1", "task-1", "candidate-event",
                                "action-candidate", "workspace-1", hash('e'),
                                17L, hash('c'), hash('d'), hash('f'),
                                Instant.EPOCH)));
        when(fixture.workflow.findActionBindings("task-1"))
                .thenReturn(List.of(fixture.action(
                        "action-candidate", "workspace-1", "NONE",
                        "step-change", "activation-change"),
                        fixture.action(
                                "action-validation", "workspace-1", hash('c'),
                                "step-validate", "activation-validate")));
        when(fixture.steps.findPlan("task-1", "revision-1"))
                .thenReturn(Optional.of(new ChainStepAuthorityPort.PlanSnapshot(
                        "task-1", "frame-1", "plan-1", "revision-1",
                        "NONE", "instruction-1", List.of(
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-change", 1, Set.of()),
                        new ChainStepAuthorityPort.StepDefinition(
                                "step-validate", 2, Set.of("step-change"))))));
        when(fixture.steps.findStepEvents("task-1", "revision-1"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> fixture.authority.verifyCandidate(
                validationScope, new ValidationRequirement(
                        "candidate-check", ValidationSubject.CANDIDATE,
                        "candidate runs"), "receipt-validation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_VALIDATION_CANDIDATE_PREDECESSOR_INVALID");
    }

    @Test
    void rejectsHiddenFailedWrongActivationAndWrongPayloadDigest()
            throws Exception {
        Fixture hidden = new Fixture(null, null);
        when(hidden.jdbc.query(anyString(),
                any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertBlocked(hidden, "CHAIN_VALIDATION_RECEIPT_NOT_VISIBLE");

        Fixture failed = new Fixture(null, null);
        when(failed.receipt.status()).thenReturn(ReceiptStatus.FAILURE);
        assertBlocked(failed, "CHAIN_VALIDATION_EFFECT_RESULT_INVALID");

        Fixture activation = new Fixture(null, null);
        when(activation.workflow.findActionBindings("task-1")).thenReturn(
                List.of(activation.action("action-validation",
                        "workspace-1", "NONE", "other-activation")));
        assertBlocked(activation, "CHAIN_VALIDATION_ACTION_BINDING_INVALID");

        Fixture digest = new Fixture(null, null);
        digest.rawDigest = hash('x');
        digest.stubReceiptRow();
        assertBlocked(digest, "CHAIN_VALIDATION_RECEIPT_DIGEST_INVALID");

        Fixture revision = new Fixture(null, null);
        when(revision.workflow.findPlanBindings("task-1")).thenReturn(List.of(
                new ChainPersistenceRecords.PlanBindingRecord(
                        "binding-1", "task-1", "binding-event",
                        "instruction-1", "route-1", "frame-1", "plan-1",
                        "revision-1", 2, "PLAN_BOOTSTRAP", "bootstrap-1",
                        hash('9'), null, Instant.EPOCH)));
        assertBlocked(revision, "CHAIN_VALIDATION_PLAN_BINDING_INVALID");
    }

    private static void assertBlocked(Fixture fixture, String code) {
        assertThatThrownBy(() -> fixture.authority.verifyActionReceipt(
                fixture.scope, fixture.actionRequirement,
                "receipt-validation")).isInstanceOf(IllegalStateException.class)
                .hasMessage(code);
    }

    private static final class Fixture {
        final ProductChainFoundationRepositoryAdapter foundations = mock(
                ProductChainFoundationRepositoryAdapter.class);
        final ProductChainWorkflowRepositoryAdapter workflow = mock(
                ProductChainWorkflowRepositoryAdapter.class);
        final ProductEffectIntentRepositoryAdapter intents = mock(
                ProductEffectIntentRepositoryAdapter.class);
        final ProductEffectOutcomeRepositoryAdapter outcomes = mock(
                ProductEffectOutcomeRepositoryAdapter.class);
        final AgentCandidateAutoApplicationService candidateProofs = mock(
                AgentCandidateAutoApplicationService.class);
        final ChainStepAuthorityPort steps = mock(
                ChainStepAuthorityPort.class);
        final NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        final ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        final PersistedEffectIntent persistedIntent = mock(
                PersistedEffectIntent.class);
        final EffectIntent intent = mock(EffectIntent.class);
        final String payload = "{\"format\":\"execution-receipt\","
                + "\"receiptId\":\"receipt-validation\","
                + "\"toolCallId\":\"action-validation\","
                + "\"status\":\"SUCCESS\"}";
        String rawDigest = sha256(payload);
        final ChainValidationRuntime.Scope scope = new ChainValidationRuntime.Scope(
                "task-1", "frame-1", "plan-1", "revision-1", 1,
                "step-1", "activation-1", "validation-key", Instant.EPOCH);
        final ValidationRequirement actionRequirement = new ValidationRequirement(
                "run-check", ValidationSubject.ACTION_RECEIPT, "command runs");
        final ProductChainValidationAuthority authority;

        Fixture(Long projectId, String projectVersion) throws Exception {
            authority = new ProductChainValidationAuthority(foundations,
                    workflow, intents, outcomes, candidateProofs, steps, jdbc,
                    new ObjectMapper());
            when(foundations.findTask("task-1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.TaskRecord("task-1", "command-1",
                            "instruction-1", null, 7L, 8L, 9L, 10L,
                            "client-1", hash('b'), projectId, projectVersion,
                            0, Instant.EPOCH)));
            when(workflow.findActionBindings("task-1")).thenReturn(List.of(
                    action("action-validation", "workspace-1", "NONE")));
            when(workflow.findPlanBindings("task-1")).thenReturn(List.of(
                    new ChainPersistenceRecords.PlanBindingRecord(
                            "binding-1", "task-1", "binding-event",
                            "instruction-1", "route-1", "frame-1", "plan-1",
                            "revision-1", 1, "PLAN_BOOTSTRAP", "bootstrap-1",
                            hash('9'), null, Instant.EPOCH)));
            when(persistedIntent.intent()).thenReturn(intent);
            when(intent.toolCallId()).thenReturn(new ToolCallId(
                    "action-validation"));
            when(intent.planId()).thenReturn(new PlanId("plan-1"));
            when(intent.stepId()).thenReturn(new PlanStepId("step-1"));
            when(persistedIntent.activationEventId()).thenReturn(
                    new EventId("activation-1"));
            when(intents.find(new ToolCallId("action-validation")))
                    .thenReturn(PersistenceResult.found(persistedIntent));
            PersistedEffectResult result = mock(PersistedEffectResult.class);
            when(result.receipt()).thenReturn(receipt);
            when(receipt.id()).thenReturn(new ReceiptId("receipt-validation"));
            when(receipt.toolCallId()).thenReturn(new ToolCallId(
                    "action-validation"));
            when(receipt.status()).thenReturn(ReceiptStatus.SUCCESS);
            when(outcomes.findResult(new ToolCallId("action-validation")))
                    .thenReturn(PersistenceResult.found(result));
            stubReceiptRow();
        }

        void effectScope(String stepId, String activationId) {
            when(intent.stepId()).thenReturn(new PlanStepId(stepId));
            when(persistedIntent.activationEventId()).thenReturn(
                    new EventId(activationId));
        }

        void stubReceiptRow() throws Exception {
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                    any(RowMapper.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked") RowMapper<Object> mapper =
                        invocation.getArgument(2);
                ResultSet row = mock(ResultSet.class);
                when(row.getString("tool_call_id"))
                        .thenReturn("action-validation");
                when(row.getString("payload_sha256")).thenReturn(rawDigest);
                when(row.getString("payload_json")).thenReturn(payload);
                return List.of(mapper.mapRow(row, 0));
            });
        }

        ChainPersistenceRecords.ActionBindingRecord action(
                String actionId, String workspaceId, String baseCandidateKey) {
            return action(actionId, workspaceId, baseCandidateKey,
                    "activation-1");
        }

        ChainPersistenceRecords.ActionBindingRecord action(
                String actionId, String workspaceId, String baseCandidateKey,
                String activationId) {
            return action(actionId, workspaceId, baseCandidateKey,
                    "step-1", activationId);
        }

        ChainPersistenceRecords.ActionBindingRecord action(
                String actionId, String workspaceId, String baseCandidateKey,
                String stepId, String activationId) {
            return new ChainPersistenceRecords.ActionBindingRecord(actionId,
                    "task-1", "action-event-" + actionId,
                    "proposal-" + actionId, 1, hash('a'),
                    "key-" + actionId, "instruction-1", "frame-1",
                    "plan-1", "revision-1", stepId, activationId,
                    workspaceId, baseCandidateKey, "intent-" + actionId,
                    null, null, null, hash('f'), Instant.EPOCH);
        }

        AgentCandidateAutoApplicationService.VerificationProof
                verificationProof() {
            return new AgentCandidateAutoApplicationService.VerificationProof(
                    "receipt-validation", List.of("Sort.java"),
                    List.of("javac", "Sort.java"), "ok", "", 0,
                    Instant.EPOCH, Instant.EPOCH, List.of(
                    new AgentCandidateAutoApplicationService
                            .VerifiedInputState(
                            "Sort.java",
                            AgentCandidateAutoApplicationService.InputPresence
                                    .PRESENT,
                            hash('a'))));
        }

        ChainPersistenceRecords.ValidationSetRecord validationSet() {
            return new ChainPersistenceRecords.ValidationSetRecord(
                    "validation-1", "task-1", "validation-event",
                    "frame-1", "plan-1", "revision-1", 1, "step-1",
                    "activation-1", hash('1'), hash('2'), hash('3'),
                    io.paperagent.v2.chain.ChainValidationConclusion.PASSED,
                    "validation-key", Instant.EPOCH);
        }
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
