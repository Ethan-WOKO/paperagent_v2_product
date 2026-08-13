package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure field selection plus version/boundary construction for module 6. */
final class ProductTaskStepRuntimeProjectionValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.TASK_AND_STEP_RUNTIME_STATE;

    private ProductTaskStepRuntimeProjectionValues() {
    }

    static Values create(
            List<String> requiredFields, ProductTaskStepRuntimeFacts facts) {
        var state = ProductTaskStepRuntimeState.derive(facts);
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            fields.put(field, fieldValue(field, facts, state));
        }
        long checkpoint = facts.stepEvents().stream().mapToLong(
                ChainStepAuthorityPort.StepEvent::authoritySequence)
                .max().orElse(0);
        long acceptedCut = maxEvent(facts.accepted(), facts.sequences());
        long applicabilityCut = maxEvent(
                facts.applicability(), facts.sequences());
        Map<String, ChainContextValue> source = Map.of(
                "chainEventCut", ChainContextValue.number(facts.taskEventCut()),
                "stepRecoveryHead", ChainContextValue.object(Map.of(
                        "checkpointHead", ChainContextValue.number(checkpoint),
                        "planRevisionId", codec().nullable(facts.building()
                                .planRevisionId()),
                        "latestStepEventId", facts.stepEvents().isEmpty()
                                ? ChainContextValue.text("NONE")
                                : codec().ref(facts.stepEvents().get(
                                facts.stepEvents().size() - 1).command().eventId()))),
                "acceptedResultAndApplicabilityCut",
                ChainContextValue.object(Map.of(
                        "acceptedResultEventSequence",
                        ChainContextValue.number(acceptedCut),
                        "applicabilityEventSequence",
                        ChainContextValue.number(applicabilityCut))),
                "outcomeId", facts.outcome() == null
                        ? ChainContextValue.text("NONE")
                        : codec().ref(facts.outcome().outcomeId()));
        Map<String, ChainContextValue> boundary = Map.of(
                "taskEventSequence", ChainContextValue.number(
                        facts.taskEventCut()),
                "checkpointHead", ChainContextValue.number(checkpoint));
        return new Values(source, boundary, parameters(facts, state), fields);
    }

    private static Map<String, ChainContextValue> parameters(
            ProductTaskStepRuntimeFacts facts,
            ProductTaskStepRuntimeState.State state) {
        Map<String, ChainContextValue> parameters = new LinkedHashMap<>();
        parameters.put("taskRef", codec().ref(facts.building().taskId()));
        parameters.put("role", ChainContextValue.text(
                facts.building().role().name()));
        if (facts.plan() != null) {
            parameters.put("planRevisionRef", codec().ref(
                    facts.plan().planRevisionId()));
        }
        if (state.current() != null) {
            parameters.put("stepRef", codec().ref(
                    state.current().definition().stepId()));
            parameters.put("activationRef", codec().ref(
                    state.current().activationEventId()));
        }
        return Map.copyOf(parameters);
    }

    private static ChainContextValue fieldValue(
            String field, ProductTaskStepRuntimeFacts facts,
            ProductTaskStepRuntimeState.State state) {
        return switch (field) {
            case "runtime.executionMode" -> ChainContextValue.text(
                    requireRoute(facts).route().name());
            case "runtime.steps" -> ChainContextValue.array(state.steps()
                    .stream().map(codec()::step).toList());
            case "runtime.currentStep" -> codec().step(requireCurrent(state));
            case "runtime.candidateResult" -> state.candidate() == null
                    ? ChainContextValue.nil() : codec().candidate(state.candidate());
            case "runtime.acceptedResultCatalog" ->
                    codec().accepted(facts.accepted());
            case "runtime.applicability" ->
                    codec().applicability(facts.applicability());
            case "runtime.predecessorAcceptedResultCatalog" ->
                    codec().accepted(state.predecessor());
            case "runtime.directDependencies" ->
                    codec().accepted(state.direct());
            case "runtime.affectedResults" ->
                    codec().accepted(state.affected());
            case "runtime.taskOutcome" -> codec().outcome(facts.outcome());
            case "runtime.acceptedResultTerminalProjection" ->
                    codec().accepted(facts.accepted());
            case "runtime.deliveryRecord" -> codec().delivery(facts.delivery());
            case "runtime.answerPayloadTemplate" ->
                    codec().answerPayloadTemplate(facts);
            case "foundation.stateHeader" -> codec().stateHeader(facts, state);
            default -> throw blocked("unsupported runtime field: " + field);
        };
    }

    private static ChainPersistenceRecords.RouteDecisionRecord requireRoute(
            ProductTaskStepRuntimeFacts facts) {
        if (facts.route() == null) {
            throw blocked("execution mode authority is missing");
        }
        return facts.route();
    }

    private static ProductTaskStepRuntimeState.StepView requireCurrent(
            ProductTaskStepRuntimeState.State state) {
        if (state.current() == null) {
            throw blocked("runtime field requires current Step");
        }
        return state.current();
    }

    private static long maxEvent(
            List<? extends ChainPersistenceRecords.TaskAuthorityFact> values,
            Map<String, Long> sequences) {
        return values.stream().mapToLong(value -> sequences.get(
                value.eventId())).max().orElse(0);
    }

    private static ProductTaskStepRuntimeValueCodec codec() {
        return ProductTaskStepRuntimeValueCodec.INSTANCE;
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    record Values(Map<String, ChainContextValue> sourceVersion,
                  Map<String, ChainContextValue> readBoundary,
                  Map<String, ChainContextValue> parameters,
                  Map<String, ChainContextValue> fields) {
    }
}
