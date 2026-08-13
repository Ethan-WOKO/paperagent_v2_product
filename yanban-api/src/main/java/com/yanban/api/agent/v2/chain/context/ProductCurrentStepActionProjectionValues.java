package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.ReceiptStatus;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure role-field selection and version-vector construction for module 7. */
final class ProductCurrentStepActionProjectionValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS;

    private ProductCurrentStepActionProjectionValues() {
    }

    static Values create(
            List<String> requiredFields,
            ProductCurrentStepActionFacts facts) {
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            fields.put(field, fieldValue(field, facts));
        }
        List<ChainContextValue> actionVector = facts.actions().stream()
                .map(value -> (ChainContextValue) ChainContextValue.object(Map.of(
                        "attemptNo", ChainContextValue.number(
                                value.binding().attemptNo()),
                        "actionRef", ref(value.binding().actionId()),
                        "eventSequence", ChainContextValue.number(
                                value.authorityEventSequence()),
                        "signatureDigest", ChainContextValue.text(
                                value.binding().actionSignatureSha256()))))
                .toList();
        long actionCut = facts.actions().stream().mapToLong(
                ProductCurrentStepActionFacts.ActionView
                        ::authorityEventSequence).max().orElse(0);
        var codec = ProductCurrentStepActionValueCodec.INSTANCE;
        Map<String, ChainContextValue> source = Map.of(
                "actionCut", ChainContextValue.object(Map.of(
                        "authorityEventSequence", ChainContextValue.number(
                                actionCut),
                        "actionSequence", ChainContextValue.number(
                                facts.actions().size()),
                        "actionVectorDigest", digest(
                                ChainContextValue.array(actionVector)))),
                "effectIntent", ChainContextValue.array(facts.actions()
                        .stream().map(codec::intentVersion).toList()),
                "progress", ChainContextValue.array(facts.actions()
                        .stream().map(codec::progressVersion).toList()),
                "receiptAndOutcomeIds", receiptAndOutcome(facts));
        return new Values(source, boundary(facts, actionCut),
                parameters(facts), fields);
    }

    static Map<String, ChainContextValue> emptySource() {
        return Map.of(
                "actionCut", ChainContextValue.object(Map.of(
                        "authorityEventSequence", ChainContextValue.number(0),
                        "actionSequence", ChainContextValue.number(0),
                        "actionVectorDigest", digest(
                                ChainContextValue.array(List.of())))),
                "effectIntent", ChainContextValue.array(List.of()),
                "progress", ChainContextValue.array(List.of()),
                "receiptAndOutcomeIds", ChainContextValue.object(Map.of(
                        "receipts", ChainContextValue.array(List.of()),
                        "candidateMaterializationFailures",
                        ChainContextValue.array(List.of()),
                        "candidates", ChainContextValue.array(List.of()),
                        "taskOutcome", ChainContextValue.nil())));
    }

    static Map<String, ChainContextValue> emptyBoundary(
            ProductCurrentStepActionFacts facts) {
        return boundary(facts, 0);
    }

    private static ChainContextValue fieldValue(
            String field, ProductCurrentStepActionFacts facts) {
        var codec = ProductCurrentStepActionValueCodec.INSTANCE;
        List<ProductCurrentStepActionFacts.ActionView> failures =
                ProductCurrentStepActionAnalysisValues.unresolvedFailures(
                        facts.actions());
        return switch (field) {
            case "action.unresolvedFailures" -> ChainContextValue.array(
                    failures.stream().map(codec::failureSummary).toList());
            case "action.terminalSummary" -> ChainContextValue.array(
                    facts.actions().stream().filter(value ->
                                    value.result() != null
                                            || value.candidateFailure() != null)
                            .map(codec::failureSummary).toList());
            case "action.currentStepAttemptTable" -> ChainContextValue.array(
                    facts.actions().stream().map(codec::attempt).toList());
            case "action.latestOrUnresolvedReceiptAndErrorExpansion" ->
                    ChainContextValue.array(keyViews(
                            facts.actions(), failures).stream()
                            .map(codec::expanded).toList());
            case "action.keyReceiptAndError" -> ChainContextValue.array(
                    keyViews(facts.actions(), failures).stream()
                            .map(codec::expanded).toList());
            case "action.diagnosis" ->
                    ProductCurrentStepActionAnalysisValues.diagnosis(
                            facts.actions());
            case "action.noProgressState" ->
                    ProductCurrentStepActionAnalysisValues.noProgress(
                            facts.actions());
            case "action.officialFailureSummaryOnly" ->
                    officialFailure(facts, codec);
            default -> throw blocked("unsupported action field: " + field);
        };
    }

    private static ChainContextValue receiptAndOutcome(
            ProductCurrentStepActionFacts facts) {
        var codec = ProductCurrentStepActionValueCodec.INSTANCE;
        return ChainContextValue.object(Map.of(
                "receipts", ChainContextValue.array(facts.actions().stream()
                        .map(codec::receiptVersion).toList()),
                "candidateMaterializationFailures",
                ChainContextValue.array(facts.actions().stream()
                        .map(codec::candidateFailureVersion).toList()),
                "candidates", ChainContextValue.array(facts.actions().stream()
                        .map(codec::candidateVersion).toList()),
                "taskOutcome", facts.taskOutcome() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.object(Map.of(
                        "outcomeRef", ref(facts.taskOutcome().outcomeId()),
                        "status", ChainContextValue.text(
                                facts.taskOutcome().outcomeType().name()),
                        "eventSequence", ChainContextValue.number(
                                facts.eventSequences().get(
                                        facts.taskOutcome().eventId()))))));
    }

    private static Map<String, ChainContextValue> boundary(
            ProductCurrentStepActionFacts facts, long actionCut) {
        var building = facts.building();
        return Map.of("planStepActivationActionFence",
                ChainContextValue.object(Map.ofEntries(
                        Map.entry("taskRef", ref(building.taskId())),
                        Map.entry("planRevisionRef", nullable(
                                building.planRevisionId())),
                        Map.entry("stepRef", nullable(building.stepId())),
                        Map.entry("activationRef", nullable(
                                building.activationEventId())),
                        Map.entry("actionAuthorityEventCut",
                                ChainContextValue.number(actionCut)),
                        Map.entry("taskAuthorityHead",
                                ChainContextValue.number(
                                        facts.taskEventCut())))));
    }

    private static Map<String, ChainContextValue> parameters(
            ProductCurrentStepActionFacts facts) {
        var building = facts.building();
        return Map.of(
                "taskRef", ref(building.taskId()),
                "role", ChainContextValue.text(building.role().name()),
                "instructionRef", ref(building.instructionId()),
                "planRevisionRef", nullable(building.planRevisionId()),
                "stepRef", nullable(building.stepId()),
                "activationRef", nullable(building.activationEventId()));
    }

    private static List<ProductCurrentStepActionFacts.ActionView> keyViews(
            List<ProductCurrentStepActionFacts.ActionView> actions,
            List<ProductCurrentStepActionFacts.ActionView> failures) {
        return ProductCurrentStepActionAnalysisValues.keyViews(
                actions, failures);
    }

    private static ChainContextValue officialFailure(
            ProductCurrentStepActionFacts facts,
            ProductCurrentStepActionValueCodec codec) {
        boolean failed = facts.taskOutcome() != null
                && facts.taskOutcome().outcomeType()
                == io.paperagent.v2.chain.ChainTaskOutcomeStatus.FAILED;
        return ChainContextValue.object(Map.of(
                "taskOutcome", codec.taskOutcomeFailure(facts.taskOutcome()),
                "actionFailures", ChainContextValue.array(failed
                        ? facts.actions().stream().filter(value ->
                                value.candidateFailure() != null
                                        || (value.result() != null
                                        && value.result().receipt().status()
                                        != ReceiptStatus.SUCCESS))
                        .map(codec::failureSummary).toList() : List.of())));
    }

    private static ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil() : ref(value);
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static ChainContextValue.Text digest(ChainContextValue value) {
        return ChainContextValue.text(ProductChainContractProjectionCodec.sha256(
                ProductChainContractProjectionCodec.canonicalJson(value)));
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
