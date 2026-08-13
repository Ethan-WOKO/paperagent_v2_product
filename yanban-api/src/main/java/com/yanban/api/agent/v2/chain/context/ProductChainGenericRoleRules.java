package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Scenario-neutral semantic rules shared by every product chain invocation. */
final class ProductChainGenericRoleRules {
    private ProductChainGenericRoleRules() {
    }

    static ChainContextValue definition(ChainRole role) {
        Objects.requireNonNull(role, "role");
        return switch (role) {
            case PLANNER -> planner();
            case EXECUTOR -> executor();
            case REFLECTOR -> reflector();
            case ANSWER -> answer();
        };
    }

    private static ChainContextValue planner() {
        return ChainContextValue.object(Map.of(
                "routingRules", ChainContextValue.object(Map.ofEntries(
                        Map.entry("classifyFromEffectiveUserRequestOnly",
                                ChainContextValue.bool(true)),
                        Map.entry("visibleProjectOrCapabilityDoesNotCreateRequirement",
                                ChainContextValue.bool(true)),
                        Map.entry("allBoundaryFlagsFalseRequiresDirectRoute",
                                ChainContextValue.bool(true)),
                        Map.entry("persistentPlanRequiresAtLeastOneTrueBoundaryFlag",
                                ChainContextValue.bool(true)),
                        Map.entry("persistentProgressMustComeFromTaskNotSelectedKind",
                                ChainContextValue.bool(true)))),
                "planningBoundary", ChainContextValue.object(Map.of(
                        "constructPlanOnly", ChainContextValue.bool(true),
                        "runtimeAuthorityComesFromCommittedTaskFrame",
                        ChainContextValue.bool(true))),
                "requirementRules", ChainContextValue.object(Map.of(
                        "coverEveryExplicitUserRequirement",
                        ChainContextValue.bool(true),
                        "placeStepSpecificRequirementsInStepConstraints",
                        ChainContextValue.bool(true),
                        "doNotInventSourceOrAuthorityReferences",
                        ChainContextValue.bool(true))),
                "proposalProtocolRules", ChainContextValue.object(Map.of(
                        "satisfiedCoverageRequiresVisibleFactRefs",
                        ChainContextValue.bool(true),
                        "plannedAndUnsatisfiedCoverageRequireEmptyFactRefs",
                        ChainContextValue.bool(true),
                        "gapValidationOnlyWhenValidatingBoundPendingItem",
                        ChainContextValue.bool(true),
                        "otherwiseGapValidationMustBeNull",
                        ChainContextValue.bool(true),
                        "candidateValidationConditionExactlyMatchesStepCompletionCondition",
                        ChainContextValue.bool(true),
                        "absentCandidateValidationConditionUsesJsonNullNeverEmptyString",
                        ChainContextValue.bool(true),
                        "schemaRepairPreservesSemanticProposalKindUnlessAuthorityChangesRoute",
                        ChainContextValue.bool(true),
                        "planningBlockedRequiresVisibleKnownFactRefs",
                        ChainContextValue.bool(true))),
                "changePreservationRules", ChainContextValue.object(Map.of(
                        "additiveRequestsPreserveUnaffectedContentAndBehavior",
                        ChainContextValue.bool(true),
                        "replacementRemovalOrNecessaryChangeMayAlterSameBehavior",
                        ChainContextValue.bool(true),
                        "describeInvariantNotImplementationTechnique",
                        ChainContextValue.bool(true))),
                "stepBoundaryRules", ChainContextValue.object(Map.of(
                        "splitOnRealDependency", ChainContextValue.bool(true),
                        "splitOnIndependentlyReviewableOutcome",
                        ChainContextValue.bool(true),
                        "splitOnDistinctDeliverable", ChainContextValue.bool(true),
                        "doNotCollapseIndependentWorkBecauseItSharesAnObject",
                        ChainContextValue.bool(true),
                        "keepRetriesAndRepairsWithTheirObjective",
                        ChainContextValue.bool(true)))));
    }

