package com.yanban.api.agent.v2.chain.finalization;

import com.yanban.api.agent.v2.chain.publish.ProductChainProjectPublishAdapter;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.project.ProjectService;
import io.paperagent.v2.chain.ChainFinalizationCheckWriter;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainCompletedOutcomePort;
import io.paperagent.v2.chain.finalization.ChainFinalizationAuthorityPort;
import io.paperagent.v2.chain.finalization.ChainFinalizationRuntime;
import io.paperagent.v2.chain.finalization.ChainFinalizationTransitionPort;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import io.paperagent.v2.chain.instruction.ChainInstructionState;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * Stable mechanical finalization entrypoint. The task lock spans the complete
 * Check -> Publish -> TaskOutcome transition, closing cancel/supersede races.
 */
@Component
public final class ProductChainFinalizationCoordinator {
    private final ChainFinalizationRepository finalization;
    private final ChainWorkflowRepository workflow;
    private final ChainInstructionStateReader instructions;
    private final ChainFinalizationRuntime runtime;
    private final ProductChainProjectPublishAdapter productPublish;
    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectService projects;
    private final TransactionTemplate transaction;
    private final ChainContextRepository contexts;

    public ProductChainFinalizationCoordinator(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ChainFinalizationCheckWriter checks,
            ChainFinalizationAuthorityPort authorities,
            ChainProjectPublishPort publish,
            ChainCompletedOutcomePort outcomes,
            ChainFinalizationTransitionPort transitions,
            ChainContextRepository contexts,
            NamedParameterJdbcTemplate jdbc,
            ProjectService projects,
            PlatformTransactionManager transactions) {
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.instructions = new ChainInstructionStateReader(
                foundations, workflow, finalization);
        this.runtime = new ChainFinalizationRuntime(
                foundations, workflow, finalization, checks, authorities,
                publish, outcomes, transitions,
                taskId -> ProductChainRuntimePolicySource.forTask(
                        contexts, taskId));
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.productPublish = publish
                instanceof ProductChainProjectPublishAdapter product
                ? product : null;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactions, "transactions"));
    }

    /**
     * Runs the existing idempotent finalization owner and returns the exact
     * persisted evidence for a stage whose marker may have been interrupted.
     */
    public ChainCompositeTransitionRuntime.StageCommitResult
            recoverCommittedStage(
                    ChainCompositeTransitionRuntime.StageCommand command) {
        Objects.requireNonNull(command, "command");
        var transition = command.transition();
        if (transition.transitionType() != ChainTransitionType.FINALIZATION) {
            throw new IllegalStateException(
                    "CHAIN_FINALIZATION_RECOVERY_TRANSITION_TYPE_INVALID");
        }
        var matching = finalization.findReadiness(transition.taskId()).stream()
                .filter(value -> transition.sourceDecisionId().equals(
                        value.reviewDecisionId()))
                .toList();
        if (matching.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_FINALIZATION_READINESS_AUTHORITY_MISSING");
        }
        finalizeReadiness(matching.get(0).readinessId(),
                transition.createdAt());
        var stages = workflow.findTransitionStages(
                        transition.transitionId()).stream()
                .filter(value -> value.stageCode() == command.stage())
                .filter(value -> value.stageOrdinal() == command.stageOrdinal())
                .toList();
        if (stages.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_FINALIZATION_STAGE_AUTHORITY_MISSING");
        }
        var stage = stages.get(0);
        return new ChainCompositeTransitionRuntime.StageCommitResult(
                stage.predecessorAuthorityType(),
                stage.predecessorAuthorityRef(),
                stage.successorAuthorityType(),
                stage.successorAuthorityRef());
    }

    public ChainFinalizationRuntime.Result finalizeReadiness(
            String readinessId, Instant committedAt) {
        Objects.requireNonNull(readinessId, "readinessId");
        Objects.requireNonNull(committedAt, "committedAt");
        if (productPublish != null) productPublish.clearDeferredFailure();
        try {
            var readiness = finalization.findReadinessById(readinessId)
                    .orElseThrow(() -> new IllegalStateException(
                            "formal finalization readiness does not exist"));
            ChainRuntimePolicy runtimePolicy =
                    ProductChainRuntimePolicySource.forTask(
                            contexts, readiness.taskId());
            int recoveries = 0;
            while (true) {
                ChainFinalizationRuntime.Result result;
                try {
                    result = execute(readinessId, committedAt, true);
                } catch (DeferredPublishRollback rollback) {
                    if (productPublish == null || recoveries >= runtimePolicy
                            .finalizationMechanicalAttemptsTotal()) {
                        throw rollback;
                    }
                    productPublish.persistDeferredFailureAfterRollback();
                    recoveries++;
                    continue;
                }
                if (result instanceof ChainFinalizationRuntime
                        .PublishFailed failed
                        && failed.failure().retryable()
                        && recoveries < runtimePolicy
                        .finalizationMechanicalAttemptsTotal()) {
                    recoveries++;
                    continue;
                }
                return result;
            }
        } finally {
            if (productPublish != null) productPublish.clearDeferredFailure();
        }
    }

    private ChainFinalizationRuntime.Result execute(
            String readinessId, Instant committedAt,
            boolean compensationAllowed) {
        return transaction.execute(status -> {
            ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                    finalization.findReadinessById(readinessId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "formal finalization readiness does not exist"));
            TaskFence task = lockTask(readiness.taskId());
            require(task.userId > 0 && task.sessionId > 0,
                    "finalization task identity is invalid");
            ChainInstructionState state = instructions.read(task.taskId);
            require(state.allowsNewSideEffects()
                            && state.currentInstruction().instructionId()
                            .equals(readiness.instructionId()),
                    "finalization readiness is no longer the current instruction");
            require(finalization.findTaskOutcome(task.taskId).isEmpty(),
                    "terminal TaskOutcome already owns this task");
            if (task.projectId != null) {
                String currentVersion = currentProjectVersion(task);
                if (!readiness.projectVersion().equals(currentVersion)) {
                    require(exactPublishedReplay(readiness, currentVersion),
                            "finalization ProjectVersion fence is stale");
                }
            }
            ChainFinalizationRuntime.Result result = runtime
                    .finalizeReadiness(readinessId, committedAt);
            if (status.isRollbackOnly()) {
                if (compensationAllowed
                        && result instanceof ChainFinalizationRuntime
                        .PublishFailed) {
                    throw new DeferredPublishRollback();
                }
                throw new IllegalStateException(
                        "finalization transaction became rollback-only");
            }
            if (result instanceof ChainFinalizationRuntime.Completed done
                    && done.published() != null) {
                require(done.published().publishedProjectVersion().equals(
                                currentProjectVersion(task)),
                        "published ProjectVersion is not current");
                require(finalization.findTaskOutcome(task.taskId)
                                .filter(done.outcome()::equals).isPresent(),
                        "completed TaskOutcome was not committed atomically");
            }
            return result;
        });
    }

    private boolean exactPublishedReplay(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String currentVersion) {
        if (productPublish == null) return false;
        ChainPersistenceRecords.FinalizationCheckRecord check = finalization
                .findFinalizationChecks(readiness.readinessId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.FinalizationCheckRecord
                                ::attemptNo).reversed())
                .filter(item -> item.resultStatus()
                        == io.paperagent.v2.chain.ChainFinalization.Outcome.PASSED)
                .findFirst().orElse(null);
        return check != null && productPublish
                .findExactSuccessfulReplay(readiness, check)
                .filter(operation -> currentVersion.equals(
                        operation.resultVersion()))
                .isPresent();
    }

    private TaskFence lockTask(String taskId) {
        var rows = jdbc.queryForList("""
                SELECT task_id, user_id, session_id, project_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, new MapSqlParameterSource("taskId", taskId));
        if (rows.size() != 1) {
            throw new IllegalStateException("finalization task does not exist");
        }
        Map<String, Object> row = rows.get(0);
        return new TaskFence(Objects.toString(row.get("task_id")),
                ((Number) row.get("user_id")).longValue(),
                ((Number) row.get("session_id")).longValue(),
                row.get("project_id") == null ? null
                        : ((Number) row.get("project_id")).longValue());
    }

    private String currentProjectVersion(TaskFence task) {
        if (task.projectId == null) return null;
        var rows = jdbc.queryForList("""
                SELECT project.id
                  FROM projects project
                 WHERE project.id = :projectId
                   AND project.user_id = :userId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("projectId", task.projectId)
                .addValue("userId", task.userId));
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "current ProjectVersion authority is missing");
        }
        return projects.manifest(task.userId, task.projectId).version();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record TaskFence(
            String taskId, long userId, long sessionId, Long projectId) {
    }

    private static final class DeferredPublishRollback
            extends RuntimeException {
    }
}
