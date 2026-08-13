package com.yanban.api.agent.v2.chain.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateStepResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CommandRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateMaterializationFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelFailureStepBlockRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionReceiptStepBlockRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FinalizationCheckRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FinalizationReadinessRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.InstructionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PendingItemEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ResultApplicabilityRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.RouteDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskInstructionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskOutcomeRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationBundleRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates root references that cannot be expressed by task-composite FKs. */
final class ProductChainRootReferenceValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final NamedParameterJdbcTemplate jdbc;

    ProductChainRootReferenceValidator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void verify(Record value) {
        if (value instanceof CommandRecord command) {
            verifyCommandTarget(command);
        } else if (value instanceof InstructionRecord instruction) {
            verifyInstructionOrigin(instruction);
        } else if (value instanceof TaskInstructionBindingRecord binding) {
            verifyBinding(binding);
        } else if (value instanceof ContextRevisionRecord context) {
            verifyInstruction(context.taskId(), context.instructionId());
        } else if (value instanceof ContextBuildFailureRecord failure) {
            verifyContextBuildFailure(failure);
        } else if (value instanceof CandidateMaterializationFailureRecord failure) {
            verifyCandidateFailure(failure);
        } else if (value instanceof ModelFailureStepBlockRecord block) {
            verifyModelFailureStepBlock(block);
        } else if (value instanceof ActionReceiptStepBlockRecord block) {
            verifyActionReceiptStepBlock(block);
        } else if (value instanceof WorkspaceCandidateRecord candidate) {
            verifyCandidateHasNoFailure(candidate);
        } else if (value instanceof RouteDecisionRecord route) {
            verifyInstruction(route.taskId(), route.instructionId());
        } else if (value instanceof PlanBindingRecord plan) {
            verifyInstruction(plan.taskId(), plan.instructionId());
        } else if (value instanceof CandidateStepResultRecord result) {
            verifyInstruction(result.taskId(), result.instructionId());
        } else if (value instanceof ValidationBundleRecord bundle) {
            verifyValidationBundleRoot(bundle);
        } else if (value instanceof ResultApplicabilityRecord applicability) {
            verifyInstruction(applicability.taskId(),
                    applicability.targetInstructionVersionId());
        } else if (value instanceof PendingItemEventRecord pending
                && pending.answerInstructionId() != null) {
            verifyInstruction(pending.taskId(), pending.answerInstructionId());
        } else if (value instanceof ActionBindingRecord action) {
            verifyInstruction(action.taskId(), action.instructionId());
        } else if (value instanceof FinalizationReadinessRecord readiness) {
            verifyInstruction(readiness.taskId(), readiness.instructionId());
        } else if (value instanceof FinalizationCheckRecord check) {
            verifyInstruction(check.taskId(), check.instructionId());
        } else if (value instanceof TaskOutcomeRecord outcome) {
            verifyInstruction(outcome.taskId(), outcome.instructionId());
            verifyCommand(outcome.taskId(), outcome.sourceCommandId());
        } else if (value instanceof DeliveryRecord delivery) {
            verifyCommand(delivery.taskId(), delivery.sourceCommandId());
        }
    }

    private void verifyValidationBundleRoot(ValidationBundleRecord bundle) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_plan_bindings plan
                 WHERE plan.task_id = :taskId
                   AND plan.instruction_id = :instructionId
                   AND plan.task_frame_id = :taskFrameId
                   AND plan.plan_id = :planId
                   AND plan.plan_revision_id = :planRevisionId
                   AND plan.plan_revision_number = :planRevisionNumber
                """, new MapSqlParameterSource()
                .addValue("taskId", bundle.taskId())
                .addValue("instructionId", bundle.instructionId())
                .addValue("taskFrameId", bundle.taskFrameId())
                .addValue("planId", bundle.planId())
                .addValue("planRevisionId", bundle.planRevisionId())
                .addValue("planRevisionNumber",
                        bundle.planRevisionNumber())) != 1) {
            throw new ProductChainPersistenceException(
                    "CHAIN_VALIDATION_BUNDLE_PLAN_ROOT_INVALID");
        }
    }

    private void verifyActionReceiptStepBlock(
            ActionReceiptStepBlockRecord block) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_action_bindings action
                  JOIN agent_v2_chain_plan_bindings plan
                    ON plan.task_id = action.task_id
                   AND plan.instruction_id = action.instruction_id
                   AND plan.task_frame_id = action.task_frame_id
                   AND plan.plan_id = action.plan_id
                   AND plan.plan_revision_id = action.plan_revision_id
                 WHERE action.task_id = :taskId
                   AND action.action_id = :actionId
                   AND action.instruction_id = :instructionId
                   AND action.task_frame_id = :taskFrameId
                   AND action.plan_id = :planId
                   AND action.plan_revision_id = :planRevisionId
                   AND plan.plan_revision_number = :planRevisionNumber
                   AND action.step_id = :stepId
                   AND action.activation_event_id = :activationEventId
                   AND action.version_fence_sha256 = :versionFence
                """, new MapSqlParameterSource()
                .addValue("taskId", block.taskId())
                .addValue("actionId", block.actionId())
                .addValue("instructionId", block.instructionId())
                .addValue("taskFrameId", block.taskFrameId())
                .addValue("planId", block.planId())
                .addValue("planRevisionId", block.planRevisionId())
                .addValue("planRevisionNumber", block.planRevisionNumber())
                .addValue("stepId", block.stepId())
                .addValue("activationEventId", block.activationEventId())
                .addValue("versionFence", block.versionFenceSha256())) != 1
                || count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_model_proposals proposal
                  JOIN agent_v2_chain_model_invocations invocation
                    ON invocation.task_id = proposal.task_id
                   AND invocation.invocation_id = proposal.invocation_id
                  JOIN agent_v2_chain_context_revisions context
                    ON context.task_id = invocation.task_id
                   AND context.context_revision_id =
                       invocation.context_revision_id
                 WHERE proposal.task_id = :taskId
                   AND proposal.proposal_id = :repairProposalId
                   AND context.context_revision_id = :repairContextRevisionId
                   AND proposal.role = 'EXECUTOR'
                   AND proposal.proposal_kind IN ('TOOL_ACTION', 'WORKSPACE_CHANGE')
                   AND proposal.payload_sha256 = :proposalSignature
                   AND context.status = 'COMPLETE'
                   AND context.instruction_id = :instructionId
                   AND context.task_frame_id = :taskFrameId
                   AND context.plan_id = :planId
                   AND context.plan_revision_id = :planRevisionId
                   AND context.plan_revision_number = :planRevisionNumber
                   AND context.step_id = :stepId
                   AND context.activation_event_id = :activationEventId
                """, new MapSqlParameterSource()
                .addValue("taskId", block.taskId())
                .addValue("repairProposalId", block.repairProposalId())
                .addValue("repairContextRevisionId",
                        block.repairContextRevisionId())
                .addValue("proposalSignature",
                        block.repairProposalSignatureSha256())
                .addValue("instructionId", block.instructionId())
                .addValue("taskFrameId", block.taskFrameId())
                .addValue("planId", block.planId())
                .addValue("planRevisionId", block.planRevisionId())
                .addValue("planRevisionNumber", block.planRevisionNumber())
                .addValue("stepId", block.stepId())
                .addValue("activationEventId", block.activationEventId())) != 1
                || !("RECEIPT".equals(block.failureAuthorityType())
                ? receiptMatches(block) : candidateFailureMatches(block))) {
            mismatch("CHAIN_ACTION_RECEIPT_STEP_BLOCK_IDENTITY_MISMATCH");
        }
    }

    private boolean receiptMatches(ActionReceiptStepBlockRecord block) {
        var rows = jdbc.query("""
                SELECT receipt.payload_sha256, receipt.payload_json
                  FROM agent_v2_effect_results result
                  JOIN agent_v2_receipts receipt
                    ON receipt.receipt_id = result.receipt_id
                   AND receipt.tool_call_id = result.tool_call_id
                 WHERE result.tool_call_id = :actionId
                   AND result.receipt_id = :receiptId
                """, new MapSqlParameterSource()
                .addValue("actionId", block.actionId())
                .addValue("receiptId", block.receiptId()),
                (result, ignored) -> new ReceiptAuthority(
                        result.getString("payload_sha256"),
                        result.getString("payload_json")));
        if (rows.size() != 1 || !block.receiptPayloadSha256().equals(
                rows.get(0).payloadSha256())) {
            return false;
        }
        try {
            JsonNode payload = JSON.readTree(rows.get(0).payloadJson());
            return block.receiptId().equals(payload.path("receiptId").asText())
                    && block.actionId().equals(
                    payload.path("toolCallId").asText())
                    && block.receiptStatus().equals(
                    payload.path("status").asText())
                    && block.failureCode().equals(
                    payload.path("resultCode").asText());
        } catch (java.io.IOException invalidPayload) {
            return false;
        }
    }

    private boolean candidateFailureMatches(
            ActionReceiptStepBlockRecord block) {
        return count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_candidate_materialization_failures failure
                 WHERE failure.task_id = :taskId
                   AND failure.action_id = :actionId
                   AND failure.candidate_failure_id = :failureRef
                   AND failure.error_code = :failureCode
                   AND failure.version_fence_sha256 = :versionFence
                """, new MapSqlParameterSource()
                .addValue("taskId", block.taskId())
                .addValue("actionId", block.actionId())
                .addValue("failureRef", block.failureAuthorityRef())
                .addValue("failureCode", block.failureCode())
                .addValue("versionFence", block.versionFenceSha256())) == 1;
    }

    private record ReceiptAuthority(String payloadSha256, String payloadJson) {
        private ReceiptAuthority {
            Objects.requireNonNull(payloadSha256, "payloadSha256");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }

    private void verifyModelFailureStepBlock(
            ModelFailureStepBlockRecord block) {
        List<String> policyVersions = jdbc.queryForList("""
                SELECT runtime_policy_version
                  FROM agent_v2_chain_model_invocations
                 WHERE task_id = :taskId
                   AND invocation_id = :invocationId
                """, Map.of("taskId", block.taskId(),
                "invocationId", block.invocationId()), String.class);
        if (policyVersions.size() != 1) {
            mismatch("CHAIN_MODEL_FAILURE_STEP_BLOCK_POLICY_MISSING");
        }
        int providerAttempts = io.paperagent.v2.chain.ChainRuntimePolicy
                .requireVersion(policyVersions.get(0))
                .providerAttemptsTotal();
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_model_invocations invocation
                  JOIN agent_v2_chain_context_revisions context
                    ON context.task_id = invocation.task_id
                   AND context.context_revision_id =
                       invocation.context_revision_id
                 WHERE invocation.task_id = :taskId
                   AND invocation.invocation_id = :invocationId
                   AND invocation.role = 'EXECUTOR'
                   AND context.status = 'COMPLETE'
                   AND context.context_revision_id = :contextRevisionId
                   AND context.instruction_id = :instructionId
                   AND context.task_frame_id = :taskFrameId
                   AND context.plan_id = :planId
                   AND context.plan_revision_id = :planRevisionId
                   AND context.plan_revision_number = :planRevisionNumber
                   AND context.step_id = :stepId
                   AND context.activation_event_id = :activationEventId
                """, new MapSqlParameterSource()
                .addValue("taskId", block.taskId())
                .addValue("invocationId", block.invocationId())
                .addValue("contextRevisionId", block.contextRevisionId())
                .addValue("instructionId", block.instructionId())
                .addValue("taskFrameId", block.taskFrameId())
                .addValue("planId", block.planId())
                .addValue("planRevisionId", block.planRevisionId())
                .addValue("planRevisionNumber",
                        block.planRevisionNumber())
                .addValue("stepId", block.stepId())
                .addValue("activationEventId",
                        block.activationEventId())) != 1
                || count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_provider_attempts attempt
                 WHERE attempt.task_id = :taskId
                   AND attempt.invocation_id = :invocationId
                   AND CONCAT(attempt.invocation_id, '#', attempt.attempt_no)
                       = :attemptRef
                   AND attempt.attempt_no = :attemptTotal
                   AND attempt.error_code IS NOT NULL
                """, new MapSqlParameterSource()
                .addValue("taskId", block.taskId())
                .addValue("invocationId", block.invocationId())
                .addValue("attemptRef", block.lastProviderAttemptRef())
                .addValue("attemptTotal", providerAttempts)) != 1) {
            mismatch("CHAIN_MODEL_FAILURE_STEP_BLOCK_IDENTITY_MISMATCH");
        }
    }

    private void verifyCandidateFailure(
            CandidateMaterializationFailureRecord failure) {
        if (count("""
                SELECT COUNT(*) FROM agent_v2_chain_action_bindings action
                 WHERE action.task_id = :taskId AND action.action_id = :actionId
                   AND action.workspace_id = :workspaceId
                   AND action.base_candidate_key = :baseCandidateKey
                   AND action.version_fence_sha256 = :versionFence
                   AND (
                     (:authorityType = 'WORKSPACE_CHANGE_BODY' AND EXISTS (
                       SELECT 1 FROM agent_v2_chain_model_proposals proposal
                        WHERE proposal.task_id = action.task_id
                          AND proposal.proposal_id = action.proposal_id
                          AND proposal.body_authority_type = :authorityType
                          AND proposal.body_authority_ref = :authorityRef
                     ))
                     OR
                     (:authorityType = 'TOOL_EFFECT_RESULT'
                       AND action.result_authority_type = :authorityType
                       AND action.result_authority_ref = :authorityRef)
                   )
                """, new MapSqlParameterSource()
                .addValue("taskId", failure.taskId())
                .addValue("actionId", failure.actionId())
                .addValue("workspaceId", failure.workspaceId())
                .addValue("baseCandidateKey", failure.baseCandidateKey())
                .addValue("versionFence", failure.versionFenceSha256())
                .addValue("authorityType",
                        failure.mutationAuthorityType())
                .addValue("authorityRef",
                        failure.mutationAuthorityRef())) != 1
                || count("""
                SELECT COUNT(*) FROM agent_v2_chain_workspace_candidates
                 WHERE task_id = :taskId AND action_id = :actionId
                """, new MapSqlParameterSource()
                .addValue("taskId", failure.taskId())
                .addValue("actionId", failure.actionId())) != 0) {
            mismatch("CHAIN_CANDIDATE_FAILURE_IDENTITY_MISMATCH");
        }
    }

    private void verifyCandidateHasNoFailure(WorkspaceCandidateRecord candidate) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_candidate_materialization_failures
                 WHERE task_id = :taskId AND action_id = :actionId
                """, new MapSqlParameterSource()
                .addValue("taskId", candidate.taskId())
                .addValue("actionId", candidate.actionId())) != 0) {
            mismatch("CHAIN_CANDIDATE_SUCCESS_FAILURE_CONFLICT");
        }
    }

    private void verifyContextBuildFailure(
            ContextBuildFailureRecord failure) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_context_revisions context
                 WHERE context.context_revision_id = :contextRevisionId
                   AND context.task_id = :taskId
                   AND context.status = 'BUILDING'
                   AND context.role = :role
                   AND context.work_state = :workState
                   AND context.call_reason = :callReason
                   AND context.instruction_id = :instructionId
                   AND context.projector_set_version = :projectorSetVersion
                   AND context.pagination_version = :paginationVersion
                   AND context.runtime_policy_version = :runtimePolicyVersion
                """, new MapSqlParameterSource()
                .addValue("contextRevisionId",
                        failure.contextRevisionId())
                .addValue("taskId", failure.taskId())
                .addValue("role", failure.role().name())
                .addValue("workState", failure.workState().name())
                .addValue("callReason", failure.callReason())
                .addValue("instructionId", failure.instructionId())
                .addValue("projectorSetVersion",
                        failure.projectorSetVersion())
                .addValue("paginationVersion",
                        failure.paginationVersion())
                .addValue("runtimePolicyVersion",
                        failure.runtimePolicyVersion())) != 1) {
            mismatch("CHAIN_CONTEXT_BUILD_FAILURE_IDENTITY_MISMATCH");
        }
    }

    private void verifyCommandTarget(CommandRecord command) {
        if (command.targetTaskId() == null) {
            return;
        }
        long matches = count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_tasks task
                 WHERE task.task_id = :taskId
                   AND task.user_id = :userId
                   AND task.session_id = :sessionId
                   AND task.root_client_request_id = :rootRequestId
                """, new MapSqlParameterSource()
                .addValue("taskId", command.targetTaskId())
                .addValue("userId", command.userId())
                .addValue("sessionId", command.sessionId())
                .addValue("rootRequestId", command.targetClientRequestId()));
        if (matches != 1) {
            mismatch("CHAIN_COMMAND_TARGET_TASK_MISMATCH");
        }
        if (command.gapId() != null && count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_pending_items
                 WHERE task_id = :taskId AND gap_id = :gapId
                """, new MapSqlParameterSource()
                .addValue("taskId", command.targetTaskId())
                .addValue("gapId", command.gapId())) != 1) {
            mismatch("CHAIN_COMMAND_TARGET_GAP_MISMATCH");
        }
    }

    private void verifyInstructionOrigin(InstructionRecord instruction) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_commands command
                    ON command.command_id = :commandId
                   AND command.user_id = task.user_id
                   AND command.session_id = task.session_id
                 WHERE task.task_id = :taskId
                   AND task.session_id = :sessionId
                   AND (task.created_by_command_id = command.command_id
                        OR command.target_task_id = task.task_id)
                """, new MapSqlParameterSource()
                .addValue("commandId", instruction.commandId())
                .addValue("taskId", instruction.originTaskId())
                .addValue("sessionId", instruction.sessionId())) != 1) {
            mismatch("CHAIN_INSTRUCTION_ORIGIN_MISMATCH");
        }
        if (instruction.parentInstructionId() != null) {
            verifyInstruction(instruction.originTaskId(),
                    instruction.parentInstructionId());
        }
        if (instruction.answeredGapId() != null && count("""
                SELECT COUNT(*) FROM agent_v2_chain_pending_items
                 WHERE task_id = :taskId AND gap_id = :gapId
                """, new MapSqlParameterSource()
                .addValue("taskId", instruction.originTaskId())
                .addValue("gapId", instruction.answeredGapId())) != 1) {
            mismatch("CHAIN_INSTRUCTION_GAP_MISMATCH");
        }
    }

    private void verifyBinding(TaskInstructionBindingRecord binding) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_instructions instruction
                    ON instruction.instruction_id = :instructionId
                   AND instruction.session_id = task.session_id
                 WHERE task.task_id = :taskId
                   AND ((:role = 'ORIGIN'
                         AND instruction.origin_task_id = task.task_id)
                        OR (:role = 'INHERITED_ROOT'
                            AND task.source_instruction_id =
                                instruction.instruction_id))
                """, new MapSqlParameterSource()
                .addValue("instructionId", binding.instructionId())
                .addValue("taskId", binding.taskId())
                .addValue("role", binding.relationRole().name())) != 1) {
            mismatch("CHAIN_INSTRUCTION_BINDING_TASK_MISMATCH");
        }
    }

    private void verifyInstruction(String taskId, String instructionId) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_task_instruction_bindings binding
                  JOIN agent_v2_chain_tasks task
                    ON task.task_id = binding.task_id
                  JOIN agent_v2_chain_instructions instruction
                    ON instruction.instruction_id = binding.instruction_id
                   AND instruction.session_id = task.session_id
                 WHERE binding.task_id = :taskId
                   AND binding.instruction_id = :instructionId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("instructionId", instructionId)) != 1) {
            mismatch("CHAIN_INSTRUCTION_TASK_MISMATCH");
        }
    }

    private void verifyCommand(String taskId, String commandId) {
        if (count("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_commands command
                    ON command.command_id = :commandId
                   AND command.user_id = task.user_id
                   AND command.session_id = task.session_id
                 WHERE task.task_id = :taskId
                   AND (task.created_by_command_id = command.command_id
                        OR EXISTS (
                            SELECT 1
                              FROM agent_v2_chain_instructions instruction
                              JOIN agent_v2_chain_task_instruction_bindings binding
                                ON binding.instruction_id =
                                   instruction.instruction_id
                               AND binding.task_id = task.task_id
                             WHERE instruction.command_id = command.command_id))
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("commandId", commandId)) != 1) {
            mismatch("CHAIN_COMMAND_TASK_MISMATCH");
        }
    }

    private long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }

    private static void mismatch(String code) {
        throw new ProductChainPersistenceException(code);
    }
}
