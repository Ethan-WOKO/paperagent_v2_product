package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.effect.ProductChainCurrentAuthorityGate;
import com.yanban.api.agent.v2.chain.effect.ProductChainEffectAuthority;
import com.yanban.api.agent.v2.chain.effect.ProductChainWorkspaceCandidateAuthority;
import com.yanban.api.agent.v2.chain.effect.ProductChainWorkspaceChangeSource;
import com.yanban.api.agent.v2.chain.finalization.ProductChainFinalizationCoordinator;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.progression.ProductChainNormalSuccessorAuthority;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureRepository;
import io.paperagent.v2.chain.ChainCandidateMaterializationFailureRepository;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainWorkspaceCandidateWriter;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.finalization.ChainFinalizationAuthorityPort;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Product composition for the durable chain recovery boundary. */
@Configuration
public class ProductChainRecoveryConfiguration {
    @Bean
    ProductChainRetainedAuthoritySource productChainRetainedAuthoritySource(
            StepRecoveryRepository steps,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainWorkflowRepository workflow,
            ChainFinalizationAuthorityPort finalization,
            ProductChainPublishAuthoritySource publishes) {
        return new ProductChainRetainedAuthoritySource(
                steps, intents, outcomes, workflow, finalization, publishes);
    }

    @Bean
    ProductChainRecoverySource productChainRecoverySource(
            ChainFoundationRepository foundations,
            ChainContextRepository contexts,
            ChainContextBuildFailureRepository contextBuildFailures,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainCandidateMaterializationFailureRepository candidateFailures,
            ChainFinalizationRepository finalization,
            ProductChainRetainedAuthoritySource retainedAuthorities) {
        return new ProductChainRecoverySource(
                foundations, contexts, contextBuildFailures, models,
                workflow, candidateFailures, finalization,
                retainedAuthorities);
    }

    @Bean
    ProductChainRecoveryStageAuthorityVerifier
            productChainRecoveryStageAuthorityVerifier(
                    ChainFoundationRepository foundations,
                    ChainContextRepository contexts,
                    ChainWorkflowRepository workflow,
                    ChainFinalizationRepository finalization,
                    ChainModelRepository models,
                    com.yanban.api.agent.v2.persistence
                            .ProductChainStepAuthorityAdapter steps,
                    ProductPlanBootstrapRepositoryAdapter bootstraps,
                    ProductPlanBootstrapCodec bootstrapCodec,
                    com.yanban.api.agent.v2.persistence
                            .ProductPlanRevisionAuthoritySource revisionAuthorities,
                    ProductChainPublishAuthoritySource publishes,
                    io.paperagent.v2.chain.ChainValidationBundleRepository
                            validationBundles,
                    io.paperagent.v2.chain.ChainValidationRepository
                            validations) {
        return new ProductChainRecoveryStageAuthorityVerifier(
                foundations, contexts, workflow, finalization, models, steps,
                bootstraps, bootstrapCodec, revisionAuthorities, publishes, validationBundles,
                validations);
    }

    @Bean
    ChainCompositeTransitionRuntime productChainRecoveryTransitionRuntime(
            ChainWorkflowRepository workflow,
            ChainTransitionWriter writer,
            ProductChainRecoveryStageAuthorityVerifier verifier) {
        return new ChainCompositeTransitionRuntime(
                workflow, writer, verifier);
    }

    @Bean
    ProductChainNormalSuccessorAuthority productChainNormalSuccessorAuthority(
            ChainFoundationRepository foundations,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainCompositeTransitionRuntime transitions,
            ProductChainRecoveryStageAuthorityVerifier verifier,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor) {
        return new ProductChainNormalSuccessorAuthority(
                foundations, models, workflow, transitions, verifier,
                planner, executor, Clock.systemUTC());
    }

    @Bean
    ProductChainFinalizationRecoverySource
            productChainFinalizationRecoverySource(
                    ChainFoundationRepository foundations,
                    ChainWorkflowRepository workflow,
                    ChainFinalizationRepository finalization,
                    ProductChainPublishAuthoritySource publishes,
                    ProductChainRecoveryStageAuthorityVerifier readiness) {
        return new ProductChainFinalizationRecoverySource(
                foundations, workflow, finalization, publishes, readiness);
    }

    @Bean
    ProductChainMechanicalFinalizationPort
            productChainMechanicalFinalizationPort(
                    ProductChainFinalizationCoordinator finalization) {
        return finalization::finalizeReadiness;
    }

    @Bean
    ChainEffectRuntime productChainRecoveryEffectRuntime(
            ChainWorkflowRepository workflow,
            ChainModelRepository models,
            ProductChainEffectAuthority effects,
            ProductChainWorkspaceChangeSource workspaceChanges,
            ProductChainWorkspaceCandidateAuthority candidates,
            ChainWorkspaceCandidateWriter candidateWriter,
            ProductChainCurrentAuthorityGate currentGate) {
        return new ChainEffectRuntime(
                workflow, models, effects, workspaceChanges, candidates,
                candidateWriter, currentGate);
    }

    @Bean
    ProductChainNextRoleSelector productChainNextRoleSelector() {
        return new ProductChainNextRoleSelector();
    }

    @Bean
    ProductChainCompositeTransitionRecovery
            productChainCompositeTransitionRecovery(
                    ChainWorkflowRepository workflow,
                    ChainCompositeTransitionRuntime transitions,
                    ProductChainRecoveryStageContinuation continuation,
                    ProductChainFinalizationRecoverySource finalization,
                    ProductChainMechanicalFinalizationPort mechanicalFinalization) {
        return new ProductChainCompositeTransitionRecovery(
                workflow, transitions, continuation, finalization,
                mechanicalFinalization, Clock.systemUTC());
    }

    @Bean
    ProductChainRecoveryCoordinator productChainRecoveryCoordinator(
            ProductChainRecoverySource source,
            ProductChainCompositeTransitionRecovery transitions,
            ChainWorkflowRepository workflow,
            ChainEffectRuntime effects,
            ProductChainNextRoleSelector roles,
            ProductChainMechanicalFinalizationPort finalization) {
        return new ProductChainRecoveryCoordinator(
                source, transitions, workflow, effects, roles, finalization);
    }
}
