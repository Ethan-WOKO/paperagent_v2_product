package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.runtime.bootstrap.DefaultPersistentPlanBootstrapper;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.DeterministicFreshExecutionGate;
import io.paperagent.v2.runtime.execution.start.DefaultFreshExecutionStarter;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires only deterministic V2 bootstrap components to the product repository.
 */
@Configuration
public class AgentV2PlanBootstrapConfiguration {
    @Bean
    ProductPersistentPlanBootstrapRequestAdapter productPersistentPlanBootstrapRequestAdapter() {
        return new ProductPersistentPlanBootstrapRequestAdapter();
    }

    @Bean
    PersistentPlanBootstrapper persistentPlanBootstrapper(
            PlanBootstrapRepository repository) {
        return new DefaultPersistentPlanBootstrapper(
                new DeterministicTaskFrameFreezer(),
                new DeterministicInitialPlanFreezer(),
                new DeterministicInitialCheckpointFreezer(),
                repository);
    }

    @Bean
    FreshExecutionStarter freshExecutionStarter(
            LeaseRepository leaseRepository,
            ExecutionStartRepository executionStartRepository) {
        return new DefaultFreshExecutionStarter(
                new DeterministicFreshExecutionGate(),
                new DeterministicExecutionStartMaterializer(),
                leaseRepository,
                executionStartRepository);
    }
}
