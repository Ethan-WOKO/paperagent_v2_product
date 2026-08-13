package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure role projection for a previously verified model-authority cut. */
final class ProductModelInvocationProjectionValues {
    private ProductModelInvocationProjectionValues() {
    }

    static Values create(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<String> requiredFields,
            List<String> lineage,
            List<InvocationView> invocations,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            List<DeliveryView> deliveries) {
        long ordinal = invocations.stream().mapToLong(value ->
                value.invocation().invocationOrdinal()).max().orElse(0);
        Map<String, ChainContextValue> source = Map.of(
                "priorInvocationCut", ChainContextValue.number(ordinal),
                "proposalStateCut", proposalStateCut(invocations),
                "currentRevisionAndCallReason", stateHeader(building));
        Map<String, ChainContextValue> boundary = Map.of(
                "priorInvocationOrdinal", ChainContextValue.number(ordinal));
        Map<String, ChainContextValue> parameters = new TreeMap<>();
        parameters.put("currentContextRevisionRef",
                ref(building.contextRevisionId()));
        parameters.put("lineageContextRefs", ChainContextValue.array(
                lineage.stream().map(value -> (ChainContextValue) ref(value))
                        .toList()));
        if (outcome != null) parameters.put("taskOutcomeRef",
                ref(outcome.outcomeId()));

        Map<String, ChainContextValue> available = new TreeMap<>();
        ChainContextValue header = stateHeader(building);
        available.put("model.stateHeader", header);
        available.put("model.callReason", ChainContextValue.object(Map.of(
                "contextRevisionRef", ref(building.contextRevisionId()),
                "callReason", ChainContextValue.text(building.callReason()))));
        available.put("model.latestAcceptedOrFailedPlannerMetadata",
                latestRole(invocations, io.paperagent.v2.chain.ChainRole.PLANNER));
        available.put("model.currentAndLatestExecutorMetadata",
                ChainContextValue.object(Map.of(
                        "current", header,
                        "latestPrior", latestRole(invocations,
                                io.paperagent.v2.chain.ChainRole.EXECUTOR))));
        available.put("model.reviewedCandidateProposal",
                ProductModelRoleMetadataValues.reviewedCandidate(
                        building, invocations));
        available.put("model.latestFailureMetadata",
                latestFailure(invocations));
        available.put("model.officialSourceRecords",
                ProductModelOfficialSourceValues.officialSources(
                        invocations, outcome, deliveries));
        available.put("model.latestDeliveryFailureMetadata",
                ProductModelOfficialSourceValues.latestDeliveryFailure(
                        deliveries));
        available.put("foundation.contextRevisionAndSourceVersions",
                ChainContextValue.object(Map.of(
                        "current", header,
                        "priorInvocationOrdinal",
                        ChainContextValue.number(ordinal),
                        "proposalStateCut", proposalStateCut(invocations))));
        Map<String, ChainContextValue> fields =
                ProductModelRequiredFieldSelector.select(
                        requiredFields, available);
        return new Values(source, boundary, parameters, fields);
    }

    private static ChainContextValue stateHeader(
            ChainPersistenceRecords.ContextRevisionRecord value) {
        Map<String, ChainContextValue> fields = new TreeMap<>();
        fields.put("contextRevisionRef", ref(value.contextRevisionId()));
        fields.put("taskId", ChainContextValue.text(value.taskId()));
        fields.put("role", ChainContextValue.text(value.role().name()));
        fields.put("workState", ChainContextValue.text(value.workState().name()));
        fields.put("callReason", ChainContextValue.text(value.callReason()));
        fields.put("instructionRef", ref(value.instructionId()));
        if (value.stepId() != null) fields.put("stepRef", ref(value.stepId()));
        if (value.candidateFingerprint() != null) fields.put(
                "candidateFingerprint", ref(value.candidateFingerprint()));
        return ChainContextValue.object(fields);
    }

    private static ChainContextValue latestRole(
            List<InvocationView> values, io.paperagent.v2.chain.ChainRole role) {
        return values.stream().filter(value -> value.invocation().role() == role)
                .reduce((left, right) -> right)
                .<ChainContextValue>map(
                        ProductModelInvocationProjectionValues::invocation)
                .orElseGet(() -> none("NO_PRIOR_" + role.name()
                        + "_INVOCATION"));
    }

    static ChainContextValue invocation(InvocationView view) {
        var value = view.invocation();
        Map<String, ChainContextValue> fields = new TreeMap<>();
        fields.put("invocationRef", ref(value.invocationId()));
        fields.put("contextRevisionRef", ref(value.contextRevisionId()));
        fields.put("ordinal", ChainContextValue.number(
                value.invocationOrdinal()));
        fields.put("role", ChainContextValue.text(value.role().name()));
        fields.put("workState", ChainContextValue.text(
                value.workState().name()));
        fields.put("callReason", ChainContextValue.text(value.callReason()));
        fields.put("provider", ChainContextValue.text(value.provider()));
        fields.put("model", ChainContextValue.text(value.model()));
        fields.put("attempts", attempts(view.attempts()));
        fields.put("proposal", proposal(view));
        return ChainContextValue.object(fields);
    }

