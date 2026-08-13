package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.effect.ProductChainCurrentAuthorityGate;
import com.yanban.api.agent.v2.chain.effect.ProductChainEffectAuthority;
import com.yanban.api.agent.v2.chain.effect.ProductChainWorkspaceCandidateAuthority;
import com.yanban.api.agent.v2.chain.effect.ProductChainWorkspaceChangeSource;
import com.yanban.api.agent.v2.chain.finalization.ProductChainFinalizationCoordinator;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanRevisionAuthoritySource;
import io.paperagent.v2.chain.ChainCandidateMaterializationFailureRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureRepository;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.finalization.ChainFinalizationAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductChainRecoveryConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ProductChainRecoveryConfiguration.class)
                    .withBean(ChainFoundationRepository.class,
                            () -> mock(ChainFoundationRepository.class))
                    .withBean(ChainContextRepository.class,
                            () -> mock(ChainContextRepository.class))
                    .withBean(ChainContextBuildFailureRepository.class,
                            () -> mock(ChainContextBuildFailureRepository.class))
                    .withBean(ChainCandidateMaterializationFailureRepository.class,
                            () -> mock(ChainCandidateMaterializationFailureRepository.class))
                    .withBean(ChainFinalizationRepository.class,
                            () -> mock(ChainFinalizationRepository.class))
                    .withBean(ChainModelRepository.class,
                            () -> mock(ChainModelRepository.class))
                    .withBean(ProductChainWorkflowRepositoryAdapter.class,
                            () -> mock(ProductChainWorkflowRepositoryAdapter.class))
                    .withBean(ProductChainStepAuthorityAdapter.class,
                            () -> mock(ProductChainStepAuthorityAdapter.class))
                    .withBean(ProductPlanRevisionAuthoritySource.class,
                            () -> mock(ProductPlanRevisionAuthoritySource.class))
                    .withBean(ProductPlanBootstrapRepositoryAdapter.class,
                            () -> mock(ProductPlanBootstrapRepositoryAdapter.class))
                    .withBean(ProductPlanBootstrapCodec.class,
                            () -> mock(ProductPlanBootstrapCodec.class))
                    .withBean(ProductChainPublishAuthoritySource.class,
                            () -> mock(ProductChainPublishAuthoritySource.class))
                    .withBean(ChainValidationBundleRepository.class,
                            () -> mock(ChainValidationBundleRepository.class))
                    .withBean(ChainValidationRepository.class,
                            () -> mock(ChainValidationRepository.class))
                    .withBean(ProjectChainPlannerProgression.class,
                            () -> mock(ProjectChainPlannerProgression.class))
                    .withBean(ProductChainExecutorProgression.class,
                            () -> mock(ProductChainExecutorProgression.class))
                    .withBean(StepRecoveryRepository.class,
                            () -> mock(StepRecoveryRepository.class))
                    .withBean(EffectIntentRepository.class,
                            () -> mock(EffectIntentRepository.class))
                    .withBean(EffectOutcomeRepository.class,
                            () -> mock(EffectOutcomeRepository.class))
                    .withBean(ChainFinalizationAuthorityPort.class,
                            () -> mock(ChainFinalizationAuthorityPort.class))
                    .withBean(ProductChainFinalizationCoordinator.class,
                            () -> mock(ProductChainFinalizationCoordinator.class))
                    .withBean(ProductChainEffectAuthority.class,
                            () -> mock(ProductChainEffectAuthority.class))
                    .withBean(ProductChainWorkspaceChangeSource.class,
                            () -> mock(ProductChainWorkspaceChangeSource.class))
                    .withBean(ProductChainWorkspaceCandidateAuthority.class,
                            () -> mock(ProductChainWorkspaceCandidateAuthority.class))
                    .withBean(ProductChainCurrentAuthorityGate.class,
                            () -> mock(ProductChainCurrentAuthorityGate.class))
                    .withBean(ProductChainRecoveryStageContinuation.class,
                            () -> mock(ProductChainRecoveryStageContinuation.class));

    @Test
    void wiresOneCompleteRecoveryBoundaryFromFormalProductAuthorities() {
        context.run(value -> {
            assertThat(value).hasNotFailed();
            assertThat(value).hasSingleBean(
                    ProductChainRetainedAuthoritySource.class);
            assertThat(value).hasSingleBean(ProductChainRecoverySource.class);
            assertThat(value).hasSingleBean(
                    ProductChainRecoveryStageAuthorityVerifier.class);
            assertThat(value).hasSingleBean(
                    ChainCompositeTransitionRuntime.class);
            assertThat(value).hasSingleBean(
                    ProductChainFinalizationRecoverySource.class);
            assertThat(value).hasSingleBean(
                    ProductChainMechanicalFinalizationPort.class);
            assertThat(value).hasSingleBean(ChainEffectRuntime.class);
            assertThat(value).hasSingleBean(ProductChainNextRoleSelector.class);
            assertThat(value).hasSingleBean(
                    ProductChainCompositeTransitionRecovery.class);
            assertThat(value).hasSingleBean(
                    ProductChainRecoveryCoordinator.class);
        });
    }

    @Test
    void mechanicalFinalizationDelegatesToTheExistingFormalOwner() {
        ProductChainFinalizationCoordinator finalization =
                mock(ProductChainFinalizationCoordinator.class);
        ProductChainMechanicalFinalizationPort port =
                new ProductChainRecoveryConfiguration()
                        .productChainMechanicalFinalizationPort(finalization);
        Instant committedAt = Instant.parse("2026-08-09T00:00:00Z");

        port.finalizeReadiness("readiness-1", committedAt);

        verify(finalization).finalizeReadiness("readiness-1", committedAt);
    }
}
