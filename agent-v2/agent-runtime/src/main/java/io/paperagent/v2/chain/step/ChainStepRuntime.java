package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.*;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Step authority facade; sole writer for finalization readiness. */
public final class ChainStepRuntime {
    private final ChainStepStateMachine stateMachine;
    private final ChainWorkflowRepository workflows;
    private final ChainFinalizationRepository finalization;
    private final ChainReadinessWriter readiness;
    private final ChainReadinessAuthorityPort authorities;
    private final ChainStepCommitGate commitGate;

    public ChainStepRuntime(
            ChainStepStateMachine stateMachine,
            ChainWorkflowRepository workflows,
            ChainFinalizationRepository finalization,
            ChainReadinessWriter readiness,
            ChainReadinessAuthorityPort authorities,
            ChainStepCommitGate commitGate) {
        this.stateMachine = Objects.requireNonNull(
                stateMachine, "stateMachine");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.commitGate = Objects.requireNonNull(commitGate, "commitGate");
    }

    public ChainStepStateMachine.ActivationOutcome activateNext(
            String taskId,
            String planRevisionId,
            String sourceDecisionId,
            String transitionId,
            Instant committedAt) {
        return stateMachine.activateNext(
                taskId, planRevisionId, sourceDecisionId, transitionId,
                committedAt);
    }

    public AppendResult<ChainStepAuthorityPort.StepEvent> completeAcceptedStep(
            ChainStepStateMachine.StepTerminalCommand command) {
        return stateMachine.completeAcceptedStep(command);
    }

    public AppendResult<ChainStepAuthorityPort.StepEvent> supersedeForReplan(
            ChainStepStateMachine.StepTerminalCommand command) {
        return stateMachine.supersedeForReplan(command);
    }

