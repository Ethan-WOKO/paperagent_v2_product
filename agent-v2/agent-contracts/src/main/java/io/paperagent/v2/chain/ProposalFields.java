package io.paperagent.v2.chain;

import io.paperagent.v2.contracts.RequirementDeclarationMode;
import io.paperagent.v2.contracts.TaskRequirements;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;

public final class ProposalFields {
    private ProposalFields() {
    }

    public record ApplicabilitySuggestion(
            String acceptedResultId,
            ChainApplicability.Outcome outcome,
            String reason,
            String usePosition) {
        public ApplicabilitySuggestion {
            acceptedResultId = ChainValues.required(acceptedResultId, "acceptedResultId");
            outcome = Objects.requireNonNull(outcome, "outcome");
            reason = ChainValues.required(reason, "reason");
            usePosition = ChainValues.required(usePosition, "usePosition");
        }
    }

    public record ReviewCommon(
            String reviewScope,
            List<String> reviewedObjectRefs,
            String decisionReason,
            List<String> directFactRefs,
            List<String> knownLimitations) {
        public ReviewCommon {
            reviewScope = ChainValues.required(reviewScope, "reviewScope");
            reviewedObjectRefs = ChainValues.nonEmptyCopy(reviewedObjectRefs, "reviewedObjectRefs");
            decisionReason = ChainValues.required(decisionReason, "decisionReason");
            directFactRefs = ChainValues.nonEmptyCopy(directFactRefs, "directFactRefs");
            knownLimitations = ChainValues.copy(knownLimitations, "knownLimitations");
        }
    }

    public enum RequirementStatus {
        PLANNED,
        SATISFIED,
        UNSATISFIED,
        NOT_APPLICABLE
    }

    public enum AssessmentStatus {
        BOUND,
        NOT_REQUIRED,
        MISSING,
        UNSATISFIED
    }

    public record RoutingBoundary(
            boolean needsTool,
            boolean needsNetwork,
            boolean needsProject,
            boolean needsPersistentProgress) {
        public boolean requiresPersistentExecution() {
            return needsTool || needsNetwork || needsProject
                    || needsPersistentProgress;
        }
    }

    /** Exact frozen requirement to original formal Receipt mapping. */
    public record ValidationSource(
            String requirementId,
            String receiptRef) {
        public ValidationSource {
            requirementId = ChainValues.required(
                    requirementId, "requirementId");
            receiptRef = ChainValues.required(receiptRef, "receiptRef");
        }
    }

    public record TaskFrameDraft(
            String objective,
            List<String> objects,
            List<String> deliverables,
            List<String> constraints,
            String projectVersion,
            String permissionTier,
            TaskRequirements requirements) {
        public TaskFrameDraft {
            objective = ChainValues.required(objective, "objective");
            objects = ChainValues.nonEmptyCopy(objects, "objects");
            deliverables = ChainValues.nonEmptyCopy(deliverables, "deliverables");
            constraints = ChainValues.copy(constraints, "constraints");
            projectVersion = ChainValues.required(projectVersion, "projectVersion");
            permissionTier = ChainValues.required(permissionTier, "permissionTier");
            requirements = Objects.requireNonNull(requirements, "requirements");
            if (requirements.declarationMode()
                    != RequirementDeclarationMode.EXPLICIT) {
                throw new IllegalArgumentException(
                        "Planner TaskFrame requirements must be explicit");
            }
        }
    }

    public record StepDraft(
            String stepKey,
            int stableOrder,
            String objective,
            List<String> dependencyStepKeys,
            List<String> completionConditions,
            List<String> scopes,
            List<String> deliverables,
            boolean mayChangeCandidate,
            String candidateValidationCompletionCondition,
            List<String> constraints,
            List<String> validationRequirementIds) {
        public StepDraft(
                String stepKey,
                int stableOrder,
                String objective,
                List<String> dependencyStepKeys,
                List<String> completionConditions,
                List<String> scopes,
                List<String> deliverables,
                boolean mayChangeCandidate,
                String candidateValidationCompletionCondition) {
            this(stepKey, stableOrder, objective, dependencyStepKeys,
                    completionConditions, scopes, deliverables,
                    mayChangeCandidate, candidateValidationCompletionCondition,
                    List.of(), List.of());
        }

        public StepDraft(
                String stepKey,
                int stableOrder,
                String objective,
                List<String> dependencyStepKeys,
                List<String> completionConditions,
                List<String> scopes,
                List<String> deliverables,
                boolean mayChangeCandidate,
                String candidateValidationCompletionCondition,
                List<String> constraints) {
            this(stepKey, stableOrder, objective, dependencyStepKeys,
                    completionConditions, scopes, deliverables,
                    mayChangeCandidate, candidateValidationCompletionCondition,
                    constraints, List.of());
        }

        public StepDraft {
            stepKey = ChainValues.required(stepKey, "stepKey");
            if (stableOrder < 1) {
                throw new IllegalArgumentException("stableOrder must be positive");
            }
            objective = ChainValues.required(objective, "objective");
            dependencyStepKeys = ChainValues.copy(dependencyStepKeys, "dependencyStepKeys");
            completionConditions = ChainValues.nonEmptyCopy(completionConditions, "completionConditions");
            scopes = ChainValues.nonEmptyCopy(scopes, "scopes");
            deliverables = ChainValues.nonEmptyCopy(deliverables, "deliverables");
            constraints = ChainValues.copy(constraints, "constraints").stream()
                    .map(value -> ChainValues.required(value, "constraints[]"))
                    .toList();
            validationRequirementIds = ChainValues.copy(
                    validationRequirementIds,
                    "validationRequirementIds").stream()
                    .map(value -> ChainValues.required(value,
                            "validationRequirementIds[]"))
                    .toList();
            if (new HashSet<>(validationRequirementIds).size()
                    != validationRequirementIds.size()) {
                throw new IllegalArgumentException(
                        "validationRequirementIds must be unique within a Step");
            }
            if (candidateValidationCompletionCondition != null) {
                candidateValidationCompletionCondition = ChainValues.required(
                        candidateValidationCompletionCondition, "candidateValidationCompletionCondition");
                if (!completionConditions.contains(candidateValidationCompletionCondition)) {
                    throw new IllegalArgumentException(
                            "candidate validation completion condition must be one of the step completion conditions");
                }
            }
        }
    }

