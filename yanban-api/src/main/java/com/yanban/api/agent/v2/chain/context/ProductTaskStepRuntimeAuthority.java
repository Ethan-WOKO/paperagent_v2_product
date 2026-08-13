package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskAuthorityFact;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads and identity-checks the exact formal authority cut for module 6. */
final class ProductTaskStepRuntimeAuthority {
    private final ChainFoundationRepository foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ChainStepAuthorityPort steps;
    private final ChainFinalizationRepository finalization;

    ProductTaskStepRuntimeAuthority(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainStepAuthorityPort steps,
            ChainFinalizationRepository finalization) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
    }

    ProductTaskStepRuntimeFacts load(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        boolean directAnswer = ProductDirectAnswerContextAuthority
                .isDirectAnswer(building);
        if (directAnswer) {
            ProductDirectAnswerContextAuthority.require(building, workflow);
        }
        foundations.findTask(building.taskId())
                .orElseThrow(() -> blocked("task is missing"));
        long eventCut = foundations.highestAuthorityEventSequence(
                building.taskId());
        Map<String, Long> sequences = authoritySequences(
                building.taskId(), eventCut);
        var routes = cut(building.taskId(),
                workflow.findRouteDecisions(building.taskId()), sequences);
        var bindings = cut(building.taskId(),
                workflow.findPlanBindings(building.taskId()), sequences);
        var candidates = cut(building.taskId(),
                workflow.findCandidateStepResults(building.taskId()), sequences);
        var reviews = cut(building.taskId(),
                workflow.findReviewDecisions(building.taskId()), sequences);
        var accepted = cut(building.taskId(),
                workflow.findAcceptedResults(building.taskId()), sequences);
        var applicability = cut(building.taskId(),
                workflow.findApplicabilityDecisions(building.taskId()), sequences);
        var outcome = finalization.findTaskOutcome(building.taskId()).orElse(null);
        if (outcome != null) requireVisible(
                outcome, building.taskId(), sequences);
        var deliveries = cut(building.taskId(),
                finalization.findDeliveries(building.taskId()), sequences);
        var binding = exactBinding(building, bindings);
        var route = exactRoute(building, binding, routes, sequences);
        var plan = exactPlan(building, binding);
        var stepEvents = plan == null ? List.<ChainStepAuthorityPort.StepEvent>of()
                : formalStepEvents(building, plan, sequences);
        verifyCurrentIdentity(building, plan, stepEvents);
        if (!directAnswer) verifyTerminalIdentity(building, outcome);
        var delivery = exactDelivery(outcome, deliveries, sequences);
        return new ProductTaskStepRuntimeFacts(
                building, eventCut, sequences, route, binding, plan,
                stepEvents, candidates, reviews, accepted, applicability,
                outcome, delivery);
    }

    private Map<String, Long> authoritySequences(String taskId, long cut) {
        Map<String, Long> result = new HashMap<>();
        long previous = 0;
        for (var event : foundations.findAuthorityEvents(taskId, cut)) {
            if (!taskId.equals(event.taskId())
                    || event.eventSequence() <= previous
                    || event.eventSequence() > cut
                    || result.put(event.eventId(), event.eventSequence()) != null) {
                throw blocked("task authority event prefix is inconsistent");
            }
            previous = event.eventSequence();
        }
        return Map.copyOf(result);
    }

    private static <T extends Record & TaskAuthorityFact> List<T> cut(
            String taskId, List<T> values, Map<String, Long> sequences) {
        List<T> visible = new ArrayList<>();
        for (T value : values) {
            if (!taskId.equals(value.taskId())) {
                throw blocked("runtime authority crosses task identity");
            }
            requireVisible(value, taskId, sequences);
            visible.add(value);
        }
        return List.copyOf(visible);
    }

    private static void requireVisible(
            TaskAuthorityFact value, String taskId, Map<String, Long> sequences) {
        if (!taskId.equals(value.taskId())) {
            throw blocked("terminal authority crosses task identity");
        }
        if (!sequences.containsKey(value.eventId())) {
            throw blocked("runtime authority has no formal task event");
        }
    }

    private static ChainPersistenceRecords.PlanBindingRecord exactBinding(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<ChainPersistenceRecords.PlanBindingRecord> values) {
        if (building.planId() == null) return null;
        var exact = values.stream()
                .filter(value -> value.planId().equals(building.planId()))
                .filter(value -> value.planRevisionId().equals(
                        building.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == building.planRevisionNumber())
                .filter(value -> value.taskFrameId().equals(
                        building.taskFrameId()))
                .filter(value -> value.instructionId().equals(
                        building.instructionId())).toList();
        if (exact.size() != 1) throw blocked("Plan runtime binding is not exact");
        return exact.get(0);
    }

    private static ChainPersistenceRecords.RouteDecisionRecord exactRoute(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.PlanBindingRecord binding,
            List<ChainPersistenceRecords.RouteDecisionRecord> values,
            Map<String, Long> sequences) {
        var exact = values.stream().filter(value -> binding == null
                        ? value.instructionId().equals(building.instructionId())
                        : value.routeDecisionId().equals(binding.routeDecisionId()))
                .sorted(Comparator.comparingLong(
                        value -> sequences.get(value.eventId()))).toList();
        if (binding != null && exact.size() != 1) {
            throw blocked("Plan route authority is not exact");
        }
        return exact.isEmpty() ? null : exact.get(exact.size() - 1);
    }

    private ChainStepAuthorityPort.PlanSnapshot exactPlan(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        if (binding == null) return null;
        var plan = steps.findPlan(building.taskId(), building.planRevisionId())
                .orElseThrow(() -> blocked("stable Plan runtime is missing"));
        if (!plan.taskId().equals(building.taskId())
                || !plan.taskFrameId().equals(building.taskFrameId())
                || !plan.planId().equals(building.planId())
                || !plan.planRevisionId().equals(building.planRevisionId())
                || !plan.targetInstructionVersionId().equals(
                building.instructionId())) {
            throw blocked("stable Plan runtime identity mismatches ContextRevision");
        }
        return plan;
    }

    private List<ChainStepAuthorityPort.StepEvent> formalStepEvents(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainStepAuthorityPort.PlanSnapshot plan,
            Map<String, Long> sequences) {
        List<ChainStepAuthorityPort.StepEvent> result = new ArrayList<>();
        for (var event : steps.findStepEvents(
                building.taskId(), building.planRevisionId())) {
            var command = event.command();
            if (!command.taskId().equals(building.taskId())
                    || !command.planRevisionId().equals(plan.planRevisionId())) {
                throw blocked("stable Step event crosses frozen Plan identity");
            }
            long formal = workflow.findTransitionStages(command.transitionId())
                    .stream().filter(stage -> building.taskId().equals(
                            stage.taskId()))
                    .filter(stage -> "STEP_EVENT".equals(
                            stage.successorAuthorityType()))
                    .filter(stage -> command.eventId().equals(
                            stage.successorAuthorityRef()))
                    .filter(stage -> sequences.containsKey(stage.eventId()))
                    .count();
            if (formal != 1) {
                throw blocked("Step event requires one exact formal stage");
            }
            result.add(event);
        }
        return result.stream().sorted(Comparator.comparingLong(
                ChainStepAuthorityPort.StepEvent::authoritySequence)).toList();
    }

    private static void verifyCurrentIdentity(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainStepAuthorityPort.PlanSnapshot plan,
            List<ChainStepAuthorityPort.StepEvent> events) {
        if (building.stepId() == null) {
            if (building.role() == ChainRole.EXECUTOR
                    || building.role() == ChainRole.REFLECTOR) {
                throw blocked("active runtime role requires a Step identity");
            }
            return;
        }
        long definitions = plan == null ? 0 : plan.steps().stream()
                .filter(value -> value.stepId().equals(building.stepId())).count();
        long activations = events.stream().filter(value -> value.command()
                        .eventKind() == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .filter(value -> value.command().stepId().equals(building.stepId()))
                .filter(value -> value.command().activationEventId().equals(
                        building.activationEventId())).count();
        if (definitions != 1 || activations != 1) {
            throw blocked("current Step activation is not exact");
        }
    }

    private static void verifyTerminalIdentity(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        if (building.role() != ChainRole.ANSWER) return;
        if (outcome == null || !outcome.instructionId().equals(
                building.instructionId())
                || !Objects.equals(outcome.taskFrameId(), building.taskFrameId())
                || !Objects.equals(outcome.finalPlanId(), building.planId())
                || !Objects.equals(outcome.finalPlanRevisionId(),
                building.planRevisionId())) {
            throw blocked("Answer TaskOutcome identity is missing or inconsistent");
        }
    }

    private static ChainPersistenceRecords.DeliveryRecord exactDelivery(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            List<ChainPersistenceRecords.DeliveryRecord> values,
            Map<String, Long> sequences) {
        if (outcome == null) return null;
        return values.stream().filter(value -> outcome.outcomeId().equals(
                        value.taskOutcomeId()))
                .max(Comparator.comparingLong(
                        value -> sequences.get(value.eventId()))).orElse(null);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                io.paperagent.v2.chain.ChainContextModule
                        .TASK_AND_STEP_RUNTIME_STATE, reason);
    }
}
