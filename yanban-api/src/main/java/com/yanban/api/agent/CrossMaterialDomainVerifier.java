package com.yanban.api.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Fail-closed verifier over trusted orchestration requirements and observable runtime facts. */
@Component
public class CrossMaterialDomainVerifier {
    private static final Set<String> PAPER_EXTENSIONS = Set.of("tex", "latex");
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "c", "cc", "cpp", "h", "hpp",
            "go", "rs", "r", "m", "scala", "sh", "sql");
    private static final Set<String> EXPERIMENT_EXTENSIONS = Set.of(
            "json", "yaml", "yml", "csv", "tsv", "toml", "ini", "cfg", "conf", "xml", "log");
    private static final Set<String> BIBTEX_EXTENSIONS = Set.of("bib");
    private final DomainConsistencyRuleEngine consistencyRules;

    public CrossMaterialDomainVerifier() {
        this(new DomainConsistencyRuleEngine());
    }

    @Autowired
    public CrossMaterialDomainVerifier(DomainConsistencyRuleEngine consistencyRules) {
        this.consistencyRules = consistencyRules;
    }

    public DomainVerification verify(AgentRuntimeRequest request,
                                     AgentRuntimeResult result,
                                     EvidenceLedger currentEvidence,
                                     int reflectionAttempts) {
        if (request == null || result == null || request.projectContext() == null) {
            return DomainVerification.notApplicable();
        }
        AgentOrchestrationRequirements requirements = request.orchestrationRequirements();
        List<ResearchMaterialRequirement> requested = requirements == null
                ? List.of() : requirements.materialRequirements();
        DomainRuntimeFacts facts = result.domainRuntimeFacts();
        boolean hasPlanFacts = facts != null && !facts.planStepOutcomes().isEmpty();
        boolean governedCodeExecution = requirements != null
                && requirements.signals().contains(AgentStrategySignal.SANDBOX_EXECUTION_REQUIRED)
                && !requirements.crossMaterial()
                && requested.stream().allMatch(item -> item.material() == ResearchMaterialKind.CODE)
                && FinalSynthesisInputProjector.hasVerifiedExecutionMaterial(result.finalSynthesisInput());
        if (governedCodeExecution) {
            // A bound code snapshot + request + successful provider receipt is complete for execution-fact
            // verification. It does not claim broader code correctness or require unrelated research material.
            return DomainVerification.notApplicable();
        }
        if (requested.isEmpty() && !hasPlanFacts) {
            return DomainVerification.notApplicable();
        }

        DomainRuntimeFacts observed = facts == null ? DomainRuntimeFacts.empty() : facts;
        EvidenceLedger ledger = currentEvidence == null ? EvidenceLedger.empty() : currentEvidence;
        LinkedHashSet<DomainVerificationReasonCode> codes = new LinkedHashSet<>();
        List<DomainVerification.MaterialCoverage> coverage = new ArrayList<>();
        CompletionStatus status = CompletionStatus.VERIFIED;
        boolean repairableCoverage = false;
        boolean unavailableCoverage = false;

        for (ResearchMaterialRequirement requirement : requested) {
            List<String> available = requirement.availableTools().stream()
                    .filter(request.toolPolicy().allowedTools()::contains)
                    .toList();
            List<DomainRuntimeFacts.ToolOutcome> acceptedOutcomes = observed.toolOutcomes().stream()
                    .filter(outcome -> available.contains(outcome.toolName()))
                    .toList();
            List<String> successfulTools = acceptedOutcomes.stream()
                    .filter(outcome -> outcome.executed() && outcome.success())
                    .map(DomainRuntimeFacts.ToolOutcome::toolName)
                    .distinct()
                    .toList();
            List<String> materialEvidence = evidenceFor(requirement.material(), ledger,
                    request.projectContext().projectId(), request.controlledWorkerDispatch() != null);
            DomainVerification.MaterialStatus materialStatus;
            if (!requirement.covered() || available.isEmpty()) {
                materialStatus = DomainVerification.MaterialStatus.TOOL_UNAVAILABLE;
                codes.add(DomainVerificationReasonCode.MATERIAL_TOOL_UNAVAILABLE);
                unavailableCoverage = true;
                status = CompletionStatus.INSUFFICIENT_EVIDENCE;
            } else if (successfulTools.isEmpty()) {
                boolean failed = acceptedOutcomes.stream().anyMatch(outcome -> outcome.executed() && !outcome.success());
                materialStatus = failed ? DomainVerification.MaterialStatus.TOOL_FAILED
                        : DomainVerification.MaterialStatus.TOOL_NOT_EXECUTED;
                codes.add(failed ? DomainVerificationReasonCode.REQUIRED_TOOL_FAILED
                        : DomainVerificationReasonCode.REQUIRED_TOOL_NOT_EXECUTED);
                repairableCoverage = true;
                status = worse(status, materialEvidence.isEmpty()
                        ? CompletionStatus.INSUFFICIENT_EVIDENCE : CompletionStatus.PARTIAL);
            } else if (materialEvidence.isEmpty()) {
                materialStatus = DomainVerification.MaterialStatus.EVIDENCE_MISSING;
                codes.add(DomainVerificationReasonCode.REQUIRED_TOOL_EXECUTED_SUCCESSFULLY);
                codes.add(DomainVerificationReasonCode.CURRENT_TRUSTED_EVIDENCE_MISSING_OR_INVALID);
                repairableCoverage = true;
                status = worse(status, CompletionStatus.INSUFFICIENT_EVIDENCE);
            } else {
                materialStatus = DomainVerification.MaterialStatus.COVERAGE_VERIFIED;
                codes.add(DomainVerificationReasonCode.REQUIRED_TOOL_EXECUTED_SUCCESSFULLY);
                codes.add(DomainVerificationReasonCode.CURRENT_TRUSTED_EVIDENCE_PRESENT);
                codes.add(DomainVerificationReasonCode.MATERIAL_COVERAGE_VERIFIED);
            }
            coverage.add(new DomainVerification.MaterialCoverage(requirement.material(), materialStatus,
                    available, successfulTools, materialEvidence));
        }

        status = applyPlanOutcomes(observed, codes, status);
        if (result.runtimeStopSignal() != AgentRuntimeStopSignal.NONE) {
            codes.add(DomainVerificationReasonCode.RUNTIME_BUDGET_EXHAUSTED);
            status = worse(status, CompletionStatus.PARTIAL);
        }

        DomainVerification.ConsistencyStatus consistencyStatus = DomainVerification.ConsistencyStatus.NOT_REQUIRED;
        List<DomainRuntimeFacts.ConsistencyFact> verifiedConsistencyFacts = List.of();
        if (requirements != null && requirements.crossMaterial()) {
            Set<ResearchMaterialKind> requestedMaterials = requested.stream()
                    .map(ResearchMaterialRequirement::material)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean allCovered = coverage.stream().allMatch(item ->
                    item.status() == DomainVerification.MaterialStatus.COVERAGE_VERIFIED);
            List<DomainRuntimeFacts.ConsistencyFact> candidates = new ArrayList<>(observed.consistencyFacts());
            candidates.addAll(consistencyRules.evaluate(requirements, coverage, ledger,
                    request.projectContext().projectId()));
            verifiedConsistencyFacts = allCovered ? candidates.stream().distinct()
                    .filter(fact -> validConsistencyFact(fact, requirements, requestedMaterials, coverage, ledger,
                            request.projectContext().projectId()))
                    .toList() : List.of();
            Set<Boolean> outcomes = verifiedConsistencyFacts.stream()
                    .map(DomainRuntimeFacts.ConsistencyFact::consistent)
                    .collect(java.util.stream.Collectors.toSet());
            if (outcomes.size() > 1) {
                consistencyStatus = DomainVerification.ConsistencyStatus.UNRESOLVED;
                codes.add(DomainVerificationReasonCode.CONSISTENCY_FACT_CONFLICT);
                codes.add(DomainVerificationReasonCode.CONSISTENCY_UNRESOLVED);
                status = worse(status, CompletionStatus.PARTIAL);
                verifiedConsistencyFacts = List.of();
            } else if (outcomes.contains(Boolean.TRUE)) {
                consistencyStatus = DomainVerification.ConsistencyStatus.VERIFIED_CONSISTENT;
                codes.add(DomainVerificationReasonCode.CONSISTENCY_VERIFIED_CONSISTENT_BY_STRUCTURED_FACT);
            } else if (outcomes.contains(Boolean.FALSE)) {
                consistencyStatus = DomainVerification.ConsistencyStatus.VERIFIED_INCONSISTENT;
                codes.add(DomainVerificationReasonCode.CONSISTENCY_VERIFIED_INCONSISTENT_BY_STRUCTURED_FACT);
            } else {
                consistencyStatus = DomainVerification.ConsistencyStatus.UNRESOLVED;
                codes.add(DomainVerificationReasonCode.CONSISTENCY_UNRESOLVED);
                status = worse(status, CompletionStatus.PARTIAL);
            }
        }

        int consumedToolCalls = observed.toolOutcomes().isEmpty()
                ? result.toolTrace().size() : observed.consumedToolCalls();
        boolean reflectionEligible = reflectionAttempts == 0
                && repairableCoverage
                && !unavailableCoverage
                && request.strategy() != AgentStrategy.PLAN_EXECUTE
                && request.strategy() != AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION
                && result.runtimeStopSignal() == AgentRuntimeStopSignal.NONE
                && result.steps() < request.maxSteps()
                && consumedToolCalls < request.toolPolicy().maxToolCalls();
        codes.add(reflectionEligible ? DomainVerificationReasonCode.REFLECTION_ELIGIBLE
                : DomainVerificationReasonCode.REFLECTION_NOT_ELIGIBLE);

        List<String> evidenceIds = coverage.stream().flatMap(item -> item.evidenceRefs().stream()).distinct().toList();
        return new DomainVerification(true, status, coverage, List.copyOf(codes), evidenceIds,
                consistencyStatus, reflectionEligible, verifiedConsistencyFacts);
    }

    private CompletionStatus applyPlanOutcomes(DomainRuntimeFacts facts,
                                               Set<DomainVerificationReasonCode> codes,
                                               CompletionStatus current) {
        CompletionStatus status = current;
        for (DomainRuntimeFacts.PlanStepOutcome step : facts.planStepOutcomes()) {
            if (step.status() == DomainRuntimeFacts.PlanStepStatus.FAILED) {
                codes.add(DomainVerificationReasonCode.PLAN_STEP_FAILED);
                status = worse(status, CompletionStatus.FAILED);
            } else if (step.status() == DomainRuntimeFacts.PlanStepStatus.SKIPPED) {
                codes.add(DomainVerificationReasonCode.PLAN_STEP_SKIPPED);
                status = worse(status, CompletionStatus.PARTIAL);
            } else if (step.controlledStop()
                    || step.status() == DomainRuntimeFacts.PlanStepStatus.DEGRADED
                    || step.status() == DomainRuntimeFacts.PlanStepStatus.PENDING
                    || step.status() == DomainRuntimeFacts.PlanStepStatus.RUNNING
                    || step.status() == DomainRuntimeFacts.PlanStepStatus.REPAIRING
                    || step.status() == DomainRuntimeFacts.PlanStepStatus.UNKNOWN) {
                codes.add(DomainVerificationReasonCode.PLAN_STEP_PARTIAL);
                status = worse(status, CompletionStatus.PARTIAL);
            }
        }
        return status;
    }

    private boolean validConsistencyFact(DomainRuntimeFacts.ConsistencyFact fact,
                                         AgentOrchestrationRequirements requirements,
                                         Set<ResearchMaterialKind> requestedMaterials,
                                         List<DomainVerification.MaterialCoverage> coverage,
                                         EvidenceLedger ledger,
                                         Long projectId) {
        return fact != null
                && fact.source() == DomainRuntimeFacts.ConsistencyFactSource.DETERMINISTIC_DOMAIN_RULE
                && consistencyRules.accepts(requirements, fact, coverage, ledger, projectId)
                && fact.materials().containsAll(requestedMaterials)
                && ledger.containsAllReferences(fact.evidenceRefs())
                && requestedMaterials.stream().allMatch(material -> evidenceFor(
                        material, ledger, projectId, false).stream()
                .anyMatch(fact.evidenceRefs()::contains));
    }

    private List<String> evidenceFor(ResearchMaterialKind material, EvidenceLedger ledger, Long projectId,
                                     boolean allowControlledWorkerEvidence) {
        return ledger.evidence().stream()
                .filter(ref -> ref.sourceType() == EvidenceSourceType.PROJECT)
                .filter(ref -> ProjectEvidenceValidator.isTrusted(ref)
                        || (allowControlledWorkerEvidence
                        && ProjectEvidenceValidator.isControlledWorkerEvidence(ref, projectId)))
                .filter(ref -> (allowControlledWorkerEvidence
                        && ProjectEvidenceValidator.isControlledWorkerEvidence(ref, projectId))
                        || ref.id().startsWith("trusted-tool:" + projectId + ":")
                        || ref.id().startsWith("trusted-plan:" + projectId + ":"))
                .filter(ref -> ref.versionStatus() == EvidenceVersionStatus.VERIFIED)
                .filter(ref -> matchesMaterial(material, ref.file()))
                .map(EvidenceRef::id)
                .distinct()
                .toList();
    }

    private boolean matchesMaterial(ResearchMaterialKind material, String path) {
        if (material == null || !StringUtils.hasText(path) || "manifest".equals(path)) return false;
        String normalized = path.toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        String extension = dot < 0 || dot == normalized.length() - 1 ? "" : normalized.substring(dot + 1);
        return switch (material) {
            case PAPER_LATEX -> PAPER_EXTENSIONS.contains(extension);
            case CODE -> CODE_EXTENSIONS.contains(extension);
            case EXPERIMENT_CONFIG -> EXPERIMENT_EXTENSIONS.contains(extension);
            case BIBTEX -> BIBTEX_EXTENSIONS.contains(extension);
        };
    }

    private CompletionStatus worse(CompletionStatus left, CompletionStatus right) {
        if (left == CompletionStatus.FAILED || right == CompletionStatus.FAILED) return CompletionStatus.FAILED;
        if (left == CompletionStatus.INSUFFICIENT_EVIDENCE || right == CompletionStatus.INSUFFICIENT_EVIDENCE) {
            return CompletionStatus.INSUFFICIENT_EVIDENCE;
        }
        if (left == CompletionStatus.PARTIAL || right == CompletionStatus.PARTIAL) return CompletionStatus.PARTIAL;
        return CompletionStatus.VERIFIED;
    }
}
