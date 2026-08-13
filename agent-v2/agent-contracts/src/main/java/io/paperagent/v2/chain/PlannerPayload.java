package io.paperagent.v2.chain;

import java.util.List;
import java.util.Objects;

public sealed interface PlannerPayload extends ChainProposalPayload permits
        PlannerPayload.DirectRoute, PlannerPayload.PersistentPlan, PlannerPayload.PlanRevision,
        PlannerPayload.NeedUserInput, PlannerPayload.NeedPermission,
        PlannerPayload.UserInstructionDisposition, PlannerPayload.PlanningBlocked {

    record DirectRoute(
            String routeReason,
            String directTaskSpecification,
            List<String> userConstraints,
            List<String> answerRequiredRefs,
            boolean needsTool,
            boolean needsNetwork,
            boolean needsProject,
            boolean needsPersistentProgress,
            GapValidation gapValidation) implements PlannerPayload {
        public DirectRoute {
            routeReason = ChainValues.required(routeReason, "routeReason");
            directTaskSpecification = ChainValues.required(directTaskSpecification, "directTaskSpecification");
            userConstraints = ChainValues.copy(userConstraints, "userConstraints");
            answerRequiredRefs = ChainValues.copy(answerRequiredRefs, "answerRequiredRefs");
            if (needsTool || needsNetwork || needsProject || needsPersistentProgress) {
                throw new IllegalArgumentException("DIRECT_ROUTE requires all persistent boundary conclusions to be false");
            }
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_DIRECT_ROUTE; }
    }

    record PersistentPlan(
            ProposalFields.TaskFrameDraft taskFrameDraft,
            ProposalFields.RoutingBoundary routingBoundary,
            List<ProposalFields.RequirementCoverage> requirementCoverage,
            ProposalFields.PlanDraft initialPlan,
            List<ProposalFields.ApplicabilitySuggestion> predecessorApplicability,
            GapValidation gapValidation) implements PlannerPayload {
        public PersistentPlan {
            taskFrameDraft = Objects.requireNonNull(taskFrameDraft, "taskFrameDraft");
            routingBoundary = Objects.requireNonNull(
                    routingBoundary, "routingBoundary");
            if (!routingBoundary.requiresPersistentExecution()) {
                throw new IllegalArgumentException(
                        "PERSISTENT_ROUTE_WITHOUT_REQUIREMENT: PERSISTENT_PLAN requires at least one persistent routing boundary");
            }
            requirementCoverage = ChainValues.nonEmptyCopy(requirementCoverage, "requirementCoverage");
            initialPlan = Objects.requireNonNull(initialPlan, "initialPlan");
            predecessorApplicability = ChainValues.copy(predecessorApplicability, "predecessorApplicability");
            requireExactValidationBindings(taskFrameDraft, initialPlan);
        }

        public PersistentPlan(
                ProposalFields.TaskFrameDraft taskFrameDraft,
                List<ProposalFields.RequirementCoverage> requirementCoverage,
                ProposalFields.PlanDraft initialPlan,
                List<ProposalFields.ApplicabilitySuggestion>
                        predecessorApplicability,
                GapValidation gapValidation) {
            this(taskFrameDraft,
                    new ProposalFields.RoutingBoundary(
                            false, false, false, true),
                    requirementCoverage, initialPlan,
                    predecessorApplicability, gapValidation);
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_PERSISTENT_PLAN; }
    }

    private static void requireExactValidationBindings(
            ProposalFields.TaskFrameDraft taskFrame,
            ProposalFields.PlanDraft plan) {
        java.util.Map<String, String> required = new java.util.LinkedHashMap<>();
        taskFrame.requirements().validationRequirements().forEach(requirement ->
                required.put(requirement.requirementId(),
                        requirement.completionCondition()));
        java.util.HashSet<String> bound = new java.util.HashSet<>();
        java.util.Map<String, ProposalFields.StepDraft> bindingSteps =
                new java.util.LinkedHashMap<>();
        ProposalFields.StepDraft lastCandidateChangingStep = null;
        for (ProposalFields.StepDraft step : plan.steps()) {
            if (step.mayChangeCandidate()) {
                lastCandidateChangingStep = step;
            }
            for (String requirementId : step.validationRequirementIds()) {
                String completionCondition = required.get(requirementId);
                if (completionCondition == null) {
                    throw new IllegalArgumentException(
                            "Step references an undeclared validation requirement");
                }
                if (!step.completionConditions().contains(completionCondition)) {
                    throw new IllegalArgumentException(
                            "validation requirement completion condition must be an exact Step completion condition");
                }
                bound.add(requirementId);
                bindingSteps.put(requirementId, step);
            }
        }
        if (!bound.equals(required.keySet())) {
            List<String> missingRequirementIds = required.keySet().stream()
                    .filter(requirementId -> !bound.contains(requirementId))
                    .toList();
            throw new IllegalArgumentException(
                    "declared validation requirements are not bound to any Step; missing requirementIds="
                            + missingRequirementIds);
        }
        if (lastCandidateChangingStep != null) {
            List<io.paperagent.v2.contracts.ValidationRequirement> candidateRequirements =
                    taskFrame.requirements().validationRequirements().stream()
                            .filter(requirement -> requirement.subject()
                                    == io.paperagent.v2.contracts.ValidationSubject.CANDIDATE)
                            .toList();
            if (candidateRequirements.size() != 1) {
                throw new IllegalArgumentException(
                        "a Candidate-changing Plan requires exactly one explicit CANDIDATE validation requirement; actual CANDIDATE requirement count="
                                + candidateRequirements.size());
            }
            ProposalFields.StepDraft boundStep = bindingSteps.get(
                    candidateRequirements.get(0).requirementId());
            if (boundStep == null || boundStep.mayChangeCandidate()
                    || !dependsOn(boundStep, lastCandidateChangingStep,
                    plan.steps())) {
                throw new IllegalArgumentException(
                        "the explicit CANDIDATE validation requirement must be closed by a later non-changing validation Step that depends on the last Candidate-changing Step");
            }
        }
    }

    private static boolean dependsOn(
            ProposalFields.StepDraft step,
            ProposalFields.StepDraft requiredPredecessor,
            List<ProposalFields.StepDraft> steps) {
        java.util.Map<String, ProposalFields.StepDraft> byKey =
                new java.util.LinkedHashMap<>();
        steps.forEach(value -> byKey.put(value.stepKey(), value));
        java.util.ArrayDeque<String> remaining = new java.util.ArrayDeque<>(
                step.dependencyStepKeys());
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        while (!remaining.isEmpty()) {
            String dependency = remaining.removeFirst();
            if (!visited.add(dependency)) {
                continue;
            }
            if (dependency.equals(requiredPredecessor.stepKey())) {
                return true;
            }
            ProposalFields.StepDraft predecessor = byKey.get(dependency);
            if (predecessor != null) {
                remaining.addAll(predecessor.dependencyStepKeys());
            }
        }
        return false;
    }

    record PlanRevision(
            String triggerDecisionOrGapRef,
            String oldRevisionRef,
            ProposalFields.PlanDraft newRevisionDraft,
            List<ProposalFields.RequirementCoverage> requirementCoverage,
            List<ProposalFields.ApplicabilitySuggestion> applicability,
            List<String> unmetRequirements,
            List<String> assumptions,
            List<String> risks,
            String taskFrameRef,
            GapValidation gapValidation) implements PlannerPayload {
        public PlanRevision {
            triggerDecisionOrGapRef = ChainValues.required(triggerDecisionOrGapRef, "triggerDecisionOrGapRef");
            oldRevisionRef = ChainValues.required(oldRevisionRef, "oldRevisionRef");
            newRevisionDraft = Objects.requireNonNull(newRevisionDraft, "newRevisionDraft");
            requirementCoverage = ChainValues.nonEmptyCopy(requirementCoverage, "requirementCoverage");
            applicability = ChainValues.copy(applicability, "applicability");
            unmetRequirements = ChainValues.copy(unmetRequirements, "unmetRequirements");
            assumptions = ChainValues.copy(assumptions, "assumptions");
            risks = ChainValues.copy(risks, "risks");
            taskFrameRef = ChainValues.required(taskFrameRef, "taskFrameRef");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_PLAN_REVISION; }
    }

    record NeedUserInput(
            List<String> missingFields,
            String userSpecificReason,
            String exactQuestion,
            String expectedFormat,
            List<String> closingConditions,
            ChainRole validationRole,
            ChainRole resumeRole,
            String resumePosition,
            GapValidation gapValidation) implements PlannerPayload {
        public NeedUserInput {
            missingFields = ChainValues.nonEmptyCopy(missingFields, "missingFields");
            userSpecificReason = ChainValues.required(userSpecificReason, "userSpecificReason");
            exactQuestion = ChainValues.required(exactQuestion, "exactQuestion");
            expectedFormat = ChainValues.required(expectedFormat, "expectedFormat");
            closingConditions = ChainValues.nonEmptyCopy(closingConditions, "closingConditions");
            validationRole = Objects.requireNonNull(validationRole, "validationRole");
            resumeRole = Objects.requireNonNull(resumeRole, "resumeRole");
            resumePosition = ChainValues.required(resumePosition, "resumePosition");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_NEED_USER_INPUT; }
    }

    record NeedPermission(
            String permissionKind,
            String scope,
            String purpose,
            String lowerPrivilegeAlternative,
            String reintakePosition,
            GapValidation gapValidation) implements PlannerPayload {
        public NeedPermission {
            permissionKind = ChainValues.required(permissionKind, "permissionKind");
            scope = ChainValues.required(scope, "scope");
            purpose = ChainValues.required(purpose, "purpose");
            lowerPrivilegeAlternative = ChainValues.required(lowerPrivilegeAlternative, "lowerPrivilegeAlternative");
            reintakePosition = ChainValues.required(reintakePosition, "reintakePosition");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_NEED_PERMISSION; }
    }

    record UserInstructionDisposition(
            String instructionRef,
            String classification,
            String oldTaskDisposition,
            boolean replyRequired,
            String continuationOrReintakePosition,
            boolean boundaryChanged,
            List<ProposalFields.ApplicabilitySuggestion> applicability,
            List<String> nonAuthoritativeReuseSuggestions,
            GapValidation gapValidation) implements PlannerPayload {
        public UserInstructionDisposition {
            instructionRef = ChainValues.required(instructionRef, "instructionRef");
            classification = ChainValues.required(classification, "classification");
            oldTaskDisposition = ChainValues.required(oldTaskDisposition, "oldTaskDisposition");
            continuationOrReintakePosition = ChainValues.required(
                    continuationOrReintakePosition, "continuationOrReintakePosition");
            applicability = ChainValues.copy(applicability, "applicability");
            nonAuthoritativeReuseSuggestions = ChainValues.copy(
                    nonAuthoritativeReuseSuggestions, "nonAuthoritativeReuseSuggestions");
            if (boundaryChanged && !applicability.isEmpty()) {
                throw new IllegalArgumentException("boundary-changing disposition cannot submit applicability");
            }
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_USER_INSTRUCTION_DISPOSITION; }
    }

    record PlanningBlocked(
            String blockerCategory,
            List<String> knownFactRefs,
            String reason,
            String recoveryCondition,
            String gapOrFailureRecommendation,
            GapValidation gapValidation) implements PlannerPayload {
        public PlanningBlocked {
            blockerCategory = ChainValues.required(blockerCategory, "blockerCategory");
            knownFactRefs = ChainValues.nonEmptyCopy(knownFactRefs, "knownFactRefs");
            reason = ChainValues.required(reason, "reason");
            recoveryCondition = ChainValues.required(recoveryCondition, "recoveryCondition");
            gapOrFailureRecommendation = ChainValues.required(gapOrFailureRecommendation, "gapOrFailureRecommendation");
        }

        @Override public ChainProposalKind kind() { return ChainProposalKind.PLANNER_PLANNING_BLOCKED; }
    }
}
