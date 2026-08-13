package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainAcceptedResultWriter;
import io.paperagent.v2.chain.ChainCandidateStepResultWriter;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.AcceptedResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateStepResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ReviewDecisionRecord;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Sole runtime boundary for candidate and accepted Step results. */
public final class ChainStepResultRuntime {
    private final ChainModelRepository models;
    private final ChainContextRepository contexts;
    private final ChainWorkflowRepository workflows;
    private final ChainCandidateStepResultWriter candidates;
    private final ChainAcceptedResultWriter accepted;
    private final ChainCandidateProposalBinder proposalBinder;

    public ChainStepResultRuntime(
            ChainModelRepository models,
            ChainContextRepository contexts,
            ChainWorkflowRepository workflows,
            ChainCandidateStepResultWriter candidates,
            ChainAcceptedResultWriter accepted,
            ChainCandidateProposalBinder proposalBinder) {
        this.models = Objects.requireNonNull(models, "models");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.proposalBinder = Objects.requireNonNull(
                proposalBinder, "proposalBinder");
    }

    public AuthoritativeAppendResult<CandidateStepResultRecord>
            commitCandidate(CandidateStepResultRecord requested) {
        Objects.requireNonNull(requested, "requested");
        var proposal = models.findProposal(requested.proposalId())
                .orElseThrow(() -> failure(
                        "CHAIN_CANDIDATE_PROPOSAL_NOT_FOUND",
                        "candidate proposal does not exist"));
        if (!proposal.taskId().equals(requested.taskId())
                || proposal.proposalKind()
                != ChainProposalKind.EXECUTOR_STEP_RESULT
                || !ChainContentKind.CANDIDATE_STEP_RESULT.name().equals(
                proposal.bodyAuthorityType())
                || !requested.contentId().equals(
                proposal.bodyAuthorityRef())) {
            throw failure("CHAIN_CANDIDATE_PROPOSAL_MISMATCH",
                    "candidate must use one accepted STEP_RESULT body");
        }
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_CANDIDATE_INVOCATION_NOT_FOUND",
                        "candidate proposal invocation does not exist"));
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_CANDIDATE_CONTEXT_NOT_FOUND",
                        "candidate proposal context does not exist"));
        if (!invocation.taskId().equals(requested.taskId())
                || !context.taskId().equals(requested.taskId())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !Objects.equals(context.instructionId(),
                requested.instructionId())
                || !Objects.equals(context.taskFrameId(),
                requested.taskFrameId())
                || !Objects.equals(context.planId(), requested.planId())
                || !Objects.equals(context.planRevisionId(),
                requested.planRevisionId())
                || !Objects.equals(context.planRevisionNumber(),
                requested.planRevisionNumber())
                || !Objects.equals(context.stepId(), requested.stepId())
                || !Objects.equals(context.activationEventId(),
                requested.activationEventId())) {
            throw failure("CHAIN_CANDIDATE_CONTEXT_MISMATCH",
                    "candidate result crosses its frozen Step identity");
        }
        List<ProposalStateEventRecord> states = proposalStates(
                requested.proposalId(), requested.taskId());
        ProposalStateEventRecord first = states.get(0);
        ProposalStateEventRecord latest = states.get(states.size() - 1);
        boolean officialReplay = latest.stateKind()
                == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                && "CANDIDATE_STEP_RESULT".equals(
                latest.officialAuthorityType())
                && requested.candidateResultId().equals(
                latest.officialAuthorityRef());
        if (first.stateKind() != ChainProposalState.ACCEPTED
                || (latest.stateKind() != ChainProposalState.ACCEPTED
                && !officialReplay)) {
            throw failure("CHAIN_CANDIDATE_PROPOSAL_NOT_ACCEPTED",
                    "only an accepted STEP_RESULT may become a candidate");
        }
        var content = models.findContent(requested.contentId())
                .orElseThrow(() -> failure(
                        "CHAIN_CANDIDATE_CONTENT_NOT_FOUND",
                        "candidate result content does not exist"));
        if (!content.taskId().equals(requested.taskId())
                || !content.invocationId().equals(
                proposal.invocationId())
                || content.contentKind()
                != ChainContentKind.CANDIDATE_STEP_RESULT) {
            throw failure("CHAIN_CANDIDATE_CONTENT_MISMATCH",
                    "candidate result content authority is invalid");
        }
        AuthorityEventRequest event = new AuthorityEventRequest(
                requested.eventId(), requested.taskId(),
                "CANDIDATE_STEP_RESULT", null,
                requested.versionFenceSha256(), requested.createdAt());
        AuthoritativeAppendResult<CandidateStepResultRecord> stored =
                candidates.appendCandidateStepResult(
                        new AuthoritativeFact<>(event, requested));
        if (!sameCandidateIgnoringAuditTime(stored.fact(), requested)) {
            throw failure("CHAIN_CANDIDATE_REPLAY_MISMATCH",
                    "candidate writer returned different contents");
        }
        bindOfficialCandidate(requested);
        return stored;
    }

    private static boolean sameCandidateIgnoringAuditTime(
            CandidateStepResultRecord left, CandidateStepResultRecord right) {
        return left.candidateResultId().equals(right.candidateResultId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.proposalId().equals(right.proposalId())
                && left.contentId().equals(right.contentId())
                && left.instructionId().equals(right.instructionId())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.planRevisionNumber() == right.planRevisionNumber()
                && left.stepId().equals(right.stepId())
                && left.activationEventId().equals(right.activationEventId())
                && Objects.equals(left.artifactId(), right.artifactId())
                && Objects.equals(left.candidateFingerprint(), right.candidateFingerprint())
                && Objects.equals(left.diffDigest(), right.diffDigest())
                && left.receiptRefs().equals(right.receiptRefs())
                && Objects.equals(left.validationId(), right.validationId())
                && Objects.equals(left.validationRequestDigest(), right.validationRequestDigest())
                && Objects.equals(left.validationReceiptDigest(), right.validationReceiptDigest())
                && left.evidenceRefs().equals(right.evidenceRefs())
                && left.versionFenceSha256().equals(right.versionFenceSha256());
    }

    public AuthoritativeAppendResult<AcceptedResultRecord> accept(
            AcceptedResultRecord requested) {
        Objects.requireNonNull(requested, "requested");
        CandidateStepResultRecord candidate = workflows
                .findCandidateStepResults(requested.taskId()).stream()
                .filter(value -> value.candidateResultId().equals(
                        requested.candidateResultId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_ACCEPTED_CANDIDATE_NOT_FOUND",
                        "accepted candidate result does not exist"));
        ReviewDecisionRecord review = workflows
                .findReviewDecisions(requested.taskId()).stream()
                .filter(value -> value.reviewDecisionId().equals(
                        requested.reviewDecisionId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_ACCEPTED_REVIEW_NOT_FOUND",
                        "formal accepting ReviewDecision does not exist"));
        if (!candidate.taskId().equals(requested.taskId())
                || !review.taskId().equals(requested.taskId())
                || !review.reviewObjectType().equals("CANDIDATE_STEP_RESULT")
                || !review.reviewObjectId().equals(
                candidate.candidateResultId())
                || (review.decisionKind()
                != ChainProposalKind.REFLECTOR_ACCEPT_STEP
                && review.decisionKind()
                != ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE)
                || !requested.contentId().equals(candidate.contentId())) {
            throw failure("CHAIN_ACCEPTED_REVIEW_MISMATCH",
                    "accepted result is not bound to an accepting review");
        }
        var transition = workflows.findTransition(requested.transitionId())
                .orElseThrow(() -> failure(
                        "CHAIN_ACCEPTED_TRANSITION_NOT_FOUND",
                        "accepted result transition does not exist"));
        ChainTransitionType expected = review.decisionKind()
                == ChainProposalKind.REFLECTOR_ACCEPT_STEP
                ? ChainTransitionType.ACCEPT_STEP
                : ChainTransitionType.FINAL_STEP_READINESS;
        if (!transition.taskId().equals(requested.taskId())
                || transition.transitionType() != expected
                || !transition.sourceDecisionId().equals(
                review.reviewDecisionId())
                || !transition.targetIdentityDigest().equals(
                requested.acceptedIdentitySha256())) {
            throw failure("CHAIN_ACCEPTED_TRANSITION_MISMATCH",
                    "accepted result uses the wrong composite transition");
        }
        failClosedAcceptedIdentity(requested, candidate, review);
        AuthorityEventRequest event = new AuthorityEventRequest(
                requested.eventId(), requested.taskId(),
                "ACCEPTED_RESULT", requested.transitionId(),
                requested.acceptedIdentitySha256(), requested.createdAt());
        AuthoritativeAppendResult<AcceptedResultRecord> stored =
                accepted.appendAcceptedResult(
                        new AuthoritativeFact<>(event, requested));
        if (!sameAcceptedResult(stored.fact(), requested)) {
            throw failure("CHAIN_ACCEPTED_REPLAY_MISMATCH",
                    "accepted result writer returned different contents");
        }
        return stored;
    }

    private static boolean sameAcceptedResult(
            AcceptedResultRecord stored, AcceptedResultRecord requested) {
        return stored.acceptedResultId().equals(requested.acceptedResultId())
                && stored.taskId().equals(requested.taskId())
                && stored.eventId().equals(requested.eventId())
                && stored.candidateResultId().equals(requested.candidateResultId())
                && stored.reviewDecisionId().equals(requested.reviewDecisionId())
                && stored.transitionId().equals(requested.transitionId())
                && stored.contentId().equals(requested.contentId())
                && stored.acceptedIdentitySha256().equals(requested.acceptedIdentitySha256());
    }

    private void bindOfficialCandidate(CandidateStepResultRecord candidate) {
        String eventId = "proposal.candidate-bound." + sha256(
                candidate.proposalId() + "\0" + candidate.candidateResultId());
        ProposalStateEventRecord state = proposalBinder.bindCandidate(
                new ChainCandidateProposalBinder.Binding(
                        candidate.proposalId(), candidate.taskId(), eventId,
                        candidate.candidateResultId(),
                        sha256("CANDIDATE_STEP_RESULT\0"
                                + candidate.candidateResultId()),
                        candidate.createdAt()));
        if (!state.proposalId().equals(candidate.proposalId())
                || !state.taskId().equals(candidate.taskId())
                || !state.eventId().equals(eventId)
                || state.stateSequence() != 2
                || state.stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !"CANDIDATE_STEP_RESULT".equals(
                state.officialAuthorityType())
                || !candidate.candidateResultId().equals(
                state.officialAuthorityRef())) {
            throw failure("CHAIN_CANDIDATE_BINDING_MISMATCH",
                    "proposal binder returned another official result");
        }
    }

    private void failClosedAcceptedIdentity(
            AcceptedResultRecord requested,
            CandidateStepResultRecord candidate,
            ReviewDecisionRecord review) {
        for (AcceptedResultRecord existing : workflows.findAcceptedResults(
                requested.taskId())) {
            boolean sameId = existing.acceptedResultId().equals(
                    requested.acceptedResultId());
            boolean sameIdentity = existing.acceptedIdentitySha256().equals(
                    requested.acceptedIdentitySha256());
            boolean sameAcceptance = existing.candidateResultId().equals(
                    candidate.candidateResultId())
                    && existing.reviewDecisionId().equals(
                    review.reviewDecisionId())
                    && existing.transitionId().equals(
                    requested.transitionId());
            if ((sameId || sameIdentity || sameAcceptance)
                    && !existing.equals(requested)) {
                throw failure("CHAIN_ACCEPTED_IDENTITY_CONFLICT",
                        "accepted result identity conflicts with formal facts");
            }
        }
    }

    private List<ProposalStateEventRecord> proposalStates(
            String proposalId, String taskId) {
        List<ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposalId).stream()
                .sorted(Comparator.comparingLong(
                        ProposalStateEventRecord::stateSequence))
                .toList();
        if (states.isEmpty()) {
            throw failure("CHAIN_CANDIDATE_PROPOSAL_UNDECIDED",
                    "candidate proposal has no admission state");
        }
        List<ChainProposalState> prefix = new java.util.ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ProposalStateEventRecord state = states.get(index);
            if (!state.proposalId().equals(proposalId)
                    || !state.taskId().equals(taskId)
                    || state.stateSequence() != index + 1L) {
                throw failure("CHAIN_CANDIDATE_PROPOSAL_STATE_IDENTITY_INVALID",
                        "proposal state prefix crosses its proposal or task");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_CANDIDATE_PROPOSAL_STATE_INVALID",
                        "candidate proposal admission prefix is invalid");
            }
            prefix.add(state.stateKind());
        }
        return states;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ChainStepException failure(
            String code, String message) {
        return new ChainStepException(code, message);
    }
}
