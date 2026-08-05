package com.yanban.api.agent.v2.context;

import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.api.agent.v2.result.V2StepResultService;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds the model-facing execution projection from existing durable facts.
 * It creates no second authority and never changes Sandbox execution.
 */
@Component
public class V2ExecutionContextSource {
    private final V2EffectHistorySource effects;
    private final V2StepResultService stepResults;
    private final NaturalLanguageCandidateAuthorityStore candidates;

    public V2ExecutionContextSource(
            V2EffectHistorySource effects,
            V2StepResultService stepResults,
            NaturalLanguageCandidateAuthorityStore candidates) {
        this.effects = effects;
        this.stepResults = stepResults;
        this.candidates = candidates;
    }

    @Transactional(readOnly = true)
    public Projection inspect(PlanId planId, PlanStepId activeStepId) {
        List<V2StepResultSnapshot> accepted =
                stepResults.acceptedCompletedFacts(planId);
        Set<ReceiptId> acceptedReceipts = new LinkedHashSet<>();
        accepted.forEach(value -> acceptedReceipts.addAll(
                value.evidenceReceiptIds()));

        List<V2EffectHistorySource.Entry> history = effects.inspect(planId);
        List<String> relatedTools = history.stream()
                .filter(entry -> belongsToActiveStep(entry, activeStepId)
                        || referencesAcceptedReceipt(
                                entry, acceptedReceipts))
                .map(V2ExecutionContextSource::toolFact)
                .toList();
        List<String> acceptedFacts = accepted.stream()
                .map(V2ExecutionContextSource::acceptedFact)
                .toList();
        Optional<String> candidate = candidates.findPrepared(
                        planId.value())
                .map(V2ExecutionContextSource::candidateFact);
        Optional<String> latestReflection = activeStepId == null
                ? Optional.empty()
                : stepResults.latestDecisionForActive(
                                planId, activeStepId)
                        .map(V2ExecutionContextSource::reflectionFact);
        return new Projection(
                acceptedFacts, relatedTools, candidate, latestReflection);
    }

    private static boolean belongsToActiveStep(
            V2EffectHistorySource.Entry entry,
            PlanStepId activeStepId) {
        return activeStepId != null
                && entry.intent().intent().stepId().equals(activeStepId);
    }

    private static boolean referencesAcceptedReceipt(
            V2EffectHistorySource.Entry entry,
            Set<ReceiptId> acceptedReceipts) {
        return entry.completed()
                && acceptedReceipts.contains(
                        entry.result().receipt().id());
    }

    private static String acceptedFact(V2StepResultSnapshot result) {
        return new StringBuilder()
                .append("acceptedStepResult[resultId=")
                .append(result.resultId())
                .append(",stepId=")
                .append(result.stepId().value())
                .append(",evidenceReceiptIds=")
                .append(result.evidenceReceiptIds().stream()
                        .map(ReceiptId::value).toList())
                .append(",result=")
                .append(result.acceptedText().orElseThrow())
                .append(']')
                .toString();
    }

    private static String toolFact(V2EffectHistorySource.Entry entry) {
        var intent = entry.intent().intent();
        StringBuilder fact = new StringBuilder()
                .append("toolExecution[stepId=")
                .append(intent.stepId().value())
                .append(",toolCallId=")
                .append(intent.toolCallId().value())
                .append(",toolKind=")
                .append(intent.kind())
                .append(",arguments=")
                .append(intent.arguments());
        if (!entry.completed()) {
            return fact.append(",status=PENDING]").toString();
        }
        var receipt = entry.result().receipt();
        return fact.append(",receiptId=")
                .append(receipt.id().value())
                .append(",status=")
                .append(receipt.status())
                .append(",resultCode=")
                .append(receipt.resultCode().orElse(""))
                .append(",exitCode=")
                .append(receipt.exitCode()
                        .map(String::valueOf).orElse(""))
                .append(",stdout=")
                .append(capture(receipt.standardOutput()))
                .append(",stderr=")
                .append(capture(receipt.standardError()))
                .append(",artifacts=")
                .append(receipt.artifactReferences())
                .append(",diff=")
                .append(receipt.resultingDiff())
                .append(']')
                .toString();
    }

    private static String candidateFact(
            NaturalLanguageCandidateAuthorityStore.Prepared prepared) {
        StringBuilder value = new StringBuilder()
                .append("preparedCandidate[diffFingerprint=")
                .append(prepared.diffFingerprint())
                .append("]");
        prepared.replacements().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> value
                        .append("\n<replacement path=\"")
                        .append(entry.getKey())
                        .append("\">\n")
                        .append(entry.getValue())
                        .append("\n</replacement>"));
        return value.toString();
    }

    private static String reflectionFact(
            V2StepResultService.ActiveDecision decision) {
        return new StringBuilder()
                .append("latestStepDecision[resultId=")
                .append(decision.resultId())
                .append(",source=")
                .append(decision.source())
                .append(",status=")
                .append(decision.status())
                .append(",proposedResult=")
                .append(decision.proposedText())
                .append(",resolutionReason=")
                .append(decision.resolutionReason().orElse(""))
                .append(",evidenceReceiptIds=")
                .append(decision.evidenceReceiptIds().stream()
                        .map(ReceiptId::value).toList())
                .append(']')
                .toString();
    }

    private static String capture(OutputCapture output) {
        if (output == null) {
            return "";
        }
        String value = output.inlineText().orElseGet(() ->
                output.artifactRef()
                        .map(reference -> "artifact:"
                                + reference.value())
                        .orElse(""));
        return output.truncated()
                ? value + "\n[OUTPUT_TRUNCATED]" : value;
    }

    public record Projection(
            List<String> acceptedStepResults,
            List<String> relatedToolResults,
            Optional<String> preparedCandidate,
            Optional<String> latestStepDecision) {
        public Projection {
            acceptedStepResults = List.copyOf(acceptedStepResults);
            relatedToolResults = List.copyOf(relatedToolResults);
            preparedCandidate = preparedCandidate == null
                    ? Optional.empty() : preparedCandidate;
            latestStepDecision = latestStepDecision == null
                    ? Optional.empty() : latestStepDecision;
        }

        public static Projection empty() {
            return new Projection(
                    List.of(), List.of(), Optional.empty(),
                    Optional.empty());
        }

        public List<String> reflectionFacts() {
            List<String> facts = new ArrayList<>(relatedToolResults);
            preparedCandidate.ifPresent(value ->
                    facts.add("candidateContent=" + value));
            latestStepDecision.ifPresent(value ->
                    facts.add("previousReflection=" + value));
            return List.copyOf(facts);
        }
    }
}
