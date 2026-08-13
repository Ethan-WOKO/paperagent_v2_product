package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Projects only model invocations belonging to the exact Context lineage. */
@Component
public final class ProductModelInvocationContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS;
    private static final String VERSION =
            "product-model-invocation-context-v1";
    private static final String PAGINATION = "none-v1";
    private final ProductModelInvocationAuthorityCutReader authority;

    public ProductModelInvocationContextProjector(
            ProductChainModelRepositoryAdapter models,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainFinalizationRepositoryAdapter finalization) {
        authority = new ProductModelInvocationAuthorityCutReader(
                Objects.requireNonNull(models, "models"),
                Objects.requireNonNull(contexts, "contexts"),
                Objects.requireNonNull(finalization, "finalization"));
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        try {
            Objects.requireNonNull(request, "request");
            var building = request.buildingRevision();
            var cut = authority.read(building);
            if (cut.invocations().isEmpty()) return empty(building, cut);
            var values = ProductModelInvocationProjectionValues.create(
                    building, request.requiredFields(MODULE), cut.lineage(),
                    cut.invocations(), cut.outcome(), cut.deliveries());
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException failure) {
            throw blocked("model authority read failed");
        }
    }

    private static ProductChainContextAuthorityProjection empty(
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .ContextRevisionRecord building,
            ProductModelInvocationAuthorityCutReader.Cut cut) {
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("priorInvocationCut", ChainContextValue.number(0),
                        "proposalStateCut", ChainContextValue.object(Map.of()),
                        "currentRevisionAndCallReason",
                        ChainContextValue.object(Map.of(
                                "contextRevisionRef",
                                ref(building.contextRevisionId()),
                                "callReason", ChainContextValue.text(
                                        building.callReason())))),
                Map.of("priorInvocationOrdinal", ChainContextValue.number(0)),
                VERSION, PAGINATION,
                Map.of("currentContextRevisionRef",
                                ref(building.contextRevisionId()),
                        "lineageContextRefs", ChainContextValue.array(
                                cut.lineage().stream().map(value ->
                                        (ChainContextValue) ref(value)).toList())),
                "priorInvocationOrdinal=0");
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
