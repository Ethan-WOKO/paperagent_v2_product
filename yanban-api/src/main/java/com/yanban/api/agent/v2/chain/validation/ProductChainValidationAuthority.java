package com.yanban.api.agent.v2.chain.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductEffectIntentRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductEffectOutcomeRepositoryAdapter;
import com.yanban.api.project.AgentCandidateAutoApplicationService;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.validation.ChainValidationAuthorityPort;
import io.paperagent.v2.chain.validation.ChainValidationRuntime;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Product verifier for already persisted, frozen-step Validation evidence. */
@Component
public final class ProductChainValidationAuthority
        implements ChainValidationAuthorityPort {
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductEffectIntentRepositoryAdapter intents;
    private final ProductEffectOutcomeRepositoryAdapter outcomes;
    private final AgentCandidateAutoApplicationService candidateProofs;
    private final ChainStepAuthorityPort steps;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProductChainValidationAuthority(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductEffectIntentRepositoryAdapter intents,
            ProductEffectOutcomeRepositoryAdapter outcomes,
            AgentCandidateAutoApplicationService candidateProofs,
            ChainStepAuthorityPort steps,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper json) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.candidateProofs = Objects.requireNonNull(candidateProofs,
                "candidateProofs");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public VerifiedActionReceipt verifyActionReceipt(
            ChainValidationRuntime.Scope scope,
            ValidationRequirement requirement,
            String receiptRef) {
        VerifiedAction verified = verifiedAction(scope, receiptRef);
        return new VerifiedActionReceipt(verified.action().actionId(),
                verified.receipt().receipt().id().value(),
                verified.payloadSha256(),
                verified.action().actionSignatureSha256());
    }

    @Override
    public VerifiedCandidate verifyCandidate(
            ChainValidationRuntime.Scope scope,
            ValidationRequirement requirement,
            String receiptRef) {
        VerifiedAction validation = verifiedAction(scope, receiptRef);
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(scope.taskId()).orElseThrow(() -> blocked(
                        "CHAIN_VALIDATION_TASK_MISSING"));
        if (task.projectId() == null || task.initialProjectVersion() == null) {
            throw blocked("CHAIN_CANDIDATE_VALIDATION_PROJECT_MISSING");
        }
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> candidates =
                workflow.findWorkspaceCandidates(scope.taskId()).stream()
                        .filter(value -> value.workspaceId().equals(
                                validation.action().workspaceId()))
                        .filter(value -> value.baseProjectVersion().equals(
                                task.initialProjectVersion()))
                        .filter(value -> value.candidateFingerprint().equals(
                                validation.action().baseCandidateKey()))
                        .toList();
        if (candidates.size() != 1) {
            throw blocked("CHAIN_VALIDATION_CANDIDATE_BINDING_INVALID");
        }
        ChainPersistenceRecords.WorkspaceCandidateRecord candidate =
                candidates.get(0);
        ChainPersistenceRecords.ActionBindingRecord candidateAction =
                exactCandidateAction(scope, candidate.actionId());
        if (!candidate.taskId().equals(scope.taskId())
                || !candidate.actionId().equals(candidateAction.actionId())
                || !candidate.workspaceId().equals(candidateAction.workspaceId())
                || !candidate.versionFenceSha256().equals(
                        candidateAction.versionFenceSha256())) {
            throw blocked("CHAIN_VALIDATION_CANDIDATE_ACTION_INVALID");
        }
        AgentCandidateAutoApplicationService.VerificationProof proof =
                candidateProofs.proofChain(task.userId(), task.projectId(),
                        task.initialProjectVersion(), scope.taskId(),
                        scope.planId(), candidate.actionId(),
                        candidate.workspaceId(), scope.stepId(),
                        candidate.artifactId());
        if (!receiptRef.equals(proof.receiptId())) {
            throw blocked("CHAIN_VALIDATION_CANDIDATE_RECEIPT_INVALID");
        }
        return new VerifiedCandidate(candidate.actionId(),
                validation.action().actionId(), receiptRef,
                validation.payloadSha256(),
                validation.action().actionSignatureSha256(),
                candidate.workspaceCandidateId(), candidate.workspaceId(),
                candidate.artifactId(), candidate.candidateFingerprint(),
                candidate.baseProjectVersion());
    }

    /**
     * Reads the one original Receipt named by a typed Validation item.
     * The V81 item remains the reference authority; this method only exposes
     * the already-persisted receipt body after rechecking its exact scope and
     * payload digest.
     */
    public ExecutionReceipt exactReceiptBody(
            ChainPersistenceRecords.ValidationSetRecord set,
            String expectedActionId,
            String expectedReceiptId,
            String expectedPayloadSha256) {
        Objects.requireNonNull(set, "set");
        expectedActionId = required(expectedActionId, "expectedActionId");
        expectedReceiptId = required(expectedReceiptId, "expectedReceiptId");
        expectedPayloadSha256 = required(
                expectedPayloadSha256, "expectedPayloadSha256");
        var scope = new ChainValidationRuntime.Scope(
                set.taskId(), set.taskFrameId(), set.planId(),
                set.planRevisionId(), set.planRevisionNumber(), set.stepId(),
                set.activationEventId(), set.validationId(), set.createdAt());
        VerifiedAction verified = verifiedAction(scope, expectedReceiptId);
        if (!expectedActionId.equals(verified.action().actionId())
                || !expectedPayloadSha256.equals(verified.payloadSha256())) {
            throw blocked("CHAIN_VALIDATION_RECEIPT_ITEM_IDENTITY_INVALID");
        }
        return verified.receipt().receipt();
    }

    private VerifiedAction verifiedAction(
            ChainValidationRuntime.Scope scope, String receiptRef) {
        verifyFrozenScope(scope);
        ReceiptRow raw = exactReceipt(receiptRef);
        ChainPersistenceRecords.ActionBindingRecord action = exactAction(
                scope, raw.actionId());
        ToolCallId callId = new ToolCallId(action.actionId());
        PersistedEffectIntent intent = successful(intents.find(callId),
                "CHAIN_VALIDATION_EFFECT_INTENT_MISSING");
        if (!callId.equals(intent.intent().toolCallId())
                || !scope.planId().equals(intent.intent().planId().value())
                || !scope.stepId().equals(intent.intent().stepId().value())
                || !scope.activationEventId().equals(
                        intent.activationEventId().value())) {
            throw blocked("CHAIN_VALIDATION_EFFECT_INTENT_INVALID");
        }
        PersistedEffectResult result = successful(outcomes.findResult(callId),
                "CHAIN_VALIDATION_EFFECT_RESULT_MISSING");
        if (!receiptRef.equals(result.receipt().id().value())
                || !callId.equals(result.receipt().toolCallId())
                || result.receipt().status() != ReceiptStatus.SUCCESS
                || !rawReceiptMatches(raw, result.receipt())) {
            throw blocked("CHAIN_VALIDATION_EFFECT_RESULT_INVALID");
        }
        return new VerifiedAction(action, result, raw.payloadSha256());
    }

    private void verifyFrozenScope(ChainValidationRuntime.Scope scope) {
        if (foundations.findTask(scope.taskId()).isEmpty()) {
            throw blocked("CHAIN_VALIDATION_TASK_MISSING");
        }
        List<ChainPersistenceRecords.PlanBindingRecord> bindings = workflow
                .findPlanBindings(scope.taskId()).stream()
                .filter(value -> value.taskFrameId().equals(
                        scope.taskFrameId()))
                .filter(value -> value.planId().equals(scope.planId()))
                .filter(value -> value.planRevisionId().equals(
                        scope.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == scope.planRevisionNumber())
                .toList();
        if (bindings.size() != 1) {
            throw blocked("CHAIN_VALIDATION_PLAN_BINDING_INVALID");
        }
    }

    private boolean rawReceiptMatches(
            ReceiptRow raw,
            io.paperagent.v2.contracts.ExecutionReceipt receipt) {
        try {
            var body = json.readTree(raw.payloadJson());
            return "execution-receipt".equals(body.path("format").asText())
                    && receipt.id().value().equals(
                    body.path("receiptId").asText())
                    && receipt.toolCallId().value().equals(
                    body.path("toolCallId").asText())
                    && receipt.status().name().equals(
                    body.path("status").asText());
        } catch (Exception invalid) {
            return false;
        }
    }

    private ChainPersistenceRecords.ActionBindingRecord exactAction(
            ChainValidationRuntime.Scope scope, String actionId) {
        List<ChainPersistenceRecords.ActionBindingRecord> matches = workflow
                .findActionBindings(scope.taskId()).stream()
                .filter(value -> value.actionId().equals(actionId))
                .toList();
        if (matches.size() != 1) {
            throw blocked("CHAIN_VALIDATION_ACTION_BINDING_MISSING");
        }
        var action = matches.get(0);
        if (!scope.taskId().equals(action.taskId())
                || !scope.taskFrameId().equals(action.taskFrameId())
                || !scope.planId().equals(action.planId())
                || !scope.planRevisionId().equals(action.planRevisionId())
                || !scope.stepId().equals(action.stepId())
                || !scope.activationEventId().equals(
                        action.activationEventId())) {
            throw blocked("CHAIN_VALIDATION_ACTION_BINDING_INVALID");
        }
        return action;
    }

    private ChainPersistenceRecords.ActionBindingRecord exactCandidateAction(
            ChainValidationRuntime.Scope scope, String actionId) {
        List<ChainPersistenceRecords.ActionBindingRecord> matches = workflow
                .findActionBindings(scope.taskId()).stream()
                .filter(value -> value.actionId().equals(actionId))
                .toList();
        if (matches.size() != 1) {
            throw blocked("CHAIN_VALIDATION_ACTION_BINDING_MISSING");
        }
        var action = matches.get(0);
        if (!scope.taskId().equals(action.taskId())
                || !scope.taskFrameId().equals(action.taskFrameId())
                || !scope.planId().equals(action.planId())
                || !scope.planRevisionId().equals(action.planRevisionId())) {
            throw blocked("CHAIN_VALIDATION_CANDIDATE_ACTION_INVALID");
        }
        if (scope.stepId().equals(action.stepId())) {
            if (!scope.activationEventId().equals(
                    action.activationEventId())) {
                throw blocked("CHAIN_VALIDATION_CANDIDATE_ACTION_INVALID");
            }
            return action;
        }
        if (!dependsOn(scope, action.stepId())
                || !completedCandidateStep(scope, action)) {
            throw blocked("CHAIN_VALIDATION_CANDIDATE_PREDECESSOR_INVALID");
        }
        return action;
    }

    private boolean dependsOn(
            ChainValidationRuntime.Scope scope, String candidateStepId) {
        ChainStepAuthorityPort.PlanSnapshot plan = steps.findPlan(
                        scope.taskId(), scope.planRevisionId())
                .orElseThrow(() -> blocked(
                        "CHAIN_VALIDATION_PLAN_BINDING_INVALID"));
        Map<String, ChainStepAuthorityPort.StepDefinition> byId =
                new java.util.HashMap<>();
        plan.steps().forEach(step -> byId.put(step.stepId(), step));
        ChainStepAuthorityPort.StepDefinition validationStep = byId.get(
                scope.stepId());
        if (validationStep == null || !byId.containsKey(candidateStepId)) {
            return false;
        }
        java.util.ArrayDeque<String> remaining = new java.util.ArrayDeque<>(
                validationStep.prerequisiteStepIds());
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        while (!remaining.isEmpty()) {
            String dependency = remaining.removeFirst();
            if (!visited.add(dependency)) {
                continue;
            }
            if (candidateStepId.equals(dependency)) {
                return true;
            }
            ChainStepAuthorityPort.StepDefinition predecessor = byId.get(
                    dependency);
            if (predecessor != null) {
                predecessor.prerequisiteStepIds().forEach(value ->
                        remaining.addLast(value));
            }
        }
        return false;
    }

    private boolean completedCandidateStep(
            ChainValidationRuntime.Scope scope,
            ChainPersistenceRecords.ActionBindingRecord candidateAction) {
        return steps.findStepEvents(
                        scope.taskId(), scope.planRevisionId()).stream()
                .filter(event -> event.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                .filter(event -> event.command().stepId().equals(
                        candidateAction.stepId()))
                .filter(event -> event.command().activationEventId().equals(
                        candidateAction.activationEventId()))
                .count() == 1;
    }

    private ReceiptRow exactReceipt(String receiptRef) {
        List<ReceiptRow> rows = jdbc.query("""
                SELECT receipt.tool_call_id, receipt.payload_sha256,
                       receipt.payload_json
                  FROM agent_v2_receipts receipt
                  JOIN agent_v2_effect_results result
                    ON result.receipt_id = receipt.receipt_id
                   AND result.tool_call_id = receipt.tool_call_id
                 WHERE receipt.receipt_id = :receiptId
                   AND receipt.tool_call_claim_owner_kind = 'EFFECT_INTENT'
                   AND receipt.receipt_owner_kind = 'EFFECT_OUTCOME'
                """, new MapSqlParameterSource("receiptId", receiptRef),
                (row, ignored) -> new ReceiptRow(
                        row.getString("tool_call_id"),
                        row.getString("payload_sha256"),
                        row.getString("payload_json")));
        if (rows.size() != 1) {
            throw blocked("CHAIN_VALIDATION_RECEIPT_NOT_VISIBLE");
        }
        ReceiptRow row = rows.get(0);
        if (!row.payloadSha256().equals(sha256(row.payloadJson()))) {
            throw blocked("CHAIN_VALIDATION_RECEIPT_DIGEST_INVALID");
        }
        return row;
    }

    private static <T> T successful(PersistenceResult<T> result,
                                    String code) {
        if (!result.successful()) {
            throw blocked(code);
        }
        return result.value().orElseThrow(() -> blocked(code));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record ReceiptRow(
            String actionId, String payloadSha256, String payloadJson) {
    }

    private record VerifiedAction(
            ChainPersistenceRecords.ActionBindingRecord action,
            PersistedEffectResult receipt,
            String payloadSha256) {
    }
}
