package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical context-value encoding for runtime authority records. */
final class ProductTaskStepRuntimeValueCodec {
    static final ProductTaskStepRuntimeValueCodec INSTANCE =
            new ProductTaskStepRuntimeValueCodec();
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductTaskStepRuntimeValueCodec() {
    }

    ChainContextValue step(ProductTaskStepRuntimeState.StepView value) {
        return ChainContextValue.object(Map.of(
                "stepId", ref(value.definition().stepId()),
                "stableOrder", ChainContextValue.number(
                        value.definition().stableOrder()),
                "prerequisiteStepIds", strings(value.definition()
                        .prerequisiteStepIds().stream().sorted().toList()),
                "status", ChainContextValue.text(value.status().name()),
                "activationEventId", nullable(value.activationEventId())));
    }

    ChainContextValue candidate(
            ChainPersistenceRecords.CandidateStepResultRecord value) {
        Map<String, ChainContextValue> result = new LinkedHashMap<>();
        result.put("candidateResultId", ref(value.candidateResultId()));
        result.put("proposalId", ref(value.proposalId()));
        result.put("contentId", ref(value.contentId()));
        result.put("stepId", ref(value.stepId()));
        result.put("activationEventId", ref(value.activationEventId()));
        result.put("artifactId", value.artifactId() == null
                ? ChainContextValue.nil()
                : ChainContextValue.number(value.artifactId()));
        result.put("candidateFingerprint", nullable(value.candidateFingerprint()));
        result.put("receiptRefs", ChainContextValue.referencedText(
                value.receiptRefs().json(), value.candidateResultId()));
        result.put("evidenceRefs", ChainContextValue.referencedText(
                value.evidenceRefs().json(), value.candidateResultId()));
        return ChainContextValue.object(result);
    }

    ChainContextValue accepted(
            List<ChainPersistenceRecords.AcceptedResultRecord> values) {
        return ChainContextValue.array(values.stream().map(value ->
                (ChainContextValue) ChainContextValue.object(Map.of(
                        "acceptedResultId", ref(value.acceptedResultId()),
                        "candidateResultId", ref(value.candidateResultId()),
                        "reviewDecisionId", ref(value.reviewDecisionId()),
                        "transitionId", ref(value.transitionId()),
                        "contentId", ref(value.contentId()),
                        "acceptedIdentitySha256", ChainContextValue.text(
                                value.acceptedIdentitySha256())))).toList());
    }

    ChainContextValue applicability(
            List<ChainPersistenceRecords.ResultApplicabilityRecord> values) {
        return ChainContextValue.array(values.stream().map(value ->
                (ChainContextValue) ChainContextValue.object(Map.of(
                        "applicabilityId", ref(value.applicabilityId()),
                        "acceptedResultId", ref(value.acceptedResultId()),
                        "sourceType", ChainContextValue.text(
                                value.sourceType().name()),
                        "sourceDecisionId", ref(value.sourceDecisionId()),
                        "targetPlanRevisionId", ref(
                                value.targetPlanRevisionId()),
                        "targetInstructionVersionId", ref(
                                value.targetInstructionVersionId()),
                        "conclusion", ChainContextValue.text(
                                value.conclusion().name()),
                        "reason", ChainContextValue.text(value.reason()))))
                .toList());
    }

    ChainContextValue outcome(ChainPersistenceRecords.TaskOutcomeRecord value) {
        if (value == null) throw blocked("Answer TaskOutcome is missing");
        Map<String, ChainContextValue> outcome = new LinkedHashMap<>();
        outcome.put("outcomeId", ref(value.outcomeId()));
        outcome.put("outcomeType", ChainContextValue.text(
                value.outcomeType().name()));
        outcome.put("instructionId", ref(value.instructionId()));
        outcome.put("acceptedSet", ChainContextValue.referencedText(
                value.acceptedSet().json(), value.outcomeId()));
        outcome.put("coverage", ChainContextValue.referencedText(
                value.coverage().json(), value.outcomeId()));
        outcome.put("finalArtifactRef", value.finalArtifactId() == null
                ? ChainContextValue.nil()
                : ref(ChainIdentity.candidateArtifactRef(
                        value.finalArtifactId())));
        outcome.put("candidateKey", refOrNone(value.candidateKey()));
        outcome.put("validationId", refOrNone(value.validationId()));
        outcome.put("failureCode", nullable(value.failureCode()));
        return ChainContextValue.object(outcome);
    }

    ChainContextValue delivery(ChainPersistenceRecords.DeliveryRecord value) {
        if (value == null) return ChainContextValue.nil();
        return ChainContextValue.object(Map.of(
                "deliveryId", ref(value.deliveryId()),
                "taskOutcomeId", ref(value.taskOutcomeId()),
                "answerContentId", nullable(value.answerContentId()),
                "assistantMessageId", value.assistantMessageId() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.number(value.assistantMessageId())));
    }

