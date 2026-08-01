package com.yanban.api.agent;

import java.util.ArrayList;
import java.util.List;

/** Deterministic cross-material decision attached to the ordinary completion decision. */
public record DomainVerification(
        boolean applicable,
        CompletionStatus status,
        List<MaterialCoverage> materialCoverage,
        List<DomainVerificationReasonCode> reasonCodes,
        List<String> evidenceRefs,
        ConsistencyStatus consistencyStatus,
        boolean reflectionEligible,
        List<DomainRuntimeFacts.ConsistencyFact> consistencyFacts
) {
    public DomainVerification {
        status = status == null ? CompletionStatus.FAILED : status;
        materialCoverage = materialCoverage == null ? List.of() : List.copyOf(materialCoverage);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        consistencyStatus = consistencyStatus == null ? ConsistencyStatus.NOT_REQUIRED : consistencyStatus;
        consistencyFacts = consistencyFacts == null ? List.of() : List.copyOf(consistencyFacts);
    }

    public DomainVerification(boolean applicable,
                              CompletionStatus status,
                              List<MaterialCoverage> materialCoverage,
                              List<DomainVerificationReasonCode> reasonCodes,
                              List<String> evidenceRefs,
                              ConsistencyStatus consistencyStatus,
                              boolean reflectionEligible) {
        this(applicable, status, materialCoverage, reasonCodes, evidenceRefs, consistencyStatus,
                reflectionEligible, List.of());
    }

    public static DomainVerification notApplicable() {
        return new DomainVerification(false, CompletionStatus.VERIFIED, List.of(), List.of(), List.of(),
                ConsistencyStatus.NOT_REQUIRED, false, List.of());
    }

    public List<String> auditReasons() {
        List<String> reasons = new ArrayList<>();
        for (MaterialCoverage coverage : materialCoverage) {
            reasons.add("DOMAIN_" + coverage.status() + " material=" + coverage.material());
        }
        reasonCodes.stream().map(code -> "DOMAIN_" + code.name()).forEach(reasons::add);
        for (DomainRuntimeFacts.ConsistencyFact fact : consistencyFacts) {
            reasons.add("DOMAIN_CONSISTENCY_FINDING rule=" + fact.ruleId()
                    + " outcome=" + (fact.consistent() ? "CONSISTENT" : "INCONSISTENT"));
        }
        return List.copyOf(reasons);
    }

    public record MaterialCoverage(
            ResearchMaterialKind material,
            MaterialStatus status,
            List<String> availableTools,
            List<String> successfulTools,
            List<String> evidenceRefs
    ) {
        public MaterialCoverage {
            if (material == null) throw new IllegalArgumentException("material must not be null");
            status = status == null ? MaterialStatus.EVIDENCE_MISSING : status;
            availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
            successfulTools = successfulTools == null ? List.of() : List.copyOf(successfulTools);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public enum MaterialStatus {
        COVERAGE_VERIFIED,
        TOOL_UNAVAILABLE,
        TOOL_NOT_EXECUTED,
        TOOL_FAILED,
        EVIDENCE_MISSING
    }

    public enum ConsistencyStatus {
        NOT_REQUIRED,
        UNRESOLVED,
        VERIFIED_CONSISTENT,
        VERIFIED_INCONSISTENT
    }
}
