package com.yanban.api.agent.v2.chain.persistence;

import com.yanban.api.agent.v2.chain.context.ProductChainSkillSnapshotRepository;
import com.yanban.api.agent.v2.chain.context.ProductChainTaskSkillSnapshot;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ProductChainSkillSnapshotRepositoryAdapter
        implements ProductChainSkillSnapshotRepository {
    private static final String TABLE =
            "agent_v2_chain_task_skill_snapshots";

    private final ProductChainTransactions transactions;

    ProductChainSkillSnapshotRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public Optional<ProductChainTaskSkillSnapshot> findByTaskId(
            String taskId) {
        return transactions.find(TABLE, ProductChainTaskSkillSnapshot.class,
                Map.of("task_id", taskId));
    }

    @Override
    public ProductChainTaskSkillSnapshot append(
            ProductChainTaskSkillSnapshot snapshot) {
        return transactions.appendTaskScoped(
                TABLE, ProductChainTaskSkillSnapshot.class, snapshot,
                Map.of("task_id", snapshot.taskId()), snapshot.taskId())
                .value();
    }
}
