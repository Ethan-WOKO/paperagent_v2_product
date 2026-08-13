package io.paperagent.v2.chain.review;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Sole TaskOutcome writer; every terminal kind has a typed, formally verified source. */
public final class ChainTaskOutcomeRuntime {
    private final ChainTaskOutcomeWriter outcomes;
    private final FormalSourceVerifier sourceVerifier;

    public ChainTaskOutcomeRuntime(
            ChainTaskOutcomeWriter outcomes,
            FormalSourceVerifier sourceVerifier) {
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.sourceVerifier = Objects.requireNonNull(sourceVerifier, "sourceVerifier");
    }

    public CommitResult commit(OutcomeCommand command) {
        return commit(command, sourceVerifier);
    }

    /**
     * Product adapters may reuse the sole runtime instance for a typed
     * boundary-specific verifier; the writer and identity rules remain here.
     */
    public CommitResult commit(
            OutcomeCommand command, FormalSourceVerifier verifier) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(verifier, "verifier");
        ChainTaskOutcomeStatus status;
        String sourceDecisionId;
        String failureCategory = null;
        String failureCode = null;
        String transitionId = null;
        if (command instanceof Completed value) {
            requireCompletedTerminalRoot(value.draft());
            verifier.verifyCompleted(value);
            status = ChainTaskOutcomeStatus.COMPLETED;
            sourceDecisionId = value.finalizationTransitionId();
            transitionId = value.finalizationTransitionId();
        } else if (command instanceof Failed value) {
            verifier.verifyFailed(value);
            status = ChainTaskOutcomeStatus.FAILED;
            sourceDecisionId = value.formalFailureSourceId();
            failureCategory = value.failureCategory();
            failureCode = value.failureCode();
        } else if (command instanceof Cancelled value) {
            require(value.draft().instructionId().equals(value.cancellationInstructionId()),
                    "CANCELLED must preserve the explicit cancellation instruction");
            verifier.verifyCancelled(value);
            status = ChainTaskOutcomeStatus.CANCELLED;
            sourceDecisionId = value.cancellationInstructionId();
        } else if (command instanceof Superseded value) {
            require(value.oldBoundary().matches(value.draft()),
                    "SUPERSEDED outcome does not preserve the old task boundary");
            require(!value.supersededByInstructionId().equals(value.oldBoundary().instructionId()),
                    "SUPERSEDED requires a distinct new instruction");
            verifier.verifySuperseded(value);
            status = ChainTaskOutcomeStatus.SUPERSEDED;
            sourceDecisionId = value.supersededByInstructionId();
        } else {
            throw new IllegalStateException("unsupported typed TaskOutcome command");
        }

