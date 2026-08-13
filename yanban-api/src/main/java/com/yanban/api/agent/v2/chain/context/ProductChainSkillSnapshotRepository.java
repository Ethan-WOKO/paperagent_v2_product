package com.yanban.api.agent.v2.chain.context;

import java.util.Optional;

public interface ProductChainSkillSnapshotRepository {
    Optional<ProductChainTaskSkillSnapshot> findByTaskId(String taskId);

    ProductChainTaskSkillSnapshot append(
            ProductChainTaskSkillSnapshot snapshot);
}
