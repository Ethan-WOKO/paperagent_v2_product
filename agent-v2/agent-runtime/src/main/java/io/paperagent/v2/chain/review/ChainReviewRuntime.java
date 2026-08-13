package io.paperagent.v2.chain.review;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainReviewDecisionWriter;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The only chain runtime in this package that commits ReviewDecision. It does
 * not accept candidate results, create PendingItems, grant permissions, submit
 * readiness, or write TaskOutcome; those remain explicit successor runtimes.
 */
public final class ChainReviewRuntime {
    private final ChainWorkflowRepository workflow;
    private final ChainReviewDecisionWriter decisions;
    private final ReviewProposalSource proposals;
    private final ProposalOfficialBinder proposalBinder;

    public ChainReviewRuntime(
            ChainWorkflowRepository workflow,
            ChainReviewDecisionWriter decisions,
            ReviewProposalSource proposals,
            ProposalOfficialBinder proposalBinder) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.proposalBinder = Objects.requireNonNull(proposalBinder, "proposalBinder");
    }

    public CommitResult commit(CommitRequest request) {
        Objects.requireNonNull(request, "request");
        FormalReviewProposal source = proposals.load(request.proposalId());
        require(source.proposal().proposalId().equals(request.proposalId())
                        && source.currentState().proposalId().equals(request.proposalId())
                        && source.proposal().taskId().equals(request.taskId())
                        && source.currentState().taskId().equals(request.taskId()),
                "review request/proposal/state identity mismatch");
        require(source.proposal().proposalKind() == source.payload().kind()
                        && source.proposal().role() == ChainRole.REFLECTOR,
                "formal review proposal kind mismatch");
        ProposalFields.ReviewCommon review = source.payload().review();
        require(review.reviewedObjectRefs().contains(request.reviewObjectId()),
                "review object was not named by the accepted proposal");
        validateCandidateBoundary(request.taskId(), request, source.payload());

        String decisionId = "review." + sha256(
                request.taskId() + "\0" + request.proposalId() + "\0"
                        + request.reviewObjectType() + "\0" + request.reviewObjectId()
                        + "\0" + source.payload().kind().name());
        requireAcceptedOrBound(source.currentState(), request, decisionId);
        ChainPersistenceRecords.ReviewDecisionRecord requested =
                new ChainPersistenceRecords.ReviewDecisionRecord(
                        decisionId, request.taskId(), request.eventId(), request.proposalId(),
                        request.reviewObjectType(), request.reviewObjectId(),
                        source.payload().kind(), review.decisionReason(),
                        canonicalArray(review.directFactRefs()), source.versionFenceSha256(),
                        request.createdAt());
        String sourceIdentity = sha256(
                request.proposalId() + "\0" + request.reviewObjectType() + "\0"
                        + request.reviewObjectId() + "\0" + source.versionFenceSha256());
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        request.eventId(), request.taskId(), "REVIEW_DECISION", null,
                        sourceIdentity, request.createdAt());
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.ReviewDecisionRecord> append =
                decisions.appendReviewDecision(
                        new ChainPersistenceRecords.AuthoritativeFact<>(event, requested));
        require(sameReviewDecision(append.fact(), requested),
                "ReviewDecision append changed frozen fields");
        proposalBinder.bindOfficialResult(
                request.taskId(), request.proposalId(), "REVIEW_DECISION", decisionId);
        return new CommitResult(
                append.fact(), append.replayed(), successorFor(source.payload()));
    }

    /**
     * Only CONTINUE and REPLAN have a model-role successor immediately after a
     * formal ReviewDecision. Other kinds must wait for their formal successor
     * fact (accepted result, PendingItem, readiness, or TaskOutcome).
     */
    public Optional<ModelRoleDirective> nextModelRole(String taskId) {
        List<ChainPersistenceRecords.ReviewDecisionRecord> committed =
                workflow.findReviewDecisions(required(taskId, "taskId"));
        if (committed.isEmpty()) {
            return Optional.empty();
        }
        ChainPersistenceRecords.ReviewDecisionRecord latest =
                committed.get(committed.size() - 1);
        return switch (latest.decisionKind()) {
            case REFLECTOR_CONTINUE_STEP -> Optional.of(new ModelRoleDirective(
                    latest.reviewDecisionId(), ChainRole.EXECUTOR, ChainWorkState.EXECUTING));
            case REFLECTOR_REPLAN_REQUIRED -> Optional.of(new ModelRoleDirective(
                    latest.reviewDecisionId(), ChainRole.PLANNER, ChainWorkState.PLANNING));
            default -> Optional.empty();
        };
    }

    private void validateCandidateBoundary(
            String taskId, CommitRequest request, ReflectorPayload payload) {
        ReflectorPayload.AcceptStep acceptance = acceptance(payload);
        if (acceptance == null) {
            return;
        }
        require(request.reviewObjectId().equals(acceptance.candidateResultId()),
                "accept decision must review its exact candidate result");
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates =
                workflow.findCandidateStepResults(taskId).stream()
                        .filter(value -> value.candidateResultId()
                                .equals(acceptance.candidateResultId()))
                        .toList();
        require(candidates.size() == 1,
                "accept decision requires one formal candidate result");
        ChainPersistenceRecords.CandidateStepResultRecord candidate = candidates.get(0);
        require(candidate.taskFrameId().equals(acceptance.taskFrameRef())
                        && candidate.planRevisionId().equals(acceptance.planRevisionRef())
                        && candidate.stepId().equals(acceptance.stepRef()),
                "accept decision changed the candidate TaskFrame/Plan/Step binding");
        if (candidate.candidateFingerprint() == null) {
            require(ChainIdentity.NONE.equals(acceptance.candidateRef()),
                    "accept decision changed the formal Candidate binding");
            return;
        }
        List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                workspaceCandidates = workflow
                .findWorkspaceCandidates(taskId).stream()
                .filter(value -> value.workspaceCandidateId().equals(
                        acceptance.candidateRef()))
                .toList();
        require(workspaceCandidates.size() == 1,
                "accept decision requires one formal WorkspaceCandidate");
        ChainPersistenceRecords.WorkspaceCandidateRecord workspaceCandidate =
                workspaceCandidates.get(0);
        require(workspaceCandidate.taskId().equals(taskId)
                        && candidate.artifactId() != null
                        && workspaceCandidate.artifactId()
                        == candidate.artifactId()
                        && workspaceCandidate.candidateFingerprint().equals(
                        candidate.candidateFingerprint())
                        && workspaceCandidate.diffDigest().equals(
                        candidate.diffDigest()),
                "accept decision changed the formal Candidate binding");
    }

    private static ReflectorPayload.AcceptStep acceptance(ReflectorPayload payload) {
        if (payload instanceof ReflectorPayload.AcceptStep value) {
            return value;
        }
        if (payload instanceof ReflectorPayload.AcceptStepAndReadyToFinalize value) {
            return value.acceptance();
        }
        return null;
    }

    private static SuccessorRequirement successorFor(ReflectorPayload payload) {
        return switch (payload.kind()) {
            case REFLECTOR_CONTINUE_STEP -> SuccessorRequirement.STEP_CONTINUATION;
            case REFLECTOR_ACCEPT_STEP -> SuccessorRequirement.ACCEPTED_RESULT_AND_STEP;
            case REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE ->
                    SuccessorRequirement.ACCEPTED_RESULT_STEP_AND_READINESS;
            case REFLECTOR_REPLAN_REQUIRED -> SuccessorRequirement.PLAN_REVISION;
            case REFLECTOR_NEED_USER_INPUT -> SuccessorRequirement.USER_PENDING_ITEM;
            case REFLECTOR_NEED_PERMISSION -> SuccessorRequirement.PERMISSION_PENDING_ITEM;
            case REFLECTOR_READY_TO_FINALIZE -> SuccessorRequirement.STEP_READINESS;
            case REFLECTOR_TASK_FAILED -> SuccessorRequirement.FAILED_TASK_OUTCOME;
            default -> throw new IllegalArgumentException("not a Reflector proposal kind");
        };
    }

    private static void requireAcceptedOrBound(
            ChainPersistenceRecords.ProposalStateEventRecord state,
            CommitRequest request,
            String decisionId) {
        require(state.taskId().equals(request.taskId())
                        && state.proposalId().equals(request.proposalId()),
                "review proposal state identity mismatch");
        if (state.stateKind() == ChainProposalState.ACCEPTED) {
            return;
        }
        require(state.stateKind() == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                        && "REVIEW_DECISION".equals(state.officialAuthorityType())
                        && decisionId.equals(state.officialAuthorityRef()),
                "review proposal is not accepted or bound to this ReviewDecision");
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(List<String> values) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, "values"));
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < copy.size(); index++) {
            if (index > 0) json.append(',');
            quote(json, copy.get(index));
        }
        json.append(']');
        String encoded = json.toString();
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(encoded), encoded);
    }

    private static void quote(StringBuilder json, String value) {
        required(value, "fact ref");
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) json.append(String.format("\\u%04x", (int) character));
                    else json.append(character);
                }
            }
        }
        json.append('"');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Persistence assigns the authoritative audit timestamp at append time.
     * It is deliberately excluded from the frozen review identity; all other
     * fields are immutable and must round-trip byte-for-byte.
     */
    private static boolean sameReviewDecision(
            ChainPersistenceRecords.ReviewDecisionRecord stored,
            ChainPersistenceRecords.ReviewDecisionRecord requested) {
        return stored.reviewDecisionId().equals(requested.reviewDecisionId())
                && stored.taskId().equals(requested.taskId())
                && stored.eventId().equals(requested.eventId())
                && stored.proposalId().equals(requested.proposalId())
                && stored.reviewObjectType().equals(requested.reviewObjectType())
                && stored.reviewObjectId().equals(requested.reviewObjectId())
                && stored.decisionKind() == requested.decisionKind()
                && stored.reason().equals(requested.reason())
                && stored.factRefs().equals(requested.factRefs())
                && stored.versionFenceSha256().equals(requested.versionFenceSha256());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireSha256(String value, String name) {
        required(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }

    public record CommitRequest(
            String taskId,
            String proposalId,
            String eventId,
            String reviewObjectType,
            String reviewObjectId,
            Instant createdAt) {
        public CommitRequest {
            required(taskId, "taskId");
            required(proposalId, "proposalId");
            required(eventId, "eventId");
            required(reviewObjectType, "reviewObjectType");
            required(reviewObjectId, "reviewObjectId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record CommitResult(
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            boolean replayed,
            SuccessorRequirement successorRequirement) {
        public CommitResult {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(successorRequirement, "successorRequirement");
        }
    }

    public enum SuccessorRequirement {
        STEP_CONTINUATION,
        ACCEPTED_RESULT_AND_STEP,
        ACCEPTED_RESULT_STEP_AND_READINESS,
        PLAN_REVISION,
        USER_PENDING_ITEM,
        PERMISSION_PENDING_ITEM,
        STEP_READINESS,
        FAILED_TASK_OUTCOME
    }

    public record ModelRoleDirective(
            String sourceReviewDecisionId,
            ChainRole role,
            ChainWorkState workState) {
        public ModelRoleDirective {
            required(sourceReviewDecisionId, "sourceReviewDecisionId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(workState, "workState");
        }
    }

    public interface ReviewProposalSource {
        FormalReviewProposal load(String proposalId);
    }

    public record FormalReviewProposal(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ProposalStateEventRecord currentState,
            ReflectorPayload payload,
            String versionFenceSha256) {
        public FormalReviewProposal {
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(currentState, "currentState");
            Objects.requireNonNull(payload, "payload");
            requireSha256(versionFenceSha256, "versionFenceSha256");
            if (!proposal.proposalId().equals(currentState.proposalId())
                    || !proposal.taskId().equals(currentState.taskId())
                    || proposal.role() != ChainRole.REFLECTOR
                    || payload.role() != ChainRole.REFLECTOR
                    || proposal.proposalKind() != payload.kind()) {
                throw new IllegalArgumentException(
                        "review proposal/state/payload identities must match");
            }
        }
    }

    public interface ProposalOfficialBinder {
        void bindOfficialResult(
                String taskId, String proposalId, String authorityType, String authorityRef);
    }
}