    public record PlanDraft(List<StepDraft> steps) {
        public PlanDraft {
            steps = ChainValues.nonEmptyCopy(steps, "steps");
            HashSet<String> seenKeys = new HashSet<>();
            HashSet<String> boundValidationRequirements = new HashSet<>();
            for (int index = 0; index < steps.size(); index++) {
                StepDraft step = Objects.requireNonNull(steps.get(index), "steps[" + index + "]");
                if (step.stableOrder() != index + 1) {
                    throw new IllegalArgumentException("step stableOrder must be contiguous and list-ordered");
                }
                if (!seenKeys.add(step.stepKey())) {
                    throw new IllegalArgumentException("stepKey must be unique within the Plan draft");
                }
                if (step.dependencyStepKeys().contains(step.stepKey())) {
                    throw new IllegalArgumentException("a step cannot depend on itself");
                }
                for (String dependency : step.dependencyStepKeys()) {
                    if (!seenKeys.contains(dependency)) {
                        throw new IllegalArgumentException(
                                "dependencies must reference an earlier step in stable order");
                    }
                }
                for (String requirementId : step.validationRequirementIds()) {
                    if (!boundValidationRequirements.add(requirementId)) {
                        throw new IllegalArgumentException(
                                "a validation requirement may be closed by only one Step");
                    }
                }
            }
        }
    }

    public record RequirementCoverage(
            String requirement, RequirementStatus status, List<String> factRefs) {
        public RequirementCoverage {
            requirement = ChainValues.required(requirement, "requirement");
            status = Objects.requireNonNull(status, "status");
            factRefs = ChainValues.copy(factRefs, "factRefs");
            if (status == RequirementStatus.SATISFIED && factRefs.isEmpty()) {
                throw new IllegalArgumentException("SATISFIED coverage requires at least one fact ref");
            }
            if ((status == RequirementStatus.PLANNED || status == RequirementStatus.NOT_APPLICABLE)
                    && !factRefs.isEmpty()) {
                throw new IllegalArgumentException(status + " coverage cannot carry fact refs");
            }
        }
    }

    public record AuthorityAssessment(
            AssessmentStatus status, String authorityRef, String reason) {
        public AuthorityAssessment {
            status = Objects.requireNonNull(status, "status");
            if (status == AssessmentStatus.BOUND) {
                authorityRef = ChainValues.required(authorityRef, "authorityRef");
                if (reason != null) {
                    throw new IllegalArgumentException("BOUND assessment cannot carry a non-binding reason");
                }
            } else {
                if (authorityRef != null) {
                    throw new IllegalArgumentException(status + " assessment cannot carry an authority ref");
                }
                reason = ChainValues.required(reason, "reason");
            }
        }
    }

    public record FinalizationAssessment(
            List<RequirementCoverage> requirementCoverage,
            AuthorityAssessment finalArtifactAssessment,
            AuthorityAssessment finalCandidateAssessment,
            AuthorityAssessment validationAssessment,
            AuthorityAssessment publishRequirementAssessment,
            List<String> userVisibleFacts,
            List<String> residualRisks) {
        public FinalizationAssessment {
            requirementCoverage = ChainValues.nonEmptyCopy(requirementCoverage, "requirementCoverage");
            finalArtifactAssessment = Objects.requireNonNull(finalArtifactAssessment, "finalArtifactAssessment");
            finalCandidateAssessment = Objects.requireNonNull(finalCandidateAssessment, "finalCandidateAssessment");
            validationAssessment = Objects.requireNonNull(validationAssessment, "validationAssessment");
            publishRequirementAssessment = Objects.requireNonNull(
                    publishRequirementAssessment, "publishRequirementAssessment");
            userVisibleFacts = ChainValues.copy(userVisibleFacts, "userVisibleFacts");
            residualRisks = ChainValues.copy(residualRisks, "residualRisks");
        }
    }
}
