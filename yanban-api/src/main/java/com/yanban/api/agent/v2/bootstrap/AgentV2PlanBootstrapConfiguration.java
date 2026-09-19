package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.DeterministicFreshExecutionGate;
import io.paperagent.v2.runtime.execution.start.DefaultFreshExecutionStarter;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import io.paperagent.v2.runtime.execution.activation.composition.DefaultStepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.composition.DefaultActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.materialization.DeterministicActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.DefaultStepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retained legacy execution wiring. Shared Project ReAct bootstrap lives in ProjectPlanBootstrapConfiguration.
 */
@Configuration
@org.springframework.context.annotation.Import(com.yanban.api.agent.reactplan.ProjectPlanBootstrapConfiguration.class)
public class AgentV2PlanBootstrapConfiguration {
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

    @Bean
    StepRecoverer stepRecoverer(
            StepRecoveryRepository stepRecoveryRepository,
            LeaseRepository leaseRepository) {
        return new DefaultStepRecoverer(
                stepRecoveryRepository,
                leaseRepository);
    }

    @Bean
    ActiveStepInterruptionComposer activeStepInterruptionComposer(
            StepInterruptionRepository stepInterruptionRepository) {
        return new DefaultActiveStepInterruptionComposer(
                new DeterministicActiveStepInterruptionMaterializer(),
                stepInterruptionRepository);
    }
}
