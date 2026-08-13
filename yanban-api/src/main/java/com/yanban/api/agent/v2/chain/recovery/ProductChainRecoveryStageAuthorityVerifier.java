package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductPlanRevisionAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Dispatches persisted-authority verification to one verifier per transition. */
public final class ProductChainRecoveryStageAuthorityVerifier
        implements ChainCompositeTransitionRuntime.StageAuthorityVerifier,
        ProductChainReadinessAuthority {
    private final ProductChainRecoveryAuthorityLookup authorities;
    private final ProductChainReadinessStageVerifier readiness;
    private final Map<ChainTransitionType, ProductChainTransitionStageVerifier>
            verifiers;

    public ProductChainRecoveryStageAuthorityVerifier(
            ChainFoundationRepository foundations,
            ChainContextRepository contexts,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ChainModelRepository models,
            ProductChainStepAuthorityAdapter steps,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductPlanRevisionAuthoritySource revisionAuthorities,
            ProductChainPublishAuthoritySource publishes,
            ChainValidationBundleRepository validationBundles,
            ChainValidationRepository validations) {
        this.authorities = new ProductChainRecoveryAuthorityLookup(
                foundations, contexts, workflow, finalization, models, steps,
                bootstraps, bootstrapCodec, revisionAuthorities, publishes, validationBundles,
                validations);
        EnumMap<ChainTransitionType, ProductChainTransitionStageVerifier>
                values = new EnumMap<>(ChainTransitionType.class);
        values.put(ChainTransitionType.GAP_RESOLUTION,
                new ProductChainGapStageVerifier(authorities));
        values.put(ChainTransitionType.ACCEPT_STEP,
                new ProductChainAcceptStepStageVerifier(authorities));
        values.put(ChainTransitionType.PLAN_CHANGE,
                new ProductChainPlanChangeStageVerifier(authorities));
        this.readiness = new ProductChainReadinessStageVerifier(authorities);
        values.put(ChainTransitionType.FINAL_STEP_READINESS,
                readiness);
        values.put(ChainTransitionType.FINALIZATION,
                new ProductChainFinalizationStageVerifier(
                        authorities, this));
        this.verifiers = Map.copyOf(values);
    }

    @Override
    public ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainCompositeTransitionRuntime.StageAuthorityQuery query) {
        Objects.requireNonNull(query, "query");
        authorities.verifyStoredTransition(query.transition(), query.stage());
        ProductChainTransitionStageVerifier verifier = verifiers.get(
                query.transition().transitionType());
        if (verifier == null) {
            throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unsupported transition type");
        }
        return verifier.verify(query.transition(), query.stage());
    }

    @Override
    public void requireExact(
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .FinalizationReadinessRecord value) {
        Objects.requireNonNull(value, "readiness");
        var transition = authorities.workflow().findTransition(
                        value.transitionId())
                .filter(item -> item.taskId().equals(value.taskId())
                        && item.transitionType()
                        == ChainTransitionType.FINAL_STEP_READINESS)
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "readiness transition is missing"));
        var stage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                item -> item.stageCode()
                        == io.paperagent.v2.chain.ChainTransitionStage
                        .READINESS_COMMITTED
                        && "FINALIZATION_READINESS".equals(
                        item.successorAuthorityType())
                        && value.readinessId().equals(
                        item.successorAuthorityRef()),
                "readiness committed stage");
        authorities.verifyStoredTransition(transition, stage);
        readiness.verify(transition, stage);
    }
}