    private static ChainContextValue executor() {
        return ChainContextValue.object(Map.of(
                "executionBoundary", ChainContextValue.object(Map.of(
                        "executeOnlyWithinFrozenAuthority",
                        ChainContextValue.bool(true),
                        "useOnlyVisibleFormalToolSchemas",
                        ChainContextValue.bool(true),
                        "oneProposalRepresentsOneTypedActionOrResult",
                        ChainContextValue.bool(true))),
                "planCompliance", ChainContextValue.object(Map.of(
                        "stepObjectiveIsMandatory", ChainContextValue.bool(true),
                        "completionConditionsAreMandatory",
                        ChainContextValue.bool(true),
                        "stepConstraintsAreMandatory", ChainContextValue.bool(true),
                        "preserveUnaffectedBehaviorRequiredByConstraints",
                        ChainContextValue.bool(true),
                        "blockWhenAuthorityCannotSatisfyRequirements",
                        ChainContextValue.bool(true))),
                "pathRules", ChainContextValue.object(Map.of(
                        "pathNamespace", ChainContextValue.text(
                                "PROJECT_RELATIVE"),
                        "root", ChainContextValue.text("."))),
                "workspaceChangeProtocol", workspaceChangeProtocol(),
                "repairRules", ChainContextValue.object(Map.of(
                        "repairOnlyFromVisibleFormalFailure",
                        ChainContextValue.bool(true),
                        "stateHowTheNextAttemptChanges",
                        ChainContextValue.bool(true)))));
    }

    static ChainContextValue workspaceChangeProtocol() {
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("format", ChainContextValue.text(
                        "CANONICAL_CHANGE_BUNDLE_V1")),
                Map.entry("canonicalExample", ChainContextValue.text(
                        "{\"changes\":[{\"expectedBaselineSha256\":\"NONE\",\"path\":\"project/relative/path\",\"text\":\"complete file text\",\"type\":\"ADD\"}]}")),
                Map.entry("rootFieldNames", ChainContextValue.array(List.of(
                        ChainContextValue.text("changes")))),
                Map.entry("addAndModifyFieldNames", ChainContextValue.array(
                        List.of("expectedBaselineSha256", "path", "text", "type")
                                .stream().map(ChainContextValue::text).toList())),
                Map.entry("deleteFieldNames", ChainContextValue.array(
                        List.of("expectedBaselineSha256", "path", "type")
                                .stream().map(ChainContextValue::text).toList())),
                Map.entry("rootAndItemsRejectAdditionalFields",
                        ChainContextValue.bool(true)),
                Map.entry("addBaselineIsLiteralNone",
                        ChainContextValue.bool(true)),
                Map.entry("modifyAndDeleteBaselineIsLowercaseSha256",
                        ChainContextValue.bool(true)),
                Map.entry("addAndModifyRequireCompleteText",
                        ChainContextValue.bool(true)),
                Map.entry("deleteForbidsText", ChainContextValue.bool(true)),
                Map.entry("pathsAreNonBlankAndCaseFoldUnique",
                        ChainContextValue.bool(true)),
                Map.entry("targetFilesExactlyMatchChangePathsInOrder",
                        ChainContextValue.bool(true)),
                Map.entry("canonicalOneLineJsonBytesRequired",
                        ChainContextValue.bool(true))));
    }

    private static ChainContextValue reflector() {
        return ChainContextValue.object(Map.of(
                "acceptanceRules", ChainContextValue.object(Map.of(
                        "authorityOnly", ChainContextValue.bool(true),
                        "reviewCandidateAgainstEveryCompletionCondition",
                        ChainContextValue.bool(true),
                        "reviewEveryStepConstraint",
                        ChainContextValue.bool(true),
                        "mustNotExecuteOrModify",
                        ChainContextValue.bool(true),
                        "missingAuthorityCannotBeInventedOrPromoted",
                        ChainContextValue.bool(true))),
                "finalizationRules", ChainContextValue.object(Map.of(
                        "readyIsNotCompletion", ChainContextValue.bool(true),
                        "mechanicalFinalizationRequired",
                        ChainContextValue.bool(true),
                        "validationAndPublishFactsMustBeFormal",
                        ChainContextValue.bool(true)))));
    }

    private static ChainContextValue answer() {
        return ChainContextValue.object(Map.of(
                "expressionRequirements", ChainContextValue.object(Map.of(
                        "useOnlyFormalVisibleTerminalFacts",
                        ChainContextValue.bool(true),
                        "answerTheEffectiveUserRequest",
                        ChainContextValue.bool(true),
                        "includeVerifiedResultsWhenPresent",
                        ChainContextValue.bool(true),
                        "stateLimitationsAndRisks",
                        ChainContextValue.bool(true),
                        "doNotOverstateValidationOrPublication",
                        ChainContextValue.bool(true),
                        "blockDeliveryWhenRequiredFactsAreMissingOrConflict",
                        ChainContextValue.bool(true))),
                "noDiscoveryExecutionOrWrite",
                ChainContextValue.object(Map.of(
                        "discoverNewFacts", ChainContextValue.bool(false),
                        "executeTools", ChainContextValue.bool(false),
                        "writeOrPublish", ChainContextValue.bool(false)))));
    }
}
