package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.util.Objects;
import java.util.Set;

/** Verifies finalization by reusing the formal finalization recovery owner. */
final class ProductChainFinalizationStageVerifier
        implements ProductChainTransitionStageVerifier {
    private final ProductChainRecoveryAuthorityLookup authorities;
    private final ProductChainFinalizationRecoverySource recovery;

    ProductChainFinalizationStageVerifier(
            ProductChainRecoveryAuthorityLookup authorities,
            ProductChainReadinessAuthority readiness) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.recovery = new ProductChainFinalizationRecoverySource(
                authorities.foundations(), authorities.workflow(),
                authorities.finalization(), authorities.publishes(),
                readiness);
    }

    @Override
    public ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        if (stage.stageCode() == ChainTransitionStage.OPEN
                || stage.stageCode() == ChainTransitionStage.COMPLETE) {
            return ProductChainRecoveryAuthorityLookup.verifiedNone(stage);
        }
        ProductChainFinalizationRecoverySource.FinalizationState state =
                recovery.inspect(transition);
        return switch (stage.stageCode()) {
            case READINESS_VERIFIED -> verifyReadiness(transition, stage);
            case FINALIZATION_CHECK_COMMITTED -> verifyCheck(
                    transition, stage);
            case PUBLISH_COMMITTED_OR_NOT_REQUIRED -> verifyPublish(
                    transition, stage);
            case TASK_OUTCOME_COMMITTED -> verifyOutcome(
                    transition, stage, state);
            case FAILED_CHECK_HANDOFF_COMMITTED -> verifyFailure(
                    transition, stage, state);
            default -> throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unsupported FINALIZATION stage");
        };
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification
            verifyReadiness(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.requirePredecessor(
                stage, "FINALIZATION_READINESS");
        var readiness = readiness(
                transition.taskId(), stage.predecessorAuthorityRef());
        ProductChainRecoveryAuthorityLookup.exact(
                transition.sourceDecisionId().equals(
                        readiness.reviewDecisionId())
                        && transition.targetIdentityDigest().equals(
                        ProductChainFinalizationRecoverySource
                                .readinessTargetDigest(readiness)),
                "finalization readiness identity drift");
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyCheck(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.requireSuccessor(
                stage, Set.of("FINALIZATION_CHECK"));
        var check = check(transition, stage.successorAuthorityRef());
        var checks = authorities.finalization().findFinalizationChecks(
                        check.readinessId()).stream()
                .sorted(java.util.Comparator.comparingInt(
                        ChainPersistenceRecords.FinalizationCheckRecord
                                ::attemptNo))
                .toList();
        ProductChainRecoveryAuthorityLookup.exact(
                !checks.isEmpty()
                        && checks.get(checks.size() - 1).equals(check),
                "FinalizationCheck stage does not bind the terminal check");
        return ChainCompositeTransitionRuntime.AuthorityVerification
                .finalization(check.resultStatus()
                        == ChainFinalization.Outcome.PASSED
                        ? ChainCompositeTransitionRuntime
                        .FinalizationCheckOutcome.PASSED
                        : ChainCompositeTransitionRuntime
                        .FinalizationCheckOutcome.FAILED);
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyPublish(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.optionalSuccessor(
                stage, "PUBLISH_RECEIPT");
        var check = terminalCheck(transition);
        var readiness = readiness(
                transition.taskId(), check.readinessId());
        ProductChainRecoveryAuthorityLookup.exact(
                check.resultStatus() == ChainFinalization.Outcome.PASSED,
                "publish stage lacks passed FinalizationCheck");
        if (stage.successorAuthorityRef() == null) {
            ProductChainRecoveryAuthorityLookup.exact(
                    readiness.publishRequirement()
                            == ChainPublishRequirement.NOT_REQUIRED,
                    "publish omission is not authorized");
        } else {
            ProductChainRecoveryAuthorityLookup.exact(
                    readiness.publishRequirement()
                            == ChainPublishRequirement.REQUIRED,
                    "publish receipt was not required");
            authorities.publishes().requireExactSuccess(
                    stage.successorAuthorityRef(), readiness, check);
        }
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyOutcome(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage,
            ProductChainFinalizationRecoverySource.FinalizationState state) {
        ProductChainRecoveryAuthorityLookup.requireSuccessor(
                stage, Set.of("TASK_OUTCOME"));
        ProductChainRecoveryAuthorityLookup.exact(
                state instanceof ProductChainFinalizationRecoverySource.Continue
                        continueState
                        && continueState.branch()
                        == ChainCompositeTransitionRuntime.Branch
                        .FINALIZATION_SUCCESS,
                "completed outcome lacks exact finalization authority");
        var check = terminalCheck(transition);
        var readiness = readiness(
                transition.taskId(), check.readinessId());
        var outcome = authorities.finalization().findTaskOutcome(
                        transition.taskId())
                .filter(value -> value.outcomeId().equals(
                        stage.successorAuthorityRef()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "completed TaskOutcome missing"));
        ProductChainRecoveryAuthorityLookup.canonical(
                outcome.coverage(), "outcome coverage");
        ProductChainRecoveryAuthorityLookup.canonical(
                outcome.acceptedSet(), "outcome accepted set");
        verifyOutcomePublish(outcome, readiness, check, transition);
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private void verifyOutcomePublish(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainPersistenceRecords.TransitionRecord transition) {
        var publishStage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage
                        .PUBLISH_COMMITTED_OR_NOT_REQUIRED,
                "publish stage");
        if (publishStage.successorAuthorityRef() == null) {
            ProductChainRecoveryAuthorityLookup.exact(
                    outcome.publishOperationId() == null
                            && outcome.publishedProjectVersion() == null
                            && outcome.publishedRevisionId() == null
                            && outcome.publishReceiptId() == null,
                    "non-publish outcome carries publish identity");
            return;
        }
        var operation = authorities.publishes().requireExactSuccess(
                publishStage.successorAuthorityRef(), readiness, check);
        ProductChainRecoveryAuthorityLookup.exact(
                operation.formalRef().equals(outcome.publishOperationId())
                        && operation.resultVersion().equals(
                        outcome.publishedProjectVersion())
                        && Objects.equals(operation.resultRevisionId(),
                        outcome.publishedRevisionId())
                        && operation.formalRef().equals(
                        outcome.publishReceiptId()),
                "completed outcome changed publish identity");
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyFailure(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage,
            ProductChainFinalizationRecoverySource.FinalizationState state) {
        ProductChainRecoveryAuthorityLookup.exact(
                state instanceof ProductChainFinalizationRecoverySource.Continue
                        continueState
                        && continueState.branch()
                        == ChainCompositeTransitionRuntime.Branch
                        .FINALIZATION_FAILED,
                "failure handoff lacks exact finalization authority");
        ProductChainRecoveryAuthorityLookup.exact(
                (stage.predecessorAuthorityType() == null
                        && stage.predecessorAuthorityRef() == null
                        || "PUBLISH_FAILURE".equals(
                        stage.predecessorAuthorityType())
                        && stage.predecessorAuthorityRef() != null)
                        && ("REVIEW_DECISION".equals(
                        stage.successorAuthorityType())
                        || "TASK_OUTCOME".equals(
                        stage.successorAuthorityType()))
                        && stage.successorAuthorityRef() != null,
                "failure handoff authority shape");
        var check = terminalCheck(transition);
        var readiness = readiness(
                transition.taskId(), check.readinessId());
        String objectType;
        String objectRef;
        String category;
        String code;
        if (stage.predecessorAuthorityType() == null) {
            ProductChainRecoveryAuthorityLookup.exact(
                    check.resultStatus() == ChainFinalization.Outcome.FAILED,
                    "check failure handoff lacks failed check");
            objectType = "FINALIZATION_CHECK";
            objectRef = check.finalizationCheckId();
            category = "FINALIZATION";
            code = check.errorCode().name();
        } else {
            var failure = authorities.publishes().requireExactFailure(
                    stage.predecessorAuthorityRef(), readiness, check);
            objectType = "PUBLISH_FAILURE";
            objectRef = failure.formalRef();
            category = "PUBLISH";
            code = ProductChainPublishAuthoritySource
                    .publishErrorCode(failure).name();
        }
        if ("REVIEW_DECISION".equals(stage.successorAuthorityType())) {
            var review = ProductChainRecoveryAuthorityLookup.one(
                    authorities.workflow().findReviewDecisions(
                            transition.taskId()),
                    value -> value.reviewDecisionId().equals(
                                    stage.successorAuthorityRef())
                            && value.reviewObjectType().equals(objectType)
                            && value.reviewObjectId().equals(objectRef)
                            && (value.decisionKind()
                            == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                            || value.decisionKind()
                            == ChainProposalKind.REFLECTOR_NEED_PERMISSION
                            || value.decisionKind()
                            == ChainProposalKind.REFLECTOR_TASK_FAILED),
                    "failure ReviewDecision");
            ProductChainRecoveryAuthorityLookup.canonical(
                    review.factRefs(), "failure review fact refs");
            return ProductChainRecoveryAuthorityLookup.verified();
        }
        String sourceCommandId = authorities.foundations()
                .findInstruction(readiness.instructionId())
                .map(ChainPersistenceRecords.InstructionRecord::commandId)
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "failure instruction command missing"));
        var outcome = authorities.finalization().findTaskOutcome(
                        transition.taskId())
                .filter(value -> value.outcomeId().equals(
                        stage.successorAuthorityRef()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "failed TaskOutcome missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                outcome.outcomeType() == ChainTaskOutcomeStatus.FAILED
                        && outcome.sourceDecisionId().equals(objectRef)
                        && outcome.instructionId().equals(
                        readiness.instructionId())
                        && outcome.sourceCommandId().equals(sourceCommandId)
                        && category.equals(outcome.failureCategory())
                        && code.equals(outcome.failureCode()),
                "failed TaskOutcome identity drift");
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private ChainPersistenceRecords.FinalizationReadinessRecord readiness(
            String taskId, String readinessId) {
        return authorities.finalization().findReadinessById(readinessId)
                .filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "FinalizationReadiness missing"));
    }

    private ChainPersistenceRecords.FinalizationCheckRecord check(
            ChainPersistenceRecords.TransitionRecord transition, String ref) {
        var readiness = authorities.finalization().findReadiness(
                transition.taskId());
        var matches = readiness.stream().flatMap(value -> authorities
                        .finalization().findFinalizationChecks(
                                value.readinessId()).stream())
                .filter(value -> value.finalizationCheckId().equals(ref)
                        && value.taskId().equals(transition.taskId())
                        && value.transitionId().equals(
                        transition.transitionId()))
                .toList();
        return ProductChainRecoveryAuthorityLookup.one(
                matches, ignored -> true, "FinalizationCheck");
    }

    private ChainPersistenceRecords.FinalizationCheckRecord terminalCheck(
            ChainPersistenceRecords.TransitionRecord transition) {
        var stage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                "FinalizationCheck stage");
        return check(transition, stage.successorAuthorityRef());
    }
}
