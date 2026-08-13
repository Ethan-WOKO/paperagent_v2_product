package io.paperagent.v2.chain.transition;

import io.paperagent.v2.chain.*;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Sole runtime writer for the four frozen applicability source types. */
public final class ChainApplicabilityRuntime {
    private final ChainWorkflowRepository workflows;
    private final ChainApplicabilityWriter writer;
    private final ChainApplicabilityAuthorityPort authorities;

    public ChainApplicabilityRuntime(
            ChainWorkflowRepository workflows,
            ChainApplicabilityWriter writer,
            ChainApplicabilityAuthorityPort authorities) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
    }

    public AuthoritativeAppendResult<ResultApplicabilityRecord> commit(
            CommitRequest request) {
        Objects.requireNonNull(request, "request");
        ChainApplicability.Identity identity = request.identity();
        AcceptedResultRecord accepted = workflows.findAcceptedResults(
                        request.taskId()).stream()
                .filter(value -> value.acceptedResultId().equals(
                        identity.acceptedResultId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_APPLICABILITY_ACCEPTED_RESULT_MISSING",
                        "applicability target is not an accepted result"));
        if (!accepted.taskId().equals(request.taskId())) {
            throw failure("CHAIN_APPLICABILITY_TASK_MISMATCH",
                    "accepted result belongs to another task");
        }
        ChainApplicabilityAuthorityPort.SourceAuthority source =
                Objects.requireNonNull(authorities.verify(
                        new ChainApplicabilityAuthorityPort.SourceQuery(
                                request.taskId(), identity.sourceType(),
                                identity.sourceDecisionId(), identity)),
                        "applicability source authority");
        if (!source.directCommitAuthority()
                || source.sourceType() != identity.sourceType()
                || !Objects.equals(source.sourceDecisionId(),
                identity.sourceDecisionId())
                || !Objects.equals(source.targetIdentity(), identity)) {
            throw failure("CHAIN_APPLICABILITY_SOURCE_INVALID",
                    "model suggestion is not a formal applicability source");
        }
        verifySourceTransition(request.taskId(), identity, source);
        String identityDigest = sha256(identity.acceptedResultId() + "\0"
                + identity.sourceType() + "\0" + identity.sourceDecisionId()
                + "\0" + identity.targetTaskFrameId() + "\0"
                + identity.targetPlanId() + "\0"
                + identity.targetPlanRevisionId() + "\0"
                + identity.targetCandidateKey() + "\0"
                + identity.targetInstructionVersionId());
        String applicabilityId = "applicability." + identityDigest;
        String eventId = "applicability.event." + identityDigest;
        List<ResultApplicabilityRecord> sameTuple = workflows
                .findApplicabilityDecisions(request.taskId()).stream()
                .filter(value -> sameIdentity(value, identity)).toList();
        if (sameTuple.size() > 1) {
            throw failure("CHAIN_APPLICABILITY_IDENTITY_CONFLICT",
                    "same formal source tuple has multiple decisions");
        }
        Instant factTime = sameTuple.isEmpty()
                ? request.committedAt() : sameTuple.get(0).createdAt();
        ResultApplicabilityRecord fact = new ResultApplicabilityRecord(
                applicabilityId, request.taskId(), eventId,
                identity.acceptedResultId(), identity.sourceType(),
                identity.sourceDecisionId(), identity.targetTaskFrameId(),
                identity.targetPlanId(), identity.targetPlanRevisionId(),
                identity.targetCandidateKey(),
                identity.targetInstructionVersionId(), request.conclusion(),
                request.reason(), factTime);
        if (sameTuple.size() == 1
                && !sameTuple.get(0).equals(fact)) {
            throw failure("CHAIN_APPLICABILITY_IDENTITY_CONFLICT",
                    "same formal source tuple has conflicting conclusions");
        }
        AuthorityEventRequest event = new AuthorityEventRequest(
                eventId, request.taskId(), "RESULT_APPLICABILITY",
                source.sourceTransitionId(), identityDigest,
                fact.createdAt());
        AuthoritativeAppendResult<ResultApplicabilityRecord> appended =
                writer.appendApplicability(
                        new AuthoritativeFact<>(event, fact));
        if (!sameDecision(appended.fact(), fact)) {
            throw failure("CHAIN_APPLICABILITY_REPLAY_MISMATCH",
                    "applicability writer returned another decision");
        }
        return appended;
    }

    private void verifySourceTransition(
            String taskId,
            ChainApplicability.Identity identity,
            ChainApplicabilityAuthorityPort.SourceAuthority source) {
        boolean transitionRequired = identity.sourceType()
                != ChainApplicability.SourceType
                .USER_INSTRUCTION_DISPOSITION;
        if (transitionRequired
                && !Objects.equals(source.sourceTransitionId(),
                identity.sourceDecisionId())) {
            throw failure("CHAIN_APPLICABILITY_TRANSITION_INVALID",
                    "transition-backed source must use its stable transition ID");
        }
        if (!transitionRequired && source.sourceTransitionId() != null) {
            throw failure("CHAIN_APPLICABILITY_TRANSITION_INVALID",
                    "instruction disposition is not transition-backed");
        }
        if (source.sourceTransitionId() == null) {
            return;
        }
        TransitionRecord transition = workflows.findTransition(
                        source.sourceTransitionId())
                .orElseThrow(() -> failure(
                        "CHAIN_APPLICABILITY_TRANSITION_MISSING",
                        "formal source transition does not exist"));
        boolean expectedType = switch (identity.sourceType()) {
            case ACCEPT_STEP -> transition.transitionType()
                    == ChainTransitionType.ACCEPT_STEP
                    || transition.transitionType()
                    == ChainTransitionType.FINAL_STEP_READINESS;
            case PLAN_REVISION, PERSISTENT_PLAN ->
                    transition.transitionType()
                    == ChainTransitionType.PLAN_CHANGE;
            case USER_INSTRUCTION_DISPOSITION -> false;
        };
        if (!transition.taskId().equals(taskId) || !expectedType) {
            throw failure("CHAIN_APPLICABILITY_TRANSITION_INVALID",
                    "formal source transition type or task is invalid");
        }
    }

    private static boolean sameIdentity(
            ResultApplicabilityRecord value,
            ChainApplicability.Identity identity) {
        return value.acceptedResultId().equals(identity.acceptedResultId())
                && value.sourceType() == identity.sourceType()
                && value.sourceDecisionId().equals(
                identity.sourceDecisionId())
                && value.targetTaskFrameId().equals(
                identity.targetTaskFrameId())
                && value.targetPlanId().equals(identity.targetPlanId())
                && value.targetPlanRevisionId().equals(
                identity.targetPlanRevisionId())
                && value.targetCandidateKey().equals(
                identity.targetCandidateKey())
                && value.targetInstructionVersionId().equals(
                identity.targetInstructionVersionId());
    }

    private static boolean sameDecision(
            ResultApplicabilityRecord stored,
            ResultApplicabilityRecord requested) {
        return stored.applicabilityId().equals(requested.applicabilityId())
                && stored.taskId().equals(requested.taskId())
                && stored.eventId().equals(requested.eventId())
                && stored.acceptedResultId().equals(requested.acceptedResultId())
                && stored.sourceType() == requested.sourceType()
                && stored.sourceDecisionId().equals(requested.sourceDecisionId())
                && stored.targetTaskFrameId().equals(requested.targetTaskFrameId())
                && stored.targetPlanId().equals(requested.targetPlanId())
                && stored.targetPlanRevisionId().equals(
                        requested.targetPlanRevisionId())
                && stored.targetCandidateKey().equals(
                        requested.targetCandidateKey())
                && stored.targetInstructionVersionId().equals(
                        requested.targetInstructionVersionId())
                && stored.conclusion() == requested.conclusion()
                && stored.reason().equals(requested.reason());
    }

    public record CommitRequest(
            String taskId,
            ChainApplicability.Identity identity,
            ChainApplicability.Outcome conclusion,
            String reason,
            Instant committedAt) {
        public CommitRequest {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(conclusion, "conclusion");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    private static ChainCompositeTransitionException failure(
            String code, String message) {
        return new ChainCompositeTransitionException(code, message);
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
}
