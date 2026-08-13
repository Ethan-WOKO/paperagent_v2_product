package io.paperagent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationCheckWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;

/** Sole submitter of formal finalization checks. */
public final class ChainFinalizationRuntime {
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final ChainFinalizationCheckWriter checks;
    private final ChainFinalizationAuthorityPort authorities;
    private final ChainProjectPublishPort publish;
    private final ChainCompletedOutcomePort outcomes;
    private final ChainFinalizationTransitionPort transitions;
    private final Function<String, ChainRuntimePolicy> runtimePolicies;

    public ChainFinalizationRuntime(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ChainFinalizationCheckWriter checks,
            ChainFinalizationAuthorityPort authorities,
            ChainProjectPublishPort publish,
            ChainCompletedOutcomePort outcomes,
            ChainFinalizationTransitionPort transitions,
            Function<String, ChainRuntimePolicy> runtimePolicies) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.checks = Objects.requireNonNull(checks, "checks");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.runtimePolicies = Objects.requireNonNull(
                runtimePolicies, "runtimePolicies");
    }

    public Result finalizeReadiness(String readinessId, Instant committedAt) {
        Objects.requireNonNull(committedAt, "committedAt");
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                finalization.findReadinessById(required(
                        readinessId, "readinessId"))
                        .orElseThrow(() -> failure(
                                ChainFinalizationException.Code
                                        .READINESS_NOT_FOUND,
                                "formal finalization readiness does not exist"));
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(readiness.taskId())
                .orElseThrow(() -> failure(
                        ChainFinalizationException.Code
                                .AUTHORITY_PREFIX_INVALID,
                        "readiness task does not exist"));
        ChainRuntimePolicy runtimePolicy = policy(readiness.taskId());
        AuthorityOrder authority = AuthorityOrder.load(
                foundations, readiness.taskId());
        authority.require(readiness, "FINALIZATION_READINESS",
                readiness.transitionId(), readiness.readinessScopeKey());
        String targetDigest = readinessDigest(readiness);
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.FINALIZATION, readiness.taskId(),
                readiness.reviewDecisionId(), targetDigest).transitionId();
        List<ChainFinalizationTransitionPort.StageAuthority> transitionPrefix =
                List.of(
                        ChainFinalizationTransitionPort.StageAuthority.open(),
                        ChainFinalizationTransitionPort.StageAuthority.predecessor(
                                ChainTransitionStage.READINESS_VERIFIED,
                                "FINALIZATION_READINESS",
                                readiness.readinessId()));
        advance(readiness, transitionId, targetDigest, transitionPrefix,
                committedAt);

        while (true) {
            List<ChainPersistenceRecords.FinalizationCheckRecord> prefix =
                    checkPrefix(readiness, transitionId, authority);
            if (!prefix.isEmpty()) {
                ChainPersistenceRecords.FinalizationCheckRecord latest =
                        prefix.get(prefix.size() - 1);
                if (latest.resultStatus() == ChainFinalization.Outcome.PASSED) {
                    List<ChainFinalizationTransitionPort.StageAuthority>
                            checkedPrefix = checkedPrefix(
                            transitionPrefix, latest);
                    advance(readiness, transitionId, targetDigest,
                            checkedPrefix, committedAt);
                    return finish(task, readiness, latest, transitionId,
                            targetDigest, checkedPrefix, committedAt);
                }
                if (latest.failureDisposition()
                        == ChainFinalization.FailureHandling.REFLECTOR_REQUIRED) {
                    return finishFailedCheck(readiness, latest, transitionId,
                            targetDigest, transitionPrefix, committedAt);
                }
                if (latest.attemptNo()
                        >= runtimePolicy.finalizationMechanicalAttemptsTotal()) {
                    throw failure(
                            ChainFinalizationException.Code
                                    .AUTHORITY_PREFIX_INVALID,
                            "retryable finalization check exhausted its policy");
                }
            }

            int attempt = prefix.size() + 1;
            ChainFinalizationAuthorityPort.Inspection inspection =
                    Objects.requireNonNull(
                            authorities.inspect(readiness),
                            "finalization inspection");
            Evaluation evaluation = evaluate(
                    readiness, inspection, attempt, authority);
            ChainPersistenceRecords.FinalizationCheckRecord appended = append(
                    readiness, transitionId, evaluation, attempt, committedAt);
            authority = AuthorityOrder.load(foundations, readiness.taskId());
            if (appended.resultStatus() == ChainFinalization.Outcome.PASSED) {
                List<ChainFinalizationTransitionPort.StageAuthority>
                        checkedPrefix = checkedPrefix(
                        transitionPrefix, appended);
                advance(readiness, transitionId, targetDigest,
                        checkedPrefix, committedAt);
                return finish(task, readiness, appended, transitionId,
                        targetDigest, checkedPrefix, committedAt);
            }
            if (appended.failureDisposition()
                    == ChainFinalization.FailureHandling.REFLECTOR_REQUIRED) {
                return finishFailedCheck(readiness, appended, transitionId,
                        targetDigest, transitionPrefix, committedAt);
            }
        }
    }

    private Result finish(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            String transitionId,
            String targetDigest,
            List<ChainFinalizationTransitionPort.StageAuthority> checkedPrefix,
            Instant committedAt) {
        ChainProjectPublishPort.Published published = null;
        ChainRuntimePolicy runtimePolicy = policy(readiness.taskId());
        if (readiness.publishRequirement() == ChainPublishRequirement.REQUIRED) {
            if (readiness.artifactId() == null
                    || ChainIdentity.NONE.equals(readiness.candidateKey())
                    || ChainIdentity.NONE.equals(readiness.validationId())) {
                throw failure(
                        ChainFinalizationException.Code.PUBLISH_RESULT_INVALID,
                        "required publish lacks Candidate or Validation identity");
            }
            ChainProjectPublishPort.PublishResult result = null;
            for (int attempt = 1; attempt <= runtimePolicy
                    .finalizationMechanicalAttemptsTotal(); attempt++) {
                String idempotencyKey = ChainProjectPublishPort
                        .stableIdempotencyKey(
                                readiness.taskId(), readiness.readinessId(),
                                check.finalizationCheckId(), attempt,
                                readiness.projectVersion(),
                                readiness.artifactId(),
                                readiness.candidateKey(),
                                readiness.validationId(),
                                runtimePolicy.policyVersion(),
                                readiness.validationRequestDigest(),
                                readiness.validationReceiptDigest());
                result = Objects.requireNonNull(publish.publish(
                            new ChainProjectPublishPort.PublishCommand(
                                    readiness.taskId(), readiness.readinessId(),
                                    check.finalizationCheckId(), attempt,
                                    idempotencyKey,
                                    readiness.projectVersion(),
                                    readiness.artifactId(),
                                    readiness.candidateKey(),
                                    readiness.validationId(),
                                    runtimePolicy.policyVersion(),
                                    readiness.validationRequestDigest(),
                                    readiness.validationReceiptDigest())),
                        "publish result");
                requirePublishAttemptIdentity(result, attempt, idempotencyKey);
                if (!(result instanceof ChainProjectPublishPort.Failed failed)
                        || !failed.retryable() || !failed.replayed()
                        || attempt == runtimePolicy
                        .finalizationMechanicalAttemptsTotal()) {
                    break;
                }
            }
            if (result instanceof ChainProjectPublishPort.Failed failed) {
                return new PublishFailed(check, failed);
            }
            published = (ChainProjectPublishPort.Published) result;
            validatePublished(readiness, published);
        }

        List<ChainFinalizationTransitionPort.StageAuthority> publishedPrefix =
                append(checkedPrefix, published == null
                        ? ChainFinalizationTransitionPort.StageAuthority
                        .noSuccessor(ChainTransitionStage
                                .PUBLISH_COMMITTED_OR_NOT_REQUIRED)
                        : ChainFinalizationTransitionPort.StageAuthority
                        .successor(ChainTransitionStage
                                        .PUBLISH_COMMITTED_OR_NOT_REQUIRED,
                                "PUBLISH_RECEIPT",
                                published.publishReceiptId()));
        advance(readiness, transitionId, targetDigest, publishedPrefix,
                committedAt);

        String sourceCommandId = foundations.findInstruction(
                        readiness.instructionId())
                .map(ChainPersistenceRecords.InstructionRecord::commandId)
                .orElseThrow(() -> failure(
                        ChainFinalizationException.Code
                                .AUTHORITY_PREFIX_INVALID,
                        "readiness instruction command authority is missing"));
        ChainCompletedOutcomePort.CompletionSubmission submission =
                Objects.requireNonNull(outcomes.complete(
                        new ChainCompletedOutcomePort.CompletionCommand(
                                sourceCommandId, transitionId,
                                readiness, check, published)),
                        "completed TaskOutcome submission");
        validateOutcome(readiness, published, transitionId,
                submission.outcome());
        AuthorityOrder.load(foundations, readiness.taskId()).require(
                submission.outcome(), "TASK_OUTCOME", transitionId,
                sha256(ChainTaskOutcomeStatus.COMPLETED + "\0"
                        + transitionId));
        List<ChainFinalizationTransitionPort.StageAuthority> outcomePrefix =
                append(publishedPrefix,
                        ChainFinalizationTransitionPort.StageAuthority.successor(
                                ChainTransitionStage.TASK_OUTCOME_COMMITTED,
                                "TASK_OUTCOME",
                                submission.outcome().outcomeId()));
        outcomePrefix = append(outcomePrefix,
                ChainFinalizationTransitionPort.StageAuthority.complete());
        advance(readiness, transitionId, targetDigest, outcomePrefix,
                committedAt);
        return new Completed(check, published, submission.outcome(),
                submission.replayed());
    }

    private Result finishFailedCheck(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            String transitionId,
            String targetDigest,
            List<ChainFinalizationTransitionPort.StageAuthority> readinessPrefix,
            Instant committedAt) {
        List<ChainFinalizationTransitionPort.StageAuthority> checked =
                checkedPrefix(readinessPrefix, check);
        advance(readiness, transitionId, targetDigest, checked, committedAt);
        Optional<ChainFinalizationAuthorityPort.FailureHandoff> handoff =
                authorities.findFailureHandoff(
                        new ChainFinalizationAuthorityPort.FailureHandoffQuery(
                                readiness.taskId(), transitionId,
                                check.finalizationCheckId()));
        if (handoff.isEmpty()) {
            return new CheckFailed(check);
        }
        ChainFinalizationAuthorityPort.FailureHandoff formal = handoff.get();
        List<ChainFinalizationTransitionPort.StageAuthority> completed = append(
                checked,
                ChainFinalizationTransitionPort.StageAuthority.successor(
                        ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED,
                        formal.authorityType(), formal.authorityRef()));
        completed = append(completed,
                ChainFinalizationTransitionPort.StageAuthority.complete());
        advance(readiness, transitionId, targetDigest, completed, committedAt);
        return new CheckFailed(check);
    }

    private List<ChainPersistenceRecords.FinalizationCheckRecord> checkPrefix(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String transitionId,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.FinalizationCheckRecord> prefix =
                finalization.findFinalizationChecks(readiness.readinessId())
                        .stream()
                        .sorted(Comparator.comparingInt(
                                ChainPersistenceRecords
                                        .FinalizationCheckRecord::attemptNo))
                        .toList();
        for (int index = 0; index < prefix.size(); index++) {
            ChainPersistenceRecords.FinalizationCheckRecord check =
                    prefix.get(index);
            authority.require(check, "FINALIZATION_CHECK", transitionId,
                    check.inputDigest());
            if (check.attemptNo() != index + 1
                    || !readiness.taskId().equals(check.taskId())
                    || !readiness.readinessId().equals(check.readinessId())
                    || !transitionId.equals(check.transitionId())
                    || !readiness.taskFrameId().equals(check.taskFrameId())
                    || !readiness.finalPlanRevisionId().equals(
                    check.finalPlanRevisionId())
                    || !readiness.acceptedSet().sha256().equals(
                    check.acceptedSetSha256())
                    || !readiness.candidateKey().equals(check.candidateKey())
                    || !readiness.workspaceId().equals(check.workspaceId())
                    || !readiness.validationId().equals(check.validationId())
                    || !Objects.equals(readiness.validationRequestDigest(),
                    check.validationRequestDigest())
                    || !Objects.equals(readiness.validationReceiptDigest(),
                    check.validationReceiptDigest())
                    || !readiness.publishRequirementDigest().equals(
                    check.publishRequirementDigest())
                    || !readiness.instructionId().equals(check.instructionId())
                    || !readiness.projectVersion().equals(check.projectVersion())
                    || !policy(readiness.taskId()).policyVersion().equals(
                    check.runtimePolicyVersion())) {
                throw failure(
                        ChainFinalizationException.Code
                                .AUTHORITY_PREFIX_INVALID,
                        "finalization check prefix changed readiness identity");
            }
            if (index + 1 < prefix.size()
                    && (check.resultStatus()
                    == ChainFinalization.Outcome.PASSED
                    || check.failureDisposition()
                    != ChainFinalization.FailureHandling.RETRYABLE)) {
                throw failure(
                        ChainFinalizationException.Code
                                .AUTHORITY_PREFIX_INVALID,
                        "terminal finalization check has a successor");
            }
        }
        return prefix;
    }

    private ChainPersistenceRecords.FinalizationCheckRecord append(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String transitionId,
            Evaluation evaluation,
            int attempt,
            Instant committedAt) {
        String checkId = "finalization-check." + sha256(
                readiness.readinessId() + "\0" + attempt + "\0"
                        + evaluation.inputDigest());
        String eventId = "finalization-check.event." + sha256(checkId);
        ChainPersistenceRecords.FinalizationCheckRecord requested =
                new ChainPersistenceRecords.FinalizationCheckRecord(
                        checkId, readiness.taskId(), eventId,
                        readiness.readinessId(), transitionId,
                        attempt, readiness.taskFrameId(),
                        readiness.finalPlanRevisionId(),
                        readiness.acceptedSet().sha256(),
                        readiness.candidateKey(), readiness.workspaceId(),
                        readiness.validationId(),
                        readiness.validationRequestDigest(),
                        readiness.validationReceiptDigest(),
                        readiness.publishRequirementDigest(),
                        readiness.instructionId(), readiness.projectVersion(),
                        evaluation.inputDigest(), evaluation.contentDigest(),
                        evaluation.publishDigest(), evaluation.outcome(),
                        evaluation.errorCode(), evaluation.failureHandling(),
                        policy(readiness.taskId()).policyVersion(), committedAt);
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, readiness.taskId(), "FINALIZATION_CHECK",
                        transitionId, evaluation.inputDigest(),
                        committedAt);
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.FinalizationCheckRecord> appended =
                checks.appendFinalizationCheck(
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                event, requested));
        if (!sameRecordIgnoringAuditTime(requested, appended.fact())
                || !event.eventId().equals(appended.event().eventId())
                || !event.taskId().equals(appended.event().taskId())
                || !event.eventType().equals(appended.event().eventType())
                || !Objects.equals(event.transitionId(),
                appended.event().transitionId())
                || !event.sourceIdentitySha256().equals(
                appended.event().sourceIdentitySha256())
                || !appended.fact().createdAt().equals(
                appended.event().committedAt())) {
            throw failure(
                    ChainFinalizationException.Code.CHECK_REPLAY_MISMATCH,
                    "finalization check append/replay changed immutable facts");
        }
        return appended.fact();
    }

    private Evaluation evaluate(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainFinalizationAuthorityPort.Inspection inspection,
            int attempt,
            AuthorityOrder authority) {
        if (inspection
                instanceof ChainFinalizationAuthorityPort.TemporarilyUnavailable
                unavailable) {
            String input = sha256(readinessDigest(readiness) + "\0UNAVAILABLE\0"
                    + unavailable.authorityRef());
            return failedEvaluation(
                    input, sha256(input + "\0CONTENT"),
                    sha256(input + "\0PUBLISH"),
                    ChainFinalization.ErrorCode
                            .AUTHORITY_TEMPORARILY_UNAVAILABLE,
                    attempt, policy(readiness.taskId()));
        }
        ChainFinalizationAuthorityPort.Available available =
                (ChainFinalizationAuthorityPort.Available) inspection;
        String input = inspectionDigest(readiness, available);
        String content = sha256(input + "\0CONTENT\0"
                + readiness.coverage().sha256() + "\0"
                + readiness.acceptedSet().sha256());
        String publish = sha256(input + "\0PUBLISH\0"
                + readiness.publishRequirementDigest() + "\0"
                + readiness.projectVersion());
        ChainFinalization.ErrorCode error = error(
                readiness, available, authority);
        if (error == null) {
            return new Evaluation(input, content, publish,
                    ChainFinalization.Outcome.PASSED,
                    ChainFinalization.FailureHandling.NONE, null);
        }
        return failedEvaluation(input, content, publish, error, attempt,
                policy(readiness.taskId()));
    }

    private static Evaluation failedEvaluation(
            String input,
            String content,
            String publish,
            ChainFinalization.ErrorCode error,
            int attempt,
            ChainRuntimePolicy runtimePolicy) {
        ChainFinalization.FailureHandling handling =
                error == ChainFinalization.ErrorCode
                        .AUTHORITY_TEMPORARILY_UNAVAILABLE
                        && attempt < runtimePolicy
                        .finalizationMechanicalAttemptsTotal()
                        ? ChainFinalization.FailureHandling.RETRYABLE
                        : ChainFinalization.FailureHandling.REFLECTOR_REQUIRED;
        return new Evaluation(input, content, publish,
                ChainFinalization.Outcome.FAILED, handling, error);
    }

    private ChainRuntimePolicy policy(String taskId) {
        return Objects.requireNonNull(runtimePolicies.apply(taskId),
                "runtime policy");
    }

    private ChainFinalization.ErrorCode error(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainFinalizationAuthorityPort.Available available,
            AuthorityOrder authority) {
        if (!readiness.taskId().equals(available.taskId())
                || !readiness.taskFrameId().equals(available.taskFrameId())
                || !readiness.finalPlanId().equals(available.planId())
                || !readiness.finalPlanRevisionId().equals(
                available.planRevisionId())
                || readiness.finalPlanRevisionNumber()
                != available.planRevisionNumber()
                || !readiness.finalStepId().equals(available.finalStepId())
                || !readiness.reviewDecisionId().equals(
                available.reviewDecisionId())
                || !readiness.coverage().sha256().equals(
                available.coverageSha256())) {
            return ChainFinalization.ErrorCode.READINESS_BINDING_MISMATCH;
        }
        if (!available.taskContractSatisfied()) {
            return ChainFinalization.ErrorCode.TASK_CONTRACT_UNSATISFIED;
        }
        if (!readiness.acceptedSet().sha256().equals(
                available.acceptedSetSha256())
                || readiness.applicabilityCutEventSequence()
                != available.applicabilityCutEventSequence()) {
            return ChainFinalization.ErrorCode.ACCEPTED_RESULT_SET_MISMATCH;
        }
        if (!readiness.instructionId().equals(
                available.currentInstructionId())
                || !readiness.projectVersion().equals(
                available.currentProjectVersion())) {
            return ChainFinalization.ErrorCode.STALE_VERSION_FENCE;
        }
        ChainFinalizationAuthorityPort.Candidate candidate =
                available.candidate();
        boolean noCandidate = ChainIdentity.NONE.equals(
                readiness.candidateKey());
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> candidates =
                noCandidate ? List.of() : workflow.findWorkspaceCandidates(
                        readiness.taskId()).stream()
                        .filter(value -> readiness.candidateKey().equals(
                                value.workspaceCandidateId()))
                        .toList();
        ChainPersistenceRecords.WorkspaceCandidateRecord formalCandidate =
                candidates.size() == 1 ? candidates.get(0) : null;
        if (formalCandidate != null) {
            authority.require(formalCandidate, "WORKSPACE_CANDIDATE", null,
                    formalCandidate.versionFenceSha256());
        }
        if (noCandidate != (candidate == null)
                || (!noCandidate
                && (formalCandidate == null
                || !readiness.candidateKey().equals(
                formalCandidate.workspaceCandidateId())
                || !readiness.candidateKey().equals(candidate.candidateKey())
                || !readiness.workspaceId().equals(candidate.workspaceId())
                || !Objects.equals(readiness.artifactId(),
                candidate.artifactId())
                || !readiness.projectVersion().equals(
                candidate.baseProjectVersion())
                || !readiness.workspaceId().equals(
                formalCandidate.workspaceId())
                || !Objects.equals(readiness.artifactId(),
                formalCandidate.artifactId())
                || !readiness.projectVersion().equals(
                formalCandidate.baseProjectVersion())
                || !candidate.fingerprint().equals(
                formalCandidate.candidateFingerprint())))) {
            return ChainFinalization.ErrorCode.CANDIDATE_BINDING_MISMATCH;
        }
        ChainFinalizationAuthorityPort.Validation validation =
                available.validation();
        boolean noValidation = ChainIdentity.NONE.equals(
                readiness.validationId());
        if ((available.validationRequired() && noValidation)
                || (!noValidation && validation == null)) {
            return ChainFinalization.ErrorCode.VALIDATION_MISSING;
        }
        if (!noValidation && validation.status()
                != ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL) {
            return ChainFinalization.ErrorCode.VALIDATION_NOT_SUCCESSFUL;
        }
        if (!noValidation
                && (!readiness.validationId().equals(
                validation.validationId())
                || !readiness.projectVersion().equals(
                validation.projectVersion())
                || !readiness.validationRequestDigest().equals(
                validation.requestDigest())
                || !readiness.validationReceiptDigest().equals(
                validation.receiptDigest())
                || (candidate != null
                && (!Objects.equals(readiness.artifactId(),
                validation.candidateArtifactId())
                || !candidate.fingerprint().equals(
                validation.candidateFingerprint()))))) {
            return ChainFinalization.ErrorCode.VALIDATION_BINDING_MISMATCH;
        }
        if (readiness.publishRequirement()
                != available.publishRequirement()
                || !readiness.publishRequirementDigest().equals(
                available.publishRequirementDigest())
                || (readiness.publishRequirement()
                == ChainPublishRequirement.REQUIRED
                && (noCandidate || noValidation))) {
            return ChainFinalization.ErrorCode.PUBLISH_REQUIREMENT_MISMATCH;
        }
        return null;
    }

    private static void validatePublished(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainProjectPublishPort.Published published) {
        if (!readiness.projectVersion().equals(
                published.baseProjectVersion())
                || !readiness.candidateKey().equals(published.candidateKey())
                || !readiness.validationId().equals(published.validationId())
                || readiness.projectVersion().equals(
                published.publishedProjectVersion())) {
            throw failure(
                    ChainFinalizationException.Code.PUBLISH_RESULT_INVALID,
                    "publish result changed its exact readiness binding");
        }
    }

    private static void requirePublishAttemptIdentity(
            ChainProjectPublishPort.PublishResult result,
            int attemptNo, String idempotencyKey) {
        boolean matches;
        if (result instanceof ChainProjectPublishPort.Published published) {
            matches = published.attemptNo() == attemptNo
                    && published.idempotencyKey().equals(idempotencyKey);
        } else if (result instanceof ChainProjectPublishPort.Failed failed) {
            matches = failed.attemptNo() == attemptNo
                    && failed.idempotencyKey().equals(idempotencyKey);
        } else {
            matches = false;
        }
        if (!matches) {
            throw failure(
                    ChainFinalizationException.Code.PUBLISH_RESULT_INVALID,
                    "publish result changed mechanical attempt identity");
        }
    }

    private static void validateOutcome(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainProjectPublishPort.Published published,
            String finalizationTransitionId,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        boolean publishMismatch = published == null
                ? outcome.publishOperationId() != null
                || outcome.publishedProjectVersion() != null
                || outcome.publishedRevisionId() != null
                || outcome.publishReceiptId() != null
                : !published.operationId().equals(outcome.publishOperationId())
                || !published.publishedProjectVersion().equals(
                outcome.publishedProjectVersion())
                || !Objects.equals(published.publishedRevisionId(),
                outcome.publishedRevisionId())
                || !published.publishReceiptId().equals(
                outcome.publishReceiptId());
        if (!readiness.taskId().equals(outcome.taskId())
                || outcome.outcomeType()
                != ChainTaskOutcomeStatus.COMPLETED
                || !readiness.instructionId().equals(outcome.instructionId())
                || !readiness.taskFrameId().equals(outcome.taskFrameId())
                || !readiness.finalPlanId().equals(outcome.finalPlanId())
                || !readiness.finalPlanRevisionId().equals(
                outcome.finalPlanRevisionId())
                || !readiness.coverage().equals(outcome.coverage())
                || !readiness.acceptedSet().equals(outcome.acceptedSet())
                || !Objects.equals(readiness.artifactId(),
                outcome.finalArtifactId())
                || !readiness.candidateKey().equals(outcome.candidateKey())
                || !readiness.validationId().equals(outcome.validationId())
                || !finalizationTransitionId.equals(
                outcome.sourceDecisionId())
                || publishMismatch) {
            throw failure(
                    ChainFinalizationException.Code.TASK_OUTCOME_INVALID,
                    "completed TaskOutcome changed finalization identity");
        }
    }

    private static String inspectionDigest(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainFinalizationAuthorityPort.Available available) {
        ChainFinalizationAuthorityPort.Candidate candidate =
                available.candidate();
        ChainFinalizationAuthorityPort.Validation validation =
                available.validation();
        return sha256(readinessDigest(readiness) + "\0"
                + available.taskId() + "\0" + available.currentInstructionId()
                + "\0" + available.taskFrameId() + "\0" + available.planId()
                + "\0" + available.planRevisionId() + "\0"
                + available.planRevisionNumber() + "\0"
                + available.finalStepId() + "\0"
                + available.reviewDecisionId() + "\0"
                + available.acceptedSetSha256() + "\0"
                + available.applicabilityCutEventSequence() + "\0"
                + available.taskContractSatisfied() + "\0"
                + available.coverageSha256() + "\0"
                + Objects.toString(candidate, "NONE") + "\0"
                + available.validationRequired() + "\0"
                + Objects.toString(validation, "NONE") + "\0"
                + available.publishRequirement() + "\0"
                + available.publishRequirementDigest() + "\0"
                + available.currentProjectVersion());
    }

    private static String readinessDigest(
            ChainPersistenceRecords.FinalizationReadinessRecord value) {
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

    private void advance(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            String transitionId,
            String targetDigest,
            List<ChainFinalizationTransitionPort.StageAuthority> prefix,
            Instant committedAt) {
        transitions.advance(new ChainFinalizationTransitionPort.AdvanceCommand(
                readiness.taskId(), transitionId,
                readiness.reviewDecisionId(), targetDigest, prefix,
                committedAt));
    }

    private static List<ChainFinalizationTransitionPort.StageAuthority>
            checkedPrefix(
            List<ChainFinalizationTransitionPort.StageAuthority> prefix,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        return append(prefix,
                ChainFinalizationTransitionPort.StageAuthority.successor(
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                        "FINALIZATION_CHECK",
                        check.finalizationCheckId()));
    }

    private static <T> List<T> append(List<T> values, T value) {
        java.util.ArrayList<T> appended = new java.util.ArrayList<>(values);
        appended.add(value);
        return List.copyOf(appended);
    }

    private static boolean sameRecordIgnoringAuditTime(
            Record expected, Record actual) {
        if (!expected.getClass().equals(actual.getClass())) return false;
        try {
            for (var component : expected.getClass().getRecordComponents()) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    continue;
                }
                if (!Objects.equals(component.getAccessor().invoke(expected),
                        component.getAccessor().invoke(actual))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private static ChainFinalizationException failure(
            ChainFinalizationException.Code code,
            String message) {
        return new ChainFinalizationException(code, message);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public sealed interface Result permits Completed, CheckFailed, PublishFailed {
    }

    public record Completed(
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainProjectPublishPort.Published published,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            boolean replayed) implements Result {
        public Completed {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record CheckFailed(
            ChainPersistenceRecords.FinalizationCheckRecord check)
            implements Result {
        public CheckFailed {
            Objects.requireNonNull(check, "check");
        }
    }

    public record PublishFailed(
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainProjectPublishPort.Failed failure) implements Result {
        public PublishFailed {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(failure, "failure");
        }
    }

    private record Evaluation(
            String inputDigest,
            String contentDigest,
            String publishDigest,
            ChainFinalization.Outcome outcome,
            ChainFinalization.FailureHandling failureHandling,
            ChainFinalization.ErrorCode errorCode) {
    }

    private record AuthorityOrder(
            String taskId,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        static AuthorityOrder load(
                ChainFoundationRepository foundations,
                String taskId) {
            long highest = foundations.highestAuthorityEventSequence(taskId);
            List<ChainPersistenceRecords.AuthorityEventRecord> prefix =
                    foundations.findAuthorityEvents(taskId, highest).stream()
                            .sorted(Comparator.comparingLong(
                                    ChainPersistenceRecords
                                            .AuthorityEventRecord
                                            ::eventSequence))
                            .toList();
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId =
                    new HashMap<>();
            Set<Long> sequences = new HashSet<>();
            if (highest != prefix.size()) {
                throw failure(
                        ChainFinalizationException.Code
                                .AUTHORITY_PREFIX_INVALID,
                        "authority event prefix is not contiguous");
            }
            for (int index = 0; index < prefix.size(); index++) {
                ChainPersistenceRecords.AuthorityEventRecord event =
                        prefix.get(index);
                if (!taskId.equals(event.taskId())
                        || event.eventSequence() != index + 1L
                        || byId.put(event.eventId(), event) != null
                        || !sequences.add(event.eventSequence())) {
                    throw failure(
                            ChainFinalizationException.Code
                                    .AUTHORITY_PREFIX_INVALID,
                            "authority event prefix is inconsistent");
                }
            }
            return new AuthorityOrder(taskId, Map.copyOf(byId));
        }

        void require(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String eventType) {
            ChainPersistenceRecords.AuthorityEventRecord event =
                    events.get(fact.eventId());
            require(fact, eventType,
                    event == null ? null : event.transitionId(),
                    event == null ? null : event.sourceIdentitySha256());
        }

        void require(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String eventType,
                String transitionId,
                String sourceIdentitySha256) {
            ChainPersistenceRecords.AuthorityEventRecord event =
                    events.get(fact.eventId());
            if (!taskId.equals(fact.taskId())
                    || event == null
                    || !eventType.equals(event.eventType())
                    || !Objects.equals(transitionId, event.transitionId())
                    || !Objects.equals(sourceIdentitySha256,
                    event.sourceIdentitySha256())) {
                throw failure(
                        ChainFinalizationException.Code
                                .AUTHORITY_PREFIX_INVALID,
                        "formal fact lacks its exact authority event");
            }
        }
    }
}
