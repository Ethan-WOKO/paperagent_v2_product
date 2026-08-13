package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
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

/** Projects only already-retained formal evidence; it never retrieves. */
@Component
public final class ProductMemoryEvidenceContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE;
    private static final String VERSION = "product-memory-evidence-v1";
    private static final String PAGINATION = "none-v1";
    private final ProductMemoryEvidenceAuthority authority;

    public ProductMemoryEvidenceContextProjector(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization,
            ObjectMapper json) {
        authority = new ProductMemoryEvidenceAuthority(
                foundations, workflow, intents, outcomes, finalization, json);
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            var facts = authority.load(request.buildingRevision());
            if (facts.empty()) return empty(facts);
            var values = ProductMemoryEvidenceProjectionValues.create(
                    request.requiredFields(MODULE), facts);
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw blocked("formal evidence authority query failed");
        }
    }

    private static ProductChainContextAuthorityProjection empty(
            ProductMemoryEvidenceFacts facts) {
        String digest = ProductChainContractProjectionCodec.sha256("[]");
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("frozenCatalogIdentityAndDigest",
                                ChainContextValue.object(Map.of(
                                        "catalog", ChainContextValue.text("EMPTY"),
                                        "digest", ChainContextValue.text(digest))),
                        "exactEvidenceRefVector", ChainContextValue.array(
                                java.util.List.of())),
                Map.of("taskCatalogCut", ChainContextValue.number(0)),
                VERSION, PAGINATION,
                Map.of("taskRef", ChainContextValue.referencedText(
                                facts.building().taskId(),
                                facts.building().taskId()),
                        "taskAuthorityHead", ChainContextValue.number(
                                facts.taskEventCut())),
                "emptyCatalogDigestAndObservationCuts");
    }

    private static ChainContextException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
