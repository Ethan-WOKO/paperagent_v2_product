package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Derives the typed finalization restart branch from formal chain authorities. */
public final class ProductChainFinalizationRecoverySource {
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final PublishFailureLookup publishFailures;
    private final ProductChainReadinessAuthority readinessAuthority;

    public ProductChainFinalizationRecoverySource(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            PublishFailureLookup publishFailures,
            ProductChainReadinessAuthority readinessAuthority) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.publishFailures = Objects.requireNonNull(
                publishFailures, "publishFailures");
        this.readinessAuthority = Objects.requireNonNull(
                readinessAuthority, "readinessAuthority");
    }

    public FinalizationState inspect(
            ChainPersistenceRecords.TransitionRecord transition) {
        Objects.requireNonNull(transition, "transition");
        if (transition.transitionType() != ChainTransitionType.FINALIZATION) {
            throw new IllegalStateException(
                    "finalization recovery requires a FINALIZATION transition");
        }
        ChainPersistenceRecords.FinalizationReadinessRecord readiness = finalization
                .findReadiness(transition.taskId()).stream()
                .filter(value -> value.taskId().equals(transition.taskId()))
                .filter(value -> value.reviewDecisionId().equals(
                        transition.sourceDecisionId()))
                .filter(value -> readinessTargetDigest(value).equals(
                        transition.targetIdentityDigest()))
                .reduce((left, right) -> {
                    throw new IllegalStateException(
                            "transition has multiple finalization readiness facts");
                }).orElseThrow(() -> new IllegalStateException(
                        "finalization transition lacks readiness"));
        readinessAuthority.requireExact(readiness);
        verifyReadinessPredecessor(transition, readiness);
        List<ChainPersistenceRecords.FinalizationCheckRecord> checks = finalization
                .findFinalizationChecks(readiness.readinessId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.FinalizationCheckRecord::attemptNo))
                .toList();
        verifyCheckPrefix(transition, readiness, checks);
        if (checks.isEmpty()) {
            return new RequiresMechanicalFinalization(readiness.readinessId());
        }
        ChainPersistenceRecords.FinalizationCheckRecord check =
                checks.get(checks.size() - 1);
        if (check.resultStatus() == ChainFinalization.Outcome.FAILED) {
            if (check.failureDisposition()
                    == ChainFinalization.FailureHandling.RETRYABLE) {
                if (check.attemptNo() >= ChainRuntimePolicy.requireVersion(
                        check.runtimePolicyVersion())
                        .finalizationMechanicalAttemptsTotal()) {
                    throw new IllegalStateException(
                            "final retryable check must fail closed");
                }
                return new RequiresMechanicalFinalization(
                        readiness.readinessId());
            }
            if (check.failureDisposition()
                    != ChainFinalization.FailureHandling.REFLECTOR_REQUIRED) {
                throw new IllegalStateException(
                        "failed check has an invalid failure disposition");
            }
            boolean handoff = exactFailureReview(
                    transition.taskId(), "FINALIZATION_CHECK",
                    check.finalizationCheckId())
                    || exactFailedOutcome(
                    transition.taskId(), readiness.instructionId(),
                    check.finalizationCheckId(),
                    "FINALIZATION", check.errorCode().name());
            return handoff
                    ? new Continue(ChainCompositeTransitionRuntime.Branch
                    .FINALIZATION_FAILED)
                    : new CheckFailure(
                            new ChainRecoveryRuntime.CheckFailureWait(
                                    check.finalizationCheckId(), check.errorCode()));
        }
        Optional<PublishFailure> publishFailure = publishFailures.find(
                transition, readiness, check);
        if (publishFailure.isPresent()) {
            PublishFailure value = publishFailure.orElseThrow();
            boolean handoff = exactFailureReview(
                    transition.taskId(), "PUBLISH_FAILURE",
                    value.formalFailureRef())
                    || exactFailedOutcome(
                    transition.taskId(), readiness.instructionId(),
                    value.formalFailureRef(),
                    "PUBLISH", value.errorCode().name());
            return handoff
                    ? new Continue(ChainCompositeTransitionRuntime.Branch
                    .FINALIZATION_FAILED)
                    : new PublishFailureState(
                    new ChainRecoveryRuntime.PublishFailureWait(
                            check.finalizationCheckId(),
                            value.formalFailureRef(), value.errorCode(),
                            value.retryable()));
        }
        boolean outcomeCommitted = exactCompletedOutcome(
                transition, readiness);
        return outcomeCommitted
                ? new Continue(ChainCompositeTransitionRuntime.Branch
                .FINALIZATION_SUCCESS)
                : new RequiresMechanicalFinalization(readiness.readinessId());
    }

    private void verifyReadinessPredecessor(
            ChainPersistenceRecords.TransitionRecord finalizationTransition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        ChainPersistenceRecords.TransitionRecord predecessor = workflow
                .findTransition(readiness.transitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "finalization readiness lacks its predecessor transition"));
        if (!predecessor.transitionId().equals(readiness.transitionId())
                || predecessor.transitionType()
                != ChainTransitionType.FINAL_STEP_READINESS
                || !predecessor.taskId().equals(
                finalizationTransition.taskId())
                || !predecessor.sourceDecisionId().equals(
                readiness.reviewDecisionId())) {
            throw new IllegalStateException(
                    "finalization readiness predecessor identity is corrupt");
        }
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(predecessor.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            ChainPersistenceRecords.TransitionStageRecord stage =
                    stages.get(index);
            if (!stage.transitionId().equals(predecessor.transitionId())
                    || !stage.taskId().equals(predecessor.taskId())
                    || stage.stageOrdinal() != index) {
                throw new IllegalStateException(
                        "readiness predecessor stage prefix is corrupt");
            }
            try {
                stage.validateNextFor(predecessor.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "readiness predecessor stage prefix is corrupt",
                        invalid);
            }
            prefix.add(stage.stageCode());
        }
        if (!predecessor.transitionType().isCompleteSequence(prefix)) {
            throw new IllegalStateException(
                    "readiness predecessor transition is not complete");
        }
        verifyReadinessStageAuthorities(predecessor, readiness, stages);
    }

    private void verifyReadinessStageAuthorities(
            ChainPersistenceRecords.TransitionRecord predecessor,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            List<ChainPersistenceRecords.TransitionStageRecord> stages) {
        requireNoAuthority(stages.get(0), "OPEN");

        String acceptedResultId = requireEitherAuthority(
                stages.get(1), "ACCEPTED_RESULT");
        ChainPersistenceRecords.AcceptedResultRecord accepted = workflow
                .findAcceptedResults(predecessor.taskId()).stream()
                .filter(value -> value.taskId().equals(predecessor.taskId()))
                .filter(value -> value.acceptedResultId().equals(
                        acceptedResultId))
                .reduce((left, right) -> {
                    throw new IllegalStateException(
                            "readiness predecessor has duplicate AcceptedResult authority");
                }).orElseThrow(() -> new IllegalStateException(
                        "readiness predecessor AcceptedResult is missing"));
        if (!predecessor.targetIdentityDigest().equals(
                accepted.acceptedIdentitySha256())) {
            throw new IllegalStateException(
                    "readiness predecessor target does not bind its AcceptedResult");
        }
        ChainPersistenceRecords.CandidateStepResultRecord candidate = workflow
                .findCandidateStepResults(predecessor.taskId()).stream()
                .filter(value -> value.taskId().equals(predecessor.taskId()))
                .filter(value -> value.candidateResultId().equals(
                        accepted.candidateResultId()))
                .filter(value -> value.contentId().equals(accepted.contentId()))
                .filter(value -> value.taskFrameId().equals(
                        readiness.taskFrameId()))
                .filter(value -> value.planId().equals(
                        readiness.finalPlanId()))
                .filter(value -> value.planRevisionId().equals(
                        readiness.finalPlanRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == readiness.finalPlanRevisionNumber())
                .filter(value -> value.stepId().equals(
                        readiness.finalStepId()))
                .filter(value -> value.instructionId().equals(
                        readiness.instructionId()))
                .reduce((left, right) -> {
                    throw new IllegalStateException(
                            "readiness predecessor has duplicate final Step candidates");
                }).orElseThrow(() -> new IllegalStateException(
                        "readiness predecessor final Step candidate is missing"));
        verifyAcceptedReview(predecessor, readiness, stages.get(1), accepted,
                candidate);

        ChainPersistenceRecords.TransitionStageRecord applicability =
                stages.get(2);
        if (applicability.predecessorAuthorityType() != null) {
            throw new IllegalStateException(
                    "readiness applicability stage has invalid authority direction");
        }
        List<ChainPersistenceRecords.ResultApplicabilityRecord> sourceSet =
                workflow.findApplicabilityDecisions(predecessor.taskId()).stream()
                        .filter(value -> value.taskId().equals(
                                predecessor.taskId()))
                        .filter(value -> predecessor.transitionId().equals(
                                value.sourceDecisionId()))
                        .toList();
        if (applicability.successorAuthorityType() == null) {
            if (!sourceSet.isEmpty()) {
                throw new IllegalStateException(
                        "empty readiness applicability stage conflicts with formal facts");
            }
        } else {
            java.util.Map<String,
                    ChainPersistenceRecords.AcceptedResultRecord>
                    acceptedById = new java.util.HashMap<>();
            for (var value : workflow.findAcceptedResults(
                    predecessor.taskId())) {
                if (acceptedById.put(value.acceptedResultId(), value)
                        != null) {
                    throw new IllegalStateException(
                            "readiness AcceptedResult authority is duplicated");
                }
            }
            java.util.Set<String> sourceAcceptedIds = sourceSet.stream()
                    .map(ChainPersistenceRecords.ResultApplicabilityRecord
                            ::acceptedResultId)
                    .collect(java.util.stream.Collectors.toSet());
            boolean currentAcceptedRemainsApplicable = sourceSet.stream()
                    .anyMatch(value -> value.acceptedResultId().equals(
                            accepted.acceptedResultId())
                            && value.conclusion()
                            == io.paperagent.v2.chain.ChainApplicability
                            .Outcome.APPLICABLE);
            boolean completeSourceSet = !sourceSet.isEmpty()
                    && sourceAcceptedIds.size() == sourceSet.size()
                    && currentAcceptedRemainsApplicable
                    && sourceSet.stream().allMatch(value ->
                    value.sourceType()
                            == io.paperagent.v2.chain.ChainApplicability
                            .SourceType.ACCEPT_STEP
                            && acceptedById.containsKey(
                            value.acceptedResultId())
                            && value.targetTaskFrameId().equals(
                            readiness.taskFrameId())
                            && value.targetPlanId().equals(
                            readiness.finalPlanId())
                            && value.targetPlanRevisionId().equals(
                            readiness.finalPlanRevisionId())
                            && value.targetCandidateKey().equals(
                            readiness.candidateKey())
                            && value.targetInstructionVersionId().equals(
                            readiness.instructionId()));
            boolean stageRefInSourceSet = sourceSet.stream().anyMatch(value ->
                    value.applicabilityId().equals(
                            applicability.successorAuthorityRef()));
            if (!"RESULT_APPLICABILITY".equals(
                    applicability.successorAuthorityType())
                    || !completeSourceSet || !stageRefInSourceSet) {
                throw new IllegalStateException(
                        "readiness applicability stage authority is invalid");
            }
        }

        String stepEventId = requireEitherAuthority(
                stages.get(3), "STEP_EVENT");
        String expectedStepEventId = "step.completed." + sha256(
                predecessor.taskId() + "\0" + readiness.finalPlanRevisionId()
                        + "\0" + readiness.finalStepId() + "\0"
                        + candidate.activationEventId() + "\0"
                        + predecessor.transitionId());
        if (!expectedStepEventId.equals(stepEventId)) {
            throw new IllegalStateException(
                    "readiness Step stage does not bind the final completion event");
        }
        ChainPersistenceRecords.TransitionStageRecord committed = stages.get(4);
        if (committed.predecessorAuthorityType() != null
                || !"FINALIZATION_READINESS".equals(
                committed.successorAuthorityType())
                || !readiness.readinessId().equals(
                committed.successorAuthorityRef())) {
            throw new IllegalStateException(
                    "readiness committed stage does not bind the readiness fact");
        }
        requireNoAuthority(stages.get(5), "COMPLETE");
    }

    private void verifyAcceptedReview(
            ChainPersistenceRecords.TransitionRecord predecessor,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.TransitionStageRecord acceptedStage,
            ChainPersistenceRecords.AcceptedResultRecord accepted,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        ChainPersistenceRecords.ReviewDecisionRecord readinessReview = workflow
                .findReviewDecisions(predecessor.taskId()).stream()
                .filter(value -> value.taskId().equals(predecessor.taskId()))
                .filter(value -> value.reviewDecisionId().equals(
                        readiness.reviewDecisionId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "readiness predecessor ReviewDecision is missing"));
        if (readinessReview.decisionKind()
                == ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE) {
            if (!accepted.reviewDecisionId().equals(
                    readiness.reviewDecisionId())
                    || !accepted.transitionId().equals(
                    predecessor.transitionId())) {
                throw new IllegalStateException(
                        "combined readiness does not bind its AcceptedResult");
            }
            return;
        }
        if (readinessReview.decisionKind()
                != ChainProposalKind.REFLECTOR_READY_TO_FINALIZE
                || !"ACCEPTED_RESULT".equals(
                acceptedStage.predecessorAuthorityType())
                || acceptedStage.successorAuthorityType() != null) {
            throw new IllegalStateException(
                    "readiness AcceptedResult direction is invalid");
        }
        ChainPersistenceRecords.ReviewDecisionRecord acceptingReview = workflow
                .findReviewDecisions(predecessor.taskId()).stream()
                .filter(value -> value.taskId().equals(predecessor.taskId()))
                .filter(value -> value.reviewDecisionId().equals(
                        accepted.reviewDecisionId()))
                .filter(value -> value.decisionKind()
                        == ChainProposalKind.REFLECTOR_ACCEPT_STEP)
                .filter(value -> "CANDIDATE_STEP_RESULT".equals(
                        value.reviewObjectType()))
                .filter(value -> value.reviewObjectId().equals(
                        candidate.candidateResultId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "pure readiness lacks its prior accepting review"));
        ChainPersistenceRecords.TransitionRecord acceptingTransition = workflow
                .findTransition(accepted.transitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "pure readiness lacks its prior ACCEPT_STEP transition"));
        if (!acceptingTransition.taskId().equals(predecessor.taskId())
                || acceptingTransition.transitionType()
                != ChainTransitionType.ACCEPT_STEP
                || !acceptingTransition.sourceDecisionId().equals(
                acceptingReview.reviewDecisionId())
                || !acceptingTransition.targetIdentityDigest().equals(
                accepted.acceptedIdentitySha256())) {
            throw new IllegalStateException(
                    "pure readiness prior acceptance authority is invalid");
        }
    }

    private static String requireEitherAuthority(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String authorityType) {
        boolean predecessor = authorityType.equals(
                stage.predecessorAuthorityType())
                && stage.successorAuthorityType() == null;
        boolean successor = authorityType.equals(
                stage.successorAuthorityType())
                && stage.predecessorAuthorityType() == null;
        if (!predecessor && !successor) {
            throw new IllegalStateException(
                    "readiness predecessor stage authority is invalid for "
                            + stage.stageCode());
        }
        return predecessor
                ? stage.predecessorAuthorityRef()
                : stage.successorAuthorityRef();
    }

    private static void requireNoAuthority(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String stageName) {
        if (stage.predecessorAuthorityType() != null
                || stage.successorAuthorityType() != null) {
            throw new IllegalStateException(
                    stageName + " readiness stage must not carry authority");
        }
    }

    private static void verifyCheckPrefix(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            List<ChainPersistenceRecords.FinalizationCheckRecord> checks) {
        boolean terminalSeen = false;
        for (int index = 0; index < checks.size(); index++) {
            ChainPersistenceRecords.FinalizationCheckRecord check =
                    checks.get(index);
            int expectedAttempt = index + 1;
            if (check.attemptNo() != expectedAttempt) {
                throw new IllegalStateException(
                        "finalization check attempts are not contiguous");
            }
            if (terminalSeen) {
                throw new IllegalStateException(
                        "finalization check was appended after a terminal result");
            }
            verifyCheckBinding(transition, readiness, check);
            if (check.resultStatus() == ChainFinalization.Outcome.PASSED
                    || check.failureDisposition()
                    == ChainFinalization.FailureHandling.REFLECTOR_REQUIRED) {
                terminalSeen = true;
            } else if (check.failureDisposition()
                    != ChainFinalization.FailureHandling.RETRYABLE) {
                throw new IllegalStateException(
                        "finalization check prefix has an invalid result");
            }
            if (check.failureDisposition()
                    == ChainFinalization.FailureHandling.RETRYABLE
                    && check.attemptNo() >= ChainRuntimePolicy.requireVersion(
                    check.runtimePolicyVersion())
                    .finalizationMechanicalAttemptsTotal()) {
                throw new IllegalStateException(
                        "final retryable check must fail closed");
            }
        }
    }

    private static void verifyCheckBinding(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        boolean exact = check.taskId().equals(transition.taskId())
                && check.readinessId().equals(readiness.readinessId())
                && check.transitionId().equals(transition.transitionId())
                && check.taskFrameId().equals(readiness.taskFrameId())
                && check.finalPlanRevisionId().equals(
                readiness.finalPlanRevisionId())
                && check.acceptedSetSha256().equals(
                readiness.acceptedSet().sha256())
                && check.candidateKey().equals(readiness.candidateKey())
                && check.workspaceId().equals(readiness.workspaceId())
                && check.validationId().equals(readiness.validationId())
                && Objects.equals(check.validationRequestDigest(),
                readiness.validationRequestDigest())
                && Objects.equals(check.validationReceiptDigest(),
                readiness.validationReceiptDigest())
                && check.publishRequirementDigest().equals(
                readiness.publishRequirementDigest())
                && check.instructionId().equals(readiness.instructionId())
                && check.projectVersion().equals(readiness.projectVersion());
        try {
            ChainRuntimePolicy.requireVersion(check.runtimePolicyVersion());
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException(
                    "finalization check runtime policy is unsupported",
                    unsupported);
        }
        if (!exact) {
            throw new IllegalStateException(
                    "finalization check does not match readiness authority");
        }
    }

    private boolean exactCompletedOutcome(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        return finalization.findTaskOutcome(transition.taskId())
                .filter(value -> value.taskId().equals(transition.taskId()))
                .filter(value -> value.outcomeType()
                        == ChainTaskOutcomeStatus.COMPLETED)
                .filter(value -> value.sourceDecisionId().equals(
                        transition.transitionId()))
                .filter(value -> value.instructionId().equals(
                        readiness.instructionId()))
                .filter(value -> Objects.equals(value.taskFrameId(),
                        readiness.taskFrameId()))
                .filter(value -> Objects.equals(value.finalPlanId(),
                        readiness.finalPlanId()))
                .filter(value -> Objects.equals(value.finalPlanRevisionId(),
                        readiness.finalPlanRevisionId()))
                .filter(value -> value.coverage().equals(readiness.coverage()))
                .filter(value -> value.acceptedSet().equals(
                        readiness.acceptedSet()))
                .filter(value -> Objects.equals(value.finalArtifactId(),
                        readiness.artifactId()))
                .filter(value -> value.candidateKey().equals(
                        readiness.candidateKey()))
                .filter(value -> value.validationId().equals(
                        readiness.validationId()))
                .filter(value -> exactPublishShape(value, readiness))
                .filter(value -> sourceCommandMatchesInstruction(
                        value.sourceCommandId(), readiness.instructionId()))
                .isPresent();
    }

    private static boolean exactPublishShape(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        boolean completePublishIdentity = outcome.publishOperationId() != null
                && outcome.publishedProjectVersion() != null
                && outcome.publishedRevisionId() != null
                && outcome.publishReceiptId() != null;
        boolean absentPublishIdentity = outcome.publishOperationId() == null
                && outcome.publishedProjectVersion() == null
                && outcome.publishedRevisionId() == null
                && outcome.publishReceiptId() == null;
        return readiness.publishRequirement() == ChainPublishRequirement.REQUIRED
                ? completePublishIdentity
                : absentPublishIdentity;
    }

    private boolean exactFailureReview(
            String taskId,
            String reviewObjectType,
            String reviewObjectId) {
        return workflow.findReviewDecisions(taskId).stream()
                .filter(value -> value.taskId().equals(taskId))
                .filter(value -> value.reviewObjectType().equals(
                        reviewObjectType))
                .filter(value -> value.reviewObjectId().equals(
                        reviewObjectId))
                .anyMatch(value -> isFailureHandoffKind(
                        value.decisionKind()));
    }

    private boolean exactFailedOutcome(
            String taskId,
            String instructionId,
            String sourceDecisionId,
            String failureCategory,
            String failureCode) {
        return finalization.findTaskOutcome(taskId)
                .filter(value -> value.taskId().equals(taskId))
                .filter(value -> value.outcomeType()
                        == ChainTaskOutcomeStatus.FAILED)
                .filter(value -> value.sourceDecisionId().equals(
                        sourceDecisionId))
                .filter(value -> failureCategory.equals(
                        value.failureCategory()))
                .filter(value -> failureCode.equals(value.failureCode()))
                .filter(value -> sourceCommandMatchesInstruction(
                        value.sourceCommandId(), instructionId))
                .isPresent();
    }

    private boolean sourceCommandMatchesInstruction(
            String sourceCommandId,
            String instructionId) {
        return foundations.findInstruction(instructionId)
                .filter(value -> value.commandId().equals(sourceCommandId))
                .isPresent();
    }

    private static boolean isFailureHandoffKind(ChainProposalKind kind) {
        return kind == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                || kind == ChainProposalKind.REFLECTOR_NEED_PERMISSION
                || kind == ChainProposalKind.REFLECTOR_TASK_FAILED;
    }

    static String readinessTargetDigest(
            ChainPersistenceRecords.FinalizationReadinessRecord value) {
        Objects.requireNonNull(value, "value");
        return sha256(value.readinessId() + "\0" + value.taskId() + "\0"
                + value.transitionId() + "\0" + value.taskFrameId() + "\0"
                + value.finalPlanId() + "\0" + value.finalPlanRevisionId()
                + "\0" + value.finalPlanRevisionNumber() + "\0"
                + value.finalStepId() + "\0" + value.reviewDecisionId()
                + "\0" + value.acceptedSet().sha256() + "\0"
                + value.applicabilityCutEventSequence() + "\0"
                + Objects.toString(value.artifactId(), "NONE") + "\0"
                + value.candidateKey() + "\0" + value.workspaceId() + "\0"
                + value.validationId() + "\0"
                + Objects.toString(value.validationRequestDigest(), "NONE")
                + "\0"
                + Objects.toString(value.validationReceiptDigest(), "NONE")
                + "\0" + value.coverage().sha256() + "\0"
                + value.publishRequirement() + "\0"
                + value.publishRequirementDigest() + "\0"
                + value.instructionId() + "\0" + value.projectVersion());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public sealed interface FinalizationState
            permits Continue, RequiresMechanicalFinalization,
            CheckFailure, PublishFailureState {
    }

    public record RequiresMechanicalFinalization(String readinessId)
            implements FinalizationState {
        public RequiresMechanicalFinalization {
            if (readinessId == null || readinessId.isBlank()) {
                throw new IllegalArgumentException(
                        "readinessId must not be blank");
            }
        }
    }

    public record Continue(ChainCompositeTransitionRuntime.Branch branch)
            implements FinalizationState {
        public Continue {
            if (branch != ChainCompositeTransitionRuntime.Branch
                    .FINALIZATION_SUCCESS
                    && branch != ChainCompositeTransitionRuntime.Branch
                    .FINALIZATION_FAILED) {
                throw new IllegalArgumentException(
                        "finalization continuation requires a finalization branch");
            }
        }
    }

    public record CheckFailure(ChainRecoveryRuntime.CheckFailureWait reason)
            implements FinalizationState {
        public CheckFailure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record PublishFailureState(
            ChainRecoveryRuntime.PublishFailureWait reason)
            implements FinalizationState {
        public PublishFailureState {
            Objects.requireNonNull(reason, "reason");
        }
    }

    @FunctionalInterface
    public interface PublishFailureLookup {
        Optional<PublishFailure> find(
                ChainPersistenceRecords.TransitionRecord transition,
                ChainPersistenceRecords.FinalizationReadinessRecord readiness,
                ChainPersistenceRecords.FinalizationCheckRecord check);
    }

    public record PublishFailure(
            String formalFailureRef,
            ChainProjectPublishPort.ErrorCode errorCode,
            boolean retryable) {
        public PublishFailure {
            if (formalFailureRef == null || formalFailureRef.isBlank()) {
                throw new IllegalArgumentException(
                        "formalFailureRef must not be blank");
            }
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }
}
