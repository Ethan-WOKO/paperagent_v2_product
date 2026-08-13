package com.yanban.api.agent.v2.chain.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Deletes only stable Plan rows that are bound to one owned chain Session. */
@Repository
public class ProductChainPlanDeletionTransactions {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainPlanDeletionTransactions(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long deleteBoundPlans(long userId, long sessionId) {
        List<String> planIds = jdbc.queryForList("""
                SELECT binding.plan_id
                  FROM agent_v2_chain_plan_bindings binding
                  JOIN agent_v2_chain_tasks task
                    ON task.task_id = binding.task_id
                 WHERE task.user_id = :userId
                   AND task.session_id = :sessionId
                 ORDER BY binding.plan_id
                """, owned(userId, sessionId), String.class);
        if (planIds.isEmpty()) {
            return 0;
        }

        MapSqlParameterSource plans = new MapSqlParameterSource("planIds", planIds);
        for (String planId : planIds) {
            List<String> locked = jdbc.queryForList("""
                    SELECT plan_id
                      FROM agent_v2_plan_bootstraps
                     WHERE plan_id = :planId
                     FOR UPDATE
                    """, new MapSqlParameterSource("planId", planId),
                    String.class);
            if (!locked.equals(List.of(planId))) {
                throw new ProductChainPersistenceException(
                        "CHAIN_PLAN_DELETION_SNAPSHOT_MISMATCH");
            }
        }
        List<String> toolCallIds = jdbc.queryForList("""
                SELECT tool_call_id
                  FROM agent_v2_effect_intents
                 WHERE plan_id IN (:planIds)
                 ORDER BY tool_call_id
                """, plans, String.class);

        delete("agent_v2_step_completion_evidence", "plan_id", plans, "planIds");
        delete("agent_v2_effect_execution_claims", "plan_id", plans, "planIds");
        if (!toolCallIds.isEmpty()) {
            MapSqlParameterSource calls = new MapSqlParameterSource(
                    "toolCallIds", toolCallIds);
            delete("agent_v2_effect_results", "tool_call_id", calls,
                    "toolCallIds");
            delete("agent_v2_effect_progress", "tool_call_id", calls,
                    "toolCallIds");
        }
        delete("agent_v2_step_completions", "plan_id", plans, "planIds");
        if (!toolCallIds.isEmpty()) {
            MapSqlParameterSource calls = new MapSqlParameterSource(
                    "toolCallIds", toolCallIds);
            delete("agent_v2_receipts", "tool_call_id", calls,
                    "toolCallIds");
        }
        delete("agent_v2_effect_intents", "plan_id", plans, "planIds");
        if (!toolCallIds.isEmpty()) {
            MapSqlParameterSource calls = new MapSqlParameterSource(
                    "toolCallIds", toolCallIds);
            delete("agent_v2_tool_call_claims", "tool_call_id", calls,
                    "toolCallIds");
        }
        delete("agent_v2_active_step_replans", "plan_id", plans, "planIds");
        delete("agent_v2_step_interruptions", "plan_id", plans, "planIds");
        delete("agent_v2_step_activations", "plan_id", plans, "planIds");
        delete("agent_v2_plan_execution_contexts", "plan_id", plans, "planIds");
        delete("agent_v2_execution_starts", "plan_id", plans, "planIds");
        delete("agent_v2_plan_leases", "plan_id", plans, "planIds");
        delete("agent_v2_plan_replans", "plan_id", plans, "planIds");
        return delete("agent_v2_plan_bootstraps", "plan_id", plans, "planIds");
    }

    private long delete(
            String table, String column, MapSqlParameterSource parameters,
            String parameterName) {
        return jdbc.update("DELETE FROM " + table + " WHERE " + column
                + " IN (:" + parameterName + ")", parameters);
    }

    private static MapSqlParameterSource owned(long userId, long sessionId) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sessionId", sessionId);
    }
}
