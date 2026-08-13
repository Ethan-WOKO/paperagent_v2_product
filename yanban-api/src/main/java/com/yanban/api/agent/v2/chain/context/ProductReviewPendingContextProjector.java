package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Projects module 10 from formal review, gap and transition authorities. */
@Component
public final class ProductReviewPendingContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS;
    private static final String VERSION = "product-review-pending-v1";
    private static final String PAGINATION = "none-v1";
    private final ProductReviewPendingAuthority authority;

    public ProductReviewPendingContextProjector(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow) {
        authority = new ProductReviewPendingAuthority(foundations, workflow);
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ProductReviewPendingFacts facts = authority.load(
                    request.buildingRevision());
            if (facts.hasNoFacts()) {
                return ProductChainContextProjectionSupport.empty(
                        MODULE,
                        ProductReviewPendingProjectionValues.emptySource(),
                        ProductReviewPendingProjectionValues.boundary(facts),
                        VERSION, PAGINATION,
                        Map.of("taskRef", ChainContextValue.referencedText(
                                        facts.building().taskId(),
                                        facts.building().taskId()),
                                "taskAuthorityHead", ChainContextValue.number(
                                        facts.taskEventCut())),
                        "allCuts=0");
            }
            var values = ProductReviewPendingProjectionValues.create(
                    request.requiredFields(MODULE), facts);
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw ProductChainContextProjectionSupport.blocked(
                    MODULE, "review authority query or identity validation failed");
        }
    }
}
