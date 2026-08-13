package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import org.springframework.stereotype.Component;

import java.util.List;

/** Pure assembly of the thirteen product authority readers. */
@Component
public final class ProductChainContextSourceFactory {
    private final ProductChainContextSourceAdapter source;

    public ProductChainContextSourceFactory(
            ProductInstructionChainContextProjector instruction,
            ProductConversationContextProjector conversation,
            ProductProjectInputContextProjector project,
            ProductTaskContractContextProjector task,
            ProductPlanStepContractContextProjector plan,
            ProductTaskStepRuntimeContextProjector runtime,
            ProductCurrentStepActionContextProjector actions,
            ProductWorkspaceCandidateContextProjector candidate,
            ProductValidationPublishContextProjector validation,
            ProductReviewPendingContextProjector review,
            ProductMemoryEvidenceContextProjector evidence,
            ProductRuntimeRulesContextProjector rules,
            ProductModelInvocationContextProjector models) {
        source = new ProductChainContextSourceAdapter(List.of(
                module(ChainContextModule.USER_INSTRUCTION_CHAIN,
                        instruction),
                module(ChainContextModule.CONVERSATION_CONTEXT,
                        conversation),
                module(ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                        project),
                module(ChainContextModule.TASK_CONTRACT, task),
                module(ChainContextModule.PLAN_AND_STEP_CONTRACT, plan),
                module(ChainContextModule.TASK_AND_STEP_RUNTIME_STATE,
                        runtime),
                module(ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS,
                        actions),
                module(ChainContextModule.WORKSPACE_AND_CANDIDATE,
                        candidate),
                module(ChainContextModule.VALIDATION_AND_PUBLISH,
                        validation),
                module(ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS,
                        review),
                module(ChainContextModule
                                .MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                        evidence),
                module(ChainContextModule
                                .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                        rules),
                module(ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                        models)));
    }

    public ProductChainContextSourceAdapter source() {
        return source;
    }

    private static ProductChainContextModuleSource module(
            ChainContextModule module,
            ProductChainContextAuthorityReader reader) {
        return new ProductChainContextModuleSource(module, reader);
    }
}
