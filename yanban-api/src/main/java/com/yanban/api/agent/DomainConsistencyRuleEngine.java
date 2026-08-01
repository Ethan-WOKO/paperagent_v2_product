package com.yanban.api.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Produces narrow deterministic findings from current server-attested evidence metadata. */
@Component
public class DomainConsistencyRuleEngine {
    static final String EVIDENCE_FILE_HASH_EQUALITY_RULE = "evidence-file-hash-equality-v1";

    public List<DomainRuntimeFacts.ConsistencyFact> evaluate(
            AgentOrchestrationRequirements requirements,
            List<DomainVerification.MaterialCoverage> coverage,
            EvidenceLedger ledger,
            Long projectId) {
        if (requirements == null
                || !requirements.consistencyChecks().contains(DomainConsistencyCheck.EVIDENCE_FILE_HASH_EQUALITY)
                || coverage == null || coverage.size() < 2 || ledger == null || projectId == null
                || coverage.stream().anyMatch(item ->
                item.status() != DomainVerification.MaterialStatus.COVERAGE_VERIFIED)) {
            return List.of();
        }

        Map<String, EvidenceRef> evidenceById = new LinkedHashMap<>();
        ledger.evidence().forEach(ref -> evidenceById.put(ref.id(), ref));
        List<ResearchMaterialKind> materials = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>();
        for (DomainVerification.MaterialCoverage item : coverage) {
            Map<String, EvidenceRef> distinctFiles = new LinkedHashMap<>();
            for (String evidenceId : item.evidenceRefs()) {
                EvidenceRef ref = evidenceById.get(evidenceId);
                if (!currentProjectEvidence(ref, projectId)) continue;
                distinctFiles.putIfAbsent(ref.file() + "\u0000" + ref.fileHash(), ref);
            }
            // The rule is deliberately undefined for an ambiguous many-file material selection.
            if (distinctFiles.size() != 1) return List.of();
            EvidenceRef selected = distinctFiles.values().iterator().next();
            materials.add(item.material());
            evidenceRefs.add(selected.id());
            hashes.add(selected.fileHash());
        }
        if (materials.size() < 2 || evidenceRefs.size() != materials.size()) return List.of();
        return List.of(new DomainRuntimeFacts.ConsistencyFact(
                EVIDENCE_FILE_HASH_EQUALITY_RULE,
                materials,
                evidenceRefs,
                hashes.size() == 1,
                DomainRuntimeFacts.ConsistencyFactSource.DETERMINISTIC_DOMAIN_RULE));
    }

    public boolean accepts(AgentOrchestrationRequirements requirements,
                           DomainRuntimeFacts.ConsistencyFact fact,
                           List<DomainVerification.MaterialCoverage> coverage,
                           EvidenceLedger ledger,
                           Long projectId) {
        return fact != null && evaluate(requirements, coverage, ledger, projectId).contains(fact);
    }

    private boolean currentProjectEvidence(EvidenceRef ref, Long projectId) {
        return ref != null
                && ref.sourceType() == EvidenceSourceType.PROJECT
                && ProjectEvidenceValidator.isTrusted(ref)
                && ref.versionStatus() == EvidenceVersionStatus.VERIFIED
                && StringUtils.hasText(ref.fileHash())
                && (ref.id().startsWith("trusted-tool:" + projectId + ":")
                || ref.id().startsWith("trusted-plan:" + projectId + ":"));
    }
}
