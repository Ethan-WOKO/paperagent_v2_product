package com.yanban.api.agent.v2.chain.effect;

import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the last current-authority check with one durable mutation. */
@Component
public class ProductChainTaskMutationFence {
    private final EntityManager entityManager;
    private final ProductChainCurrentAuthorityGate currentGate;

    public ProductChainTaskMutationFence(
            EntityManager entityManager,
            ProductChainCurrentAuthorityGate currentGate) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.currentGate = Objects.requireNonNull(currentGate, "currentGate");
    }

    @Transactional
    public ChainEffectRuntime.PreparedEffect prepareCurrent(
            ChainEffectRuntime.FrozenMutation mutation,
            Supplier<ChainEffectRuntime.PreparedEffect> prepare) {
        return executeCurrent(mutation, prepare);
    }

    @Transactional
    public ChainEffectRuntime.MaterializedCandidate materializeCurrent(
            ChainEffectRuntime.FrozenMutation mutation,
            Supplier<ChainEffectRuntime.MaterializedCandidate> materialize) {
        return executeCurrent(mutation, materialize);
    }

    private <T> T executeCurrent(
            ChainEffectRuntime.FrozenMutation mutation, Supplier<T> action) {
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(action, "action");
        List<?> rows = entityManager.createNativeQuery("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """)
                .setParameter("taskId", mutation.taskId())
                .getResultList();
        require(rows.size() == 1, "chain task is unavailable");
        require(currentGate.classify(mutation)
                        == ChainEffectRuntime.GateStatus.CURRENT,
                "chain mutation authority is no longer current");
        return action.get();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