    private static ChainContextValue attempts(
            List<ChainPersistenceRecords.ProviderAttemptRecord> values) {
        return ChainContextValue.array(values.stream().map(value ->
                (ChainContextValue) ChainContextValue.object(Map.of(
                        "attemptNo", ChainContextValue.number(value.attemptNo()),
                        "schemaValidation", ChainContextValue.text(
                                value.schemaValidationStatus().name()),
                        "proposalValidation", ChainContextValue.text(
                                value.proposalValidationStatus().name()),
                        "finishReason", value.finishReason() == null
                                ? ChainContextValue.nil()
                                : ChainContextValue.text(value.finishReason()),
                        "errorCode", value.errorCode() == null
                                ? ChainContextValue.nil()
                                : ChainContextValue.text(value.errorCode()))))
                .toList());
    }

    private static ChainContextValue proposal(InvocationView view) {
        if (view.proposal() == null) return none("NO_PROPOSAL");
        var proposal = view.proposal();
        var latest = view.states().isEmpty() ? null
                : view.states().get(view.states().size() - 1);
        Map<String, ChainContextValue> fields = new TreeMap<>();
        fields.put("proposalRef", ref(proposal.proposalId()));
        fields.put("kind", ChainContextValue.text(
                proposal.proposalKind().wireName()));
        fields.put("payloadDigest", ChainContextValue.text(
                proposal.payload().sha256()));
        fields.put("sourceRefsDigest", ChainContextValue.text(
                proposal.sourceRefs().sha256()));
        fields.put("state", latest == null ? ChainContextValue.text("NONE")
                : ChainContextValue.text(latest.stateKind().name()));
        fields.put("stateSequence", ChainContextValue.number(
                latest == null ? 0 : latest.stateSequence()));
        fields.put("stateEventRef", latest == null
                ? ChainContextValue.nil() : ref(latest.eventId()));
        if (proposal.proposalKind()
                == ChainProposalKind.EXECUTOR_STEP_BLOCKED) {
            ExecutorPayload.StepBlocked blocked = stepBlocked(proposal);
            fields.put("formalStepBlock", ChainContextValue.object(Map.of(
                    "failureCategory", ChainContextValue.text(
                            blocked.failureCategory()),
                    "errorRef", ref(blocked.errorRef()),
                    "attemptedActionOrRepairRefs", refs(
                            blocked.attemptedActionOrRepairRefs()),
                    "noProgressReason", ChainContextValue.text(
                            blocked.noProgressReason()),
                    "reviewRecommendation", ChainContextValue.text(
                            blocked.reviewRecommendation()),
                    "remainingMissingFields", texts(
                            blocked.remainingMissingFields()),
                    "exactQuestion", blocked.exactQuestion() == null
                            ? ChainContextValue.nil()
                            : ChainContextValue.text(blocked.exactQuestion()),
                    "expectedFormat", blocked.expectedFormat() == null
                            ? ChainContextValue.nil()
                            : ChainContextValue.text(blocked.expectedFormat()))));
        }
        fields.put("formalResult", latest != null
                && latest.stateKind()
                == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                ? ChainContextValue.object(Map.of(
                        "authorityType", ChainContextValue.text(
                                latest.officialAuthorityType()),
                        "authorityRef", ref(latest.officialAuthorityRef())))
                : ChainContextValue.nil());
        return ChainContextValue.object(fields);
    }

    private static ExecutorPayload.StepBlocked stepBlocked(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        return (ExecutorPayload.StepBlocked)
                new io.paperagent.v2.chain.model
                        .StrictChainProviderOutputParser().parse(
                        encoded, ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, null).payload();
    }

    private static ChainContextValue refs(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(value -> (ChainContextValue) ref(value)).toList());
    }

    private static ChainContextValue texts(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(value -> (ChainContextValue) ChainContextValue.text(value))
                .toList());
    }

    private static ChainContextValue latestFailure(
            List<InvocationView> values) {
        for (int index = values.size() - 1; index >= 0; index--) {
            InvocationView value = values.get(index);
            boolean failedAttempt = value.attempts().stream()
                    .anyMatch(attempt -> attempt.errorCode() != null);
            boolean failedState = !value.states().isEmpty()
                    && Set.of(ChainProposalState.REJECTED,
                    ChainProposalState.STALE).contains(value.states()
                    .get(value.states().size() - 1).stateKind());
            if (failedAttempt || failedState) return invocation(value);
        }
        return none("NO_PRIOR_MODEL_FAILURE");
    }

    private static ChainContextValue proposalStateCut(
            List<InvocationView> values) {
        Map<String, ChainContextValue> result = new TreeMap<>();
        values.stream().filter(value -> value.proposal() != null)
                .forEach(value -> result.put(value.proposal().proposalId(),
                        proposal(value)));
        return ChainContextValue.object(result);
    }

    static ChainContextValue none(String reason) {
        return ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("NONE"),
                "reason", ChainContextValue.text(reason)));
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    record InvocationView(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states) {
    }

    record DeliveryView(
            ChainPersistenceRecords.DeliveryRecord delivery,
            List<ChainPersistenceRecords.DeliveryEventRecord> events) {
    }

    record Values(
            Map<String, ChainContextValue> sourceVersion,
            Map<String, ChainContextValue> readBoundary,
            Map<String, ChainContextValue> parameters,
            Map<String, ChainContextValue> fields) {
    }
}