    public AuthoritativeAppendResult<FinalizationReadinessRecord>
            commitReadiness(ReadinessCommand command) {
        Objects.requireNonNull(command, "command");
        TransitionRecord transition = workflows.findTransition(
                        command.transitionId())
                .orElseThrow(() -> failure("CHAIN_READINESS_TRANSITION_MISSING",
                        "FINAL_STEP_READINESS transition does not exist"));
        if (!transition.taskId().equals(command.taskId())
                || transition.transitionType()
                != ChainTransitionType.FINAL_STEP_READINESS
                || !transition.sourceDecisionId().equals(
                command.reviewDecisionId())) {
            throw failure("CHAIN_READINESS_TRANSITION_INVALID",
                    "readiness transition type, task, or source is invalid");
        }
        ReviewDecisionRecord review = workflows.findReviewDecisions(
                        command.taskId()).stream()
                .filter(value -> value.reviewDecisionId().equals(
                        command.reviewDecisionId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_READINESS_REVIEW_MISSING",
                        "formal readiness ReviewDecision does not exist"));
        if (!review.taskId().equals(command.taskId())
                || (review.decisionKind()
                != ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE
                && review.decisionKind()
                != ChainProposalKind.REFLECTOR_READY_TO_FINALIZE)) {
            throw failure("CHAIN_READINESS_REVIEW_INVALID",
                    "ReviewDecision is not a readiness authority");
        }
        requireTransitionPredecessors(transition);
        ChainReadinessAuthorityPort.VerifiedReadinessMaterial material =
                Objects.requireNonNull(authorities.verify(
                        new ChainReadinessAuthorityPort.ReadinessQuery(
                                command.taskId(), command.transitionId(),
                                command.reviewDecisionId())),
                        "verified readiness material");
        boolean noCandidate = material.artifactId() == null;
        if (noCandidate != ChainIdentity.NONE.equals(material.candidateKey())
                || noCandidate != ChainIdentity.NONE.equals(
                material.workspaceId())) {
            throw failure("CHAIN_READINESS_CANDIDATE_IDENTITY_MISMATCH",
                    "readiness Candidate, Workspace, and artifact must be all present or NONE");
        }
        if (material.publishRequirement()
                == ChainPublishRequirement.REQUIRED
                && (noCandidate || ChainIdentity.NONE.equals(
                material.projectVersion()))) {
            throw failure("CHAIN_READINESS_PUBLISH_IDENTITY_MISSING",
                    "required publish needs Project and Candidate authority");
        }
        ChainStepStateMachine.PlanState planState = stateMachine.derive(
                command.taskId(), material.finalPlanRevisionId());
        if (!planState.plan().taskFrameId().equals(material.taskFrameId())
                || !planState.plan().planId().equals(material.finalPlanId())
                || !planState.plan().targetCandidateKey().equals(
                material.candidateKey())
                || !planState.plan().targetInstructionVersionId().equals(
                material.instructionId())) {
            throw failure("CHAIN_READINESS_PLAN_IDENTITY_MISMATCH",
                    "verified readiness crosses the current Plan identity");
        }
        ChainStepStateMachine.StepState step = planState.steps().stream()
                .filter(value -> value.stepId().equals(
                        material.finalStepId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_READINESS_FINAL_STEP_MISSING",
                        "verified final Step does not exist"));
        if (step.status() != ChainStepStatus.COMPLETED
                || !Objects.equals(step.activationEventId(),
                material.activationEventId())) {
            throw failure("CHAIN_READINESS_FINAL_STEP_INCOMPLETE",
                    "readiness requires the formally completed final Step");
        }
        int finalOrder = planState.steps().stream()
                .mapToInt(ChainStepStateMachine.StepState::stableOrder)
                .max().orElseThrow();
        boolean planCompleted = step.stableOrder() == finalOrder
                && planState.steps().stream().allMatch(value ->
                        value.status() == ChainStepStatus.COMPLETED
                                || value.status()
                                == ChainStepStatus.SUPERSEDED_BY_REPLAN);
        if (!planCompleted) {
            throw failure("CHAIN_READINESS_DEPENDENCIES_INCOMPLETE",
                    "final Step dependencies are not formally complete");
        }
        verifyAcceptedSet(command.taskId(), material.acceptedResultIds(),
                material.acceptedSet());
        String scopeKey = sha256(command.taskId() + "\0"
                + material.finalPlanRevisionId());
        String readinessIdentity = sha256(command.taskId() + "\0"
                + command.transitionId() + "\0" + material.taskFrameId()
                + "\0" + material.finalPlanRevisionId() + "\0"
                + material.acceptedSet().sha256() + "\0"
                + material.candidateKey() + "\0" + material.validationId()
                + "\0" + material.publishRequirementDigest() + "\0"
                + material.instructionId() + "\0" + material.projectVersion());
        String readinessId = "readiness." + readinessIdentity;
        String eventId = "readiness.event." + readinessIdentity;
        var existing = finalization.findReadinessByScope(scopeKey);
        Instant factTime = existing
                .map(FinalizationReadinessRecord::createdAt)
                .orElse(command.committedAt());
        new ChainIdentity.Readiness(
                command.transitionId(), material.taskFrameId(),
                material.finalPlanRevisionId(), material.acceptedSet().sha256(),
                material.artifactId() == null
                        ? null : material.artifactId().toString(),
                material.candidateKey(), material.workspaceId(),
                material.validationId(), material.validationRequestDigest(),
                material.validationReceiptDigest(),
                material.publishRequirementDigest(), material.instructionId(),
                material.projectVersion());
        FinalizationReadinessRecord fact = new FinalizationReadinessRecord(
                readinessId, command.taskId(), eventId,
                command.transitionId(), scopeKey, material.taskFrameId(),
                material.finalPlanId(), material.finalPlanRevisionId(),
                material.finalPlanRevisionNumber(), material.finalStepId(),
                command.reviewDecisionId(), material.acceptedSet(),
                material.applicabilityCutEventSequence(), material.artifactId(),
                material.candidateKey(), material.workspaceId(),
                material.validationId(), material.validationRequestDigest(),
                material.validationReceiptDigest(), material.coverage(),
                material.publishRequirement(),
                material.publishRequirementDigest(), material.instructionId(),
                material.projectVersion(), factTime);
        if (existing.isPresent()
                && !sameIgnoringAuditTime(existing.get(), fact)) {
            throw failure("CHAIN_READINESS_SCOPE_CONFLICT",
                    "readiness scope already contains another fact");
        }
        if (existing.isEmpty()) {
            commitGate.requireCurrent(new ChainStepCommitGate.GateQuery(
                    ChainStepCommitGate.CommitKind.FINALIZATION_READINESS,
                    command.taskId(), material.instructionId(),
                    material.taskFrameId(), material.finalPlanId(),
                    material.finalPlanRevisionId(), material.finalStepId(),
                    material.activationEventId()));
        }
        AuthorityEventRequest event = new AuthorityEventRequest(
                eventId, command.taskId(), "FINALIZATION_READINESS",
                command.transitionId(), scopeKey, fact.createdAt());
        AuthoritativeAppendResult<FinalizationReadinessRecord> appended =
                readiness.appendReadiness(new AuthoritativeFact<>(event, fact));
        if (!sameIgnoringAuditTime(appended.fact(), fact)
                || !appended.event().eventId().equals(event.eventId())
                || !appended.event().taskId().equals(event.taskId())
                || !appended.event().eventType().equals(event.eventType())
                || !Objects.equals(appended.event().transitionId(),
                event.transitionId())
                || !appended.event().sourceIdentitySha256().equals(
                event.sourceIdentitySha256())
                || !appended.event().committedAt().equals(
                appended.fact().createdAt())
                || (existing.isPresent()
                && !existing.get().equals(appended.fact()))) {
            throw failure("CHAIN_READINESS_REPLAY_MISMATCH",
                    "readiness writer returned another fact");
        }
        return appended;
    }

