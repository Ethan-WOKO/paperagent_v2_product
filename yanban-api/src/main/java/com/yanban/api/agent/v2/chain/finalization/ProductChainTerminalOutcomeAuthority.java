package com.yanban.api.agent.v2.chain.finalization;

import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Sole reader of the terminal root selected by TaskOutcome for Answer and
 * Delivery. It never chooses the latest readiness, check, Step or activation.
 */
@Component
public final class ProductChainTerminalOutcomeAuthority {
    private final ChainFinalizationRepository finalization;
    private final ProductChainStepAuthorityAdapter steps;

    public ProductChainTerminalOutcomeAuthority(
            ChainFinalizationRepository finalization,
            ProductChainStepAuthorityAdapter steps) {
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.steps = Objects.requireNonNull(steps, "steps");
    }

    public TerminalFacts requireExact(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");
        if (!task.taskId().equals(outcome.taskId())) {
            throw invalid("CHAIN_TERMINAL_OUTCOME_TASK_INVALID");
        }
        if (outcome.finalizationReadinessId() == null) {
            if (outcome.outcomeType() == ChainTaskOutcomeStatus.COMPLETED) {
                throw invalid("CHAIN_TERMINAL_OUTCOME_ROOT_MISSING");
            }
            return new TerminalFacts(null, null, null, null,
                    task.initialProjectVersion(), ValidationIdentity.none());
        }
        var readiness = finalization.findReadinessById(
                        outcome.finalizationReadinessId())
                .orElseThrow(() -> invalid(
                        "CHAIN_TERMINAL_READINESS_MISSING"));
        List<ChainPersistenceRecords.FinalizationCheckRecord> checks =
                finalization.findFinalizationChecks(readiness.readinessId())
                        .stream().filter(value -> value.finalizationCheckId()
                                .equals(outcome.finalizationCheckId()))
                        .toList();
        if (checks.size() != 1) {
            throw invalid("CHAIN_TERMINAL_CHECK_NOT_EXACT");
        }
        var check = checks.get(0);
        verifyRoot(outcome, readiness, check);
        StepIdentity step = exactStep(readiness);
        String version = outcome.publishedProjectVersion() == null
                ? readiness.projectVersion()
                : outcome.publishedProjectVersion();
        ValidationIdentity validation = ChainIdentity.NONE.equals(
                outcome.validationId())
                ? ValidationIdentity.none()
                : new ValidationIdentity(outcome.validationId(),
                outcome.validationRequestDigest(),
                outcome.validationReceiptDigest());
        return new TerminalFacts(readiness, check, step.stepId(),
                step.activationEventId(), version, validation);
    }

    private static void verifyRoot(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        if (!readiness.taskId().equals(outcome.taskId())
                || !readiness.readinessId().equals(
                outcome.finalizationReadinessId())
                || !check.taskId().equals(outcome.taskId())
                || !check.readinessId().equals(readiness.readinessId())
                || !check.finalizationCheckId().equals(
                outcome.finalizationCheckId())
                || !readiness.instructionId().equals(outcome.instructionId())
                || !readiness.taskFrameId().equals(outcome.taskFrameId())
                || !readiness.finalPlanId().equals(outcome.finalPlanId())
                || !readiness.finalPlanRevisionId().equals(
                outcome.finalPlanRevisionId())
                || !check.taskFrameId().equals(readiness.taskFrameId())
                || !check.finalPlanRevisionId().equals(
                readiness.finalPlanRevisionId())
                || !check.instructionId().equals(readiness.instructionId())
                || !check.candidateKey().equals(readiness.candidateKey())
                || !outcome.candidateKey().equals(readiness.candidateKey())
                || !Objects.equals(outcome.finalArtifactId(),
                readiness.artifactId())
                || !check.workspaceId().equals(readiness.workspaceId())
                || !check.projectVersion().equals(readiness.projectVersion())
                || !outcome.validationId().equals(readiness.validationId())
                || !check.validationId().equals(readiness.validationId())
                || !Objects.equals(outcome.validationRequestDigest(),
                readiness.validationRequestDigest())
                || !Objects.equals(outcome.validationReceiptDigest(),
                readiness.validationReceiptDigest())
                || !Objects.equals(check.validationRequestDigest(),
                readiness.validationRequestDigest())
                || !Objects.equals(check.validationReceiptDigest(),
                readiness.validationReceiptDigest())
                || outcome.publishRequirement()
                != readiness.publishRequirement()
                || !outcome.publishRequirementDigest().equals(
                readiness.publishRequirementDigest())
                || !check.publishRequirementDigest().equals(
                readiness.publishRequirementDigest())) {
            throw invalid("CHAIN_TERMINAL_ROOT_IDENTITY_INVALID");
        }
        if (outcome.outcomeType() == ChainTaskOutcomeStatus.COMPLETED
                && check.resultStatus() != ChainFinalization.Outcome.PASSED) {
            throw invalid("CHAIN_TERMINAL_COMPLETED_CHECK_INVALID");
        }
    }

    private StepIdentity exactStep(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        List<ChainStepAuthorityPort.StepEvent> events = steps.findStepEvents(
                readiness.taskId(), readiness.finalPlanRevisionId());
        List<ChainStepAuthorityPort.StepEvent> completed = events.stream()
                .filter(value -> value.command().stepId().equals(
                        readiness.finalStepId()))
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                .filter(value -> value.command().transitionId().equals(
                        readiness.transitionId()))
                .filter(value -> value.command().sourceDecisionId().equals(
                        readiness.reviewDecisionId()))
                .toList();
        if (completed.size() != 1) {
            throw invalid("CHAIN_TERMINAL_STEP_COMPLETION_NOT_EXACT");
        }
        String activationId = completed.get(0).command()
                .activationEventId();
        List<ChainStepAuthorityPort.StepEvent> activated = events.stream()
                .filter(value -> value.command().stepId().equals(
                        readiness.finalStepId()))
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .filter(value -> value.command().eventId().equals(
                        activationId))
                .filter(value -> value.command().activationEventId().equals(
                        activationId))
                .toList();
        boolean superseded = events.stream().anyMatch(value ->
                value.command().activationEventId().equals(activationId)
                        && value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind
                        .SUPERSEDED_BY_REPLAN);
        if (activated.size() != 1 || superseded) {
            throw invalid("CHAIN_TERMINAL_STEP_ACTIVATION_NOT_EXACT");
        }
        return new StepIdentity(readiness.finalStepId(), activationId);
    }

    public record TerminalFacts(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            String finalStepId,
            String activationEventId,
            String effectiveProjectVersion,
            ValidationIdentity validation) {
        public TerminalFacts {
            Objects.requireNonNull(effectiveProjectVersion,
                    "effectiveProjectVersion");
            Objects.requireNonNull(validation, "validation");
            if ((finalStepId == null) != (activationEventId == null)) {
                throw new IllegalArgumentException(
                        "terminal Step identity must be all-or-none");
            }
        }
    }

    public record ValidationIdentity(
            String validationId,
            String requestDigest,
            String receiptDigest) {
        public ValidationIdentity {
            int present = (validationId == null ? 0 : 1)
                    + (requestDigest == null ? 0 : 1)
                    + (receiptDigest == null ? 0 : 1);
            if (present != 0 && present != 3) {
                throw new IllegalArgumentException(
                        "terminal Validation identity must be all-or-none");
            }
        }

        static ValidationIdentity none() {
            return new ValidationIdentity(null, null, null);
        }
    }

    private record StepIdentity(String stepId, String activationEventId) {
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
