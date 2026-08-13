package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Projects module 6 from formal task, Step and result authorities. */
@Component
public final class ProductTaskStepRuntimeContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.TASK_AND_STEP_RUNTIME_STATE;
    private static final String VERSION = "product-task-step-runtime-v2";
    private static final String PAGINATION = "none-v1";
    private final ProductTaskStepRuntimeAuthority authority;

    public ProductTaskStepRuntimeContextProjector(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainStepAuthorityPort steps,
            ChainFinalizationRepository finalization) {
        authority = new ProductTaskStepRuntimeAuthority(
                foundations, workflow, steps, finalization);
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ProductTaskStepRuntimeFacts facts = authority.load(
                    request.buildingRevision());
            if (ProductDirectAnswerContextAuthority.isDirectAnswer(
                    facts.building())) {
                return presentDirectAnswer(request, facts);
            }
            if (facts.hasNoRuntimeFacts()) return empty(request, facts);
            var values = ProductTaskStepRuntimeProjectionValues.create(
                    request.requiredFields(MODULE), facts);
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw blocked("runtime authority query or identity validation failed");
        }
    }

    private ProductChainContextAuthorityProjection presentDirectAnswer(
            ChainContextProjectionRequest request,
            ProductTaskStepRuntimeFacts facts) {
        var route = Objects.requireNonNull(facts.route(), "DIRECT route");
        Map<String, ChainContextValue> fields = new java.util.LinkedHashMap<>();
        for (String field : request.requiredFields(MODULE)) {
            fields.put(field, switch (field) {
                case "runtime.taskOutcome", "runtime.deliveryRecord" ->
                        ChainContextValue.nil();
                case "runtime.acceptedResultTerminalProjection" ->
                        ChainContextValue.array(java.util.List.of());
                case "runtime.answerPayloadTemplate" ->
                        ProductTaskStepRuntimeValueCodec.INSTANCE
                                .directAnswerPayloadTemplate(route);
                case "foundation.stateHeader" ->
                        ProductTaskStepRuntimeValueCodec.INSTANCE
                                .directStateHeader(facts);
                default -> throw blocked(
                        "unsupported DIRECT runtime field: " + field);
            });
        }
        long cut = facts.taskEventCut();
        return ProductChainContextProjectionSupport.present(
                MODULE,
                Map.of("chainEventCut", ChainContextValue.number(cut),
                        "stepRecoveryHead", ChainContextValue.text("NONE"),
                        "acceptedResultAndApplicabilityCut",
                        ChainContextValue.text("NONE"),
                        "outcomeId", ChainContextValue.text("NONE")),
                Map.of("taskEventSequence", ChainContextValue.number(cut),
                        "checkpointHead", ChainContextValue.number(0)),
                VERSION, PAGINATION,
                Map.of("taskRef", ChainContextValue.referencedText(
                                facts.building().taskId(),
                                facts.building().taskId()),
                        "routeDecisionRef", ChainContextValue.referencedText(
                                route.routeDecisionId(),
                                route.routeDecisionId()),
                        "role", ChainContextValue.text("ANSWER")),
                Map.copyOf(fields),
                request.requiredFields(MODULE).toArray(String[]::new));
    }

    private ProductChainContextAuthorityProjection empty(
            ChainContextProjectionRequest request,
            ProductTaskStepRuntimeFacts facts) {
        if (facts.building().role() != ChainRole.PLANNER
                || facts.building().planId() != null
                || facts.building().stepId() != null) {
            throw blocked("only the initial Planner may observe empty runtime state");
        }
        String digest = ProductChainContractProjectionCodec.sha256(
                "runtime=NONE\0" + facts.building().taskId() + "\0"
                        + facts.taskEventCut());
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("chainEventCut", ChainContextValue.number(0),
                        "stepRecoveryHead", ChainContextValue.text("NONE"),
                        "acceptedResultAndApplicabilityCut",
                        ChainContextValue.text("NONE"),
                        "outcomeId", ChainContextValue.text("NONE")),
                Map.of("taskEventSequence", ChainContextValue.number(
                                facts.taskEventCut()),
                        "checkpointHead", ChainContextValue.number(0)),
                VERSION, PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest),
                        "taskAuthorityHead", ChainContextValue.number(
                                facts.taskEventCut())),
                "allCuts=0");
    }

    private static ChainContextException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