    private static boolean sameIgnoringAuditTime(
            FinalizationReadinessRecord left,
            FinalizationReadinessRecord right) {
        try {
            for (var component : FinalizationReadinessRecord.class
                    .getRecordComponents()) {
                if (component.getName().equals("createdAt")) continue;
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

    private void requireTransitionPredecessors(TransitionRecord transition) {
        List<TransitionStageRecord> stages = workflows.findTransitionStages(
                        transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        TransitionStageRecord::stageOrdinal)).toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        Set<ChainTransitionStage> unique = new HashSet<>();
        for (TransitionStageRecord stage : stages) {
            if (!stage.taskId().equals(transition.taskId())
                    || !stage.transitionId().equals(
                    transition.transitionId())
                    || !unique.add(stage.stageCode())) {
                throw failure("CHAIN_READINESS_TRANSITION_PREFIX_INVALID",
                        "readiness transition stage identity is invalid");
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_READINESS_TRANSITION_PREFIX_INVALID",
                        "readiness transition prefix is invalid");
            }
            prefix.add(stage.stageCode());
        }
        if (!prefix.contains(
                ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED)) {
            throw failure("CHAIN_READINESS_PREDECESSOR_MISSING",
                    "readiness transition predecessors are incomplete");
        }
    }

    private void verifyAcceptedSet(
            String taskId,
            List<String> acceptedResultIds,
            CanonicalJson acceptedSet) {
        if (new HashSet<>(acceptedResultIds).size()
                != acceptedResultIds.size()) {
            throw failure("CHAIN_READINESS_ACCEPTED_SET_INVALID",
                    "verified accepted set contains duplicate IDs");
        }
        Set<String> formal = workflows.findAcceptedResults(taskId).stream()
                .map(AcceptedResultRecord::acceptedResultId)
                .collect(java.util.stream.Collectors.toSet());
        if (!formal.containsAll(acceptedResultIds)) {
            throw failure("CHAIN_READINESS_ACCEPTED_SET_INVALID",
                    "verified accepted set references a non-formal result");
        }
        String expectedJson = acceptedResultIds.stream().sorted()
                .map(ChainStepRuntime::jsonString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        if (!acceptedSet.json().equals(expectedJson)
                || !acceptedSet.sha256().equals(sha256(expectedJson))) {
            throw failure("CHAIN_READINESS_ACCEPTED_SET_INVALID",
                    "acceptedSet JSON differs from its verified ID set");
        }
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(
                                java.util.Locale.ROOT,
                                "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('\"').toString();
    }

    public record ReadinessCommand(
            String taskId,
            String transitionId,
            String reviewDecisionId,
            Instant committedAt) {
        public ReadinessCommand {
            if (taskId == null || taskId.isBlank()
                    || transitionId == null || transitionId.isBlank()
                    || reviewDecisionId == null
                    || reviewDecisionId.isBlank()) {
                throw new IllegalArgumentException(
                        "readiness command identity must not be blank");
            }
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    private static ChainStepException failure(String code, String message) {
        return new ChainStepException(code, message);
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
