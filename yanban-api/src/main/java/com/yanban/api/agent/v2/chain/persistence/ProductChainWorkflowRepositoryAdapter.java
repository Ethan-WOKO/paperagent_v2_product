package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainAcceptedResultWriter;
import io.paperagent.v2.chain.ChainActionBindingWriter;
import io.paperagent.v2.chain.ChainApplicabilityWriter;
import io.paperagent.v2.chain.ChainCandidateStepResultWriter;
import io.paperagent.v2.chain.ChainPendingItemWriter;
import io.paperagent.v2.chain.ChainPermissionDecisionWriter;
import io.paperagent.v2.chain.ChainPlanBindingWriter;
import io.paperagent.v2.chain.ChainReviewDecisionWriter;
import io.paperagent.v2.chain.ChainRouteDecisionWriter;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainWorkspaceCandidateWriter;
import io.paperagent.v2.chain.ChainInstructionDispositionWriter;
import io.paperagent.v2.chain.ChainModelFailureStepBlockWriter;
import io.paperagent.v2.chain.ChainActionReceiptStepBlockWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AcceptedResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateStepResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PendingItemEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PendingItemRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PermissionDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ResultApplicabilityRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ReviewDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.RouteDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskAuthorityFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.InstructionDispositionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelFailureStepBlockRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionReceiptStepBlockRecord;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductChainWorkflowRepositoryAdapter
        implements ChainWorkflowRepository, ChainTransitionWriter,
        ChainRouteDecisionWriter, ChainPlanBindingWriter,
        ChainCandidateStepResultWriter, ChainReviewDecisionWriter,
        ChainAcceptedResultWriter, ChainApplicabilityWriter,
        ChainPendingItemWriter, ChainPermissionDecisionWriter,
        ChainActionBindingWriter, ChainWorkspaceCandidateWriter,
        ChainInstructionDispositionWriter, ChainModelFailureStepBlockWriter,
        ChainActionReceiptStepBlockWriter {
    private final ProductChainTransactions transactions;

    public ProductChainWorkflowRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public AuthoritativeAppendResult<TransitionRecord> appendTransition(
            AuthoritativeFact<TransitionRecord> transition) {
        return append("agent_v2_chain_transitions", TransitionRecord.class,
                transition,
                Map.of("transition_id", transition.fact().transitionId()));
    }

    @Override
    public AuthoritativeAppendResult<TransitionStageRecord>
            appendTransitionStage(
                    AuthoritativeFact<TransitionStageRecord> stage) {
        return append("agent_v2_chain_transition_stages",
                TransitionStageRecord.class, stage,
                ordered("transition_id", stage.fact().transitionId(),
                        "stage_code", stage.fact().stageCode().name()));
    }

    @Override
    public Optional<TransitionRecord> findTransition(String transitionId) {
        return transactions.find("agent_v2_chain_transitions",
                TransitionRecord.class,
                Map.of("transition_id", transitionId));
    }

    @Override
    public List<TransitionStageRecord> findTransitionStages(
            String transitionId) {
        return transactions.findAll("agent_v2_chain_transition_stages",
                TransitionStageRecord.class,
                Map.of("transition_id", transitionId), "stage_ordinal");
    }

    @Override
    public List<TransitionRecord> findIncompleteTransitions(String taskId) {
        return query("""
                SELECT transition.*
                  FROM agent_v2_chain_transitions transition
                 WHERE transition.task_id = :taskId
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_transition_stages stage
                        WHERE stage.transition_id = transition.transition_id
                          AND stage.stage_code = 'COMPLETE')
                 ORDER BY transition.created_at, transition.transition_id
                """, Map.of("taskId", taskId), TransitionRecord.class);
    }

    @Override
    public List<RouteDecisionRecord> findRouteDecisions(String taskId) {
        return authorityOrdered("agent_v2_chain_route_decisions",
                "route", taskId, RouteDecisionRecord.class);
    }

    @Override
    public List<PlanBindingRecord> findPlanBindings(String taskId) {
        return authorityOrdered("agent_v2_chain_plan_bindings",
                "binding", taskId, PlanBindingRecord.class);
    }

    @Override
    public List<InstructionDispositionRecord> findInstructionDispositions(
            String taskId) {
        return authorityOrdered("agent_v2_chain_instruction_dispositions",
                "disposition", taskId, InstructionDispositionRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<InstructionDispositionRecord>
            appendInstructionDisposition(
                    AuthoritativeFact<InstructionDispositionRecord> disposition) {
        return append("agent_v2_chain_instruction_dispositions",
                InstructionDispositionRecord.class, disposition,
                Map.of("disposition_id", disposition.fact().dispositionId()));
    }

    @Override
    public List<CandidateStepResultRecord> findCandidateStepResults(
            String taskId) {
        return authorityOrdered("agent_v2_chain_candidate_step_results",
                "candidate", taskId, CandidateStepResultRecord.class);
    }

    @Override
    public List<ModelFailureStepBlockRecord> findModelFailureStepBlocks(
            String taskId) {
        return authorityOrdered("agent_v2_chain_model_failure_step_blocks",
                "step_block", taskId, ModelFailureStepBlockRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<ModelFailureStepBlockRecord>
            appendModelFailureStepBlock(
                    AuthoritativeFact<ModelFailureStepBlockRecord> block) {
        return append("agent_v2_chain_model_failure_step_blocks",
                ModelFailureStepBlockRecord.class, block,
                Map.of("step_block_id", block.fact().stepBlockId()));
    }

    @Override
    public List<ActionReceiptStepBlockRecord> findActionReceiptStepBlocks(
            String taskId) {
        return authorityOrdered("agent_v2_chain_action_receipt_step_blocks",
                "step_block", taskId, ActionReceiptStepBlockRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<ActionReceiptStepBlockRecord>
            appendActionReceiptStepBlock(
                    AuthoritativeFact<ActionReceiptStepBlockRecord> block) {
        return append("agent_v2_chain_action_receipt_step_blocks",
                ActionReceiptStepBlockRecord.class, block,
                Map.of("step_block_id", block.fact().stepBlockId()));
    }

    @Override
    public List<ReviewDecisionRecord> findReviewDecisions(String taskId) {
        return authorityOrdered("agent_v2_chain_review_decisions",
                "review", taskId, ReviewDecisionRecord.class);
    }

    @Override
    public List<AcceptedResultRecord> findAcceptedResults(String taskId) {
        return authorityOrdered("agent_v2_chain_accepted_results",
                "accepted", taskId, AcceptedResultRecord.class);
    }

    @Override
    public List<ResultApplicabilityRecord> findApplicabilityDecisions(
            String taskId) {
        return authorityOrdered("agent_v2_chain_result_applicability",
                "applicability", taskId,
                ResultApplicabilityRecord.class);
    }

    @Override
    public List<PendingItemRecord> findPendingItems(String taskId) {
        return authorityOrdered("agent_v2_chain_pending_items",
                "item", taskId, PendingItemRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<RouteDecisionRecord> appendRouteDecision(
            AuthoritativeFact<RouteDecisionRecord> routeDecision) {
        return append("agent_v2_chain_route_decisions",
                RouteDecisionRecord.class, routeDecision,
                Map.of("route_decision_id",
                        routeDecision.fact().routeDecisionId()));
    }

    @Override
    public AuthoritativeAppendResult<PlanBindingRecord> appendPlanBinding(
            AuthoritativeFact<PlanBindingRecord> planBinding) {
        return append("agent_v2_chain_plan_bindings", PlanBindingRecord.class,
                planBinding,
                Map.of("plan_binding_id",
                        planBinding.fact().planBindingId()));
    }

    @Override
    public AuthoritativeAppendResult<CandidateStepResultRecord>
            appendCandidateStepResult(
                    AuthoritativeFact<CandidateStepResultRecord> result) {
        return append("agent_v2_chain_candidate_step_results",
                CandidateStepResultRecord.class, result,
                Map.of("candidate_result_id",
                        result.fact().candidateResultId()));
    }

    @Override
    public AuthoritativeAppendResult<AcceptedResultRecord> appendAcceptedResult(
            AuthoritativeFact<AcceptedResultRecord> result) {
        return append("agent_v2_chain_accepted_results",
                AcceptedResultRecord.class, result,
                Map.of("accepted_result_id",
                        result.fact().acceptedResultId()));
    }

    @Override
    public AuthoritativeAppendResult<ReviewDecisionRecord> appendReviewDecision(
            AuthoritativeFact<ReviewDecisionRecord> decision) {
        return append("agent_v2_chain_review_decisions",
                ReviewDecisionRecord.class, decision,
                Map.of("review_decision_id",
                        decision.fact().reviewDecisionId()));
    }

    @Override
    public AuthoritativeAppendResult<ResultApplicabilityRecord>
            appendApplicability(
                    AuthoritativeFact<ResultApplicabilityRecord> applicability) {
        return append("agent_v2_chain_result_applicability",
                ResultApplicabilityRecord.class, applicability,
                Map.of("applicability_id",
                        applicability.fact().applicabilityId()));
    }

    @Override
    public AuthoritativeAppendResult<PendingItemRecord> appendPendingItem(
            AuthoritativeFact<PendingItemRecord> item) {
        return append("agent_v2_chain_pending_items", PendingItemRecord.class,
                item, Map.of("gap_id", item.fact().gapId()));
    }

    @Override
    public AuthoritativeAppendResult<PendingItemEventRecord>
            appendPendingItemEvent(
                    AuthoritativeFact<PendingItemEventRecord> event) {
        PendingItemEventRecord fact = event.fact();
        return append("agent_v2_chain_pending_item_events",
                PendingItemEventRecord.class, event,
                ordered("gap_id", fact.gapId(), "response_round",
                        fact.responseRound(), "event_kind",
                        fact.eventKind().name()));
    }

    @Override
    public List<PendingItemRecord> findOpenPendingItems(String taskId) {
        return query("""
                SELECT item.*
                  FROM agent_v2_chain_pending_items item
                 WHERE item.task_id = :taskId
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_pending_item_events state
                        WHERE state.gap_id = item.gap_id
                          AND state.event_kind IN
                              ('RESOLVED','REJECTED','CANCELLED'))
                 ORDER BY item.created_at, item.gap_id
                """, Map.of("taskId", taskId), PendingItemRecord.class);
    }

    @Override
    public List<PendingItemEventRecord> findPendingItemEvents(String gapId) {
        return query("""
                SELECT state.*
                  FROM agent_v2_chain_pending_item_events state
                  JOIN agent_v2_chain_authority_events authority
                    ON authority.event_id = state.event_id
                   AND authority.task_id = state.task_id
                 WHERE state.gap_id = :gapId
                 ORDER BY authority.event_sequence
                """, Map.of("gapId", gapId),
                PendingItemEventRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<PermissionDecisionRecord>
            appendPermissionDecision(
                    AuthoritativeFact<PermissionDecisionRecord> decision) {
        return append("agent_v2_chain_permission_decisions",
                PermissionDecisionRecord.class, decision,
                Map.of("permission_decision_id",
                        decision.fact().permissionDecisionId()));
    }

    @Override
    public List<PermissionDecisionRecord> findPermissionDecisions(
            String taskId) {
        return authorityOrdered("agent_v2_chain_permission_decisions",
                "permission", taskId, PermissionDecisionRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<ActionBindingRecord> appendActionBinding(
            AuthoritativeFact<ActionBindingRecord> binding) {
        return append("agent_v2_chain_action_bindings",
                ActionBindingRecord.class, binding,
                Map.of("action_id", binding.fact().actionId()));
    }

    @Override
    public List<ActionBindingRecord> findActionBindings(String taskId) {
        return authorityOrdered("agent_v2_chain_action_bindings",
                "action", taskId, ActionBindingRecord.class);
    }

    @Override
    public AuthoritativeAppendResult<WorkspaceCandidateRecord>
            appendWorkspaceCandidate(
                    AuthoritativeFact<WorkspaceCandidateRecord> candidate) {
        return append("agent_v2_chain_workspace_candidates",
                WorkspaceCandidateRecord.class, candidate,
                Map.of("workspace_candidate_id",
                        candidate.fact().workspaceCandidateId()));
    }

    @Override
    public List<WorkspaceCandidateRecord> findWorkspaceCandidates(
            String taskId) {
        return authorityOrdered("agent_v2_chain_workspace_candidates",
                "candidate", taskId, WorkspaceCandidateRecord.class);
    }

    @Override
    public List<ActionBindingRecord> findInFlightActions(String taskId) {
        return query("""
                SELECT action.*
                  FROM agent_v2_chain_action_bindings action
                 WHERE action.task_id = :taskId
                   AND action.result_authority_type IS NULL
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_workspace_candidates candidate
                        WHERE candidate.task_id = action.task_id
                          AND candidate.action_id = action.action_id)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_candidate_materialization_failures failure
                        WHERE failure.task_id = action.task_id
                          AND failure.action_id = action.action_id)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_action_receipt_step_blocks block
                        WHERE block.task_id = action.task_id
                          AND block.action_id = action.action_id)
                 ORDER BY action.created_at, action.action_id
                """, Map.of("taskId", taskId), ActionBindingRecord.class);
    }

    private <T extends Record & TaskAuthorityFact>
            AuthoritativeAppendResult<T> append(
            String table, Class<T> type, AuthoritativeFact<T> fact,
            Map<String, Object> identity) {
        return transactions.appendAuthoritative(table, type, fact, identity);
    }

    private <T extends Record> List<T> query(
            String sql, Map<String, Object> parameters, Class<T> type) {
        return transactions.jdbc().queryForList(sql,
                        new MapSqlParameterSource(parameters)).stream()
                .map(row -> transactions.codec().decode(type, row)).toList();
    }

    private <T extends Record> List<T> authorityOrdered(
            String table, String alias, String taskId, Class<T> type) {
        return query("SELECT " + alias + ".* FROM " + table + " " + alias
                        + " JOIN agent_v2_chain_authority_events authority"
                        + " ON authority.event_id = " + alias + ".event_id"
                        + " AND authority.task_id = " + alias + ".task_id"
                        + " WHERE " + alias + ".task_id = :taskId"
                        + " ORDER BY authority.event_sequence",
                Map.of("taskId", taskId), type);
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
