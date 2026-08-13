package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure integrity checks for formal runtime authorities. */
final class ProductTaskStepRuntimeValidator {
    private ProductTaskStepRuntimeValidator() {
    }

    static void validate(ProductTaskStepRuntimeFacts facts) {
        validateResultGraph(facts);
        if (facts.plan() != null) {
            validateDefinitions(facts.plan().steps());
            validateStepEvents(facts, facts.plan().steps());
        }
    }

    private static void validateResultGraph(ProductTaskStepRuntimeFacts facts) {
        var candidates = unique(facts.candidates(), ChainPersistenceRecords
                .CandidateStepResultRecord::candidateResultId);
        var reviews = unique(facts.reviews(), ChainPersistenceRecords
                .ReviewDecisionRecord::reviewDecisionId);
        Set<String> acceptedIds = new HashSet<>();
        for (var accepted : facts.accepted()) {
            var candidate = candidates.get(accepted.candidateResultId());
            var review = reviews.get(accepted.reviewDecisionId());
            if (!acceptedIds.add(accepted.acceptedResultId())
                    || candidate == null || review == null
                    || !accepted.contentId().equals(candidate.contentId())
                    || !review.reviewObjectId().equals(
                    candidate.candidateResultId())) {
                throw blocked("AcceptedResult graph is incomplete or inconsistent");
            }
        }
        for (var value : facts.applicability()) {
            if (!acceptedIds.contains(value.acceptedResultId())) {
                throw blocked("applicability references no formal AcceptedResult");
            }
        }
    }

    private static void validateDefinitions(
            List<ChainStepAuthorityPort.StepDefinition> values) {
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (var value : values) {
            if (!ids.add(value.stepId()) || !orders.add(value.stableOrder())) {
                throw blocked("Step definitions are ambiguous");
            }
        }
        for (var value : values) {
            if (value.prerequisiteStepIds().contains(value.stepId())
                    || !ids.containsAll(value.prerequisiteStepIds())) {
                throw blocked("Step dependency definition is invalid");
            }
        }
    }

    private static void validateStepEvents(
            ProductTaskStepRuntimeFacts facts,
            List<ChainStepAuthorityPort.StepDefinition> definitions) {
        Set<String> ids = definitions.stream().map(
                ChainStepAuthorityPort.StepDefinition::stepId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, ChainStepAuthorityPort.StepEvent> prior = new HashMap<>();
        long sequence = 0;
        for (var event : facts.stepEvents()) {
            var command = event.command();
            var previous = prior.get(command.stepId());
            if (event.authoritySequence() <= sequence
                    || !ids.contains(command.stepId())
                    || (command.eventKind()
                    == ChainStepAuthorityPort.StepEventKind.ACTIVATED
                    && previous != null)
                    || (command.eventKind()
                    != ChainStepAuthorityPort.StepEventKind.ACTIVATED
                    && (previous == null || previous.command().eventKind()
                    != ChainStepAuthorityPort.StepEventKind.ACTIVATED
                    || !previous.command().activationEventId().equals(
                    command.activationEventId())))) {
                throw blocked("stable Step event prefix is invalid");
            }
            prior.put(command.stepId(), event);
            sequence = event.authoritySequence();
        }
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
}
