package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure versioning and role-field projection for module 11. */
final class ProductMemoryEvidenceProjectionValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE;

    private ProductMemoryEvidenceProjectionValues() {
    }

    static Values create(
            List<String> requiredFields, ProductMemoryEvidenceFacts facts) {
        String catalogDigest = digest(facts.entries());
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            fields.put(field, field(field, facts));
        }
        Map<String, ChainContextValue> source = Map.of(
                "frozenCatalogIdentityAndDigest",
                ChainContextValue.object(Map.of(
                        "catalogId", ChainContextValue.text(
                                "task-evidence:" + facts.building().taskId()),
                        "digest", ChainContextValue.text(catalogDigest),
                        "entryCount", ChainContextValue.number(
                                facts.entries().size()))),
                "exactEvidenceRefVector", ChainContextValue.array(
                        facts.entries().stream().map(value ->
                                (ChainContextValue) ChainContextValue
                                        .referencedText(value.authorityRef(),
                                                value.authorityRef()))
                                .toList()));
        long catalogCut = facts.entries().stream().mapToLong(
                ProductMemoryEvidenceFacts.EvidenceEntry::taskEventSequence)
                .max().orElse(0);
        Map<String, ChainContextValue> boundary = Map.of(
                "taskCatalogCut", ChainContextValue.number(catalogCut));
        Map<String, ChainContextValue> parameters = new LinkedHashMap<>();
        parameters.put("taskRef", ref(facts.building().taskId()));
        parameters.put("role", ChainContextValue.text(
                facts.building().role().name()));
        parameters.put("taskAuthorityHead", ChainContextValue.number(
                facts.taskEventCut()));
        if (facts.building().planRevisionId() != null) {
            parameters.put("planRevisionRef", ref(
                    facts.building().planRevisionId()));
        }
        if (facts.building().activationEventId() != null) {
            parameters.put("activationRef", ref(
                    facts.building().activationEventId()));
        }
        return new Values(source, boundary, Map.copyOf(parameters), fields);
    }

    private static ChainContextValue field(
            String field, ProductMemoryEvidenceFacts facts) {
        List<ProductMemoryEvidenceFacts.EvidenceEntry> values = facts.entries();
        return switch (field) {
            case "evidence.frozenCompleteCatalog" -> entries(values, true);
            case "evidence.currentStepMechanicalExpansion" ->
                    entries(current(values, facts), true);
            case "evidence.planningProjection",
                    "evidence.frozenCatalog" -> entries(values, false);
            case "evidence.candidateReferencedMechanicalExpansion" ->
                    entries(current(values, facts).stream().filter(value ->
                            "CANDIDATE_EVIDENCE".equals(value.kind()))
                            .toList(), true);
            case "evidence.directFrozenOrAcceptedDeliveryEvidence" ->
                    entries(values.stream().filter(
                            ProductMemoryEvidenceFacts.EvidenceEntry
                                    ::acceptedDelivery).toList(), true);
            default -> throw blocked("unsupported evidence field: " + field);
        };
    }

    private static List<ProductMemoryEvidenceFacts.EvidenceEntry> current(
            List<ProductMemoryEvidenceFacts.EvidenceEntry> values,
            ProductMemoryEvidenceFacts facts) {
        var building = facts.building();
        if (building.activationEventId() == null) return List.of();
        return values.stream()
                .filter(value -> Objects.equals(value.planRevisionId(),
                        building.planRevisionId()))
                .filter(value -> Objects.equals(value.stepId(),
                        building.stepId()))
                .filter(value -> Objects.equals(value.activationEventId(),
                        building.activationEventId())).toList();
    }

    private static ChainContextValue entries(
            List<ProductMemoryEvidenceFacts.EvidenceEntry> values,
            boolean expanded) {
        return ChainContextValue.array(values.stream().map(value -> {
            Map<String, ChainContextValue> item = new LinkedHashMap<>();
            item.put("kind", ChainContextValue.text(value.kind()));
            item.put("authorityRef", ref(value.authorityRef()));
            item.put("digest", ChainContextValue.text(value.digest()));
            item.put("taskEventSequence", ChainContextValue.number(
                    value.taskEventSequence()));
            item.put("planRevisionId", nullable(value.planRevisionId()));
            item.put("stepId", nullable(value.stepId()));
            item.put("activationEventId", nullable(
                    value.activationEventId()));
            item.put("acceptedDelivery", ChainContextValue.bool(
                    value.acceptedDelivery()));
            if (expanded) {
                item.put("mechanicalRefs", ChainContextValue.object(
                        value.details()));
            }
            return (ChainContextValue) ChainContextValue.object(item);
        }).toList());
    }

    private static String digest(
            List<ProductMemoryEvidenceFacts.EvidenceEntry> values) {
        StringBuilder stable = new StringBuilder();
        for (var value : values) {
            stable.append(value.kind()).append('\0')
                    .append(value.authorityRef()).append('\0')
                    .append(value.digest()).append('\0')
                    .append(value.taskEventSequence()).append('\n');
        }
        return ProductChainContractProjectionCodec.sha256(stable.toString());
    }

    private static ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.text(value);
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
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
