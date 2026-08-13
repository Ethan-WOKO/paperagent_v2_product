package io.paperagent.v2.chain.transition;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Recovery driver for the five frozen composite transitions.
 *
 * <p>It never invokes a model or creates an action. A stage committer is an
 * idempotent formal-authority append; its stage marker is written only after
 * that authority reports success.</p>
 */
public final class ChainCompositeTransitionRuntime {
    private final ChainWorkflowRepository repository;
    private final ChainTransitionWriter writer;
    private final StageAuthorityVerifier authorityVerifier;
    private final TransitionFaultInjector faults;

    public ChainCompositeTransitionRuntime(
            ChainWorkflowRepository repository,
            ChainTransitionWriter writer,
            StageAuthorityVerifier authorityVerifier) {
        this(repository, writer, authorityVerifier, ignored -> { });
    }

    ChainCompositeTransitionRuntime(
            ChainWorkflowRepository repository,
            ChainTransitionWriter writer,
            StageAuthorityVerifier authorityVerifier,
            TransitionFaultInjector faults) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.authorityVerifier = Objects.requireNonNull(
                authorityVerifier, "authorityVerifier");
        this.faults = Objects.requireNonNull(faults, "faults");
    }

    public RecoveryOutcome resume(
            TransitionRequest request,
            StageCommitter committer) {
        return advance(request, null, committer);
    }

    /**
     * Advances the same formal transition only through {@code targetStage}.
     * Missing predecessors are committed in the frozen path order, while
     * later stages are left untouched for a subsequent call. A replay whose
     * target is already present performs no stage commit.
     */
    public RecoveryOutcome resumeThrough(
            TransitionRequest request,
            ChainTransitionStage targetStage,
            StageCommitter committer) {
        return advance(request, Objects.requireNonNull(
                targetStage, "targetStage"), committer);
    }

    private RecoveryOutcome advance(
            TransitionRequest request,
            ChainTransitionStage targetStage,
            StageCommitter committer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(committer, "committer");
        List<ChainTransitionStage> path = selectedPath(request);
        int lastOrdinal = path.size() - 1;
        if (targetStage != null) {
            lastOrdinal = path.indexOf(targetStage);
            if (lastOrdinal < 0) {
                throw new IllegalArgumentException(
                        "targetStage does not belong to the selected path");
            }
        }
        TransitionRecord transition = ensureTransition(request);
        List<TransitionStageRecord> committed = readPrefix(
                transition, path, request.branch());
        int recoveredStages = 0;
        for (int ordinal = committed.size(); ordinal <= lastOrdinal; ordinal++) {
            ChainTransitionStage stage = path.get(ordinal);
            StageCommitResult result = stage == ChainTransitionStage.OPEN
                    || stage == ChainTransitionStage.COMPLETE
                    ? StageCommitResult.none()
                    : Objects.requireNonNull(committer.commit(
                            new StageCommand(transition, stage, ordinal)),
                            "stage commit result");
            verifyStageEvidence(stage, result);
            verifyNestedGapSuccessor(transition, stage, result);
            TransitionStageRecord marker = marker(
                    transition, stage, ordinal, result,
                    request.committedAt());
            verifyFormalAuthority(
                    transition, marker, request.branch());
            faults.afterSuccessorCommitted(stage);
            AuthorityEventRequest event = new AuthorityEventRequest(
                    marker.eventId(), transition.taskId(),
                    "TRANSITION_STAGE", transition.transitionId(),
                    transition.targetIdentityDigest(),
                    request.committedAt());
            var appended = writer.appendTransitionStage(
                    new AuthoritativeFact<>(event, marker));
            if (!sameStage(appended.fact(), marker)
                    || !sameDatabaseInstant(
                            appended.fact().committedAt(),
                            appended.event().committedAt())) {
                throw failure("CHAIN_TRANSITION_STAGE_REPLAY_MISMATCH",
                        "transition writer returned a different stage");
            }
            committed = append(committed, appended.fact());
            recoveredStages++;
        }
        return new RecoveryOutcome(
                transition, List.copyOf(committed), recoveredStages,
                transition.transitionType().isCompleteSequence(
                        committed.stream().map(
                                TransitionStageRecord::stageCode).toList()));
    }

    private TransitionRecord ensureTransition(TransitionRequest request) {
        String transitionId = new ChainIdentity.Transition(
                request.type(), request.taskId(),
                request.sourceDecisionId(),
                request.targetIdentityDigest()).transitionId();
        var existing = repository.findTransition(transitionId);
        if (existing.isPresent()) {
            TransitionRecord stored = existing.get();
            if (!sameIdentity(stored, request)) {
                throw failure("CHAIN_TRANSITION_IDENTITY_MISMATCH",
                        "stored transition conflicts with its stable identity");
            }
            return stored;
        }
        String eventId = "transition.open." + sha256(transitionId);
        TransitionRecord requested = new TransitionRecord(
                transitionId, request.taskId(), eventId, request.type(),
                request.sourceDecisionId(), request.targetIdentityDigest(),
                request.committedAt());
        AuthorityEventRequest event = new AuthorityEventRequest(
                eventId, request.taskId(), "TRANSITION", transitionId,
                request.targetIdentityDigest(), request.committedAt());
        var appended = writer.appendTransition(
                new AuthoritativeFact<>(event, requested));
        if (!sameTransition(appended.fact(), requested)
                || !sameDatabaseInstant(
                        appended.fact().createdAt(),
                        appended.event().committedAt())) {
            throw failure("CHAIN_TRANSITION_REPLAY_MISMATCH",
                    "transition writer returned another transition");
        }
        return appended.fact();
    }

    private static boolean sameTransition(
            TransitionRecord left,
            TransitionRecord right) {
        return left.transitionId().equals(right.transitionId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.transitionType() == right.transitionType()
                && left.sourceDecisionId().equals(right.sourceDecisionId())
                && left.targetIdentityDigest().equals(
                        right.targetIdentityDigest());
    }

    private static boolean sameStage(
            TransitionStageRecord left,
            TransitionStageRecord right) {
        return left.transitionId().equals(right.transitionId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.stageCode() == right.stageCode()
                && left.stageOrdinal() == right.stageOrdinal()
                && Objects.equals(left.predecessorAuthorityType(),
                        right.predecessorAuthorityType())
                && Objects.equals(left.predecessorAuthorityRef(),
                        right.predecessorAuthorityRef())
                && Objects.equals(left.successorAuthorityType(),
                        right.successorAuthorityType())
                && Objects.equals(left.successorAuthorityRef(),
                        right.successorAuthorityRef());
    }

    private static boolean sameDatabaseInstant(Instant left, Instant right) {
        return left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private List<TransitionStageRecord> readPrefix(
            TransitionRecord transition,
            List<ChainTransitionStage> selectedPath,
            Branch branch) {
        List<TransitionStageRecord> stages = repository
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        TransitionStageRecord::stageOrdinal))
                .toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        Set<ChainTransitionStage> codes = new HashSet<>();
        for (TransitionStageRecord stage : stages) {
            if (!stage.taskId().equals(transition.taskId())
                    || !stage.transitionId().equals(
                    transition.transitionId())
                    || !codes.add(stage.stageCode())) {
                throw failure("CHAIN_TRANSITION_PREFIX_INVALID",
                        "transition stage identities are invalid");
            }
            try {
                stage.validateNextFor(
                        transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_TRANSITION_PREFIX_INVALID",
                        "transition stage prefix is not legal");
            }
            verifyFormalAuthority(transition, stage, branch);
            prefix.add(stage.stageCode());
        }
        if (prefix.size() > selectedPath.size()
                || !selectedPath.subList(0, prefix.size()).equals(prefix)) {
            throw failure("CHAIN_TRANSITION_BRANCH_CONFLICT",
                    "recovery selected a different finalization branch");
        }
        return stages;
    }

    private void verifyFormalAuthority(
            TransitionRecord transition,
            TransitionStageRecord stage,
            Branch branch) {
        verifyStageAuthorityShape(stage.stageCode(), new StageCommitResult(
                stage.predecessorAuthorityType(),
                stage.predecessorAuthorityRef(),
                stage.successorAuthorityType(),
                stage.successorAuthorityRef()));
        if (stage.stageCode() == ChainTransitionStage.OPEN
                || stage.stageCode() == ChainTransitionStage.COMPLETE) {
            return;
        }
        AuthorityVerification verification = Objects.requireNonNull(
                authorityVerifier.verify(
                        new StageAuthorityQuery(transition, stage)),
                "stage authority verification");
        if (!verification.formalAuthorityVerified()) {
            throw failure("CHAIN_TRANSITION_STAGE_AUTHORITY_UNVERIFIED",
                    "transition stage authority is not a formal fact");
        }
        boolean applicabilityBarrier = stage.stageCode()
                == ChainTransitionStage.APPLICABILITY_COMMITTED
                || stage.stageCode()
                == ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY;
        boolean emptyAuthority = stage.predecessorAuthorityType() == null
                && stage.successorAuthorityType() == null;
        if (applicabilityBarrier && emptyAuthority
                && !verification.emptyAuthoritySetVerified()) {
            throw failure("CHAIN_TRANSITION_EMPTY_BARRIER_UNVERIFIED",
                    "empty applicability barrier lacks formal verification");
        }
        if (applicabilityBarrier && !emptyAuthority
                && verification.emptyAuthoritySetVerified()) {
            throw failure("CHAIN_TRANSITION_STAGE_AUTHORITY_INVALID",
                    "non-empty applicability barrier claims an empty set");
        }
        if (stage.stageCode()
                == ChainTransitionStage.FINALIZATION_CHECK_COMMITTED) {
            FinalizationCheckOutcome actual =
                    verification.finalizationCheckOutcome();
            boolean accepted = branch == Branch.FINALIZATION_SUCCESS
                    ? actual == FinalizationCheckOutcome.PASSED
                    : actual == FinalizationCheckOutcome.FAILED
                    || actual == FinalizationCheckOutcome.PASSED;
            if (!accepted) {
                throw failure("CHAIN_TRANSITION_FINALIZATION_BRANCH_MISMATCH",
                        "selected branch conflicts with the formal check");
            }
        } else if (stage.stageCode()
                == ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED) {
            FinalizationCheckOutcome check = finalizationCheckOutcome(
                    transition);
            boolean publishFailure = "PUBLISH_FAILURE".equals(
                    stage.predecessorAuthorityType());
            if ((check == FinalizationCheckOutcome.PASSED) != publishFailure) {
                throw failure("CHAIN_TRANSITION_FINALIZATION_BRANCH_MISMATCH",
                        "failed branch lacks its exact check/publish handoff");
            }
        } else if (verification.finalizationCheckOutcome() != null) {
            throw failure("CHAIN_TRANSITION_STAGE_AUTHORITY_INVALID",
                    "only finalization check may carry a check outcome");
        }
    }

    private FinalizationCheckOutcome finalizationCheckOutcome(
            TransitionRecord transition) {
        TransitionStageRecord check = repository.findTransitionStages(
                        transition.transitionId()).stream()
                .filter(stage -> stage.stageCode()
                        == ChainTransitionStage.FINALIZATION_CHECK_COMMITTED)
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_TRANSITION_FINALIZATION_BRANCH_MISMATCH",
                        "failed handoff has no formal FinalizationCheck"));
        AuthorityVerification verification = Objects.requireNonNull(
                authorityVerifier.verify(
                        new StageAuthorityQuery(transition, check)),
                "finalization check verification");
        if (!verification.formalAuthorityVerified()
                || verification.finalizationCheckOutcome() == null) {
            throw failure("CHAIN_TRANSITION_FINALIZATION_BRANCH_MISMATCH",
                    "failed handoff check authority is invalid");
        }
        return verification.finalizationCheckOutcome();
    }

    private void verifyNestedGapSuccessor(
            TransitionRecord transition,
            ChainTransitionStage stage,
            StageCommitResult result) {
        if (transition.transitionType()
                != ChainTransitionType.GAP_RESOLUTION
                || stage
                != ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED
                || !"TRANSITION".equals(result.successorAuthorityType())) {
            return;
        }
        TransitionRecord successor = repository.findTransition(
                        result.successorAuthorityRef())
                .orElseThrow(() -> failure(
                        "CHAIN_GAP_SUCCESSOR_TRANSITION_MISSING",
                        "nested normal successor transition is missing"));
        List<ChainTransitionStage> stages = repository
                .findTransitionStages(successor.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        TransitionStageRecord::stageOrdinal))
                .map(TransitionStageRecord::stageCode).toList();
        if (!successor.transitionType().isCompleteSequence(stages)) {
            throw failure("CHAIN_GAP_SUCCESSOR_TRANSITION_INCOMPLETE",
                    "gap cannot resolve before its normal successor completes");
        }
    }

    private static void verifyStageEvidence(
            ChainTransitionStage stage,
            StageCommitResult result) {
        boolean evidenceOptional = stage == ChainTransitionStage.OPEN
                || stage == ChainTransitionStage.COMPLETE
                || stage
                == ChainTransitionStage.NEXT_STEP_ACTIVATED_OR_NONE
                || stage
                == ChainTransitionStage.OLD_STEP_SUPERSEDED_OR_NONE
                || stage
                == ChainTransitionStage.APPLICABILITY_COMMITTED
                || stage
                == ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY;
        if (!evidenceOptional
                && result.predecessorAuthorityType() == null
                && result.successorAuthorityType() == null) {
            throw failure("CHAIN_TRANSITION_STAGE_EVIDENCE_MISSING",
                    "formal transition stage lacks its authority reference");
        }
        verifyStageAuthorityShape(stage, result);
    }

    private static void verifyStageAuthorityShape(
            ChainTransitionStage stage,
            StageCommitResult result) {
        switch (stage) {
            case OPEN, COMPLETE -> requireNone(stage, result);
            case NORMAL_SUCCESSOR_COMMITTED ->
                    requireAllowedSuccessor(stage, result, Set.of(
                            "ROUTE_DECISION", "PLAN_BINDING", "TRANSITION",
                             "ACTION_BINDING", "WORKSPACE_CANDIDATE",
                             "CANDIDATE_STEP_RESULT", "PENDING_ITEM",
                             "TASK_OUTCOME", "MODEL_INVOCATION",
                             "INSTRUCTION_DISPOSITION"));
            case PENDING_RESOLVED ->
                    requireSuccessor(stage, result, "PENDING_ITEM_EVENT");
            case ACCEPTED_RESULT_COMMITTED ->
                    requireSuccessor(stage, result, "ACCEPTED_RESULT");
            case APPLICABILITY_COMMITTED ->
                    requireOptionalSuccessor(
                            stage, result, "RESULT_APPLICABILITY");
            case STEP_COMPLETED, NEW_STEP_ACTIVATED ->
                    requireSuccessor(stage, result, "STEP_EVENT");
            case NEXT_STEP_ACTIVATED_OR_NONE,
                    OLD_STEP_SUPERSEDED_OR_NONE ->
                    requireOptionalSuccessor(stage, result, "STEP_EVENT");
            case TASKFRAME_PLAN_COMMITTED ->
                    requireSuccessor(stage, result, "PLAN_BINDING");
            case ACCEPTED_RESULT_COMMITTED_OR_VERIFIED ->
                    requireEitherAuthority(stage, result, "ACCEPTED_RESULT");
            case APPLICABILITY_COMMITTED_OR_EMPTY ->
                    requireOptionalSuccessor(
                            stage, result, "RESULT_APPLICABILITY");
            case STEP_COMPLETED_OR_VERIFIED ->
                    requireEitherAuthority(stage, result, "STEP_EVENT");
            case READINESS_COMMITTED ->
                    requireSuccessor(stage, result, "FINALIZATION_READINESS");
            case READINESS_VERIFIED ->
                    requirePredecessor(stage, result, "FINALIZATION_READINESS");
            case FINALIZATION_CHECK_COMMITTED ->
                    requireSuccessor(stage, result, "FINALIZATION_CHECK");
            case PUBLISH_COMMITTED_OR_NOT_REQUIRED ->
                    requireOptionalSuccessor(stage, result, "PUBLISH_RECEIPT");
            case TASK_OUTCOME_COMMITTED ->
                    requireSuccessor(stage, result, "TASK_OUTCOME");
            case FAILED_CHECK_HANDOFF_COMMITTED ->
                    requireFailureHandoff(stage, result);
        }
    }

    private static void requireFailureHandoff(
            ChainTransitionStage stage, StageCommitResult result) {
        if ((result.predecessorAuthorityType() != null
                && !"PUBLISH_FAILURE".equals(
                result.predecessorAuthorityType()))
                || (!"REVIEW_DECISION".equals(
                result.successorAuthorityType())
                && !"TASK_OUTCOME".equals(
                result.successorAuthorityType()))) {
            invalidShape(stage);
        }
    }

    private static void requireNone(
            ChainTransitionStage stage, StageCommitResult result) {
        if (result.predecessorAuthorityType() != null
                || result.successorAuthorityType() != null) {
            invalidShape(stage);
        }
    }

    private static void requireSuccessor(
            ChainTransitionStage stage,
            StageCommitResult result,
            String type) {
        if (result.predecessorAuthorityType() != null
                || !type.equals(result.successorAuthorityType())) {
            invalidShape(stage);
        }
    }

    private static void requireAllowedSuccessor(
            ChainTransitionStage stage,
            StageCommitResult result,
            Set<String> allowedTypes) {
        if (result.predecessorAuthorityType() != null
                || !allowedTypes.contains(
                result.successorAuthorityType())) {
            invalidShape(stage);
        }
    }

    private static void requireOptionalSuccessor(
            ChainTransitionStage stage,
            StageCommitResult result,
            String type) {
        if (result.predecessorAuthorityType() != null
                || (result.successorAuthorityType() != null
                && !type.equals(result.successorAuthorityType()))) {
            invalidShape(stage);
        }
    }

    private static void requirePredecessor(
            ChainTransitionStage stage,
            StageCommitResult result,
            String type) {
        if (!type.equals(result.predecessorAuthorityType())
                || result.successorAuthorityType() != null) {
            invalidShape(stage);
        }
    }

    private static void requireEitherAuthority(
            ChainTransitionStage stage,
            StageCommitResult result,
            String type) {
        boolean predecessor = type.equals(
                result.predecessorAuthorityType())
                && result.successorAuthorityType() == null;
        boolean successor = type.equals(result.successorAuthorityType())
                && result.predecessorAuthorityType() == null;
        if (!predecessor && !successor) {
            invalidShape(stage);
        }
    }

    private static void invalidShape(ChainTransitionStage stage) {
        throw failure("CHAIN_TRANSITION_STAGE_AUTHORITY_SHAPE_INVALID",
                "stage authority type/direction is invalid for " + stage);
    }

    private static TransitionStageRecord marker(
            TransitionRecord transition,
            ChainTransitionStage stage,
            int ordinal,
            StageCommitResult result,
            Instant committedAt) {
        String eventId = "transition.stage." + sha256(
                transition.transitionId() + "\0" + ordinal
                        + "\0" + stage.name());
        return new TransitionStageRecord(
                transition.transitionId(), stage, transition.taskId(),
                eventId, ordinal,
                result.predecessorAuthorityType(),
                result.predecessorAuthorityRef(),
                result.successorAuthorityType(),
                result.successorAuthorityRef(), committedAt);
    }

    private static List<ChainTransitionStage> selectedPath(
            TransitionRequest request) {
        if (request.type() != ChainTransitionType.FINALIZATION) {
            return request.type().paths().get(0);
        }
        return switch (request.branch()) {
            case FINALIZATION_SUCCESS -> request.type().paths().get(0);
            case FINALIZATION_FAILED -> request.type().paths().get(1);
            case STANDARD -> throw new IllegalArgumentException(
                    "FINALIZATION requires an explicit success/failure branch");
        };
    }

    private static boolean sameIdentity(
            TransitionRecord stored,
            TransitionRequest request) {
        return stored.taskId().equals(request.taskId())
                && stored.transitionType() == request.type()
                && stored.sourceDecisionId().equals(
                request.sourceDecisionId())
                && stored.targetIdentityDigest().equals(
                request.targetIdentityDigest());
    }

    private static List<TransitionStageRecord> append(
            List<TransitionStageRecord> values,
            TransitionStageRecord value) {
        List<TransitionStageRecord> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
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

    private static ChainCompositeTransitionException failure(
            String code, String message) {
        return new ChainCompositeTransitionException(code, message);
    }

    @FunctionalInterface
    public interface StageCommitter {
        StageCommitResult commit(StageCommand command);
    }

    @FunctionalInterface
    public interface StageAuthorityVerifier {
        AuthorityVerification verify(StageAuthorityQuery query);
    }

    public record StageAuthorityQuery(
            TransitionRecord transition,
            TransitionStageRecord stage) {
        public StageAuthorityQuery {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(stage, "stage");
        }
    }

    public record AuthorityVerification(
            boolean formalAuthorityVerified,
            FinalizationCheckOutcome finalizationCheckOutcome,
            boolean emptyAuthoritySetVerified) {
        public AuthorityVerification {
            if (!formalAuthorityVerified
                    && (finalizationCheckOutcome != null
                    || emptyAuthoritySetVerified)) {
                throw new IllegalArgumentException(
                        "unverified authority cannot carry verification facts");
            }
            if (finalizationCheckOutcome != null
                    && emptyAuthoritySetVerified) {
                throw new IllegalArgumentException(
                        "finalization outcome cannot prove an empty set");
            }
        }

        public static AuthorityVerification verified() {
            return new AuthorityVerification(true, null, false);
        }

        public static AuthorityVerification verifiedEmpty() {
            return new AuthorityVerification(true, null, true);
        }

        public static AuthorityVerification finalization(
                FinalizationCheckOutcome outcome) {
            return new AuthorityVerification(
                    true, Objects.requireNonNull(outcome, "outcome"), false);
        }
    }

    public enum FinalizationCheckOutcome {
        PASSED,
        FAILED
    }

    @FunctionalInterface
    interface TransitionFaultInjector {
        void afterSuccessorCommitted(ChainTransitionStage stage);
    }

    public record StageCommand(
            TransitionRecord transition,
            ChainTransitionStage stage,
            int stageOrdinal) {
        public StageCommand {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(stage, "stage");
            if (stageOrdinal < 0) {
                throw new IllegalArgumentException(
                        "stageOrdinal must not be negative");
            }
        }
    }

    public record StageCommitResult(
            String predecessorAuthorityType,
            String predecessorAuthorityRef,
            String successorAuthorityType,
            String successorAuthorityRef) {
        public StageCommitResult {
            paired(predecessorAuthorityType,
                    predecessorAuthorityRef, "predecessor authority");
            paired(successorAuthorityType,
                    successorAuthorityRef, "successor authority");
        }

        public static StageCommitResult none() {
            return new StageCommitResult(null, null, null, null);
        }

        public static StageCommitResult successor(
                String type, String ref) {
            return new StageCommitResult(null, null, type, ref);
        }
    }

    public enum Branch {
        STANDARD,
        FINALIZATION_SUCCESS,
        FINALIZATION_FAILED
    }

    public record TransitionRequest(
            ChainTransitionType type,
            String taskId,
            String sourceDecisionId,
            String targetIdentityDigest,
            Branch branch,
            Instant committedAt) {
        public TransitionRequest {
            Objects.requireNonNull(type, "type");
            taskId = required(taskId, "taskId");
            sourceDecisionId = required(
                    sourceDecisionId, "sourceDecisionId");
            if (targetIdentityDigest == null
                    || !targetIdentityDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "targetIdentityDigest must be lowercase SHA-256");
            }
            branch = Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(committedAt, "committedAt");
            if (type != ChainTransitionType.FINALIZATION
                    && branch != Branch.STANDARD) {
                throw new IllegalArgumentException(
                        "only FINALIZATION has a branch");
            }
        }
    }

    public record RecoveryOutcome(
            TransitionRecord transition,
            List<TransitionStageRecord> committedStages,
            int recoveredStages,
            boolean complete) {
        public RecoveryOutcome {
            Objects.requireNonNull(transition, "transition");
            committedStages = List.copyOf(Objects.requireNonNull(
                    committedStages, "committedStages"));
            if (recoveredStages < 0) {
                throw new IllegalArgumentException(
                        "recoveredStages must not be negative");
            }
        }
    }

    private static void paired(
            String left, String right, String name) {
        if ((left == null) != (right == null)) {
            throw new IllegalArgumentException(name + " must be paired");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