    ChainContextValue answerPayloadTemplate(
            ProductTaskStepRuntimeFacts facts) {
        ChainPersistenceRecords.TaskOutcomeRecord outcome = facts.outcome();
        if (outcome == null) throw blocked("Answer TaskOutcome is missing");
        Map<String, ChainContextValue> payload = new LinkedHashMap<>();
        String kind;
        if (outcome.outcomeType() == ChainTaskOutcomeStatus.COMPLETED) {
            kind = "FINAL_DELIVERY";
            payload.put("taskOutcomeRef", ref(outcome.outcomeId()));
            payload.put("artifactAndCandidateRefs", outcome.finalArtifactId()
                    == null
                    ? strings(List.of("NONE"))
                    : strings(List.of(ChainIdentity.candidateArtifactRef(
                            outcome.finalArtifactId()),
                            outcome.candidateKey())));
            payload.put("validationRef", refOrNone(outcome.validationId()));
            payload.put("publishRef", refOrNone(outcome.publishReceiptId()));
        } else {
            kind = "STATUS_OR_FAILURE";
            String decisionRef = facts.reviews().stream()
                    .max(java.util.Comparator.comparingLong(value ->
                            facts.sequences().get(value.eventId())))
                    .map(ChainPersistenceRecords.ReviewDecisionRecord
                            ::reviewDecisionId)
                    .orElse("NONE");
            payload.put("taskOrStepStatusRef", ref(outcome.outcomeId()));
            payload.put("latestDecisionRef", refOrNone(decisionRef));
            payload.put("blockerOrTaskOutcomeRef", ref(outcome.outcomeId()));
        }
        payload.put("inlineAnswerBody", ChainContextValue.text(
                "<GENERATE_USER_VISIBLE_BODY_FROM_FORMAL_FACTS>"));
        return ChainContextValue.object(Map.of(
                "copyRule", ChainContextValue.text(
                        "COPY_COMPLETE_ROOT_EXACTLY_AND_REPLACE_ONLY_INLINE_ANSWER_BODY"),
                "selectedKind", ChainContextValue.text(kind),
                "root", ChainContextValue.object(Map.of(
                        "schemaVersion", ChainContextValue.text("1"),
                        "kind", ChainContextValue.text(kind),
                        "payload", ChainContextValue.object(payload)))));
    }

    ChainContextValue directAnswerPayloadTemplate(
            ChainPersistenceRecords.RouteDecisionRecord route) {
        try {
            JsonNode specificationRoot = JSON.readTree(
                    route.directTaskSpecification().json());
            JsonNode specification = specificationRoot == null
                    ? null : specificationRoot.get("specification");
            JsonNode refs = JSON.readTree(route.answerRequiredRefs().json());
            if (specificationRoot == null || !specificationRoot.isObject()
                    || specificationRoot.size() != 1
                    || specification == null || !specification.isTextual()
                    || refs == null || !refs.isArray()) {
                throw blocked("DIRECT route canonical payload is invalid");
            }
            List<ChainContextValue> factRefs = new java.util.ArrayList<>();
            for (JsonNode value : refs) {
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw blocked("DIRECT route fact refs are invalid");
                }
                factRefs.add(ref(value.textValue()));
            }
            String kind = io.paperagent.v2.chain.ChainProposalKind
                    .ANSWER_DIRECT_ANSWER.wireName();
            Map<String, ChainContextValue> payload = Map.of(
                    "routeDecisionRef", ref(route.routeDecisionId()),
                    "directTaskSpecification",
                    ChainContextValue.text(specification.textValue()),
                    "inlineAnswerBody", ChainContextValue.text(
                            "<GENERATE_DIRECT_USER_VISIBLE_BODY>"),
                    "factRefs", ChainContextValue.array(factRefs));
            return ChainContextValue.object(Map.of(
                    "copyRule", ChainContextValue.text(
                            "COPY_COMPLETE_ROOT_EXACTLY_AND_REPLACE_ONLY_INLINE_ANSWER_BODY"),
                    "selectedKind", ChainContextValue.text(kind),
                    "root", ChainContextValue.object(Map.of(
                            "schemaVersion", ChainContextValue.text("1"),
                            "kind", ChainContextValue.text(kind),
                            "payload", ChainContextValue.object(payload)))));
        } catch (JsonProcessingException invalid) {
            throw blocked("DIRECT route canonical payload is invalid");
        }
    }

    ChainContextValue stateHeader(
            ProductTaskStepRuntimeFacts facts,
            ProductTaskStepRuntimeState.State state) {
        return stateHeaderForCurrent(facts, state.current());
    }

    ChainContextValue directStateHeader(
            ProductTaskStepRuntimeFacts facts) {
        return stateHeaderForCurrent(facts, null);
    }

    private ChainContextValue stateHeaderForCurrent(
            ProductTaskStepRuntimeFacts facts,
            ProductTaskStepRuntimeState.StepView current) {
        Map<String, ChainContextValue> result = new LinkedHashMap<>();
        result.put("taskId", ref(facts.building().taskId()));
        result.put("role", ChainContextValue.text(
                facts.building().role().name()));
        result.put("executionMode", facts.route() == null
                ? ChainContextValue.text("UNDECIDED")
                : ChainContextValue.text(facts.route().route().name()));
        result.put("taskEventSequence", ChainContextValue.number(
                facts.taskEventCut()));
        result.put("planRevisionId", nullable(
                facts.building().planRevisionId()));
        result.put("stepId", current == null
                ? ChainContextValue.nil()
                : ref(current.definition().stepId()));
        result.put("activationEventId", current == null
                ? ChainContextValue.nil()
                : ref(current.activationEventId()));
        result.put("stepStatus", current == null
                ? ChainContextValue.nil()
                : ChainContextValue.text(current.status().name()));
        result.put("taskOutcomeId", facts.outcome() == null
                ? ChainContextValue.nil() : ref(facts.outcome().outcomeId()));
        return ChainContextValue.object(result);
    }

    ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.text(value);
    }

    ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private ChainContextValue.Text refOrNone(String value) {
        return ref(value == null || value.isBlank() ? "NONE" : value);
    }

    private static ChainContextValue strings(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ChainContextValue::text).toList());
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE, reason);
    }
}
