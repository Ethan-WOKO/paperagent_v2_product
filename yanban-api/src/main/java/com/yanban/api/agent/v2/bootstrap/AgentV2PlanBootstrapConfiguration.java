package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.agent.v2.adapter.bootstrap.ProductWorkspaceIdDerivation;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.runtime.bootstrap.DefaultPersistentPlanBootstrapper;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.DeterministicFreshExecutionGate;
import io.paperagent.v2.runtime.execution.start.DefaultFreshExecutionStarter;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import io.paperagent.v2.runtime.execution.activation.composition.DefaultStepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires deterministic V2 bootstrap and execution-start composition.
 */
@Configuration
public class AgentV2PlanBootstrapConfiguration {
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

    @Bean
    ExecutionStartRecoverer executionStartRecoverer(
            ExecutionStartRecoveryRepository recoveryRepository,
            LeaseRepository leaseRepository,
            ExecutionStartRepository executionStartRepository) {
        return new DefaultExecutionStartRecoverer(
                recoveryRepository,
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                leaseRepository,
                executionStartRepository);
    }

    @Bean
    StepActivationComposer stepActivationComposer(
            LeaseRepository leaseRepository,
            StepActivationRepository stepActivationRepository) {
        return new DefaultStepActivationComposer(
                new DeterministicCommittedStepActivationMaterializer(),
                leaseRepository,
                stepActivationRepository);
    }
}
