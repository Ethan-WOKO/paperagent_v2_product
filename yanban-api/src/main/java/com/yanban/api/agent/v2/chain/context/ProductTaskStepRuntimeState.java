package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure validation and Step-state derivation for a formal runtime cut. */
final class ProductTaskStepRuntimeState {
    private ProductTaskStepRuntimeState() {
    }

    static State derive(ProductTaskStepRuntimeFacts facts) {
        ProductTaskStepRuntimeValidator.validate(facts);
        List<StepView> steps = deriveSteps(facts);
        StepView current = current(facts, steps);
        var candidate = currentCandidate(facts);
        if (facts.building().role() == ChainRole.REFLECTOR
                && candidate == null
                && !reviewsFormalBlock(facts.building().callReason())) {
            throw blocked("Reflector requires a formal CandidateStepResult");
        }
        if (current != null && (facts.building().role() == ChainRole.EXECUTOR
                || facts.building().role() == ChainRole.REFLECTOR)
                && current.status() != ChainStepStatus.ACTIVE
                && current.status() != ChainStepStatus.AWAITING_REVIEW) {
            throw blocked("active runtime role references a terminal Step");
        }
        var direct = acceptedForSteps(facts, current == null ? Set.of()
                : current.definition().prerequisiteStepIds());
        var affected = acceptedForSteps(facts, affectedSteps(facts, current));
        return new State(steps, current, candidate, direct, affected,
                predecessorAccepted(facts));
    }

    private static boolean reviewsFormalBlock(String callReason) {
        return Set.of(
                "STEP_BLOCKED_REVIEW",
                "MODEL_CALL_FAILED_REVIEW",
                "CONTEXT_BUILD_FAILURE_REVIEW",
                "ACTION_FAILURE_REVIEW").contains(callReason);
    }

    private static List<StepView> deriveSteps(ProductTaskStepRuntimeFacts facts) {
        if (facts.plan() == null) return List.of();
        var definitions = facts.plan().steps().stream()
                .sorted(Comparator.comparingInt(
                        ChainStepAuthorityPort.StepDefinition::stableOrder))
                .toList();
        Set<String> completed = facts.stepEvents().stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                .map(value -> value.command().stepId())
                .collect(java.util.stream.Collectors.toSet());
        List<StepView> result = new ArrayList<>();
        for (var definition : definitions) {
            var own = facts.stepEvents().stream()
                    .filter(value -> value.command().stepId().equals(
                            definition.stepId())).toList();
            var latest = own.isEmpty() ? null : own.get(own.size() - 1);
            ChainStepStatus status;
            String activation = null;
            if (latest == null) {
                status = completed.containsAll(definition.prerequisiteStepIds())
                        ? ChainStepStatus.READY : ChainStepStatus.NOT_STARTED;
            } else {
                activation = latest.command().activationEventId();
                status = switch (latest.command().eventKind()) {
                    case COMPLETED -> ChainStepStatus.COMPLETED;
                    case SUPERSEDED_BY_REPLAN ->
                            ChainStepStatus.SUPERSEDED_BY_REPLAN;
                    case ACTIVATED -> activeStatus(
                            facts, definition.stepId(), activation);
                };
            }
            result.add(new StepView(definition, status, activation));
        }
        return List.copyOf(result);
    }

    private static ChainStepStatus activeStatus(
            ProductTaskStepRuntimeFacts facts, String stepId,
            String activation) {
        var candidate = latestCandidate(facts, stepId, activation);
        if (candidate == null) return ChainStepStatus.ACTIVE;
        var review = facts.reviews().stream()
                .filter(value -> "CANDIDATE_STEP_RESULT".equals(
                        value.reviewObjectType()))
                .filter(value -> value.reviewObjectId().equals(
                        candidate.candidateResultId()))
                .max(Comparator.comparingLong(value -> facts.sequences().get(
                        value.eventId()))).orElse(null);
        return review != null && review.decisionKind()
                == ChainProposalKind.REFLECTOR_CONTINUE_STEP
                ? ChainStepStatus.ACTIVE : ChainStepStatus.AWAITING_REVIEW;
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord
            currentCandidate(ProductTaskStepRuntimeFacts facts) {
        if (facts.building().stepId() == null) return null;
        return latestCandidate(facts, facts.building().stepId(),
                facts.building().activationEventId());
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord
            latestCandidate(ProductTaskStepRuntimeFacts facts,
            String stepId, String activation) {
        return facts.candidates().stream()
                .filter(value -> Objects.equals(value.planId(),
                        facts.building().planId()))
                .filter(value -> Objects.equals(value.planRevisionId(),
                        facts.building().planRevisionId()))
                .filter(value -> value.stepId().equals(stepId))
                .filter(value -> value.activationEventId().equals(activation))
                .max(Comparator.comparingLong(value -> facts.sequences().get(
                        value.eventId()))).orElse(null);
    }

    private static StepView current(
            ProductTaskStepRuntimeFacts facts, List<StepView> steps) {
        if (facts.building().stepId() == null) return null;
        return steps.stream().filter(value -> value.definition().stepId()
                .equals(facts.building().stepId())).findFirst()
                .orElseThrow(() -> blocked("current Step definition is missing"));
    }

    private static Set<String> affectedSteps(
            ProductTaskStepRuntimeFacts facts, StepView current) {
        if (facts.plan() == null || current == null) return Set.of();
        Set<String> affected = new HashSet<>(Set.of(
                current.definition().stepId()));
        boolean changed;
        do {
            changed = false;
            for (var step : facts.plan().steps()) {
                if (!affected.contains(step.stepId())
                        && step.prerequisiteStepIds().stream()
                        .anyMatch(affected::contains)) {
                    changed |= affected.add(step.stepId());
                }
            }
        } while (changed);
        return Set.copyOf(affected);
    }

    private static List<ChainPersistenceRecords.AcceptedResultRecord>
            acceptedForSteps(ProductTaskStepRuntimeFacts facts,
            Set<String> stepIds) {
        var byId = unique(facts.candidates(), ChainPersistenceRecords
                .CandidateStepResultRecord::candidateResultId);
        return facts.accepted().stream().filter(value -> stepIds.contains(
                byId.get(value.candidateResultId()).stepId())).toList();
    }

    private static List<ChainPersistenceRecords.AcceptedResultRecord>
            predecessorAccepted(ProductTaskStepRuntimeFacts facts) {
        var byId = unique(facts.candidates(), ChainPersistenceRecords
                .CandidateStepResultRecord::candidateResultId);
        return facts.accepted().stream().filter(value -> !Objects.equals(
                byId.get(value.candidateResultId()).planRevisionId(),
                facts.building().planRevisionId())).toList();
    }

    private static <T> Map<String, T> unique(
            List<T> values, java.util.function.Function<T, String> identity) {
        Map<String, T> result = new HashMap<>();
        for (T value : values) {
            if (result.put(identity.apply(value), value) != null) {
                throw blocked("runtime authority identity is duplicated");
            }
        }
        return Map.copyOf(result);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE, reason);
    }

    record StepView(ChainStepAuthorityPort.StepDefinition definition,
                    ChainStepStatus status, String activationEventId) {
    }

    record State(List<StepView> steps, StepView current,
                 ChainPersistenceRecords.CandidateStepResultRecord candidate,
                 List<ChainPersistenceRecords.AcceptedResultRecord> direct,
                 List<ChainPersistenceRecords.AcceptedResultRecord> affected,
                 List<ChainPersistenceRecords.AcceptedResultRecord> predecessor) {
    }
}
