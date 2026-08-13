package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.context.ChainContextVersionMatrix;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainContextSourceFactoryTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-08T00:00:00Z");

    private static final List<Class<?>> PROJECTOR_TYPES = List.of(
            ProductInstructionChainContextProjector.class,
            ProductConversationContextProjector.class,
            ProductProjectInputContextProjector.class,
            ProductTaskContractContextProjector.class,
            ProductPlanStepContractContextProjector.class,
            ProductTaskStepRuntimeContextProjector.class,
            ProductCurrentStepActionContextProjector.class,
            ProductWorkspaceCandidateContextProjector.class,
            ProductValidationPublishContextProjector.class,
            ProductReviewPendingContextProjector.class,
            ProductMemoryEvidenceContextProjector.class,
            ProductRuntimeRulesContextProjector.class,
            ProductModelInvocationContextProjector.class);

    @Test
    void constructorRequiresExactlyTheThirteenFixedSpringProjectors() {
        assertThat(ProductChainContextSourceFactory.class
                .getDeclaredConstructors()).singleElement()
                .satisfies(constructor -> assertThat(
                        constructor.getParameterTypes())
                        .containsExactlyElementsOf(PROJECTOR_TYPES));
        assertThat(PROJECTOR_TYPES).allSatisfy(projector ->
                assertThat(projector.isAnnotationPresent(Component.class))
                        .as("%s is a Spring component", projector.getName())
                        .isTrue());
    }

    @Test
    void sourceProjectsAllThirteenModulesInFrozenOrder() {
        List<ChainContextModule> projectorCalls = new ArrayList<>();
        ProductInstructionChainContextProjector instruction = projector(
                ProductInstructionChainContextProjector.class,
                ChainContextModule.USER_INSTRUCTION_CHAIN, projectorCalls);
        ProductConversationContextProjector conversation = projector(
                ProductConversationContextProjector.class,
                ChainContextModule.CONVERSATION_CONTEXT, projectorCalls);
        ProductProjectInputContextProjector project = projector(
                ProductProjectInputContextProjector.class,
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                projectorCalls);
        ProductTaskContractContextProjector task = projector(
                ProductTaskContractContextProjector.class,
                ChainContextModule.TASK_CONTRACT, projectorCalls);
        ProductPlanStepContractContextProjector plan = projector(
                ProductPlanStepContractContextProjector.class,
                ChainContextModule.PLAN_AND_STEP_CONTRACT, projectorCalls);
        ProductTaskStepRuntimeContextProjector runtime = projector(
                ProductTaskStepRuntimeContextProjector.class,
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE,
                projectorCalls);
        ProductCurrentStepActionContextProjector action = projector(
                ProductCurrentStepActionContextProjector.class,
                ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS,
                projectorCalls);
        ProductWorkspaceCandidateContextProjector workspace = projector(
                ProductWorkspaceCandidateContextProjector.class,
                ChainContextModule.WORKSPACE_AND_CANDIDATE, projectorCalls);
        ProductValidationPublishContextProjector validation = projector(
                ProductValidationPublishContextProjector.class,
                ChainContextModule.VALIDATION_AND_PUBLISH, projectorCalls);
        ProductReviewPendingContextProjector review = projector(
                ProductReviewPendingContextProjector.class,
                ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS,
                projectorCalls);
        ProductMemoryEvidenceContextProjector evidence = projector(
                ProductMemoryEvidenceContextProjector.class,
                ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                projectorCalls);
        ProductRuntimeRulesContextProjector rules = projector(
                ProductRuntimeRulesContextProjector.class,
                ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                projectorCalls);
        ProductModelInvocationContextProjector models = projector(
                ProductModelInvocationContextProjector.class,
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                projectorCalls);

        ProductChainContextSourceFactory factory =
                new ProductChainContextSourceFactory(
                        instruction, conversation, project, task, plan,
                        runtime, action, workspace, validation, review,
                        evidence, rules, models);

        var snapshots = factory.source().project(
                new ChainContextProjectionRequest(buildingRevision(),
                        100_000));

        assertThat(snapshots)
                .extracting(snapshot -> snapshot.module())
                .containsExactlyElementsOf(
                        ChainContextInputMatrix.orderedModules());
        assertThat(projectorCalls).containsExactlyElementsOf(
                ChainContextInputMatrix.orderedModules());
        assertThat(snapshots).hasSize(13)
                .allSatisfy(snapshot -> assertThat(
                        snapshot.projectionFieldNames())
                        .containsExactlyInAnyOrderElementsOf(
                                ChainContextInputMatrix.requiredProjectionFields(
                                        ChainRole.ANSWER,
                                        snapshot.module())));
    }

    private static <T extends ProductChainContextAuthorityReader> T projector(
            Class<T> type,
            ChainContextModule module,
            List<ChainContextModule> calls) {
        T projector = mock(type);
        when(projector.read(any(ChainContextProjectionRequest.class)))
                .thenAnswer(invocation -> {
                    calls.add(module);
                    return projection(module, invocation.getArgument(0));
                });
        return projector;
    }

    private static ProductChainContextAuthorityProjection projection(
            ChainContextModule module,
            ChainContextProjectionRequest request) {
        var version = ChainContextVersionMatrix.requirement(module);
        return new ProductChainContextAuthorityProjection(
                ChainContextModuleStatus.PRESENT,
                values(version.sourceVersionFields(), module, "source"),
                values(version.readBoundaryFields(), module, "boundary"),
                "product-projector-v1", "stable-id-v1",
                Map.of("pageSize", ChainContextValue.number(25)),
                values(request.requiredFields(module), module, "field"),
                null);
    }

    private static ContextRevisionRecord buildingRevision() {
        return new ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.ANSWER,
                ChainWorkState.DELIVERING, "TASK_OUTCOME",
                "instruction.1", "task-frame.1", "plan.1",
                "revision.1", 1L, "step.1", "activation.1",
                41L, "project-version.1", "workspace.1", null,
                null, null, null, null, "product-projectors-v1",
                "stable-id-v1", "chain-runtime-v1",
                ChainContextRevisionStatus.BUILDING, 0, null, null,
                null, null, null, CREATED_AT, null);
    }

    private static Map<String, ChainContextValue> values(
            List<String> names,
            ChainContextModule module,
            String group) {
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        for (String name : names) {
            values.put(name, ChainContextValue.referencedText(
                    group + "-value-" + name,
                    module.wireName() + ":" + group + ":" + name));
        }
        return Map.copyOf(values);
    }
}
