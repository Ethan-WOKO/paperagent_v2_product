package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainSessionDeletionPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Product deletion closure for chain-owned rows. */
@Service
public class ProductChainSessionDeletionService
        implements ChainSessionDeletionPort {
    private final ProductChainPlanDeletionTransactions planDeletion;
    private final ProductChainSessionMutationLock sessionMutationLock;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainSessionDeletionService(
            ProductChainPlanDeletionTransactions planDeletion,
            ProductChainSessionMutationLock sessionMutationLock,
            NamedParameterJdbcTemplate jdbc) {
        this.planDeletion = planDeletion;
        this.sessionMutationLock = sessionMutationLock;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public long deleteOwnedSessionData(long userId, long sessionId) {
        sessionMutationLock.lockOwnedSession(userId, sessionId);
        sessionMutationLock.lockOwnedTasks(userId, sessionId);
        MapSqlParameterSource owned = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sessionId", sessionId);
        breakUnboundedContextCascade(owned);
        deleteCrossCascadeBindings(owned);
        planDeletion.deleteBoundPlans(userId, sessionId);

        long deletedTasks = jdbc.update("""
                DELETE FROM agent_v2_chain_tasks
                 WHERE user_id = :userId
                   AND session_id = :sessionId
                """, owned);
        jdbc.update("""
                DELETE FROM agent_v2_chain_instructions
                 WHERE session_id = :sessionId
                   AND command_id IN (
                       SELECT command_id
                         FROM agent_v2_chain_commands
                        WHERE user_id = :userId
                          AND session_id = :sessionId)
                """, owned);
        jdbc.update("""
                DELETE FROM agent_v2_chain_commands
                 WHERE user_id = :userId
                   AND session_id = :sessionId
                """, owned);
        return deletedTasks;
    }

    private void breakUnboundedContextCascade(
            MapSqlParameterSource owned) {
        jdbc.update("""
                UPDATE agent_v2_chain_context_revisions
                   SET parent_context_revision_id = NULL
                 WHERE parent_context_revision_id IS NOT NULL
                   AND task_id IN (
                       SELECT task_id
                         FROM agent_v2_chain_tasks
                        WHERE user_id = :userId
                          AND session_id = :sessionId)
                """, owned);
    }

    private void deleteCrossCascadeBindings(
            MapSqlParameterSource owned) {
        deleteOwnedTaskRows("agent_v2_chain_validation_bundle_sets", owned);
        jdbc.update("""
                DELETE FROM agent_v2_chain_candidate_validation_items
                 WHERE task_id IN (
                       SELECT task_id
                         FROM agent_v2_chain_tasks
                        WHERE user_id = :userId
                          AND session_id = :sessionId)
                """, owned);
        jdbc.update("""
                DELETE FROM agent_v2_chain_action_receipt_validation_items
                 WHERE task_id IN (
                       SELECT task_id
                         FROM agent_v2_chain_tasks
                        WHERE user_id = :userId
                          AND session_id = :sessionId)
                """, owned);
        deleteOwnedTaskRows("agent_v2_chain_task_outcomes", owned);
    }

    private void deleteOwnedTaskRows(
            String table, MapSqlParameterSource owned) {
        jdbc.update("DELETE FROM " + table + " WHERE task_id IN ("
                + "SELECT task_id FROM agent_v2_chain_tasks "
                + "WHERE user_id = :userId AND session_id = :sessionId)",
                owned);
    }
}
