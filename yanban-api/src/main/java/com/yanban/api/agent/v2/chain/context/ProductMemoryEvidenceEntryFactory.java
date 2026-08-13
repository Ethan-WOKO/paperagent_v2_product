package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.ExecutionReceipt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure construction of evidence entries from already-verified authorities. */
final class ProductMemoryEvidenceEntryFactory {
    ProductMemoryEvidenceFacts.EvidenceEntry receipt(
            ProductMemoryEvidenceActionReader.ActionView action) {
        ExecutionReceipt receipt = action.result().receipt();
        List<String> artifacts = receipt.artifactReferences().stream()
                .map(value -> value.value()).toList();
        List<String> events = receipt.eventReferences().stream()
                .map(value -> value.value()).toList();
        Map<String, ChainContextValue> details = new HashMap<>();
        details.put("receiptId", ref(receipt.id().value()));
        details.put("toolCallId", ref(receipt.toolCallId().value()));
        details.put("status", ChainContextValue.text(receipt.status().name()));
        details.put("exitCode", receipt.exitCode().isEmpty()
                ? ChainContextValue.nil()
                : ChainContextValue.number(receipt.exitCode().orElseThrow()));
        details.put("resultCode", receipt.resultCode().isEmpty()
                ? ChainContextValue.nil()
                : ChainContextValue.text(receipt.resultCode().orElseThrow()));
        details.put("artifactRefs", strings(artifacts));
        details.put("eventRefs", strings(events));
        details.put("resultingDiff", receipt.resultingDiff().isEmpty()
                ? ChainContextValue.nil()
                : ref(receipt.resultingDiff().orElseThrow().value()));
        String digest = ProductChainContractProjectionCodec.sha256(
                receipt.id().value() + "\0" + receipt.toolCallId().value()
                        + "\0" + receipt.status() + "\0"
                        + receipt.exitCode().map(String::valueOf).orElse("NONE")
                        + "\0" + receipt.resultCode().orElse("NONE")
                        + "\0" + artifacts
                        + "\0" + events + "\0"
                        + receipt.resultingDiff().map(value -> value.value())
                        .orElse("NONE"));
        var binding = action.binding();
        return new ProductMemoryEvidenceFacts.EvidenceEntry(
                "ACTION_RECEIPT", receipt.id().value(), digest,
                action.eventSequence(), binding.planRevisionId(),
                binding.stepId(), binding.activationEventId(), false, details);
    }

    ProductMemoryEvidenceFacts.EvidenceEntry candidate(
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            List<String> receiptRefs, List<String> evidenceRefs,
            boolean acceptedDelivery, long eventSequence) {
        Map<String, ChainContextValue> details = new HashMap<>();
        details.put("candidateResultId", ref(candidate.candidateResultId()));
        details.put("contentId", ref(candidate.contentId()));
        details.put("receiptRefs", strings(receiptRefs));
        details.put("evidenceRefs", strings(evidenceRefs));
        details.put("acceptedDelivery", ChainContextValue.bool(
                acceptedDelivery));
        String digest = ProductChainContractProjectionCodec.sha256(
                candidate.candidateResultId() + "\0"
                        + candidate.contentId() + "\0"
                        + candidate.receiptRefs().sha256() + "\0"
                        + candidate.evidenceRefs().sha256());
        return new ProductMemoryEvidenceFacts.EvidenceEntry(
                "CANDIDATE_EVIDENCE", candidate.candidateResultId(), digest,
                eventSequence, candidate.planRevisionId(), candidate.stepId(),
                candidate.activationEventId(), acceptedDelivery, details);
    }

    private static ChainContextValue strings(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ChainContextValue::text).toList());
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }
}
