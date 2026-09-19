package com.yanban.api.agent.reactplan;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.agent.v2.adapter.bootstrap.ProductWorkspaceIdDerivation;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.runtime.bootstrap.DefaultPersistentPlanBootstrapper;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared deterministic Plan shell for Project ReAct; not the retired model orchestration. */
@Configuration
public class ProjectPlanBootstrapConfiguration {
    @Bean
    ProductPlanIdDerivation productPlanIdDerivation() {
        return new ProductPlanIdDerivation();
    }

    @Bean
    ProductWorkspaceIdDerivation productWorkspaceIdDerivation() {
        return new ProductWorkspaceIdDerivation();
    }

    @Bean
    ProductPersistentPlanBootstrapRequestAdapter productPersistentPlanBootstrapRequestAdapter(
            ProductPlanIdDerivation planIds) {
        return new ProductPersistentPlanBootstrapRequestAdapter(planIds);
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

}
