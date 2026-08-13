package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps V2 Step events to their separate chain-authority event domain. */
final class ProductPlanAuthorityCutReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.PLAN_AND_STEP_CONTRACT;
    private static final String STEP_EVENT = "STEP_EVENT";
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ChainFoundationRepository foundations;

    ProductPlanAuthorityCutReader(
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainFoundationRepository foundations) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
    }

    long emptyAuthorityCut(String taskId) {
        return foundations.highestAuthorityEventSequence(taskId);
    }

    long chainAuthorityCut(
            String taskId, PlanBindingRecord binding,
            List<ChainStepAuthorityPort.StepEvent> visibleEvents) {
        long readCut = foundations.highestAuthorityEventSequence(taskId);
        Map<String, Long> sequences = new HashMap<>();
        foundations.findAuthorityEvents(taskId, readCut).forEach(value -> {
            if (!value.taskId().equals(taskId)
                    || value.eventSequence() > readCut
                    || sequences.putIfAbsent(value.eventId(),
                    value.eventSequence()) != null) {
                throw blocked("chain authority event cut is inconsistent");
            }
        });
        long result = exactSequence(sequences, binding.eventId(),
                "Plan binding");
        for (var event : visibleEvents) {
            var stages = workflow.findTransitionStages(
                    event.command().transitionId()).stream()
                    .filter(stage -> stage.taskId().equals(taskId))
                    .filter(stage -> STEP_EVENT.equals(
                            stage.successorAuthorityType()))
                    .filter(stage -> event.command().eventId().equals(
                            stage.successorAuthorityRef()))
                    .toList();
            if (stages.size() != 1) {
                throw blocked("Step event requires one formal transition stage");
            }
            result = Math.max(result, exactSequence(sequences,
                    stages.get(0).eventId(), "Step transition stage"));
        }
        return result;
    }

    private static long exactSequence(
            Map<String, Long> sequences, String eventId, String authority) {
        Long value = sequences.get(eventId);
        if (value == null) {
            throw blocked(authority + " is outside the chain authority cut");
        }
        return value;
    }

    private static ChainContextException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
