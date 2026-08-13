package com.yanban.api.agent.v2.chain.model;

import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainProposalStateWriter;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.model.ChainProposalCurrentFence;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Product transaction boundary for proposal admission.
 *
 * <p>The task lock, current-state fence and proposal-state authority append
 * share one transaction. Product callers must use this adapter rather than
 * invoking the core admission service directly.</p>
 */
public final class ProductChainProposalAdmissionAdapter {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ChainProposalAdmissionService admissions;

    public ProductChainProposalAdmissionAdapter(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ChainModelRepository models,
            ChainProposalStateWriter states) {
        this(jdbc, transactions, models, states,
                new ProductChainProposalCurrentFence(jdbc));
    }

    ProductChainProposalAdmissionAdapter(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ChainModelRepository models,
            ChainProposalStateWriter states,
            ChainProposalCurrentFence currentFence) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactions, "transactions"));
        this.admissions = new ChainProposalAdmissionService(
                Objects.requireNonNull(models, "models"),
                Objects.requireNonNull(states, "states"),
                Objects.requireNonNull(currentFence, "currentFence"));
    }

    public ChainProposalAdmissionService.AdmissionResult admit(
            ChainProposalAdmissionService.AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        return required(transaction.execute(status -> {
            lockTask(request.taskId());
            return admissions.admit(request);
        }));
    }

    public ChainProposalAdmissionService.AdmissionResult
            replaceByOfficialResult(
                    ChainProposalAdmissionService.OfficialReplacement
                            request) {
        Objects.requireNonNull(request, "request");
        return required(transaction.execute(status -> {
            lockTask(request.taskId());
            return admissions.replaceByOfficialResult(request);
        }));
    }

    private void lockTask(String taskId) {
        List<String> tasks = jdbc.queryForList("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, new MapSqlParameterSource("taskId", taskId),
                String.class);
        if (tasks.size() != 1) {
            throw new IllegalArgumentException("proposal task does not exist");
        }
    }

    private static ChainProposalAdmissionService.AdmissionResult required(
            ChainProposalAdmissionService.AdmissionResult value) {
        return Objects.requireNonNull(value, "proposal admission transaction");
    }
}
