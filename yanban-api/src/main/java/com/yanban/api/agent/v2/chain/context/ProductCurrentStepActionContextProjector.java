package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Projects module 7 from formal Action/Effect/Receipt/Outcome authorities. */
@Component
public final class ProductCurrentStepActionContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS;
    private static final String VERSION = "product-current-step-action-v1";
    private static final String PAGINATION = "none-v1";
    private final ProductCurrentStepActionAuthority authority;

    public ProductCurrentStepActionContextProjector(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization,
            ProductChainCandidateMaterializationFailureRepositoryAdapter
                    candidateFailures) {
        authority = new ProductCurrentStepActionAuthority(
                foundations, workflow, intents, outcomes, finalization,
                candidateFailures);
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ProductCurrentStepActionFacts facts = authority.load(
                    request.buildingRevision());
            if (facts.initialPlannerWithoutActionAuthority()) {
                return ProductChainContextProjectionSupport.empty(
                        MODULE,
                        ProductCurrentStepActionProjectionValues.emptySource(),
                        ProductCurrentStepActionProjectionValues.emptyBoundary(
                                facts),
                        VERSION, PAGINATION,
                        Map.of("taskAuthorityHead", ChainContextValue.number(
                                        facts.taskEventCut()),
                                "taskRef", ChainContextValue.referencedText(
                                        facts.building().taskId(),
                                        facts.building().taskId())),
                        "actionSequence=0");
            }
            var values = ProductCurrentStepActionProjectionValues.create(
                    request.requiredFields(MODULE), facts);
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw ProductChainContextProjectionSupport.blocked(
                    MODULE, "action authority query or identity validation failed");
        }
    }
}