        OutcomeDraft draft = command.draft();
        String outcomeId = "outcome." + sha256(
                draft.taskId() + "\0" + status + "\0" + sourceDecisionId);
        ChainPersistenceRecords.TaskOutcomeRecord requested = draft.toRecord(
                outcomeId, status, failureCategory, failureCode, sourceDecisionId);
        String sourceIdentity = sha256(status + "\0" + sourceDecisionId);
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        draft.eventId(), draft.taskId(), "TASK_OUTCOME", transitionId,
                        sourceIdentity, draft.createdAt());
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TaskOutcomeRecord> appended = outcomes.appendTaskOutcome(
                new ChainPersistenceRecords.AuthoritativeFact<>(event, requested));
        require(sameRecordIgnoringAuditTime(appended.fact(), requested),
                "TaskOutcome append/replay changed immutable contents");
        requireEvent(event, appended.event(), appended.fact().createdAt());
        return new CommitResult(appended.fact(), appended.replayed());
    }

    private static void requireEvent(
            ChainPersistenceRecords.AuthorityEventRequest requested,
            ChainPersistenceRecords.AuthorityEventRecord stored,
            Instant storedFactTime) {
        require(stored.eventId().equals(requested.eventId())
                        && stored.taskId().equals(requested.taskId())
                        && stored.eventType().equals(requested.eventType())
                        && Objects.equals(stored.transitionId(), requested.transitionId())
                        && stored.sourceIdentitySha256().equals(requested.sourceIdentitySha256())
                        && stored.committedAt().equals(storedFactTime),
                "TaskOutcome authority event changed immutable contents");
    }

    private static boolean sameRecordIgnoringAuditTime(
            Record left, Record right) {
        if (!left.getClass().equals(right.getClass())) return false;
        try {
            for (var component : left.getClass().getRecordComponents()) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    continue;
                }
                if (!Objects.equals(component.getAccessor().invoke(left),
                        component.getAccessor().invoke(right))) {
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void requireCompletedTerminalRoot(OutcomeDraft draft) {
        require(draft.finalizationReadinessId() != null
                        && !draft.finalizationReadinessId().isBlank()
                        && draft.finalizationCheckId() != null
                        && !draft.finalizationCheckId().isBlank()
                        && draft.publishRequirement() != null
                        && draft.publishRequirementDigest() != null
                        && !draft.publishRequirementDigest().isBlank(),
                "new COMPLETED outcome requires exact finalization authority");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public sealed interface OutcomeCommand permits Completed, Failed, Cancelled, Superseded {
        OutcomeDraft draft();
    }

    public record Completed(
            OutcomeDraft draft,
            String finalizationTransitionId) implements OutcomeCommand {
        public Completed {
            Objects.requireNonNull(draft, "draft");
            required(finalizationTransitionId, "finalizationTransitionId");
        }
    }

    public record Failed(
            OutcomeDraft draft,
            String formalFailureSourceId,
            String failureCategory,
            String failureCode) implements OutcomeCommand {
        public Failed {
            Objects.requireNonNull(draft, "draft");
            required(formalFailureSourceId, "formalFailureSourceId");
            required(failureCategory, "failureCategory");
            required(failureCode, "failureCode");
        }
    }

    public record Cancelled(
            OutcomeDraft draft,
            String cancellationInstructionId) implements OutcomeCommand {
        public Cancelled {
            Objects.requireNonNull(draft, "draft");
            required(cancellationInstructionId, "cancellationInstructionId");
        }
    }

    public record Superseded(
            OutcomeDraft draft,
            OldBoundary oldBoundary,
            String supersededByInstructionId) implements OutcomeCommand {
        public Superseded {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(oldBoundary, "oldBoundary");
            required(supersededByInstructionId, "supersededByInstructionId");
        }
    }

    /** Immutable old-task fields that SUPERSEDED must preserve before naming the new instruction. */
    public record OldBoundary(
            String instructionId,
            String taskFrameId,
            String finalPlanId,
            String finalPlanRevisionId,
            ChainPersistenceRecords.CanonicalJson coverage,
            ChainPersistenceRecords.CanonicalJson acceptedSet) {
        public OldBoundary {
            required(instructionId, "instructionId");
            Objects.requireNonNull(coverage, "coverage");
            Objects.requireNonNull(acceptedSet, "acceptedSet");
        }

        private boolean matches(OutcomeDraft draft) {
            return instructionId.equals(draft.instructionId())
                    && Objects.equals(taskFrameId, draft.taskFrameId())
                    && Objects.equals(finalPlanId, draft.finalPlanId())
                    && Objects.equals(finalPlanRevisionId, draft.finalPlanRevisionId())
                    && coverage.equals(draft.coverage())
                    && acceptedSet.equals(draft.acceptedSet());
        }
    }

    public record OutcomeDraft(
            String taskId,
            String eventId,
            String sourceCommandId,
            String instructionId,
            String taskFrameId,
            String finalPlanId,
            String finalPlanRevisionId,
            ChainPersistenceRecords.CanonicalJson coverage,
            ChainPersistenceRecords.CanonicalJson acceptedSet,
            Long finalArtifactId,
            String candidateKey,
            String finalizationReadinessId,
            String finalizationCheckId,
            String validationId,
            String validationRequestDigest,
            String validationReceiptDigest,
            ChainPublishRequirement publishRequirement,
            String publishRequirementDigest,
            String publishOperationId,
            String publishedProjectVersion,
            Long publishedRevisionId,
            String publishReceiptId,
            ChainPersistenceRecords.CanonicalJson incompleteItems,
            ChainPersistenceRecords.CanonicalJson limitations,
            ChainPersistenceRecords.CanonicalJson risks,
            Instant createdAt) {
        /** Source-compatible shape for outcomes that have no finalization root. */
        public OutcomeDraft(
                String taskId, String eventId, String sourceCommandId,
                String instructionId, String taskFrameId,
                String finalPlanId, String finalPlanRevisionId,
                ChainPersistenceRecords.CanonicalJson coverage,
                ChainPersistenceRecords.CanonicalJson acceptedSet,
                Long finalArtifactId, String candidateKey,
                String validationId, String publishOperationId,
                String publishedProjectVersion, Long publishedRevisionId,
                String publishReceiptId,
                ChainPersistenceRecords.CanonicalJson incompleteItems,
                ChainPersistenceRecords.CanonicalJson limitations,
                ChainPersistenceRecords.CanonicalJson risks,
                Instant createdAt) {
            this(taskId, eventId, sourceCommandId, instructionId,
                    taskFrameId, finalPlanId, finalPlanRevisionId, coverage,
                    acceptedSet, finalArtifactId, candidateKey, null, null,
                    validationId, null, null, null, null,
                    publishOperationId, publishedProjectVersion,
                    publishedRevisionId, publishReceiptId, incompleteItems,
                    limitations, risks, createdAt);
        }

        public OutcomeDraft {
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(sourceCommandId, "sourceCommandId");
            required(instructionId, "instructionId");
            Objects.requireNonNull(coverage, "coverage");
            Objects.requireNonNull(acceptedSet, "acceptedSet");
            required(candidateKey, "candidateKey");
            required(validationId, "validationId");
            Objects.requireNonNull(incompleteItems, "incompleteItems");
            Objects.requireNonNull(limitations, "limitations");
            Objects.requireNonNull(risks, "risks");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        private ChainPersistenceRecords.TaskOutcomeRecord toRecord(
                String outcomeId,
                ChainTaskOutcomeStatus status,
                String failureCategory,
                String failureCode,
                String sourceDecisionId) {
            return new ChainPersistenceRecords.TaskOutcomeRecord(
                    outcomeId, taskId, eventId, sourceCommandId, status, instructionId,
                    taskFrameId, finalPlanId, finalPlanRevisionId, coverage, acceptedSet,
                    finalArtifactId, candidateKey, finalizationReadinessId,
                    finalizationCheckId, validationId, validationRequestDigest,
                    validationReceiptDigest, publishRequirement,
                    publishRequirementDigest, publishOperationId,
                    publishedProjectVersion, publishedRevisionId, publishReceiptId,
                    incompleteItems, limitations, risks, failureCategory, failureCode,
                    sourceDecisionId, createdAt);
        }
    }

    public record CommitResult(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            boolean replayed) {
        public CommitResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public interface FormalSourceVerifier {
        void verifyCompleted(Completed command);

        void verifyFailed(Failed command);

        void verifyCancelled(Cancelled command);

        void verifySuperseded(Superseded command);
    }
}
