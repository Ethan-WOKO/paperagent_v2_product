package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainRole;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen 13-by-4 minimum-input matrix. Values are stable semantic field names,
 * not prompt prose and not physical persistence columns.
 */
public final class ChainContextInputMatrix {
    private static final List<String> COMMON_FOUNDATION = List.of(
            "foundation.instructionChain",
            "foundation.conversationWithCoverage",
            "foundation.taskFrameAndHardBoundary",
            "foundation.stateHeader",
            "foundation.latestDecisionCallReasonAndPendingItem",
            "foundation.roleRulesSchemaPermissionBoundaryAndSkills",
            "foundation.contextRevisionAndSourceVersions");

    private static final Map<ChainRole, Map<ChainContextModule, List<String>>> MATRIX = build();

    private ChainContextInputMatrix() {
    }

    public static List<String> commonFoundation() {
        return COMMON_FOUNDATION;
    }

    public static List<String> requirements(ChainRole role, ChainContextModule module) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(module, "module");
        return MATRIX.get(role).get(module);
    }

    /** Requirements a source projector must attest for this concrete module projection. */
    public static List<String> requiredProjectionFields(
            ChainRole role, ChainContextModule module) {
        List<String> required = new ArrayList<>(requirements(role, module));
        String foundation = switch (module) {
            case USER_INSTRUCTION_CHAIN -> COMMON_FOUNDATION.get(0);
            case CONVERSATION_CONTEXT -> COMMON_FOUNDATION.get(1);
            case TASK_CONTRACT -> COMMON_FOUNDATION.get(2);
            case TASK_AND_STEP_RUNTIME_STATE -> COMMON_FOUNDATION.get(3);
            case REVIEW_DECISIONS_AND_PENDING_ITEMS -> COMMON_FOUNDATION.get(4);
            case RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS -> COMMON_FOUNDATION.get(5);
            case MODEL_INVOCATIONS_AND_PROPOSALS -> COMMON_FOUNDATION.get(6);
            default -> null;
        };
        if (foundation != null) {
            required.add(foundation);
        }
        return List.copyOf(required);
    }

    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(52);
        for (ChainContextModule module : orderedModules()) {
            for (ChainRole role : ChainRole.values()) {
                entries.add(new Entry(role, module, requirements(role, module)));
            }
        }
        return List.copyOf(entries);
    }

    public static List<ChainContextModule> orderedModules() {
        return List.of(ChainContextModule.values()).stream()
                .sorted(java.util.Comparator.comparingInt(ChainContextModule::ordinalCode))
                .toList();
    }

    public record Entry(ChainRole role, ChainContextModule module, List<String> requirements) {
        public Entry {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(module, "module");
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
            if (requirements.isEmpty()) {
                throw new IllegalArgumentException("matrix entry requirements must not be empty");
            }
        }
    }

    private static Map<ChainRole, Map<ChainContextModule, List<String>>> build() {
        EnumMap<ChainRole, Map<ChainContextModule, List<String>>> matrix = new EnumMap<>(ChainRole.class);
        for (ChainRole role : ChainRole.values()) {
            matrix.put(role, new EnumMap<>(ChainContextModule.class));
        }

        put(matrix, ChainContextModule.USER_INSTRUCTION_CHAIN,
                List.of("instructions.completeStructure", "instructions.effectiveBodies", "instructions.relations"),
                List.of("instructions.completeStructure", "instructions.effectiveBodies", "instructions.relations", "instructions.runningInstructionState"),
                List.of("instructions.completeStructure", "instructions.effectiveBodies", "instructions.relations", "instructions.reviewScope"),
                List.of("instructions.completeStructure", "instructions.effectiveBodies", "instructions.relations", "instructions.expressionRequirements"));
        put(matrix, ChainContextModule.CONVERSATION_CONTEXT,
                List.of("conversation.recentComplete", "conversation.earlierSummary", "conversation.summaryCoverage"),
                List.of("conversation.latestUserMessage", "conversation.recentComplete", "conversation.earlierSummary", "conversation.summaryCoverage"),
                List.of("conversation.latestUserMessage", "conversation.recentComplete", "conversation.earlierSummary", "conversation.summaryCoverage"),
                List.of("conversation.recentComplete", "conversation.earlierSummary", "conversation.summaryCoverage"));
        put(matrix, ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                List.of("project.version", "project.manifest.complete", "project.explicitInputExpansion"),
                List.of("project.version", "project.manifest.complete", "project.currentStepObjects", "project.targetAndModifiedFileExpansion"),
                List.of("project.version", "project.manifest.complete", "project.reviewedAndDiffAffectedExpansion"),
                List.of("project.finalInputAndDeliveryObjects", "project.artifactOrCandidate", "project.deliveryManifest", "project.userVisibleBodyExpansion"));
        put(matrix, ChainContextModule.TASK_CONTRACT,
                List.of("taskFrame.completeOrExplicitEmpty", "taskFrame.hardBoundary"),
                List.of("taskFrame.complete", "taskFrame.hardBoundary"),
                List.of("taskFrame.complete", "taskFrame.deliveryRequirements", "taskFrame.validationRequirements"),
                List.of("taskFrame.persistentCompleteOrDirectEmpty", "taskFrame.officialRouteOrOutcome"));
        put(matrix, ChainContextModule.PLAN_AND_STEP_CONTRACT,
                List.of("plan.currentRevisionCompleteOrExplicitEmpty"),
                List.of("plan.currentRevisionComplete", "plan.currentStep", "plan.dependencies", "plan.completionConditions", "plan.constraints", "plan.scope", "plan.deliverables"),
                List.of("plan.currentRevisionComplete", "plan.currentStep", "plan.directDependencies", "plan.affectedSteps"),
                List.of("plan.persistentTerminalOrDirectEmpty"));
        put(matrix, ChainContextModule.TASK_AND_STEP_RUNTIME_STATE,
                List.of("runtime.executionMode", "runtime.steps", "runtime.acceptedResultCatalog", "runtime.applicability", "runtime.predecessorAcceptedResultCatalog"),
                List.of("runtime.currentStep", "runtime.candidateResult", "runtime.acceptedResultCatalog", "runtime.directDependencies"),
                List.of("runtime.currentStep", "runtime.candidateResult", "runtime.acceptedResultCatalog", "runtime.directDependencies", "runtime.affectedResults"),
                List.of("runtime.taskOutcome", "runtime.acceptedResultTerminalProjection", "runtime.deliveryRecord", "runtime.answerPayloadTemplate"));
        put(matrix, ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS,
                List.of("action.unresolvedFailures", "action.terminalSummary"),
                List.of("action.currentStepAttemptTable", "action.latestOrUnresolvedReceiptAndErrorExpansion"),
                List.of("action.currentStepAttemptTable", "action.keyReceiptAndError", "action.diagnosis", "action.noProgressState"),
                List.of("action.officialFailureSummaryOnly"));
        put(matrix, ChainContextModule.WORKSPACE_AND_CANDIDATE,
                List.of("workspace.manifest.complete", "workspace.currentState", "workspace.diffSummary"),
                List.of("workspace.exactVersion", "workspace.manifest.complete", "workspace.targetAndModifiedFileExpansion"),
                List.of("workspace.reviewedCandidate", "workspace.diff", "workspace.artifacts", "workspace.affectedFileExpansion"),
                List.of("workspace.finalArtifactOrCandidate", "workspace.deliveryManifest"));
        put(matrix, ChainContextModule.VALIDATION_AND_PUBLISH,
                List.of("validation.latestState", "validation.finalizationState", "validation.publishState", "validation.failureSummary", "validation.publishRequirement"),
                List.of("validation.currentStepFormalValidation", "validation.finalizationFailureSeparated"),
                List.of("validation.authoritativeValidation", "validation.finalizationCheckResult", "validation.publishRequirement", "validation.publishFailure"),
                List.of("validation.finalValidation", "validation.coverageSkipAndRisk", "validation.finalizationAndPublishResultOrNotRequired"));
        put(matrix, ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS,
                List.of("review.latestDecision", "review.replanGap", "review.instructionDisposition", "review.resumePosition"),
                List.of("review.latestDecision", "review.previousReviewGap", "review.loopState"),
                List.of("review.objectBoundDecisionHistory", "review.currentGap", "review.loopState"),
                List.of("review.currentQuestionPermissionFailureCompletionOrInstructionDecision"));
        put(matrix, ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                List.of("evidence.frozenCompleteCatalog", "evidence.planningProjection"),
                List.of("evidence.frozenCompleteCatalog", "evidence.currentStepMechanicalExpansion"),
                List.of("evidence.frozenCatalog", "evidence.candidateReferencedMechanicalExpansion"),
                List.of("evidence.directFrozenOrAcceptedDeliveryEvidence"));
        put(matrix, ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                List.of("rules.plannerSchema", "rules.capabilities", "rules.toolCategories", "rules.skills", "rules.permissions", "rules.hardBoundary"),
                List.of("rules.executorSchema", "rules.completeToolSchemas", "rules.skills", "rules.permissions", "rules.workingDirectory", "rules.writeScope"),
                List.of("rules.reflectorSchema", "rules.acceptanceRules", "rules.permissions", "rules.skillAcceptanceRequirements", "rules.finalizationRules"),
                List.of("rules.answerSchema", "rules.expressionRequirements", "rules.noDiscoveryExecutionOrWrite"));
        put(matrix, ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                List.of("model.stateHeader", "model.callReason", "model.latestAcceptedOrFailedPlannerMetadata"),
                List.of("model.stateHeader", "model.callReason", "model.currentAndLatestExecutorMetadata"),
                List.of("model.stateHeader", "model.reviewedCandidateProposal", "model.latestFailureMetadata"),
                List.of("model.stateHeader", "model.officialSourceRecords", "model.latestDeliveryFailureMetadata"));

        for (ChainRole role : ChainRole.values()) {
            Map<ChainContextModule, List<String>> roleMatrix = matrix.get(role);
            if (roleMatrix.size() != 13) {
                throw new ExceptionInInitializerError("incomplete context input matrix for " + role);
            }
            matrix.put(role, Map.copyOf(roleMatrix));
        }
        return Map.copyOf(matrix);
    }

    private static void put(
            Map<ChainRole, Map<ChainContextModule, List<String>>> matrix,
            ChainContextModule module,
            List<String> planner,
            List<String> executor,
            List<String> reflector,
            List<String> answer) {
        matrix.get(ChainRole.PLANNER).put(module, List.copyOf(planner));
        matrix.get(ChainRole.EXECUTOR).put(module, List.copyOf(executor));
        matrix.get(ChainRole.REFLECTOR).put(module, List.copyOf(reflector));
        matrix.get(ChainRole.ANSWER).put(module, List.copyOf(answer));
    }
}
