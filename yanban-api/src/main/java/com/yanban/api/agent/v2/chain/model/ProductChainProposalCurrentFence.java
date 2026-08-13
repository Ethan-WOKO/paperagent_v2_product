package com.yanban.api.agent.v2.chain.model;

import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.model.ChainProposalCurrentFence;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Database-backed current-state fence evaluated while the task row is locked
 * by {@link ProductChainProposalAdmissionAdapter}.
 */
public final class ProductChainProposalCurrentFence
        implements ChainProposalCurrentFence {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainProposalCurrentFence(
            NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean isCurrent(Check check) {
        Objects.requireNonNull(check, "check");
        if (!allowedRoleState(check.role(), check.workState())) {
            return false;
        }
        List<Integer> current = jdbc.queryForList("""
                SELECT 1
                  FROM agent_v2_chain_model_invocations invocation
                  JOIN agent_v2_chain_context_revisions context_revision
                    ON context_revision.task_id = invocation.task_id
                   AND context_revision.context_revision_id =
                       invocation.context_revision_id
                   AND context_revision.completion_token =
                       invocation.completion_token
                 WHERE invocation.task_id = :taskId
                   AND invocation.invocation_id = :invocationId
                   AND invocation.context_revision_id = :contextRevisionId
                   AND invocation.role = :role
                   AND invocation.work_state = :workState
                   AND context_revision.status = 'COMPLETE'
                   AND invocation.invocation_ordinal = (
                       SELECT MAX(latest.invocation_ordinal)
                         FROM agent_v2_chain_model_invocations latest
                        WHERE latest.task_id = invocation.task_id)
                   AND 1 = (
                       SELECT COUNT(*)
                         FROM agent_v2_chain_model_invocations tied
                        WHERE tied.task_id = invocation.task_id
                          AND tied.invocation_ordinal =
                              invocation.invocation_ordinal)
                   AND context_revision.instruction_id = (
                       SELECT binding.instruction_id
                         FROM agent_v2_chain_task_instruction_bindings binding
                        WHERE binding.task_id = invocation.task_id
                        ORDER BY binding.task_instruction_sequence DESC
                        LIMIT 1)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_context_revisions newer_context
                        WHERE newer_context.task_id = invocation.task_id
                          AND newer_context.context_revision_id <>
                              context_revision.context_revision_id
                          AND newer_context.created_at >=
                              context_revision.created_at)
                   /* A successful effect receipt is the durable completion
                      fence for the preceding action. The executor pump may
                      therefore admit the next role turn before a later
                      reflector binds the receipt as a step result. */
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_transitions transition_record
                        WHERE transition_record.task_id = invocation.task_id
                          AND NOT EXISTS (
                              SELECT 1
                                FROM agent_v2_chain_transition_stages stage
                               WHERE stage.task_id = transition_record.task_id
                                 AND stage.transition_id =
                                     transition_record.transition_id
                                 AND stage.stage_code = 'COMPLETE')
                          AND NOT (
                              transition_record.transition_type =
                                  'GAP_RESOLUTION'
                              AND transition_record.source_decision_id =
                                  invocation.invocation_id))
                   AND (
                       invocation.role = 'ANSWER'
                       OR NOT EXISTS (
                           SELECT 1
                             FROM agent_v2_chain_task_outcomes outcome
                            WHERE outcome.task_id = invocation.task_id))
                """, new MapSqlParameterSource()
                .addValue("taskId", check.taskId())
                .addValue("invocationId", check.invocationId())
                .addValue("contextRevisionId", check.contextRevisionId())
                .addValue("role", check.role().name())
                .addValue("workState", check.workState().name()),
                Integer.class);
        return current.size() == 1;
    }

    private static boolean allowedRoleState(
            ChainRole role, ChainWorkState state) {
        return switch (role) {
            case PLANNER -> state == ChainWorkState.PLANNING
                    || state == ChainWorkState.CLASSIFYING_INSTRUCTION
                    || state == ChainWorkState.VALIDATING_PENDING_ITEM;
            case EXECUTOR -> state == ChainWorkState.EXECUTING
                    || state == ChainWorkState.VALIDATING_PENDING_ITEM;
            case REFLECTOR -> state == ChainWorkState.AWAITING_REVIEW
                    || state == ChainWorkState.FINALIZING;
            case ANSWER -> state == ChainWorkState.DIRECT_ANSWERING
                    || state == ChainWorkState.WAITING_USER
                    || state == ChainWorkState.WAITING_PERMISSION
                    || state == ChainWorkState.DELIVERING
                    || state == ChainWorkState.TERMINAL;
        };
    }
}
